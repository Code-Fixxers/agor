import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
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
      const query: Record<string, unknown> = { $limit: args.limit ?? 10 };
      if (args.sessionId) {
        // Explicit sessionId = caller opted in, even if archived
        query.session_id = await resolveSessionId(ctx, args.sessionId);
      } else if (!args.includeArchived) {
        // Unscoped listing: exclude tasks whose parent session is archived
        const sessionsResult = await ctx.app.service('sessions').find({
          query: { archived: false, $limit: 10000, $select: ['session_id'] },
          ...ctx.baseServiceParams,
        });
        const ids = (Array.isArray(sessionsResult) ? sessionsResult : sessionsResult.data).map(
          (s: { session_id: string }) => s.session_id
        );
        if (ids.length === 0) return textResult({ total: 0, data: [] });
        query.session_id = { $in: ids };
      }
      const tasks = await ctx.app.service('tasks').find({ query, ...ctx.baseServiceParams });
      return textResult(tasks);
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
