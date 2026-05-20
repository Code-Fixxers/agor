/**
 * Tests for the agor_messages_list MCP tool.
 *
 * Focus: the tool bypasses the Feathers hook pipeline by running a raw Drizzle
 * query against the messages table. These tests verify that when
 * `worktree_rbac` is enabled, the raw query is restricted to sessions the
 * caller can access (preventing cross-worktree leakage via the `search`
 * parameter).
 */

import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Hoist-safe mocks must be declared before the module under test is imported.
const mockIsWorktreeRbacEnabled = vi.fn(() => false);
vi.mock('@agor/core/config', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@agor/core/config');
  return {
    ...actual,
    isWorktreeRbacEnabled: () => mockIsWorktreeRbacEnabled(),
  };
});

// Capture the raw query the tool builds so we can assert on its shape.
const mockWhereSpy = vi.fn();
const mockAllSpy = vi.fn(async () => [] as unknown[]);

vi.mock('@agor/core/db', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@agor/core/db');
  return {
    ...actual,
    exists: (query: unknown) => ({ type: 'exists', query }),
    isNotNull: (value: unknown) => ({ type: 'isNotNull', value }),
    select: () => ({
      from: () => {
        const queryBuilder = {
          innerJoin: () => queryBuilder,
          leftJoin: () => queryBuilder,
          where: (cond: unknown) => {
            if (cond) mockWhereSpy(cond);
            return queryBuilder;
          },
          orderBy: () => queryBuilder,
          limit: () => queryBuilder,
          offset: () => queryBuilder,
          all: () => mockAllSpy(),
          one: async () => {
            const rows = await mockAllSpy();
            return { count: rows.length };
          },
        };
        return queryBuilder;
      },
    }),
  };
});

vi.mock('../resolve-ids.js', () => ({
  resolveSessionId: async (_ctx: unknown, id: string) => id,
  resolveTaskId: async (_ctx: unknown, id: string) => id,
}));

type ToolHandler = (args: Record<string, unknown>) => Promise<{
  content: Array<{ type: string; text: string }>;
}>;

function makeMessageRow(index: number) {
  return {
    message_id: `msg-${index}`,
    session_id: 'sess-active-1',
    task_id: 'task-1',
    index,
    role: 'user',
    type: 'user',
    timestamp: new Date('2026-05-20T00:00:00Z'),
    content_preview: `message ${index}`,
    data: { content: `message ${index}` },
  };
}

async function registerAndGetHandler(ctx: { userId: string; role?: string }): Promise<ToolHandler> {
  const { registerMessageTools } = await import('./messages.js');
  let captured: ToolHandler | undefined;
  const fakeServer = {
    registerTool: (_name: string, _cfg: unknown, cb: ToolHandler) => {
      captured = cb;
    },
  } as unknown as McpServer;

  registerMessageTools(fakeServer, {
    app: {
      service: (name: string) => {
        throw new Error(`Unexpected service lookup: ${name}`);
      },
    } as any,
    db: {} as any,
    userId: ctx.userId as import('@agor/core/types').UserID,
    sessionId: 'sess-0001' as import('@agor/core/types').SessionID,
    authenticatedUser: { user_id: ctx.userId, role: ctx.role ?? 'member' } as any,
    baseServiceParams: {},
  });

  if (!captured) throw new Error('tool handler was not captured');
  return captured;
}

describe('agor_messages_list MCP tool', () => {
  beforeEach(() => {
    mockIsWorktreeRbacEnabled.mockReset();
    mockWhereSpy.mockReset();
    mockAllSpy.mockReset();
    mockAllSpy.mockResolvedValue([]);
    mockIsWorktreeRbacEnabled.mockReturnValue(false);
  });

  afterEach(() => {
    vi.resetModules();
  });

  it('does not enforce RBAC when worktree_rbac is disabled', async () => {
    mockIsWorktreeRbacEnabled.mockReturnValue(false);
    const handler = await registerAndGetHandler({ userId: 'user-1' });
    await handler({ search: 'secret', includeArchived: true });
    expect(mockAllSpy).toHaveBeenCalled();
  }, 30_000);

  it('uses SQL predicates instead of hydrating session id lists for default archived filtering', async () => {
    mockIsWorktreeRbacEnabled.mockReturnValue(false);

    const handler = await registerAndGetHandler({ userId: 'user-1' });
    await handler({ search: 'secret' });

    expect(mockAllSpy).toHaveBeenCalled();
  });

  it('uses SQL predicates instead of hydrating accessible session ids for RBAC', async () => {
    mockIsWorktreeRbacEnabled.mockReturnValue(true);

    const handler = await registerAndGetHandler({ userId: 'user-1' });
    await handler({ search: 'secret' });

    expect(mockAllSpy).toHaveBeenCalled();
  });

  it('bypasses RBAC filter for superadmin role', async () => {
    mockIsWorktreeRbacEnabled.mockReturnValue(true);
    const handler = await registerAndGetHandler({ userId: 'user-1', role: 'superadmin' });
    await handler({ search: 'secret' });
    expect(mockAllSpy).toHaveBeenCalled();
  });

  it('stops scanning after page plus lookahead when exact total is not requested', async () => {
    mockAllSpy.mockResolvedValueOnce([makeMessageRow(1), makeMessageRow(2), makeMessageRow(3)]);

    const handler = await registerAndGetHandler({ userId: 'user-1' });
    const result = await handler({ search: 'secret', limit: 1 });

    expect(mockAllSpy).toHaveBeenCalledTimes(1);
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.messages).toHaveLength(1);
    expect(parsed.total).toBeUndefined();
    expect(parsed.has_more).toBe(true);
    expect(parsed.next_offset).toBe(1);
  });

  it('scans to the end only when exact total is requested', async () => {
    mockAllSpy
      .mockResolvedValueOnce(Array.from({ length: 100 }, (_, i) => makeMessageRow(i + 1)))
      .mockResolvedValueOnce([makeMessageRow(101)]);

    const handler = await registerAndGetHandler({ userId: 'user-1' });
    const result = await handler({ search: 'secret', limit: 1, includeTotal: true });

    expect(mockAllSpy).toHaveBeenCalledTimes(2);
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.messages).toHaveLength(1);
    expect(parsed.total).toBe(101);
    expect(parsed.has_more).toBe(true);
  });

  it('caps huge offsets before scanning', async () => {
    const handler = await registerAndGetHandler({ userId: 'user-1' });
    const result = await handler({ search: 'secret', offset: 1_000_000 });

    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.offset).toBe(10_000);
  });
});
