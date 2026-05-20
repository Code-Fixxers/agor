/**
 * Artifact MCP Tools
 *
 * Agent-facing tools for publishing and managing Sandpack artifacts on boards.
 * Artifacts are DB-backed live web applications that render on the board canvas.
 *
 * The format is intentionally small: a file map plus declarative metadata
 * (`required_env_vars`, `agor_grants`, `sandpack_config`). The daemon
 * synthesizes a per-viewer `.env` and resolves daemon-supplied capabilities
 * at render time. There is no Handlebars layer, no per-fetch JS rendering,
 * and no `sandpack.json`/`agor.config.js` sidecar.
 */

import { WorktreeRepository } from '@agor/core/db';
import type {
  AgorGrants,
  AgorRuntimeConfig,
  BoardID,
  SandpackConfig,
  UserRole,
  UUID,
  WorktreeID,
} from '@agor/core/types';
import { NotFoundError } from '@agor/core/utils/errors';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import type { ArtifactsService } from '../../services/artifacts.js';
import { hasWorktreePermission } from '../../utils/worktree-authorization.js';
import { resolveArtifactId, resolveBoardId, resolveWorktreeId } from '../resolve-ids.js';
import type { McpContext } from '../server.js';
import { coerceString, textResult } from '../utils.js';

const SANDPACK_TEMPLATES = [
  'react',
  'react-ts',
  'vanilla',
  'vanilla-ts',
  'vue',
  'vue3',
  'svelte',
  'solid',
  'angular',
] as const;

const SandpackConfigSchema = z
  .object({
    template: z.enum(SANDPACK_TEMPLATES).optional(),
    customSetup: z
      .object({
        dependencies: z.record(z.string(), z.string()).optional(),
        devDependencies: z.record(z.string(), z.string()).optional(),
        entry: z.string().optional(),
        environment: z.string().optional(),
      })
      .optional(),
    theme: z.union([z.string(), z.record(z.string(), z.unknown())]).optional(),
    options: z.record(z.string(), z.unknown()).optional(),
  })
  .passthrough()
  .optional();

const AgorGrantsSchema = z
  .object({
    agor_token: z.boolean().optional(),
    agor_api_url: z.boolean().optional(),
    agor_user_email: z.boolean().optional(),
    agor_artifact_id: z.boolean().optional(),
    agor_board_id: z.boolean().optional(),
    agor_proxies: z.array(z.string()).optional(),
  })
  .optional();

const AgorRuntimeSchema = z
  .object({
    enabled: z
      .boolean()
      .optional()
      .describe(
        "Inject the daemon-side `agor-runtime.js` into the served bundle (as an iframe-level `<script>` via Sandpack's `externalResources`). Default: true. Set false to opt the artifact out of agent DOM introspection (e.g. if the artifact's own code conflicts with our message listener)."
      ),
  })
  .optional();

export function registerArtifactTools(server: McpServer, ctx: McpContext): void {
  // Tool 1: agor_artifacts_publish
  server.registerTool(
    'agor_artifacts_publish',
    {
      description: 'Publish or update a live Sandpack artifact from a folder.',
      inputSchema: z.object({
        folderPath: z.string().describe('Absolute path to folder containing artifact files'),
        boardId: z
          .string()
          .optional()
          .describe(
            'Board to place the artifact on. REQUIRED when creating. IGNORED when updating (artifactId given) — to move an artifact between boards use agor_artifacts_update.'
          ),
        name: z
          .string()
          .optional()
          .describe(
            'Artifact display name. REQUIRED when creating; on update (artifactId given) defaults to the existing name if omitted. PASSING A DIFFERENT NAME ON UPDATE WILL RENAME THE ARTIFACT.'
          ),
        artifactId: z
          .string()
          .optional()
          .describe('If provided, update existing artifact (must be owned by you)'),
        template: z
          .enum(SANDPACK_TEMPLATES)
          .optional()
          .describe(
            'Sandpack template (default: react). Also settable via sandpackConfig.template.'
          ),
        public: z
          .boolean()
          .optional()
          .describe('Whether the artifact is visible to all board viewers (default: true)'),
        sandpackConfig: SandpackConfigSchema.describe(
          'Author-controlled Sandpack provider config (sanitized on write).'
        ),
        requiredEnvVars: z
          .array(z.string())
          .optional()
          .describe(
            'Env var NAMES (no prefix) the artifact needs. Daemon synthesizes a per-viewer .env at render time.'
          ),
        agorGrants: AgorGrantsSchema.describe(
          'Daemon capabilities to inject. See tool description for the full list.'
        ),
        agorRuntime: AgorRuntimeSchema.describe(
          'Controls injection of the daemon-side `agor-runtime.js` (which powers agent DOM introspection via agor_artifacts_query_dom). Default: enabled.'
        ),
        x: z.number().optional().describe('X position on board (default: 0, only used on create)'),
        y: z.number().optional().describe('Y position on board (default: 0, only used on create)'),
        width: z
          .number()
          .optional()
          .describe('Width in pixels (default: 600, only used on create)'),
        height: z
          .number()
          .optional()
          .describe('Height in pixels (default: 400, only used on create)'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const boardIdRaw = coerceString(args.boardId);
      const resolvedBoardId = boardIdRaw ? await resolveBoardId(ctx, boardIdRaw) : undefined;
      const resolvedArtifactId = coerceString(args.artifactId)
        ? await resolveArtifactId(ctx, coerceString(args.artifactId)!)
        : undefined;
      const artifact = await service.publishArtifact(
        {
          folderPath: coerceString(args.folderPath)!,
          board_id: resolvedBoardId,
          name: coerceString(args.name),
          artifact_id: resolvedArtifactId,
          template: args.template,
          public: args.public,
          sandpack_config: args.sandpackConfig as SandpackConfig | undefined,
          required_env_vars: args.requiredEnvVars,
          agor_grants: args.agorGrants as AgorGrants | undefined,
          agor_runtime: args.agorRuntime as AgorRuntimeConfig | undefined,
          x: args.x,
          y: args.y,
          width: args.width,
          height: args.height,
        },
        ctx.userId
      );

      const { files: _files, ...artifactSummary } = artifact;
      return textResult({
        artifact: artifactSummary,
        instructions: args.artifactId
          ? 'Artifact updated. Changes are live on the board.'
          : 'Artifact created and placed on the board. To update it later, call agor_artifacts_publish again with the artifact_id.',
      });
    }
  );

  // Tool 2: agor_artifacts_check_build
  server.registerTool(
    'agor_artifacts_check_build',
    {
      description: 'Check build readiness of artifact files in a folder.',
      inputSchema: z.object({
        folderPath: z
          .string()
          .describe('Absolute path to the folder containing artifact files to check'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const result = await service.checkBuildFromFolder(coerceString(args.folderPath)!);
      // Mirror getStatus shape — `build_status` (not `status`) and `build_errors`
      // (always an array, never undefined) so agents can parse one schema across
      // both tools.
      return textResult({
        build_status: result.status,
        build_errors: result.errors,
      });
    }
  );

  // Tool 3: agor_artifacts_status
  server.registerTool(
    'agor_artifacts_status',
    {
      description: 'Get artifact build state, Sandpack errors, and recent console logs.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const status = await service.getStatus(coerceString(args.artifactId)!, ctx.userId);
      return textResult(status);
    }
  );

  // Tool 4: agor_artifacts_delete
  server.registerTool(
    'agor_artifacts_delete',
    {
      description:
        'Delete an artifact. Removes database record and board placement. Does not touch the filesystem.',
      annotations: { destructiveHint: true },
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID to delete'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const artifactId = coerceString(args.artifactId)!;

      // deleteArtifact loads the row, runs the owner/admin check, performs
      // the delete, and returns the artifact so we can emit `removed`
      // without a redundant pre-delete fetch. role on AuthenticatedUser is
      // loosely typed as `string`; auth strategies enforce a valid value
      // upstream so the cast to UserRole is honest.
      const artifact = await service.deleteArtifact(
        artifactId,
        ctx.userId,
        ctx.authenticatedUser.role as UserRole
      );
      ctx.app.service('artifacts').emit('removed', artifact);

      return textResult({ success: true, artifactId });
    }
  );

  // Tool 5: agor_artifacts_get
  server.registerTool(
    'agor_artifacts_get',
    {
      description: 'Get an artifact by ID, including its file map and declarative metadata.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID (full UUID or short prefix)'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const artifactId = coerceString(args.artifactId)!;

      let artifact: Awaited<ReturnType<typeof service.get>>;
      try {
        artifact = await service.get(artifactId, ctx.baseServiceParams);
      } catch (err) {
        if (err instanceof NotFoundError) {
          return textResult({ error: `Artifact ${artifactId} not found` });
        }
        throw err;
      }

      if (!service.isVisibleTo(artifact, ctx.userId)) {
        return textResult({ error: `Artifact ${artifactId} not found` });
      }

      const { files, ...metadata } = artifact;
      return textResult({
        artifact: metadata,
        files: files ?? {},
      });
    }
  );

  // Tool 6: agor_artifacts_update
  server.registerTool(
    'agor_artifacts_update',
    {
      description:
        'Update artifact metadata without re-reading files from disk. For file changes, use agor_artifacts_publish.',
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID to update (full UUID or short prefix)'),
        boardId: z.string().optional().describe('Move the artifact to a different board'),
        name: z.string().optional().describe('Rename the artifact'),
        description: z.string().optional().describe('Update the description'),
        public: z
          .boolean()
          .optional()
          .describe('Change visibility (true = visible to all board viewers, false = owner only)'),
        archived: z.boolean().optional().describe('Archive or unarchive the artifact'),
        x: z.number().optional().describe('New X position on board'),
        y: z.number().optional().describe('New Y position on board'),
        width: z.number().optional().describe('New width in pixels'),
        height: z.number().optional().describe('New height in pixels'),
        sandpackConfig: SandpackConfigSchema.describe(
          "Replace the artifact's sandpack_config (sanitized on write)."
        ),
        requiredEnvVars: z
          .array(z.string())
          .optional()
          .describe("Replace the artifact's required_env_vars list."),
        agorGrants: AgorGrantsSchema.describe("Replace the artifact's agor_grants object."),
        agorRuntime: AgorRuntimeSchema.describe(
          "Replace the artifact's agor_runtime config (controls agor-runtime.js injection)."
        ),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const artifactId = await resolveArtifactId(ctx, coerceString(args.artifactId)!);

      const boardIdInput = coerceString(args.boardId);
      const resolvedBoardId = boardIdInput ? await resolveBoardId(ctx, boardIdInput) : undefined;

      const updated = await service.updateMetadata(
        artifactId,
        {
          name: coerceString(args.name),
          description: coerceString(args.description),
          public: args.public,
          archived: args.archived,
          board_id: resolvedBoardId as BoardID | undefined,
          x: args.x,
          y: args.y,
          width: args.width,
          height: args.height,
          sandpack_config: args.sandpackConfig as SandpackConfig | undefined,
          required_env_vars: args.requiredEnvVars,
          agor_grants: args.agorGrants as AgorGrants | undefined,
          agor_runtime: args.agorRuntime as AgorRuntimeConfig | undefined,
        },
        ctx.userId,
        ctx.authenticatedUser.role as UserRole
      );

      const { files: _files, ...artifactSummary } = updated;
      return textResult({
        artifact: artifactSummary,
        instructions: 'Artifact metadata updated.',
      });
    }
  );

  // Tool 7: agor_artifacts_land
  server.registerTool(
    'agor_artifacts_land',
    {
      description: "Materialize an artifact's stored files into a worktree.",
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID to materialize (full UUID or short prefix)'),
        worktreeId: z.string().describe('Destination worktree ID (full UUID or short prefix)'),
        subpath: z
          .string()
          .optional()
          .describe(
            'Worktree-relative path for the destination folder. Default: .agor/artifacts/<slug>-<short-id> derived from the artifact name. Must not be absolute or escape the worktree.'
          ),
        overwrite: z
          .boolean()
          .optional()
          .describe('Remove the destination folder first if it exists. Default: false.'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const artifactId = await resolveArtifactId(ctx, coerceString(args.artifactId)!);
      const worktreeId = await resolveWorktreeId(ctx, coerceString(args.worktreeId)!);

      let artifact: Awaited<ReturnType<typeof service.get>>;
      try {
        artifact = await service.get(artifactId, ctx.baseServiceParams);
      } catch (err) {
        if (err instanceof NotFoundError) {
          return textResult({ error: `Artifact ${artifactId} not found` });
        }
        throw err;
      }
      if (!service.isVisibleTo(artifact, ctx.userId)) {
        return textResult({ error: `Artifact ${artifactId} not found` });
      }

      const worktree = (await ctx.app
        .service('worktrees')
        .get(worktreeId, ctx.baseServiceParams)) as {
        worktree_id: string;
        path: string;
        others_can?: 'none' | 'view' | 'session' | 'prompt' | 'all';
      };

      const worktreeRepo = new WorktreeRepository(ctx.db);
      const worktreeIdBranded = worktree.worktree_id as WorktreeID;
      const userIdBranded = ctx.userId as UUID;
      const isOwner = await worktreeRepo.isOwner(worktreeIdBranded, userIdBranded);
      const fullWorktree = await worktreeRepo.findById(worktreeIdBranded);
      if (!fullWorktree) {
        return textResult({ error: `Worktree ${worktreeId} not found` });
      }
      const canWrite = hasWorktreePermission(
        fullWorktree,
        userIdBranded,
        isOwner,
        'session',
        ctx.authenticatedUser.role
      );
      if (!canWrite) {
        return textResult({
          error: `Forbidden: 'session' permission or higher is required to land artifacts into worktree ${worktreeId}`,
        });
      }

      const result = await service.land(artifactId, worktree.path, {
        subpath: coerceString(args.subpath),
        overwrite: args.overwrite,
      });

      return textResult({
        artifactId,
        worktreeId: worktree.worktree_id,
        destinationPath: result.destinationPath,
        fileCount: result.fileCount,
        bytesWritten: result.bytesWritten,
        instructions: `Artifact materialized to ${result.destinationPath}. The folder includes \`agor.artifact.json\` — keep it: it carries template/sandpack_config/required_env_vars/agor_grants for round-trip publishing. Edit source files there, then call agor_artifacts_publish with folderPath=${result.destinationPath} and artifactId=${artifactId} to push changes back.`,
      });
    }
  );

  // Tool 8: agor_artifacts_list
  server.registerTool(
    'agor_artifacts_list',
    {
      description: 'List artifacts, optionally filtered by board.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        boardId: z.string().optional().describe('Filter by board ID'),
        limit: z.number().optional().describe('Maximum number of results (default: 10)'),
        detail: z
          .enum(['list', 'full'])
          .optional()
          .describe('"list" (default) returns summaries; "full" returns declarative metadata.'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const boardIdRaw = coerceString(args.boardId);
      const boardId = boardIdRaw ? await resolveBoardId(ctx, boardIdRaw) : undefined;
      const limit = typeof args.limit === 'number' ? args.limit : 10;

      let artifactsList: unknown[];
      if (boardId) {
        artifactsList = await service.findByBoardId(boardId as never, ctx.userId, {
          limit,
          omitFiles: true,
        });
      } else {
        artifactsList = await service.findVisible(ctx.userId, { limit, omitFiles: true });
      }

      const detail = args.detail ?? 'list';

      const shaped = (artifactsList as Record<string, unknown>[]).map((a) => {
        const { files: _f, ...rest } = a;
        if (detail === 'list') {
          const { description, sandpack_config, required_env_vars, ...minimal } = rest;
          return minimal;
        }
        return rest;
      });

      return textResult({
        total: shaped.length,
        data: shaped,
      });
    }
  );

  // Tool 9: agor_artifacts_export_codesandbox
  server.registerTool(
    'agor_artifacts_export_codesandbox',
    {
      description: `Export an artifact to CodeSandbox via their "define API". Returns a sandbox URL and ID. Useful for sharing or demoing — the artifact runs in CodeSandbox's standard environment, not Agor.

CAVEAT: daemon-supplied capabilities (\`AGOR_TOKEN\`, \`AGOR_PROXY_*\`, etc.) won't work on CodeSandbox. The exported sandbox can read \`required_env_vars\` from CodeSandbox's "Secret Keys" UI — the names match because both sides use the same prefix-per-template convention (Vite → \`VITE_\`, CRA → \`REACT_APP_\`, etc.).`,
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID to export (full UUID or short prefix)'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const artifactId = await resolveArtifactId(ctx, coerceString(args.artifactId)!);
      try {
        const result = await service.exportToCodeSandbox(artifactId, ctx.userId);
        return textResult(result);
      } catch (err) {
        return textResult({
          error: err instanceof Error ? err.message : String(err),
        });
      }
    }
  );

  // Tool 10: agor_artifacts_query_dom
  server.registerTool(
    'agor_artifacts_query_dom',
    {
      description: `Query the rendered DOM of a running artifact via CSS selector.

Round-trip: this MCP call → daemon → WebSocket → your own browser tab(s) viewing the artifact → Sandpack iframe → \`agor-runtime.js\` (auto-injected at render time) → response back up the chain. Replies carry serialized matches: tag, attributes, textContent, outerHTML.

REQUIREMENTS:
- The artifact must have \`agor_runtime.enabled !== false\` (default is enabled). If the author disabled introspection, the call returns a clean error.
- A browser tab logged in as YOU must be currently viewing the artifact. The daemon scopes responses to the requesting user — another viewer's browser cannot answer your query (and so cannot leak their secret-bearing render).
- If no qualifying tab is open, the call times out (default 5s) with an error suggesting you open the artifact and retry.

Caps: 50 nodes max, 50KB outerHTML per node, 5KB textContent per node. Tightened for context budget.

Use cases:
- "Did my artifact actually render the new heading?" — \`{ selector: 'h1' }\`
- "Inspect a list of cards" — \`{ selector: '.card', multiple: true }\`
- "Get the full document" — use \`{ selector: 'html' }\`, or call \`agor_artifacts_query_document_html\` for an unstructured dump of the entire \`document.documentElement.outerHTML\`.`,
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID (full UUID or short prefix)'),
        selector: z
          .string()
          .describe('CSS selector to match (e.g. "h1", ".card", "[data-test=\'submit\']")'),
        multiple: z
          .boolean()
          .optional()
          .describe('querySelectorAll vs querySelector. Default: false (single match).'),
        maxNodes: z
          .number()
          .optional()
          .describe('Max nodes to return (capped at 50 by the runtime). Default: 50.'),
        timeoutMs: z
          .number()
          .optional()
          .describe('How long to wait for the browser to answer (500-30000). Default: 5000.'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const artifactId = await resolveArtifactId(ctx, coerceString(args.artifactId)!);
      try {
        const result = await service.queryArtifactRuntime({
          artifactId,
          userId: ctx.userId,
          kind: 'query_dom',
          args: {
            selector: coerceString(args.selector),
            multiple: args.multiple,
            maxNodes: args.maxNodes,
          },
          timeoutMs: args.timeoutMs,
        });
        return textResult(result);
      } catch (err) {
        return textResult({
          error: err instanceof Error ? err.message : String(err),
        });
      }
    }
  );

  // Tool 11: agor_artifacts_query_document_html
  server.registerTool(
    'agor_artifacts_query_document_html',
    {
      description: `Return the rendered artifact's full \`document.documentElement.outerHTML\` (unstructured dump).

Same round-trip as agor_artifacts_query_dom: requires \`agor_runtime.enabled\` and a browser tab logged in as YOU currently viewing the artifact.

Capped at 200KB. Truncated output ends with \`... [truncated]\`. For targeted queries prefer agor_artifacts_query_dom with a CSS selector — this tool is the "give me everything" escape hatch when you don't know what to look for yet.`,
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID (full UUID or short prefix)'),
        timeoutMs: z
          .number()
          .optional()
          .describe('How long to wait for the browser to answer (500-30000). Default: 5000.'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const artifactId = await resolveArtifactId(ctx, coerceString(args.artifactId)!);
      try {
        const result = await service.queryArtifactRuntime({
          artifactId,
          userId: ctx.userId,
          kind: 'document_html',
          args: {},
          timeoutMs: args.timeoutMs,
        });
        return textResult(result);
      } catch (err) {
        return textResult({
          error: err instanceof Error ? err.message : String(err),
        });
      }
    }
  );
}
