import type { Board } from '@agor/core/types';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import type { BoardsServiceImpl } from '../../declarations.js';
import type { McpContext } from '../server.js';
import { coerceString, textResult } from '../utils.js';

export function registerBoardTools(server: McpServer, ctx: McpContext): void {
  // Tool 1: agor_boards_get
  server.registerTool(
    'agor_boards_get',
    {
      description:
        'Get information about a board, including zones, layout, and positioned entities (worktrees, cards). ' +
        'The response includes a `url` field with a clickable link to view the board in the UI. ' +
        'Set includeEntities=true to include positioned worktree/card entities with their coordinates.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        boardId: z.string().describe('Board ID (UUIDv7 or short ID)'),
        includeEntities: z
          .boolean()
          .optional()
          .describe(
            'Include positioned entities (worktrees, cards) with their x/y coordinates and zone assignments (default: false). Enable when you need to know where worktrees are placed on the canvas.'
          ),
      }),
    },
    async (args) => {
      const boardId = coerceString(args.boardId);
      if (!boardId) throw new Error('boardId is required');
      const board = await ctx.app.service('boards').get(boardId, ctx.baseServiceParams);

      const includeEntities = args.includeEntities === true; // default false, opt-in
      if (includeEntities) {
        const boardObjectsResult = await ctx.app
          .service('board-objects')
          .find({ query: { board_id: board.board_id }, ...ctx.baseServiceParams });
        const entities = (
          boardObjectsResult as { data: import('@agor/core/types').BoardEntityObject[] }
        ).data;
        return textResult({ ...board, entities });
      }

      return textResult(board);
    }
  );

  // Tool 2: agor_boards_list
  server.registerTool(
    'agor_boards_list',
    {
      description:
        'List all boards accessible to the current user. Each board includes a `url` field with a clickable link to view the board in the UI. By default, archived boards are excluded.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        limit: z
          .number()
          .optional()
          .describe('Maximum number of results (default: 10 for summary, 50 for full)'),
        includeArchived: z
          .boolean()
          .optional()
          .describe(
            'Include archived boards in results (default: false). By default, archived boards are excluded.'
          ),
        archived: z
          .boolean()
          .optional()
          .describe(
            'Filter to show ONLY archived boards. When true, returns only archived boards. Overrides includeArchived.'
          ),
        detailLevel: z
          .enum(['summary', 'full'])
          .optional()
          .describe(
            'Level of detail to return (default: summary). Summary omits large fields like objects (zone templates).'
          ),
      }),
    },
    async (args) => {
      const query: Record<string, unknown> = {};
      const detailLevel = args.detailLevel ?? 'summary';
      query.$limit = args.limit ?? (detailLevel === 'summary' ? 10 : 50);
      if (args.archived === true) {
        query.archived = true;
      } else if (!args.includeArchived) {
        query.archived = false;
      }

      if (detailLevel === 'summary') {
        query.$select = [
          'board_id',
          'name',
          'slug',
          'description',
          'icon',
          'url',
          'created_at',
          'updated_at',
          'archived',
        ];
      }

      const boards = await ctx.app.service('boards').find({ query, ...ctx.baseServiceParams });

      type BoardSummary = Omit<import('@agor/core/types').Board, 'objects'>;
      let allData: import('@agor/core/types').Board[] | BoardSummary[] = Array.isArray(boards)
        ? boards
        : boards.data;
      if (detailLevel === 'summary') {
        allData = (allData as import('@agor/core/types').Board[]).map((board) => {
          const { objects, ...rest } = board;
          return rest;
        });
      }

      if (Array.isArray(boards)) {
        return textResult(allData);
      }
      return textResult({
        ...boards,
        data: allData,
      });
    }
  );

  // Tool 3: agor_boards_update
  server.registerTool(
    'agor_boards_update',
    {
      description:
        'Update board metadata (name, icon, background) or objects (zones, text, markdown). Zones group worktrees and trigger sessions.',
      annotations: { idempotentHint: true },
      inputSchema: z.object({
        boardId: z.string().describe('Board ID (UUIDv7 or short ID)'),
        name: z.string().optional().describe('Board name (optional)'),
        description: z.string().optional().describe('Board description (optional)'),
        icon: z.string().optional().describe('Board icon/emoji (optional)'),
        color: z.string().optional().describe('Board color (hex format, optional)'),
        backgroundColor: z
          .string()
          .optional()
          .describe('Board background color (hex format, optional)'),
        customCss: z
          .string()
          .optional()
          .describe(
            'Custom CSS for board canvas animations (@keyframes, animation, background-size, etc.). Rendered in a scoped <style> tag. Dangerous patterns like url(), expression(), @import are blocked.'
          ),
        slug: z.string().optional().describe('URL-friendly slug (optional)'),
        customContext: z
          .object({})
          .passthrough()
          .optional()
          .describe('Custom context for templates (optional)'),
        upsertObjects: z
          .object({})
          .passthrough()
          .optional()
          .describe(
            'Board objects to upsert (zones, text, markdown). Keys are object IDs, values are object data.'
          ),
        removeObjects: z
          .array(z.string())
          .optional()
          .describe('Array of object IDs to remove from the board'),
        archived: z.boolean().optional().describe('Archive (true) or unarchive (false) the board'),
      }),
    },
    async (args) => {
      const boardId = coerceString(args.boardId);
      if (!boardId) throw new Error('boardId is required');
      const boardsService = ctx.app.service('boards') as unknown as BoardsServiceImpl;

      const metadataUpdates: Record<string, unknown> = {};
      if (args.name !== undefined) metadataUpdates.name = args.name;
      if (args.description !== undefined) metadataUpdates.description = args.description;
      if (args.icon !== undefined) metadataUpdates.icon = args.icon;
      if (args.color !== undefined) metadataUpdates.color = args.color;
      if (args.backgroundColor !== undefined)
        metadataUpdates.background_color = args.backgroundColor;
      if (args.customCss !== undefined) metadataUpdates.custom_css = args.customCss;
      if (args.slug !== undefined) metadataUpdates.slug = args.slug;
      if (args.customContext !== undefined) metadataUpdates.custom_context = args.customContext;

      if (Object.keys(metadataUpdates).length > 0) {
        await ctx.app.service('boards').patch(boardId, metadataUpdates, ctx.baseServiceParams);
      }

      if (
        args.upsertObjects &&
        typeof args.upsertObjects === 'object' &&
        !Array.isArray(args.upsertObjects)
      ) {
        const updatedBoard = await boardsService.batchUpsertBoardObjects(
          boardId,
          args.upsertObjects as unknown as unknown[],
          ctx.baseServiceParams
        );
        ctx.app.service('boards').emit('patched', updatedBoard);
      }

      if (args.removeObjects && Array.isArray(args.removeObjects)) {
        let finalBoard: Board | undefined;
        for (const objectId of args.removeObjects) {
          finalBoard = await boardsService.removeBoardObject(
            boardId,
            objectId,
            ctx.baseServiceParams
          );
        }
        if (finalBoard) ctx.app.service('boards').emit('patched', finalBoard);
      }

      if (args.archived !== undefined) {
        if (args.archived) {
          await boardsService.archive(boardId, ctx.baseServiceParams);
        } else {
          await boardsService.unarchive(boardId, ctx.baseServiceParams);
        }
      }

      const board = await ctx.app.service('boards').get(boardId, ctx.baseServiceParams);
      return textResult({ board, note: 'Board updated successfully.' });
    }
  );

  // Tool 4: agor_boards_create
  server.registerTool(
    'agor_boards_create',
    {
      description: 'Create a new board. Returns the created board object with its ID and URL.',
      inputSchema: z.object({
        name: z.string().describe('Board name (required)'),
        slug: z
          .string()
          .optional()
          .describe('URL-friendly slug (optional, auto-derived from name if not provided)'),
        description: z.string().optional().describe('Board description (optional)'),
        icon: z.string().optional().describe('Board icon/emoji (optional, e.g. "📋")'),
        color: z.string().optional().describe('Board color in hex format (optional)'),
        backgroundColor: z
          .string()
          .optional()
          .describe('Board background color in hex format (optional)'),
        customCss: z
          .string()
          .optional()
          .describe(
            'Custom CSS for board canvas animations (@keyframes, animation, etc.). Optional.'
          ),
      }),
    },
    async (args) => {
      const boardName = coerceString(args.name);
      if (!boardName) throw new Error('name is required');

      const boardData: Record<string, unknown> = {
        name: boardName,
        created_by: ctx.userId,
      };
      if (args.slug !== undefined) boardData.slug = coerceString(args.slug);
      if (args.description !== undefined) boardData.description = coerceString(args.description);
      if (args.icon !== undefined) boardData.icon = coerceString(args.icon);
      if (args.color !== undefined) boardData.color = coerceString(args.color);
      if (args.backgroundColor !== undefined)
        boardData.background_color = coerceString(args.backgroundColor);
      if (args.customCss !== undefined) boardData.custom_css = coerceString(args.customCss);

      const board = await ctx.app.service('boards').create(boardData, ctx.baseServiceParams);
      return textResult(board);
    }
  );

  // Tool 5: agor_boards_bulk_create
  server.registerTool(
    'agor_boards_bulk_create',
    {
      description: 'Create multiple boards in one operation.',
      inputSchema: z.object({
        boards: z
          .array(
            z.object({
              name: z.string().describe('Board name'),
              slug: z.string().optional().describe('URL-friendly slug'),
              description: z.string().optional().describe('Board description'),
              icon: z.string().optional().describe('Board icon/emoji'),
              color: z.string().optional().describe('Board color'),
              backgroundColor: z.string().optional().describe('Board background color'),
              customCss: z.string().optional().describe('Custom CSS'),
            })
          )
          .describe('List of boards to create'),
      }),
    },
    async (args) => {
      const creations = args.boards.map((b) => {
        const boardData: Record<string, unknown> = {
          name: coerceString(b.name)!,
          created_by: ctx.userId,
        };
        if (b.slug !== undefined) boardData.slug = coerceString(b.slug);
        if (b.description !== undefined) boardData.description = coerceString(b.description);
        if (b.icon !== undefined) boardData.icon = coerceString(b.icon);
        if (b.color !== undefined) boardData.color = coerceString(b.color);
        if (b.backgroundColor !== undefined)
          boardData.background_color = coerceString(b.backgroundColor);
        if (b.customCss !== undefined) boardData.custom_css = coerceString(b.customCss);
        return boardData;
      });

      const results = [];
      for (const boardData of creations) {
        const result = await ctx.app.service('boards').create(boardData, ctx.baseServiceParams);
        results.push(result);
      }
      return textResult({ created: results.length, boards: results });
    }
  );
}
