import { getBaseUrl, isWorktreeRbacEnabled } from '@agor/core/config';
import {
  and,
  boards,
  eq,
  exists,
  inArray,
  isNotNull,
  jsonExtract,
  or,
  select,
  sessions,
  sql,
  tasks,
  worktreeOwners,
  worktrees,
} from '@agor/core/db';
import { type UUID, WORKTREE_PERMISSION_LEVELS } from '@agor/core/types';
import { getBoardUrl, getSessionUrl } from '@agor/core/utils/url';
import { isSuperAdmin } from '../utils/worktree-authorization.js';
import type { McpContext } from './server.js';

const DEFAULT_SUMMARY_LIMIT = 10;
const MAX_SUMMARY_LIMIT = 100;

type ExistsQuery = {
  from(table: unknown): ExistsQuery;
  innerJoin(table: unknown, on: unknown): ExistsQuery;
  leftJoin(table: unknown, on: unknown): ExistsQuery;
  where(condition: unknown): Parameters<typeof exists>[0];
};

type ListQuery = {
  innerJoin(table: unknown, on: unknown): ListQuery;
  leftJoin(table: unknown, on: unknown): ListQuery;
  where(condition: unknown): ListQuery;
  limit(limit: number): ListQuery;
  all(): Promise<Array<Record<string, unknown>>>;
};

function clampLimit(value: unknown, fallback = DEFAULT_SUMMARY_LIMIT): number {
  const raw = typeof value === 'number' ? value : fallback;
  return Math.min(Math.max(1, Math.floor(raw) || fallback), MAX_SUMMARY_LIMIT);
}

function toIso(value: unknown): string | undefined {
  if (!value) return undefined;
  return new Date(value as string | number | Date).toISOString();
}

function parseMaybeJson<T>(value: unknown): T | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value !== 'string') return value as T;
  try {
    return JSON.parse(value) as T;
  } catch {
    return value as T;
  }
}

function truncate(value: unknown, maxLength: number): string | undefined {
  if (typeof value !== 'string') return undefined;
  return value.length > maxLength ? `${value.slice(0, maxLength - 3)}...` : value;
}

function rbacBypassed(ctx: McpContext): boolean {
  return (
    !isWorktreeRbacEnabled() || isSuperAdmin(ctx.authenticatedUser?.role as string | undefined)
  );
}

function accessibleWorktreeCondition(ctx: McpContext) {
  const permissiveLevels = WORKTREE_PERMISSION_LEVELS.filter((level) => level !== 'none');
  return or(isNotNull(worktreeOwners.user_id), inArray(worktrees.others_can, permissiveLevels));
}

function rawExistsSelect(ctx: McpContext): ExistsQuery {
  const dbSelect = (ctx.db as unknown as { select?: (columns: Record<string, unknown>) => unknown })
    .select;
  return (typeof dbSelect === 'function'
    ? dbSelect({ _: sql`1` })
    : select(ctx.db, { _: sql`1` })) as unknown as ExistsQuery;
}

function boardVisibilityCondition(ctx: McpContext) {
  if (rbacBypassed(ctx)) return undefined;
  const accessibleWorktreeExists = exists(
    rawExistsSelect(ctx)
      .from(worktrees)
      .leftJoin(
        worktreeOwners,
        and(
          eq(worktreeOwners.worktree_id, worktrees.worktree_id),
          eq(worktreeOwners.user_id, ctx.userId)
        )
      )
      .where(and(eq(worktrees.board_id, boards.board_id), accessibleWorktreeCondition(ctx)))
  );
  return or(eq(boards.created_by, ctx.userId), accessibleWorktreeExists);
}

function sessionTypeCondition(ctx: McpContext, sessionType?: 'gateway' | 'scheduled' | 'agent') {
  if (!sessionType) return undefined;
  const gatewayThreadId = jsonExtract(
    ctx.db,
    sessions.data,
    'custom_context.gateway_source.thread_id'
  );
  if (sessionType === 'gateway') return isNotNull(gatewayThreadId);
  if (sessionType === 'scheduled') return eq(sessions.scheduled_from_worktree, true);
  return and(eq(sessions.scheduled_from_worktree, false), sql`${gatewayThreadId} is null`);
}

export async function listBoardSummaries(
  ctx: McpContext,
  args: { limit?: number; includeArchived?: boolean; archived?: boolean }
) {
  const limit = clampLimit(args.limit);
  const conditions = [];
  if (args.archived === true) {
    conditions.push(eq(boards.archived, true));
  } else if (!args.includeArchived) {
    conditions.push(eq(boards.archived, false));
  }
  const visibility = boardVisibilityCondition(ctx);
  if (visibility) conditions.push(visibility);

  const baseUrl = await getBaseUrl();
  let query = select(ctx.db, {
    board_id: boards.board_id,
    name: boards.name,
    slug: boards.slug,
    description: jsonExtract(ctx.db, boards.data, 'description'),
    icon: jsonExtract(ctx.db, boards.data, 'icon'),
    created_at: boards.created_at,
    updated_at: boards.updated_at,
    archived: boards.archived,
  }).from(boards) as unknown as ListQuery;

  if (conditions.length > 0) query = query.where(and(...conditions));
  const rows = await query.limit(limit + 1).all();
  const page = rows.slice(0, limit);

  return {
    limit,
    has_more: rows.length > limit,
    data: page.map((row) => ({
      board_id: row.board_id,
      name: row.name,
      slug: row.slug,
      description: row.description ?? undefined,
      icon: row.icon ?? undefined,
      url: getBoardUrl(row.board_id as UUID, row.slug as string | null | undefined, baseUrl),
      created_at: toIso(row.created_at),
      last_updated: toIso(row.updated_at) ?? toIso(row.created_at),
      archived: Boolean(row.archived),
    })),
  };
}

export async function listWorktreeSummaries(
  ctx: McpContext,
  args: {
    repoId?: string;
    limit?: number;
    includeArchived?: boolean;
    archived?: boolean;
  }
) {
  const limit = clampLimit(args.limit);
  const conditions = [];
  if (args.repoId) conditions.push(eq(worktrees.repo_id, args.repoId));
  if (args.archived === true) {
    conditions.push(eq(worktrees.archived, true));
  } else if (!args.includeArchived) {
    conditions.push(eq(worktrees.archived, false));
  }
  if (!rbacBypassed(ctx)) conditions.push(accessibleWorktreeCondition(ctx));

  let query = select(ctx.db, {
    worktree_id: worktrees.worktree_id,
    repo_id: worktrees.repo_id,
    board_id: worktrees.board_id,
    name: worktrees.name,
    ref: worktrees.ref,
    path: jsonExtract(ctx.db, worktrees.data, 'path'),
    app_url: worktrees.app_url,
    created_at: worktrees.created_at,
    updated_at: worktrees.updated_at,
    archived: worktrees.archived,
  })
    .from(worktrees)
    .leftJoin(
      worktreeOwners,
      and(
        eq(worktreeOwners.worktree_id, worktrees.worktree_id),
        eq(worktreeOwners.user_id, ctx.userId)
      )
    ) as unknown as ListQuery;

  if (conditions.length > 0) query = query.where(and(...conditions));
  const rows = await query.limit(limit + 1).all();
  const page = rows.slice(0, limit);

  return {
    limit,
    has_more: rows.length > limit,
    data: page.map((row) => ({
      worktree_id: row.worktree_id,
      repo_id: row.repo_id,
      board_id: row.board_id ?? undefined,
      name: row.name,
      ref: row.ref,
      path: row.path,
      app_url: row.app_url ?? undefined,
      created_at: toIso(row.created_at),
      updated_at: toIso(row.updated_at) ?? toIso(row.created_at),
      archived: Boolean(row.archived),
    })),
  };
}

export async function listSessionSummaries(
  ctx: McpContext,
  args: {
    limit?: number;
    status?: string;
    boardId?: string;
    worktreeId?: string;
    includeArchived?: boolean;
    archived?: boolean;
    sessionType?: 'gateway' | 'scheduled' | 'agent';
  }
) {
  const limit = clampLimit(args.limit);
  const conditions = [];
  if (args.status) conditions.push(eq(sessions.status, args.status as never));
  if (args.boardId) conditions.push(eq(sessions.board_id, args.boardId));
  if (args.worktreeId) conditions.push(eq(sessions.worktree_id, args.worktreeId));
  if (args.archived === true) {
    conditions.push(eq(sessions.archived, true));
  } else if (!args.includeArchived) {
    conditions.push(eq(sessions.archived, false));
  }
  const typeCondition = sessionTypeCondition(ctx, args.sessionType);
  if (typeCondition) conditions.push(typeCondition);
  if (!rbacBypassed(ctx)) conditions.push(accessibleWorktreeCondition(ctx));

  const baseUrl = await getBaseUrl();
  let query = select(ctx.db, {
    session_id: sessions.session_id,
    worktree_id: sessions.worktree_id,
    status: sessions.status,
    agentic_tool: sessions.agentic_tool,
    created_at: sessions.created_at,
    updated_at: sessions.updated_at,
    archived: sessions.archived,
    scheduled_from_worktree: sessions.scheduled_from_worktree,
    title: jsonExtract(ctx.db, sessions.data, 'title'),
    model_config: jsonExtract(ctx.db, sessions.data, 'model_config'),
    custom_context: jsonExtract(ctx.db, sessions.data, 'custom_context'),
    worktree_board_id: worktrees.board_id,
    board_slug: boards.slug,
  })
    .from(sessions)
    .innerJoin(worktrees, eq(sessions.worktree_id, worktrees.worktree_id))
    .leftJoin(boards, eq(worktrees.board_id, boards.board_id))
    .leftJoin(
      worktreeOwners,
      and(
        eq(worktreeOwners.worktree_id, worktrees.worktree_id),
        eq(worktreeOwners.user_id, ctx.userId)
      )
    ) as unknown as ListQuery;

  if (conditions.length > 0) query = query.where(and(...conditions));
  const rows = await query.limit(limit + 1).all();
  const page = rows.slice(0, limit);

  return {
    limit,
    has_more: rows.length > limit,
    data: page.map((row) => {
      const modelConfig = parseMaybeJson(row.model_config);
      const customContext = parseMaybeJson(row.custom_context);
      return {
        session_id: row.session_id,
        worktree_id: row.worktree_id,
        title: typeof row.title === 'string' ? row.title : undefined,
        status: row.status,
        agentic_tool: row.agentic_tool,
        url: getSessionUrl(
          row.session_id as UUID,
          row.worktree_board_id as UUID | null | undefined,
          row.board_slug as string | null | undefined,
          baseUrl
        ),
        created_at: toIso(row.created_at),
        last_updated: toIso(row.updated_at) ?? toIso(row.created_at),
        archived: Boolean(row.archived),
        model_config: modelConfig,
        ...(args.sessionType
          ? {
              custom_context: customContext,
              scheduled_from_worktree: Boolean(row.scheduled_from_worktree),
            }
          : {}),
      };
    }),
  };
}

export async function listTaskSummaries(
  ctx: McpContext,
  args: { sessionId?: string; limit?: number; includeArchived?: boolean }
) {
  const limit = clampLimit(args.limit);
  const conditions = [];
  if (args.sessionId) {
    conditions.push(eq(tasks.session_id, args.sessionId));
  } else if (!args.includeArchived) {
    conditions.push(
      exists(
        rawExistsSelect(ctx)
          .from(sessions)
          .where(and(eq(sessions.session_id, tasks.session_id), eq(sessions.archived, false)))
      )
    );
  }

  if (!rbacBypassed(ctx)) {
    conditions.push(
      exists(
        rawExistsSelect(ctx)
          .from(sessions)
          .innerJoin(worktrees, eq(sessions.worktree_id, worktrees.worktree_id))
          .leftJoin(
            worktreeOwners,
            and(
              eq(worktreeOwners.worktree_id, worktrees.worktree_id),
              eq(worktreeOwners.user_id, ctx.userId)
            )
          )
          .where(and(eq(sessions.session_id, tasks.session_id), accessibleWorktreeCondition(ctx)))
      )
    );
  }

  let query = select(ctx.db, {
    task_id: tasks.task_id,
    session_id: tasks.session_id,
    status: tasks.status,
    queue_position: tasks.queue_position,
    created_at: tasks.created_at,
    completed_at: tasks.completed_at,
    created_by: tasks.created_by,
    session_md5: tasks.session_md5,
    full_prompt: jsonExtract(ctx.db, tasks.data, 'full_prompt'),
    model: jsonExtract(ctx.db, tasks.data, 'model'),
    tool_use_count: jsonExtract(ctx.db, tasks.data, 'tool_use_count'),
    duration_ms: jsonExtract(ctx.db, tasks.data, 'duration_ms'),
  }).from(tasks) as unknown as ListQuery;

  if (conditions.length > 0) query = query.where(and(...conditions));
  const rows = await query.limit(limit + 1).all();
  const page = rows.slice(0, limit);

  return {
    limit,
    has_more: rows.length > limit,
    data: page.map((row) => ({
      task_id: row.task_id,
      session_id: row.session_id,
      status: row.status,
      queue_position: row.queue_position ?? undefined,
      created_at: toIso(row.created_at),
      completed_at: toIso(row.completed_at),
      created_by: row.created_by,
      session_md5: row.session_md5 ?? undefined,
      full_prompt_preview: truncate(row.full_prompt, 500),
      model: row.model ?? undefined,
      tool_use_count: row.tool_use_count ?? undefined,
      duration_ms: row.duration_ms ?? undefined,
    })),
  };
}
