import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { describe, expect, it } from 'vitest';

type ToolHandler = (args: Record<string, unknown>) => Promise<{
  content: Array<{ type: string; text: string }>;
}>;

async function registerAndCaptureTools(
  ctx: {
    app: unknown;
    userId: string;
    sessionId?: string;
  },
  toolNames: string[]
): Promise<Record<string, ToolHandler>> {
  const { registerWorktreeTools } = await import('./worktrees.js');
  const captured: Record<string, ToolHandler> = {};
  const fakeServer = {
    registerTool: (name: string, _cfg: unknown, cb: ToolHandler) => {
      if (toolNames.includes(name)) captured[name] = cb;
    },
  } as unknown as McpServer;

  registerWorktreeTools(fakeServer, {
    app: ctx.app as any,
    db: {} as any,
    userId: ctx.userId as any,
    sessionId: ctx.sessionId as any,
    authenticatedUser: { user_id: ctx.userId, role: 'member' } as any,
    baseServiceParams: {},
  });

  return captured;
}

describe('agor_worktrees_update', () => {
  it('fails clearly when API-key callers omit both worktreeId and session context', async () => {
    const app = {
      service: (_name: string) => {
        throw new Error('Should not reach service lookup without session context');
      },
    };

    const tools = await registerAndCaptureTools({ app, userId: 'user-1', sessionId: undefined }, [
      'agor_worktrees_update',
    ]);

    await expect(tools.agor_worktrees_update({ notes: 'hello' })).rejects.toThrow(
      /X-Agor-Session-Id|sessionId/
    );
  }, 30_000);
});
