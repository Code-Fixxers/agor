import { WorktreeRepository, type WorktreeWithZoneAndSessions } from '@agor/core/db';
import {
  AVAILABLE_CLAUDE_MODEL_ALIASES,
  CODEX_MODEL_METADATA,
  COPILOT_MODEL_METADATA,
  DEFAULT_CLAUDE_MODEL,
  DEFAULT_CODEX_MODEL,
  DEFAULT_COPILOT_MODEL,
  DEFAULT_GEMINI_MODEL,
  GEMINI_MODELS,
} from '@agor/core/models';
import { resolveSessionDefaults } from '@agor/core/sessions';
import {
  AGENTIC_TOOL_CAPABILITIES,
  type AgenticToolName,
  type Board,
  getSessionType,
  type Session,
  type SessionID,
  type SessionType,
  type ZoneBoardObject,
} from '@agor/core/types';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import type { SessionsServiceImpl } from '../../declarations.js';
import type { SessionParams } from '../../services/sessions.js';
import { ensureCanPromptTargetSession } from '../../utils/worktree-authorization.js';
import {
  resolveBoardId,
  resolveMcpServerId,
  resolveSessionId,
  resolveWorktreeId,
} from '../resolve-ids.js';
import type { McpContext } from '../server.js';
import { sessionContextRequiredResult, textResult } from '../utils.js';
import { listAttachedMcpServers } from './mcp-servers.js';

/**
 * Shared Zod schema for specifying a model override at session-create / spawn /
 * subsession time. Mirrors Session['model_config']: `model` is required (the
 * whole point of this object is to pin a specific model), while `mode`,
 * `effort`, and `provider` are optional and fall back to sensible defaults.
 * Wired through to `session.model_config` so the executor actually spawns on
 * the requested model (see query-builder.ts).
 *
 * Accepts two shapes for MCP-client ergonomics:
 *   - String shorthand: `"claude-opus-4-6"` — coerced via `coerceModelConfig`
 *     in each handler to `{ model: "claude-opus-4-6" }`. Most callers just
 *     want to pin a model — forcing them to construct the full object is
 *     hostile UX (and several MCP clients silently drop nested objects in
 *     tool args, see PR #1056 background).
 *   - Full object: `{ mode, model, effort, provider }` for callers that need
 *     to override `mode`/`effort`/`provider`.
 *
 * IMPORTANT — no `.transform()` here. Zod's JSON-Schema converter
 * (`zod/v4-mini`'s `toJSONSchema`, used in `mcp/server.ts` to populate the
 * cached registry consumed by `agor_search_tools(detail:"full")`) throws on
 * transforms with "Transforms cannot be represented in JSON Schema". The
 * catch in `server.ts` then degrades the WHOLE containing tool schema to
 * `{ type: 'object' }`, hiding every input parameter from MCP clients. So
 * normalization happens in `coerceModelConfig` instead, called inline by
 * each handler.
 *
 * Call `agor_models_list` to discover valid model IDs per agenticTool.
 */
const modelConfigObjectSchema = z.object({
  mode: z.enum(['alias', 'exact']).optional().describe("Model selection mode (default: 'alias')"),
  // .min(1): reject empty-string model explicitly so callers don't silently
  // fall through to user defaults when they meant to pin a specific model.
  model: z
    .string()
    .min(1)
    .describe("Model identifier (e.g. 'claude-opus-4-6', 'claude-sonnet-4-6')"),
  effort: z
    .enum(['low', 'medium', 'high', 'max'])
    .optional()
    .describe('Reasoning effort level (default: high)'),
  provider: z.string().optional().describe("Provider ID (OpenCode only, e.g. 'anthropic')"),
});

const modelConfigInputSchema = z
  .union([
    z
      .string()
      .min(1)
      .describe(
        "Shorthand: just the model ID string (e.g. 'claude-opus-4-6'). Equivalent to { model: <id> }."
      ),
    modelConfigObjectSchema,
  ])
  .optional()
  .describe(
    "Model override for this session. Pass either a model ID string (e.g. 'claude-opus-4-6') or a full { mode, model, effort, provider } object. Overrides the user default model_config and is threaded through to the spawned agent process. Call agor_models_list to discover valid model IDs per agenticTool."
  );

/**
 * Normalize the two input shapes (string shorthand or full object) into the
 * partial-object shape downstream code expects (`ModelConfigInput` from
 * `@agor/core/models`). See `modelConfigInputSchema` for why this lives at
 * the handler boundary instead of as a Zod `.transform()`.
 */
type ModelConfigArg = string | z.infer<typeof modelConfigObjectSchema> | undefined;
function coerceModelConfig(
  input: ModelConfigArg
): z.infer<typeof modelConfigObjectSchema> | undefined {
  if (input === undefined) return undefined;
  if (typeof input === 'string') return { model: input };
  return input;
}

export function registerSessionTools(server: McpServer, ctx: McpContext): void {
  // Tool 1: agor_sessions_list
  server.registerTool(
    'agor_sessions_list',
    {
      description: 'List sessions accessible to the user. Each result includes a `url` field.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        limit: z.number().optional().describe('Default: 50'),
        status: z
          .enum(['idle', 'running', 'completed', 'failed'])
          .optional()
          .describe('Filter by status'),
        boardId: z.string().optional().describe('Filter by board'),
        worktreeId: z.string().optional().describe('Filter by worktree'),
        includeArchived: z.boolean().optional().describe('Include archived (default: false)'),
        archived: z.boolean().optional().describe('ONLY archived (overrides includeArchived)'),
        sessionType: z
          .enum(['gateway', 'scheduled', 'agent'])
          .optional()
          .describe('Filter: gateway (messaging) | scheduled | agent (manual)'),
        detailLevel: z
          .enum(['summary', 'full'])
          .optional()
          .describe(
            'Level of detail to return (default: summary). Summary omits large text fields like notes and last_message.'
          ),
      }),
    },
    async (args) => {
      const query: Record<string, unknown> = {};
      // When sessionType is set, skip service-level pagination (it runs before our filter)
      // and apply the requested limit ourselves after filtering.
      const requestedLimit = args.limit ?? 50;
      if (!args.sessionType) query.$limit = requestedLimit;
      if (args.status) query.status = args.status;
      if (args.boardId) query.board_id = await resolveBoardId(ctx, args.boardId);
      if (args.worktreeId) query.worktree_id = await resolveWorktreeId(ctx, args.worktreeId);
      if (args.archived === true) {
        query.archived = true;
      } else if (!args.includeArchived) {
        query.archived = false;
      }
      const result = await ctx.app.service('sessions').find({ query, ...ctx.baseServiceParams });

      let allData: Session[] = Array.isArray(result) ? result : result.data;

      // Apply sessionType filter (post-query since custom_context/scheduled_from_worktree aren't in query schema)
      if (args.sessionType) {
        const targetType = args.sessionType as SessionType;
        const filterFn = (s: Session) => getSessionType(s) === targetType;
        const filtered = allData.filter(filterFn);
        allData = requestedLimit ? filtered.slice(0, requestedLimit) : filtered;
      }

      const detailLevel = args.detailLevel ?? 'summary';
      if (detailLevel === 'summary') {
        allData = allData.map((session) => {
          // biome-ignore lint/suspicious/noExplicitAny: Omitting fields dynamically
          const { notes, last_message, ...rest } = session as any;
          return rest;
        }) as Session[];
      }

      if (Array.isArray(result)) {
        return textResult(allData);
      }
      return textResult({
        ...result,
        data: allData,
        // biome-ignore lint/suspicious/noExplicitAny: Paginated result type
        total: args.sessionType ? allData.length : (result as any).total,
      });
    }
  );

  // Tool 2: agor_sessions_get
  server.registerTool(
    'agor_sessions_get',
    {
      description:
        'Get a session with genealogy, state, a `url` field, and attached MCP servers including OAuth auth status.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        sessionId: z.string().describe('Session ID (UUIDv7 or short ID)'),
      }),
    },
    async (args) => {
      const sessionParams: SessionParams = {
        ...ctx.baseServiceParams,
        _include_last_message: true,
        _last_message_truncation_length: 500,
      };
      const session = await ctx.app
        .service('sessions')
        .get(args.sessionId, sessionParams as Parameters<SessionsServiceImpl['get']>[1]);
      const attached_mcp_servers = await listAttachedMcpServers(ctx, session.session_id);
      return textResult({ ...session, attached_mcp_servers });
    }
  );

  // Tool 3: agor_sessions_get_current
  server.registerTool(
    'agor_sessions_get_current',
    {
      description:
        'Get the current session plus denormalized worktree/repo/board context and attached MCP servers with OAuth auth status.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({}),
    },
    async () => {
      if (!ctx.sessionId) return sessionContextRequiredResult();
      const currentSessionId = ctx.sessionId;
      const currentSessionParams: SessionParams = {
        ...ctx.baseServiceParams,
        _include_last_message: true,
        _last_message_truncation_length: 500,
      };
      const session = await ctx.app
        .service('sessions')
        .get(currentSessionId, currentSessionParams as Parameters<SessionsServiceImpl['get']>[1]);

      // Denormalize worktree, repo, and board context
      let worktree: Record<string, unknown> | null = null;
      let repo: Record<string, unknown> | null = null;
      let board: Record<string, unknown> | null = null;

      if (session.worktree_id) {
        try {
          const wt = await ctx.app
            .service('worktrees')
            .get(session.worktree_id, ctx.baseServiceParams);
          worktree = {
            worktree_id: wt.worktree_id,
            name: wt.name,
            ref: wt.ref,
            path: wt.path,
            board_id: wt.board_id,
            repo_id: wt.repo_id,
          };

          if (wt.repo_id) {
            try {
              const r = await ctx.app.service('repos').get(wt.repo_id, ctx.baseServiceParams);
              repo = {
                repo_id: r.repo_id,
                name: r.name,
                slug: r.slug,
              };
            } catch {
              // repo may have been deleted
            }
          }

          if (wt.board_id) {
            try {
              const b = await ctx.app.service('boards').get(wt.board_id, ctx.baseServiceParams);
              board = {
                board_id: b.board_id,
                name: b.name,
                slug: b.slug,
              };
            } catch {
              // board may have been deleted
            }
          }
        } catch {
          // worktree may have been deleted
        }
      }

      const attached_mcp_servers = await listAttachedMcpServers(ctx, currentSessionId);

      return textResult({
        session,
        worktree,
        repo,
        board,
        attached_mcp_servers,
      });
    }
  );

  // Tool 3b: agor_sessions_get_current_context
  // Returns a lean, deduplicated orientation payload. Each field appears exactly once.
  // Agents needing full entity details should call get_current, sessions_get, etc.
  server.registerTool(
    'agor_sessions_get_current_context',
    {
      description:
        'Lean orientation snapshot for the current session: identity, user, git, worktree, board, repo, genealogy, siblings. Deduplicated.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        includeSiblings: z
          .boolean()
          .optional()
          .describe('Include sibling sessions in same worktree (default: true)'),
      }),
    },
    async (args) => {
      if (!ctx.sessionId) return sessionContextRequiredResult();
      const currentSessionId = ctx.sessionId;
      const includeSiblings = args.includeSiblings !== false;

      // Fetch session and user in parallel (no dependencies)
      const [session, user] = await Promise.all([
        ctx.app.service('sessions').get(currentSessionId, ctx.baseServiceParams),
        ctx.app.service('users').get(ctx.userId, ctx.baseServiceParams),
      ]);

      // Build the lean response — each piece of information appears exactly once
      const result: Record<string, unknown> = {
        // Session identity (the minimum to know "who am I")
        session_id: session.session_id,
        url: session.url,
        status: session.status,
        agentic_tool: session.agentic_tool,
        title: session.title,
        model: session.model_config?.model || null,
        effort: session.model_config?.effort || null,

        // User (who is authenticated / who is prompting)
        user_name: user.name,
        user_email: user.email,
        user_role: user.role,
      };

      // Creator info only when different from authenticated user
      if (session.created_by && session.created_by !== ctx.userId) {
        try {
          const creator = await ctx.app
            .service('users')
            .get(session.created_by, ctx.baseServiceParams);
          result.created_by_name = creator.name;
          result.created_by_email = creator.email;
        } catch {
          // creator may have been deleted
        }
      }

      // Genealogy (flat — no nested object needed)
      const gen = session.genealogy;
      result.genealogy = gen?.parent_session_id
        ? 'spawned'
        : gen?.forked_from_session_id
          ? 'forked'
          : 'root';
      result.parent_session_id = gen?.parent_session_id || gen?.forked_from_session_id || null;
      result.children_count = gen?.children?.length || 0;

      // Git state (flat)
      result.branch = session.git_state?.ref || null;
      result.base_sha = session.git_state?.base_sha || null;
      result.current_sha = session.git_state?.current_sha || null;

      if (session.worktree_id) {
        try {
          // worktrees.get returns WorktreeWithZoneAndSessions (enriched with zone info)
          const wt = (await ctx.app
            .service('worktrees')
            .get(session.worktree_id, ctx.baseServiceParams)) as WorktreeWithZoneAndSessions;

          // Worktree context (no IDs that duplicate other sections)
          result.worktree_id = wt.worktree_id;
          result.worktree_name = wt.name;
          result.worktree_path = wt.path;
          result.base_ref = wt.base_ref || null;
          result.issue_url = wt.issue_url || null;
          result.pull_request_url = wt.pull_request_url || null;
          result.notes = wt.notes || null;
          result.zone_label = wt.zone_label || null;
          result.environment_status = wt.environment_instance?.status || null;
          result.app_url = wt.app_url || null;

          // Fetch repo and board in parallel
          const [repoResult, boardResult] = await Promise.allSettled([
            wt.repo_id
              ? ctx.app.service('repos').get(wt.repo_id, ctx.baseServiceParams)
              : Promise.reject(new Error('no repo')),
            wt.board_id
              ? ctx.app.service('boards').get(wt.board_id, ctx.baseServiceParams)
              : Promise.reject(new Error('no board')),
          ]);

          if (repoResult.status === 'fulfilled') {
            const r = repoResult.value;
            result.repo_slug = r.slug;
            result.repo_name = r.name;
            result.repo_path = r.local_path;
            result.default_branch = r.default_branch || null;
          }

          if (boardResult.status === 'fulfilled') {
            const b = boardResult.value;
            result.board_name = b.name;
            result.board_slug = b.slug;

            // Extract zones from board objects
            const boardObjects: Board['objects'] = b.objects;
            if (boardObjects) {
              const zones: { label?: string; status?: string; has_trigger: boolean }[] = [];
              for (const obj of Object.values(boardObjects)) {
                if (obj.type === 'zone') {
                  const zone = obj as ZoneBoardObject;
                  zones.push({
                    label: zone.label,
                    status: zone.status,
                    has_trigger: !!zone.trigger,
                  });
                }
              }
              if (zones.length > 0) {
                result.board_zones = zones;
              }
            }
          }

          // Sibling sessions in the same worktree
          if (includeSiblings) {
            try {
              // Fetch 11 to guarantee 10 siblings after excluding current session
              const siblings = await ctx.app.service('sessions').find({
                query: {
                  worktree_id: session.worktree_id,
                  archived: false,
                  $limit: 11,
                  $sort: { last_updated: -1 },
                },
                ...ctx.baseServiceParams,
              });
              const siblingList = (Array.isArray(siblings) ? siblings : siblings.data)
                .filter((s: { session_id: string }) => s.session_id !== session.session_id)
                .slice(0, 10)
                .map(
                  (s: {
                    session_id: string;
                    title?: string;
                    status: string;
                    agentic_tool: string;
                  }) => ({
                    session_id: s.session_id,
                    title: s.title,
                    status: s.status,
                    agentic_tool: s.agentic_tool,
                  })
                );
              if (siblingList.length > 0) {
                result.sibling_sessions = siblingList;
              }
            } catch {
              // non-critical, skip
            }
          }
        } catch {
          // worktree may have been deleted
        }
      }

      return textResult(result);
    }
  );

  // Tool 4: agor_sessions_spawn
  server.registerTool(
    'agor_sessions_spawn',
    {
      description:
        'Spawn a child session in the current worktree. Tracks parent-child genealogy. Config inherits from parent.',
      inputSchema: z.object({
        prompt: z.string().describe('Prompt for the subsession'),
        title: z.string().optional().describe('Defaults to first 100 chars of prompt'),
        agenticTool: z
          .enum(['claude-code', 'codex', 'gemini', 'opencode'])
          .optional()
          .describe('Agent (default: parent)'),
        enableCallback: z
          .boolean()
          .optional()
          .describe('Callback parent on completion (default: true)'),
        includeLastMessage: z
          .boolean()
          .optional()
          .describe('Include final result in callback (default: true)'),
        includeOriginalPrompt: z
          .boolean()
          .optional()
          .describe('Include spawn prompt in callback (default: false)'),
        extraInstructions: z.string().optional().describe('Appended to spawn prompt'),
        taskId: z.string().optional().describe('Link to task'),
        mcpServerIds: z
          .array(z.string())
          .optional()
          .describe(
            'MCP server IDs to attach. Overrides parent session inheritance. Omit to inherit from parent. Pass empty array for no MCPs.'
          ),
        modelConfig: modelConfigInputSchema,
      }),
    },
    async (args) => {
      if (!ctx.sessionId) return sessionContextRequiredResult();
      const currentSessionId = ctx.sessionId;
      const spawnData: Partial<import('@agor/core/types').SpawnConfig> = {
        prompt: args.prompt,
        title: args.title,
        agent: args.agenticTool as AgenticToolName | undefined,
        enableCallback: args.enableCallback,
        includeLastMessage: args.includeLastMessage,
        includeOriginalPrompt: args.includeOriginalPrompt,
        extraInstructions: args.extraInstructions,
        task_id: args.taskId,
        mcpServerIds: args.mcpServerIds,
        modelConfig: coerceModelConfig(args.modelConfig),
      };

      const childSession = await (
        ctx.app.service('sessions') as unknown as SessionsServiceImpl
      ).spawn(currentSessionId, spawnData, ctx.baseServiceParams);

      const task = await ctx.app.service('/sessions/:id/prompt').create(
        {
          prompt: args.prompt,
          permissionMode: childSession.permission_config?.mode || 'acceptEdits',
          stream: true,
        },
        {
          ...ctx.baseServiceParams,
          route: { id: childSession.session_id },
        }
      );

      return textResult({
        session: childSession,
        taskId: task.task_id,
        status: task.status,
        note: 'Subsession created and prompt execution started in background.',
      });
    }
  );

  // Tool 5: agor_sessions_prompt
  server.registerTool(
    'agor_sessions_prompt',
    {
      description:
        'Prompt a session. Modes: continue (append), fork (sibling), subsession (child), btw (ephemeral fork; auto-callback + auto-archive).',
      inputSchema: z.object({
        sessionId: z.string().describe('Target session ID'),
        prompt: z.string().describe('Prompt to execute'),
        mode: z
          .enum(['continue', 'fork', 'subsession', 'btw'])
          .describe('continue | fork | subsession | btw'),
        agenticTool: z
          .enum(['claude-code', 'codex', 'gemini'])
          .optional()
          .describe('subsession mode only; defaults to parent'),
        title: z.string().optional().describe('For fork/subsession'),
        taskId: z.string().optional().describe('Fork/spawn point task ID'),
        mcpServerIds: z
          .array(z.string())
          .optional()
          .describe(
            'MCP server IDs for subsession mode. Overrides parent inheritance. Omit to inherit from parent. Pass empty array for no MCPs.'
          ),
        modelConfig: modelConfigInputSchema,
      }),
    },
    async (args) => {
      const mode = args.mode;
      const sessionId = await resolveSessionId(ctx, args.sessionId);

      if (mode === 'continue') {
        // The prompt route returns the Task entity directly. Whether it ran
        // immediately or got queued is encoded in `task.status` — there's no
        // separate "queued vs ran" wire shape to branch on.
        const task = await ctx.app
          .service('/sessions/:id/prompt')
          .create(
            { prompt: args.prompt, stream: true },
            { ...ctx.baseServiceParams, route: { id: sessionId } }
          );

        if (task.status === 'queued') {
          return textResult({
            success: true,
            queued: true,
            taskId: task.task_id,
            queue_position: task.queue_position,
            note: 'Session is busy. Prompt has been queued and will execute automatically when the session becomes idle.',
          });
        }
        return textResult({
          success: true,
          taskId: task.task_id,
          status: task.status,
          note: 'Prompt added to existing session and execution started.',
        });
      } else if (mode === 'fork' || mode === 'btw') {
        // Check if the target session's tool supports forking
        const targetSession = await ctx.app
          .service('sessions')
          .get(sessionId, ctx.baseServiceParams);
        const caps = AGENTIC_TOOL_CAPABILITIES[targetSession.agentic_tool as AgenticToolName];
        if (caps && !caps.supportsSessionFork) {
          return textResult({
            error: `${targetSession.agentic_tool} does not support session forking. Use mode "subsession" instead to delegate work to a fresh session.`,
          });
        }
        if (mode === 'btw' && !ctx.sessionId) return sessionContextRequiredResult();

        // Shared fork+prompt flow for both "fork" and "btw" modes
        const forkData: { prompt: string; task_id?: string } = { prompt: args.prompt };
        if (args.taskId) forkData.task_id = args.taskId;

        const forkedSession = await (
          ctx.app.service('sessions') as unknown as SessionsServiceImpl
        ).fork(sessionId, forkData, ctx.baseServiceParams);

        // Build patch for the fork — title for both modes, btw-specific metadata for btw
        const forkPatch: Record<string, unknown> = {};
        if (args.title) forkPatch.title = args.title;

        if (mode === 'btw') {
          forkPatch.fork_origin = 'btw';
          forkPatch.callback_config = {
            enabled: true,
            callback_session_id: ctx.sessionId as SessionID,
            callback_created_by: ctx.userId,
            callback_mode: 'once',
          };
        }

        if (Object.keys(forkPatch).length > 0) {
          await ctx.app
            .service('sessions')
            .patch(forkedSession.session_id, forkPatch, ctx.baseServiceParams);
        }

        const updatedSession = await ctx.app
          .service('sessions')
          .get(forkedSession.session_id, ctx.baseServiceParams);

        const task = await ctx.app.service('/sessions/:id/prompt').create(
          {
            prompt: args.prompt,
            permissionMode: updatedSession.permission_config?.mode,
            stream: true,
          },
          { ...ctx.baseServiceParams, route: { id: forkedSession.session_id } }
        );

        const note =
          mode === 'btw'
            ? 'Ephemeral "btw" fork created. Result will be sent back via callback when done, then the fork will auto-archive.'
            : 'Forked session created and prompt execution started.';

        return textResult({
          session: updatedSession,
          taskId: task.task_id,
          status: task.status,
          note,
        });
      } else if (mode === 'subsession') {
        const spawnData: Partial<import('@agor/core/types').SpawnConfig> = {
          prompt: args.prompt,
          mcpServerIds: args.mcpServerIds,
          modelConfig: coerceModelConfig(args.modelConfig),
        };
        if (args.title) spawnData.title = args.title;
        if (args.agenticTool) spawnData.agent = args.agenticTool as AgenticToolName;
        if (args.taskId) spawnData.task_id = args.taskId;

        const childSession = await (
          ctx.app.service('sessions') as unknown as SessionsServiceImpl
        ).spawn(sessionId, spawnData, ctx.baseServiceParams);

        const task = await ctx.app.service('/sessions/:id/prompt').create(
          {
            prompt: args.prompt,
            permissionMode: childSession.permission_config?.mode,
            stream: true,
          },
          { ...ctx.baseServiceParams, route: { id: childSession.session_id } }
        );

        return textResult({
          session: childSession,
          taskId: task.task_id,
          status: task.status,
          note: 'Subsession created and prompt execution started.',
        });
      }

      return textResult({ error: `Unknown mode: ${mode}` });
    }
  );

  // Tool 6: agor_sessions_create
  server.registerTool(
    'agor_sessions_create',
    {
      description:
        'Create an independent session in a worktree (no parent-child link). MCPs inherit from worktree > user defaults unless overridden; `modelConfig` can pin a model.',
      inputSchema: z.object({
        worktreeId: z.string().describe('Worktree ID (required)'),
        agenticTool: z.enum(['claude-code', 'codex', 'gemini']).describe('Agent (required)'),
        title: z.string().optional(),
        description: z.string().optional(),
        contextFiles: z.array(z.string()).optional().describe('Context file paths'),
        initialPrompt: z.string().optional().describe('Prompt to execute immediately'),
        enableCallback: z
          .boolean()
          .optional()
          .describe('Notify creator on completion (default: false)'),
        callbackSessionId: z
          .string()
          .optional()
          .describe('Session to notify (default: creating session)'),
        includeLastMessage: z
          .boolean()
          .optional()
          .describe('Include final result in callback (default: true)'),
        includeOriginalPrompt: z
          .boolean()
          .optional()
          .describe('Include original prompt in callback (default: false)'),
        callbackMode: z
          .enum(['once', 'persistent'])
          .optional()
          .describe('"once" (default) or "persistent"'),
        mcpServerIds: z
          .array(z.string())
          .optional()
          .describe(
            'MCP server IDs to attach. Overrides worktree and user default inheritance. Omit to use worktree config > user defaults.'
          ),
        modelConfig: modelConfigInputSchema,
      }),
    },
    async (args) => {
      const agenticTool = args.agenticTool as AgenticToolName;

      // Fetch user data to get unix_username
      const user = await ctx.app.service('users').get(ctx.userId, ctx.baseServiceParams);

      // Get worktree to extract repo context
      const worktree = await ctx.app
        .service('worktrees')
        .get(args.worktreeId, ctx.baseServiceParams);

      // Get current git state
      const { getGitState, getCurrentBranch } = await import('@agor/core/git');
      const currentSha = await getGitState(worktree.path);
      const currentRef = await getCurrentBranch(worktree.path);

      // Resolve permission_config / model_config / inherited mcp_server_ids
      // from the explicit MCP args (highest priority) > user defaults > system
      // fallback. Single source of truth for this dance lives in
      // `@agor/core/sessions` so MCP tools, the gateway, and the
      // `before:create` hook can't drift apart.
      //
      // For the explicit MCP args we resolve short IDs first; worktree/user
      // defaults are already full UUIDs, so the helper passes them through.
      const explicitMcpServerIds =
        args.mcpServerIds !== undefined
          ? await Promise.all(args.mcpServerIds.map((id) => resolveMcpServerId(ctx, id)))
          : undefined;
      const resolvedDefaults = resolveSessionDefaults({
        agenticTool,
        user,
        worktree,
        overrides: {
          modelConfig: coerceModelConfig(args.modelConfig),
          mcpServerIds: explicitMcpServerIds,
        },
      });
      const permissionConfig = resolvedDefaults.permission_config;
      const modelConfig = resolvedDefaults.model_config;
      const mcpServerIds = resolvedDefaults.mcp_server_ids;
      const permissionMode = permissionConfig.mode;
      // Track whether the caller explicitly requested these servers. When they
      // did, we surface attach failures in the response instead of silently
      // dropping them (the "mcpServerId doesn't stick" bug). For inherited
      // servers (worktree/user defaults) we preserve the existing "gracefully
      // skip deleted/invalid" behavior so startup doesn't get chatty.
      const mcpServerIdsFromArgs = args.mcpServerIds !== undefined;

      // Build callback configuration for remote session callbacks
      const callbackConfig: Record<string, unknown> = {};

      // Determine the effective callback target session ID
      const effectiveCallbackSessionId = args.callbackSessionId || ctx.sessionId;
      const wantsCallback = args.enableCallback || args.callbackSessionId;
      if (wantsCallback && !effectiveCallbackSessionId) return sessionContextRequiredResult();

      // Validate user has prompt permission on the callback target session's worktree
      if (wantsCallback && args.callbackSessionId) {
        const worktreeRepo = new WorktreeRepository(ctx.db);
        await ensureCanPromptTargetSession(
          args.callbackSessionId,
          ctx.userId,
          ctx.app,
          worktreeRepo
        );
      }

      if (args.enableCallback !== undefined) {
        callbackConfig.enabled = args.enableCallback;
      }
      if (wantsCallback) {
        callbackConfig.enabled = true;
        callbackConfig.callback_session_id = effectiveCallbackSessionId;
        callbackConfig.callback_created_by = ctx.userId;
      }
      if (args.includeLastMessage !== undefined) {
        callbackConfig.include_last_message = args.includeLastMessage;
      }
      if (args.includeOriginalPrompt !== undefined) {
        callbackConfig.include_original_prompt = args.includeOriginalPrompt;
      }
      if (wantsCallback) {
        callbackConfig.callback_mode = args.callbackMode ?? 'once';
      }

      const sessionData: Record<string, unknown> = {
        worktree_id: worktree.worktree_id,
        agentic_tool: agenticTool,
        status: 'idle',
        title: args.title,
        description: args.description,
        created_by: ctx.userId,
        unix_username: user.unix_username,
        permission_config: permissionConfig,
        ...(modelConfig && { model_config: modelConfig }),
        ...(Object.keys(callbackConfig).length > 0 && { callback_config: callbackConfig }),
        contextFiles: args.contextFiles || [],
        git_state: {
          ref: currentRef,
          base_sha: currentSha,
          current_sha: currentSha,
        },
        genealogy: { children: [] },
        tasks: [],
      };

      const session = await ctx.app.service('sessions').create(sessionData, ctx.baseServiceParams);

      // Attach MCP servers (inherited from worktree or user defaults, or
      // explicitly requested via args.mcpServerIds). Explicit failures are
      // collected and returned to the caller so they don't silently vanish.
      const mcpAttachFailures: Array<{ mcp_server_id: string; reason: string }> = [];
      if (mcpServerIds && mcpServerIds.length > 0) {
        for (const mcpServerId of mcpServerIds) {
          try {
            // Attach via the session-scoped REST surface — `session-mcp-servers`
            // (flat) is read-only here; the create handler lives on
            // `/sessions/:id/mcp-servers` with `{ mcpServerId }` (camelCase).
            // See register-routes.ts: `/sessions/:id/mcp-servers` create handler.
            await ctx.app
              .service('/sessions/:id/mcp-servers')
              .create(
                { mcpServerId },
                { ...ctx.baseServiceParams, route: { id: session.session_id } }
              );
          } catch (error) {
            const reason = error instanceof Error ? error.message : String(error);
            if (mcpServerIdsFromArgs) {
              // Caller explicitly asked for this server — surface the failure.
              mcpAttachFailures.push({ mcp_server_id: mcpServerId, reason });
            } else {
              // Inherited from worktree/user defaults — gracefully skip.
              console.warn(
                `Skipped MCP server ${mcpServerId} for session ${session.session_id}: ${reason}`
              );
            }
          }
        }
      }

      // Execute initial prompt if provided
      let initialTask = null;
      if (args.initialPrompt) {
        initialTask = await ctx.app
          .service('/sessions/:id/prompt')
          .create(
            { prompt: args.initialPrompt, permissionMode, stream: true },
            { ...ctx.baseServiceParams, route: { id: session.session_id } }
          );
      }

      const callbackNote = callbackConfig.callback_session_id
        ? ` Callback will be sent to session ${(callbackConfig.callback_session_id as string).substring(0, 8)} on completion.`
        : '';

      const mcpFailureNote =
        mcpAttachFailures.length > 0
          ? ` Warning: ${mcpAttachFailures.length} requested MCP server(s) failed to attach — see mcpAttachFailures.`
          : '';

      return textResult({
        session,
        taskId: initialTask?.task_id,
        note: args.initialPrompt
          ? `Session created and initial prompt execution started.${callbackNote}${mcpFailureNote}`
          : `Session created successfully.${callbackNote}${mcpFailureNote}`,
        ...(mcpAttachFailures.length > 0 && { mcpAttachFailures }),
      });
    }
  );

  // Tool 7: agor_sessions_update
  server.registerTool(
    'agor_sessions_update',
    {
      description:
        'Update session metadata (title, description, status, archived, callback config). Useful for agents to self-document their work or manage callback settings.',
      annotations: { idempotentHint: true },
      inputSchema: z.object({
        sessionId: z.string().describe('Session ID to update (UUIDv7 or short ID)'),
        title: z.string().optional().describe('New session title (optional)'),
        description: z.string().optional().describe('New session description (optional)'),
        status: z
          .enum(['idle', 'running', 'completed', 'failed'])
          .optional()
          .describe('New session status (optional)'),
        archived: z
          .boolean()
          .optional()
          .describe('Set archive state. true to archive, false to unarchive (optional)'),
        enableCallback: z
          .boolean()
          .optional()
          .describe('Enable or disable callbacks on this session (optional)'),
        callbackMode: z
          .enum(['once', 'persistent'])
          .optional()
          .describe(
            'Callback mode: "once" fires once then auto-disables, "persistent" fires every time (optional)'
          ),
      }),
    },
    async (args) => {
      const updates: Record<string, unknown> = {};
      if (args.title !== undefined) updates.title = args.title;
      if (args.description !== undefined) updates.description = args.description;
      if (args.status !== undefined) updates.status = args.status;
      if (args.archived !== undefined) {
        updates.archived = args.archived;
        updates.archived_reason = args.archived ? 'manual' : undefined;
      }

      // Handle callback config updates
      if (args.enableCallback !== undefined || args.callbackMode !== undefined) {
        const sessionId = await resolveSessionId(ctx, args.sessionId);
        const existingSession = await ctx.app
          .service('sessions')
          .get(sessionId, ctx.baseServiceParams);
        const existingCallback = existingSession.callback_config || {};
        updates.callback_config = {
          ...existingCallback,
          ...(args.enableCallback !== undefined ? { enabled: args.enableCallback } : {}),
          ...(args.callbackMode !== undefined ? { callback_mode: args.callbackMode } : {}),
        };
      }

      if (Object.keys(updates).length === 0) {
        throw new Error(
          'At least one field (title, description, status, archived, enableCallback, callbackMode) must be provided'
        );
      }

      const session = await ctx.app
        .service('sessions')
        .patch(args.sessionId, updates, ctx.baseServiceParams);
      return textResult({ session, note: 'Session updated successfully.' });
    }
  );

  // Tool 8: agor_sessions_archive
  server.registerTool(
    'agor_sessions_archive',
    {
      description:
        'Archive a session (soft delete). Archived sessions are hidden from listings by default but can be restored. By default, all child sessions (forks and subsessions) are also archived. Set includeChildren to false to archive only the target session.',
      annotations: { destructiveHint: true },
      inputSchema: z.object({
        sessionId: z.string().describe('Session ID to archive (UUIDv7 or short ID)'),
        includeChildren: z
          .boolean()
          .optional()
          .describe('Also archive all child sessions (forks and subsessions). Default: true.'),
      }),
    },
    async (args) => {
      const includeChildren = args.includeChildren !== false;
      const sessionsService = ctx.app.service('sessions') as unknown as SessionsServiceImpl;
      let archivedCount = 0;

      await ctx.app
        .service('sessions')
        .patch(
          args.sessionId,
          { archived: true, archived_reason: 'manual' },
          ctx.baseServiceParams
        );
      archivedCount++;

      if (includeChildren) {
        const collectDescendantIds = async (parentId: string): Promise<string[]> => {
          const gen = await sessionsService.getGenealogy(parentId, ctx.baseServiceParams);
          const ids: string[] = [];
          for (const child of gen.children) {
            ids.push(child.session_id);
            const nested = await collectDescendantIds(child.session_id);
            ids.push(...nested);
          }
          return ids;
        };

        const descendantIds = await collectDescendantIds(args.sessionId);
        for (const childId of descendantIds) {
          await ctx.app
            .service('sessions')
            .patch(childId, { archived: true, archived_reason: 'manual' }, ctx.baseServiceParams);
          archivedCount++;
        }
      }

      return textResult({
        success: true,
        archivedCount,
        message: `Archived ${archivedCount} session(s).`,
      });
    }
  );

  // Tool 9: agor_sessions_unarchive
  server.registerTool(
    'agor_sessions_unarchive',
    {
      description:
        'Restore a previously archived session. By default, all child sessions are also unarchived. Set includeChildren to false to unarchive only the target session.',
      inputSchema: z.object({
        sessionId: z.string().describe('Session ID to unarchive (UUIDv7 or short ID)'),
        includeChildren: z
          .boolean()
          .optional()
          .describe('Also unarchive all child sessions (forks and subsessions). Default: true.'),
      }),
    },
    async (args) => {
      const includeChildren = args.includeChildren !== false;
      const sessionsService = ctx.app.service('sessions') as unknown as SessionsServiceImpl;
      let unarchivedCount = 0;

      await ctx.app
        .service('sessions')
        .patch(
          args.sessionId,
          { archived: false, archived_reason: undefined },
          ctx.baseServiceParams
        );
      unarchivedCount++;

      if (includeChildren) {
        const collectDescendantIds = async (parentId: string): Promise<string[]> => {
          const gen = await sessionsService.getGenealogy(parentId, ctx.baseServiceParams);
          const ids: string[] = [];
          for (const child of gen.children) {
            ids.push(child.session_id);
            const nested = await collectDescendantIds(child.session_id);
            ids.push(...nested);
          }
          return ids;
        };

        const descendantIds = await collectDescendantIds(args.sessionId);
        for (const childId of descendantIds) {
          await ctx.app
            .service('sessions')
            .patch(childId, { archived: false, archived_reason: undefined }, ctx.baseServiceParams);
          unarchivedCount++;
        }
      }

      return textResult({
        success: true,
        unarchivedCount,
        message: `Unarchived ${unarchivedCount} session(s).`,
      });
    }
  );

  // Tool 10: agor_sessions_bulk_archive
  server.registerTool(
    'agor_sessions_bulk_archive',
    {
      description:
        'Archive multiple sessions matching filter criteria. Supports filtering by session type (gateway/scheduled/agent), age, status, board, and worktree. Returns a dry-run preview by default — set dryRun to false to actually archive. Respects RBAC: sessions the current user cannot modify are skipped and reported as errors.',
      annotations: { destructiveHint: true },
      inputSchema: z.object({
        sessionType: z
          .enum(['gateway', 'scheduled', 'agent'])
          .optional()
          .describe(
            "Filter by session type. 'gateway' = messaging integrations, 'scheduled' = cron-triggered, 'agent' = manually created."
          ),
        olderThanDays: z
          .number()
          .int()
          .positive()
          .max(365)
          .optional()
          .describe('Only archive sessions last updated more than this many days ago'),
        status: z
          .enum(['idle', 'running', 'completed', 'failed'])
          .optional()
          .describe('Only archive sessions with this status'),
        boardId: z.string().optional().describe('Only archive sessions on this board'),
        worktreeId: z.string().optional().describe('Only archive sessions in this worktree'),
        dryRun: z
          .boolean()
          .optional()
          .describe(
            'Preview which sessions would be archived without actually archiving them (default: true)'
          ),
      }),
    },
    async (args) => {
      const dryRun = args.dryRun !== false;

      // Build service query for non-archived sessions
      const query: Record<string, unknown> = { archived: false };
      if (args.status) query.status = args.status;
      if (args.boardId) query.board_id = await resolveBoardId(ctx, args.boardId);
      if (args.worktreeId) query.worktree_id = await resolveWorktreeId(ctx, args.worktreeId);

      // Fetch all matching sessions (paginate through all results)
      const allSessions: Session[] = [];
      let skip = 0;
      const pageSize = 200;

      while (true) {
        const result = await ctx.app
          .service('sessions')
          .find({ query: { ...query, $limit: pageSize, $skip: skip }, ...ctx.baseServiceParams });
        const page: Session[] = Array.isArray(result) ? result : result.data;
        allSessions.push(...page);
        if (page.length < pageSize) break;
        skip += pageSize;
      }

      // Apply post-query filters (sessionType, age)
      const cutoffDate = args.olderThanDays
        ? new Date(Date.now() - args.olderThanDays * 24 * 60 * 60 * 1000)
        : null;

      const toArchive = allSessions.filter((s) => {
        if (args.sessionType && getSessionType(s) !== args.sessionType) return false;
        if (cutoffDate) {
          const lastUpdated = new Date(s.last_updated || s.created_at);
          if (lastUpdated >= cutoffDate) return false;
        }
        return true;
      });

      if (dryRun) {
        return textResult({
          dryRun: true,
          wouldArchive: toArchive.length,
          totalMatched: allSessions.length,
          ...(cutoffDate && { cutoffDate: cutoffDate.toISOString() }),
          sessions: toArchive.map((s) => ({
            session_id: s.session_id,
            title: s.title,
            status: s.status,
            session_type: getSessionType(s),
            last_updated: s.last_updated,
            created_at: s.created_at,
            worktree_id: s.worktree_id,
          })),
          message: `Would archive ${toArchive.length} session(s). Set dryRun=false to proceed.`,
        });
      }

      // Archive each session (through service layer for RBAC)
      let archivedCount = 0;
      const errors: { session_id: string; error: string }[] = [];

      for (const session of toArchive) {
        try {
          await ctx.app
            .service('sessions')
            .patch(
              session.session_id,
              { archived: true, archived_reason: 'manual' },
              ctx.baseServiceParams
            );
          archivedCount++;
        } catch (error) {
          errors.push({
            session_id: session.session_id,
            error: error instanceof Error ? error.message : String(error),
          });
        }
      }

      return textResult({
        success: true,
        archivedCount,
        failedCount: errors.length,
        ...(cutoffDate && { cutoffDate: cutoffDate.toISOString() }),
        errors: errors.length > 0 ? errors : undefined,
        message: `Archived ${archivedCount} session(s).${errors.length > 0 ? ` ${errors.length} failed (insufficient permissions or other errors).` : ''}`,
      });
    }
  );

  // Tool 12: agor_sessions_stop
  server.registerTool(
    'agor_sessions_stop',
    {
      description:
        'Stop a running session. Kills the executor process and sets the session to idle. Use this for emergency stops, timeout-based cancellation, or human-in-the-loop gates. Only works on sessions in active states (running, awaiting_permission, stopping).',
      annotations: { destructiveHint: true },
      inputSchema: z.object({
        sessionId: z.string().describe('Session ID to stop (UUIDv7 or short ID)'),
        reason: z
          .string()
          .optional()
          .describe(
            'Audit log reason for the stop (e.g. "timeout", "user requested", "safety gate")'
          ),
      }),
    },
    async (args) => {
      const sessionId = await resolveSessionId(ctx, args.sessionId);

      const result = await ctx.app
        .service('/sessions/:id/stop')
        .create(
          { ...(args.reason ? { reason: args.reason } : {}) },
          { ...ctx.baseServiceParams, route: { id: sessionId } }
        );

      const stopResult = result as { success: boolean; status?: string; reason?: string };

      if (!stopResult.success) {
        return textResult({
          success: false,
          sessionId,
          error: stopResult.reason || 'Failed to stop session',
        });
      }

      return textResult({
        success: true,
        sessionId,
        status: stopResult.status,
        ...(args.reason ? { reason: args.reason } : {}),
        note: stopResult.reason || 'Session stopped successfully.',
      });
    }
  );

  // Tool 13: agor_models_list
  //
  // Discovery tool so MCP-driven agents can find valid `model` strings without
  // having to scrape tool descriptions. Sourced from the same in-process model
  // registries the UI uses (packages/core/src/models/*), so when a new model
  // ships and the registry is updated, this tool returns it on the very next
  // call — no MCP-tool-description redeploy needed.
  //
  // Caveats:
  //   - Gemini's authoritative list is fetched live from the Google API per
  //     user (fetchGeminiModels). The hardcoded fallback IS exposed here as a
  //     best-effort starter list.
  //   - Copilot has dynamic discovery via `client.listModels()` exposed at
  //     /copilot-models in the daemon. The static fallback is exposed here.
  //   - OpenCode is a provider+model matrix and doesn't have a single static
  //     list — it's exposed via the worktree config UI today.
  server.registerTool(
    'agor_models_list',
    {
      description:
        'List valid model IDs grouped by agenticTool. Use this to discover what to pass for `modelConfig` (or its string shorthand) in agor_sessions_create / spawn / prompt. Sourced live from the daemon model registry — when new models ship and the registry is updated, this tool returns them on the next call.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        agenticTool: z
          .enum(['claude-code', 'codex', 'copilot', 'gemini'])
          .optional()
          .describe('Filter to a single agentic tool. Omit to return all tools.'),
      }),
    },
    async (args) => {
      const claudeModels = AVAILABLE_CLAUDE_MODEL_ALIASES.map((m) => ({
        id: m.id,
        displayName: m.displayName,
        description: m.description,
        family: m.family,
      }));

      const codexModels = Object.entries(CODEX_MODEL_METADATA).map(([id, meta]) => ({
        id,
        displayName: meta.name,
        description: meta.description,
      }));

      const copilotModels = Object.entries(COPILOT_MODEL_METADATA).map(([id, meta]) => ({
        id,
        displayName: meta.name,
        description: meta.description,
        provider: meta.provider,
      }));

      // Note: Gemini's live list comes from the Google API (per-user API key).
      // We surface the hardcoded fallback so agents have *something* to pass —
      // but more recent models may exist on the user's account.
      const geminiModels = Object.entries(GEMINI_MODELS).map(([id, meta]) => ({
        id,
        displayName: meta.name,
        description: meta.description,
        useCase: meta.useCase,
      }));

      const all = {
        'claude-code': {
          default: DEFAULT_CLAUDE_MODEL,
          models: claudeModels,
        },
        codex: {
          default: DEFAULT_CODEX_MODEL,
          models: codexModels,
        },
        copilot: {
          default: DEFAULT_COPILOT_MODEL,
          models: copilotModels,
          note: "Copilot models are also fetched live via /copilot-models (uses the SDK's listModels()). This is the static fallback — BYOK-configured models may not appear here.",
        },
        gemini: {
          default: DEFAULT_GEMINI_MODEL,
          models: geminiModels,
          note: 'Gemini models are normally fetched live from the Google API per-user. This is the static fallback list — newer models may exist.',
        },
      };

      if (args.agenticTool) {
        return textResult({ [args.agenticTool]: all[args.agenticTool] });
      }
      return textResult(all);
    }
  );
}
