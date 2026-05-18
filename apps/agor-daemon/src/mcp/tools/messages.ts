import { isWorktreeRbacEnabled } from '@agor/core/config';
import {
  and,
  asc,
  desc,
  eq,
  inArray,
  messages as messagesTable,
  or,
  SessionRepository,
  select,
  sql,
} from '@agor/core/db';
import type { ContentBlock } from '@agor/core/types';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { isSuperAdmin } from '../../utils/worktree-authorization.js';
import { resolveSessionId, resolveTaskId } from '../resolve-ids.js';
import type { McpContext } from '../server.js';
import { coerceString, textResult } from '../utils.js';

export function registerMessageTools(server: McpServer, ctx: McpContext): void {
  // Tool 1: agor_messages_list
  server.registerTool(
    'agor_messages_list',
    {
      description:
        "Page through a session's messages or search across sessions. With sessionId: chronological transcript. Without sessionId: searches active sessions only (archived excluded unless includeArchived=true). Tool calls filtered by default.",
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        sessionId: z
          .string()
          .optional()
          .describe('Session ID to scope messages to (optional when using search)'),
        taskId: z.string().optional().describe('Task ID to scope messages to (optional)'),
        search: z
          .string()
          .optional()
          .describe(
            'Keyword search across message content. Space-separated terms are AND\'d, pipe (|) for OR. Example: "OAuth middleware" requires both; "OAuth | JWT" matches either.'
          ),
        includeToolCalls: z
          .boolean()
          .optional()
          .describe(
            'Include tool call messages and tool_use content blocks (default: false). When false, strips tool noise for cleaner output.'
          ),
        contentMode: z
          .enum(['preview', 'full'])
          .optional()
          .describe(
            'Content detail level. "preview" returns first 200 chars (default). "full" returns complete text content.'
          ),
        limit: z.number().optional().describe('Maximum number of messages to return (default: 20)'),
        offset: z.number().optional().describe('Skip first N messages (default: 0)'),
        order: z
          .enum(['asc', 'desc'])
          .optional()
          .describe(
            'Sort order by message index. Default: "asc" when browsing a session, "desc" when searching.'
          ),
        role: z.enum(['user', 'assistant']).optional().describe('Filter by message role'),
        includeArchived: z
          .boolean()
          .optional()
          .describe(
            'Include messages from archived sessions (default: false). Ignored when sessionId/taskId is explicitly set.'
          ),
      }),
    },
    async (args) => {
      const sessionIdRaw = coerceString(args.sessionId);
      const taskIdRaw = coerceString(args.taskId);
      const search = coerceString(args.search);

      if (!sessionIdRaw && !taskIdRaw && !search) {
        throw new Error('at least one of sessionId, taskId, or search is required');
      }

      const sessionId = sessionIdRaw ? await resolveSessionId(ctx, sessionIdRaw) : undefined;
      const taskId = taskIdRaw ? await resolveTaskId(ctx, taskIdRaw) : undefined;
      const includeArchived = args.includeArchived === true;

      const includeToolCalls = args.includeToolCalls === true;
      const contentMode = args.contentMode === 'full' ? 'full' : 'preview';
      const rawLimit = typeof args.limit === 'number' ? args.limit : 20;
      const limit = Math.min(Math.max(0, Math.floor(rawLimit)) || 20, 100);
      const rawOffset = typeof args.offset === 'number' ? args.offset : 0;
      const offset = Math.max(0, Math.floor(rawOffset)) || 0;
      const order =
        args.order === 'asc' || args.order === 'desc'
          ? args.order
          : search && !sessionId
            ? 'desc'
            : 'asc';
      const role = args.role === 'user' || args.role === 'assistant' ? args.role : undefined;

      // Build WHERE conditions
      const conditions = [];
      if (sessionId) conditions.push(eq(messagesTable.session_id, sessionId));
      if (taskId) conditions.push(eq(messagesTable.task_id, taskId));
      if (role) conditions.push(eq(messagesTable.role, role));

      if (!includeToolCalls) {
        conditions.push(
          sql`${messagesTable.type} NOT IN ('file-history-snapshot', 'permission_request', 'input_request')`
        );
        // SQL-level heuristic to drop messages that consist solely of tool calls/results.
        // If content is an array with tool blocks but no text blocks, we filter it out here
        // so that SQL pagination (limit/offset) remains perfectly accurate.
        conditions.push(
          sql`NOT (${messagesTable.role} = 'user' AND CAST(${messagesTable.data} AS TEXT) LIKE '%"type":"tool_result"%' AND CAST(${messagesTable.data} AS TEXT) NOT LIKE '%"type":"text"%')`
        );
        conditions.push(
          sql`NOT (${messagesTable.role} = 'assistant' AND CAST(${messagesTable.data} AS TEXT) LIKE '%"type":"tool_use"%' AND CAST(${messagesTable.data} AS TEXT) NOT LIKE '%"type":"text"%')`
        );
      }

      // Search: parse "term1 term2 | term3 term4" into (t1 AND t2) OR (t3 AND t4)
      if (search) {
        const orGroups = search.split(/\s*\|\s*/).map((group) => {
          const terms = group.trim().split(/\s+/).filter(Boolean);
          return terms.map(
            (term) =>
              sql`LOWER(CAST(${messagesTable.data} AS TEXT)) LIKE ${`%${term.toLowerCase()}%`}`
          );
        });
        const searchCondition =
          orGroups.length === 1
            ? and(...orGroups[0])
            : or(...orGroups.map((andTerms) => and(...andTerms)));
        if (searchCondition) conditions.push(searchCondition);
      }

      // Default-exclude messages from archived sessions for unscoped queries.
      // An explicit sessionId/taskId (caller opted in) bypasses this filter.
      if (!sessionId && !taskId && !includeArchived) {
        const activeSessions = await ctx.app.service('sessions').find({
          query: { archived: false, $limit: 10000, $select: ['session_id'] },
          ...ctx.baseServiceParams,
        });
        const activeIds = (
          Array.isArray(activeSessions) ? activeSessions : activeSessions.data
        ).map((s: { session_id: string }) => s.session_id);
        if (activeIds.length === 0) {
          return textResult({ messages: [], total: 0, offset, limit });
        }
        conditions.push(inArray(messagesTable.session_id, activeIds));
      }

      // RBAC enforcement: when worktree_rbac is enabled, restrict this search
      // to sessions the caller can access. Superadmins bypass. When RBAC is
      // disabled (default / open-access mode), skip this filter entirely to
      // preserve backward-compatible behavior.
      if (isWorktreeRbacEnabled()) {
        const userRole = ctx.authenticatedUser?.role as string | undefined;
        if (!isSuperAdmin(userRole)) {
          const sessionRepo = new SessionRepository(ctx.db);
          const accessibleSessions = await sessionRepo.findAccessibleSessions(ctx.userId);
          const accessibleIds = accessibleSessions.map((s) => s.session_id);
          if (accessibleIds.length === 0) {
            return textResult({ messages: [], total: 0, offset, limit });
          }
          conditions.push(inArray(messagesTable.session_id, accessibleIds));
        }
      }

      const orderCol = sessionId ? messagesTable.index : messagesTable.timestamp;
      const orderBy = order === 'desc' ? desc(orderCol) : asc(orderCol);
      const whereCondition = conditions.length > 0 ? and(...conditions) : undefined;

      // Count total matches
      // biome-ignore lint/suspicious/noExplicitAny: Drizzle count
      // Count total matches
      // biome-ignore lint/suspicious/noExplicitAny: Drizzle count
      let countQuery = select(ctx.db, { count: sql`COUNT(*)` } as any).from(messagesTable);
      if (whereCondition) {
        // biome-ignore lint/suspicious/noExplicitAny: Drizzle type
        countQuery = (countQuery as any).where(whereCondition);
      }
      const countResult = (await (
        countQuery as unknown as { one: () => Promise<unknown> }
      ).one()) as { count: number } | undefined;
      const total = Number(countResult?.count ?? 0);

      const pageRows = await select(ctx.db)
        .from(messagesTable)
        .where(whereCondition)
        .orderBy(orderBy)
        .limit(limit)
        .offset(offset)
        .all();

      // Post-process
      type ProcessedMessage = {
        message_id: string;
        session_id: string;
        index: number;
        role: string;
        timestamp: string;
        task_id?: string;
        text: string;
        tool_call_count?: number;
      };

      const processed: ProcessedMessage[] = [];

      for (const row of pageRows) {
        const data = row.data as {
          content?: unknown;
          tool_uses?: unknown[];
          metadata?: unknown;
        };
        const content = data?.content;

        let text: string;
        let toolCallCount = 0;

        if (contentMode === 'preview') {
          text = row.content_preview || '';
        } else {
          if (typeof content === 'string') {
            text = content;
          } else if (Array.isArray(content)) {
            const blocks = content as ContentBlock[];
            const textBlocks: string[] = [];
            for (const block of blocks) {
              if (block.type === 'text' && typeof block.text === 'string') {
                textBlocks.push(block.text);
              } else if (block.type === 'tool_use') {
                toolCallCount++;
              }
            }
            text = textBlocks.join('\n\n');
          } else {
            text = row.content_preview || '';
          }
        }

        if (contentMode === 'preview' && Array.isArray(content)) {
          for (const block of content as ContentBlock[]) {
            if (block.type === 'tool_use') toolCallCount++;
          }
        }

        // We no longer skip empty assistant messages in memory.
        // The SQL `NOT LIKE '%"type":"text"%'` condition successfully excluded them.

        const msg: ProcessedMessage = {
          message_id: row.message_id,
          session_id: row.session_id,
          index: row.index,
          role: row.role,
          timestamp:
            row.timestamp instanceof Date ? row.timestamp.toISOString() : String(row.timestamp),
          text,
        };
        if (row.task_id) msg.task_id = row.task_id;
        if (toolCallCount > 0) msg.tool_call_count = toolCallCount;
        processed.push(msg);
      }

      return textResult({ messages: processed, total, offset, limit });
    }
  );
}
