import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { describe, expect, it, vi } from 'vitest';
import { registerArtifactTools } from './artifacts.js';

vi.mock('../resolve-ids.js', () => ({
  resolveArtifactId: async (_ctx: unknown, id: string) => id,
  resolveBoardId: async (_ctx: unknown, id: string) => id,
  resolveWorktreeId: async (_ctx: unknown, id: string) => id,
}));

type ToolHandler = (args: Record<string, unknown>) => Promise<{
  content: Array<{ type: string; text: string }>;
}>;

function registerAndGetHandler(service: Record<string, unknown>): ToolHandler {
  const handlers = new Map<string, ToolHandler>();
  const fakeServer = {
    registerTool: (name: string, _cfg: unknown, cb: ToolHandler) => {
      handlers.set(name, cb);
    },
  } as unknown as McpServer;

  registerArtifactTools(fakeServer, {
    app: {
      service: (name: string) => {
        if (name !== 'artifacts') throw new Error(`Unexpected service lookup: ${name}`);
        return service;
      },
    } as any,
    db: {} as any,
    userId: 'user-1' as import('@agor/core/types').UserID,
    authenticatedUser: { user_id: 'user-1', role: 'member' } as any,
    baseServiceParams: {},
  });

  const handler = handlers.get('agor_artifacts_list');
  if (!handler) throw new Error('agor_artifacts_list handler was not registered');
  return handler;
}

describe('agor_artifacts_list', () => {
  it('passes limit through when listing artifacts for a board', async () => {
    const findByBoardId = vi.fn(async () => [
      {
        artifact_id: 'artifact-1',
        board_id: 'board-1',
        name: 'artifact',
        files: { 'src/App.tsx': 'large payload' },
      },
    ]);
    const handler = registerAndGetHandler({ findByBoardId });

    const result = await handler({ boardId: 'board-1', limit: 3 });

    expect(findByBoardId).toHaveBeenCalledWith('board-1', 'user-1', {
      limit: 3,
      omitFiles: true,
    });
    const payload = JSON.parse(result.content[0].text);
    expect(payload.data[0]).not.toHaveProperty('files');
  });
});
