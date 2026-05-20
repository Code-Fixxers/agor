import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { listTaskSummaries } from '../lean-list-queries.js';
import { resolveSessionId } from '../resolve-ids.js';
import type { McpContext } from '../server.js';
import { textResult } from '../utils.js';

export function registerTaskTools(server: McpServer, ctx: McpContext): void {
  // Tool 1: agor_tasks_list
  server.registerTool(
    'agor_tasks_list',
    {
      description:
        'List tasks (user prompts) in a session. Tasks from archived sessions are excluded unless sessionId is passed explicitly.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        sessionId: z.string().optional().describe('Session ID to scope to'),
        limit: z.number().optional().describe('Default: 10'),
        includeArchived: z
          .boolean()
          .optional()
          .describe('Include tasks from archived sessions (default: false)'),
      }),
    },
    async (args) => {
      const sessionId = args.sessionId ? await resolveSessionId(ctx, args.sessionId) : undefined;
      return textResult(await listTaskSummaries(ctx, { ...args, sessionId }));
    }
  );

  // Tool 2: agor_tasks_get
  server.registerTool(
    'agor_tasks_get',
    {
      description: 'Get detailed information about a specific task',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        taskId: z.string().describe('Task ID (UUIDv7 or short ID)'),
      }),
    },
    async (args) => {
      const task = await ctx.app.service('tasks').get(args.taskId, ctx.baseServiceParams);
      return textResult(task);
    }
  );
}
