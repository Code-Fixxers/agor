/**
 * Artifact MCP Tools
 *
 * Agent-facing tools for publishing and managing Sandpack artifacts on boards.
 * Artifacts are DB-backed live web applications that render on the board canvas.
 */

import { WorktreeRepository } from '@agor/core/db';
import type { BoardID, UUID, WorktreeID } from '@agor/core/types';
import { NotFoundError } from '@agor/core/utils/errors';
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import type { ArtifactsService } from '../../services/artifacts.js';
import { hasWorktreePermission } from '../../utils/worktree-authorization.js';
import { resolveArtifactId, resolveBoardId, resolveWorktreeId } from '../resolve-ids.js';
import type { McpContext } from '../server.js';
import { coerceString, textResult } from '../server.js';

export function registerArtifactTools(server: McpServer, ctx: McpContext): void {
  // Tool 1: agor_artifacts_publish
  server.registerTool(
    'agor_artifacts_publish',
    {
      description:
        'Publish a folder as a Sandpack artifact on a board. Creates new if artifactId is omitted, else updates (must own). Include /agor.config.js for per-user Handlebars-templated secrets — see docs.',
      inputSchema: z.object({
        folderPath: z.string().describe('Absolute path to folder containing artifact files'),
        boardId: z.string().describe('Board to place the artifact on'),
        name: z.string().describe('Artifact display name'),
        artifactId: z
          .string()
          .optional()
          .describe('If provided, update existing artifact (must be owned by you)'),
        template: z
          .enum([
            'react',
            'react-ts',
            'vanilla',
            'vanilla-ts',
            'vue',
            'vue3',
            'svelte',
            'solid',
            'angular',
          ])
          .optional()
          .describe('Sandpack template (default: react)'),
        public: z
          .boolean()
          .optional()
          .describe('Whether the artifact is visible to all board viewers (default: true)'),
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
        useLocalBundler: z
          .boolean()
          .optional()
          .describe(
            'Use self-hosted bundler (requires --with-sandpack build). Default: false. Breaks CJS-only packages.'
          ),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const resolvedBoardId = await resolveBoardId(ctx, coerceString(args.boardId)!);
      const resolvedArtifactId = coerceString(args.artifactId)
        ? await resolveArtifactId(ctx, coerceString(args.artifactId)!)
        : undefined;
      const artifact = await service.publish(
        {
          folderPath: coerceString(args.folderPath)!,
          board_id: resolvedBoardId,
          name: coerceString(args.name)!,
          artifact_id: resolvedArtifactId,
          template: args.template,
          public: args.public,
          use_local_bundler: args.useLocalBundler,
          x: args.x,
          y: args.y,
          width: args.width,
          height: args.height,
        },
        ctx.userId
      );

      // Omit files blob from response to avoid context bloat for agents
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
      description:
        'Check build readiness of artifact files in a folder. Verifies source files exist and are non-empty (does not run a real build or syntax check). Use this before publishing to verify basic structure.',
      inputSchema: z.object({
        folderPath: z
          .string()
          .describe('Absolute path to the folder containing artifact files to check'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const result = await service.checkBuildFromFolder(coerceString(args.folderPath)!);
      return textResult(result);
    }
  );

  // Tool 3: agor_artifacts_status
  server.registerTool(
    'agor_artifacts_status',
    {
      description:
        "Get artifact build status, Sandpack errors, and console logs. Live fields (sandpack_error, console_logs) require a browser viewing the artifact.",
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const status = await service.getStatus(coerceString(args.artifactId)!);
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

      // Get artifact before deletion for the emit
      const artifact = await service.get(artifactId, ctx.baseServiceParams);
      await service.deleteArtifact(artifactId);
      ctx.app.service('artifacts').emit('removed', artifact);

      return textResult({ success: true, artifactId });
    }
  );

  // Tool 5: agor_artifacts_get
  server.registerTool(
    'agor_artifacts_get',
    {
      description:
        'Get a single artifact by ID, including its full file map (path → content). Use this to read artifact source code from another worktree without filesystem access. Respects visibility: public artifacts are readable by anyone; private artifacts are only readable by their creator.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID (full UUID or short prefix)'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const artifactId = coerceString(args.artifactId)!;

      // Fetch the artifact via the Feathers get() method (inherited from DrizzleService)
      let artifact: Awaited<ReturnType<typeof service.get>>;
      try {
        artifact = await service.get(artifactId, ctx.baseServiceParams);
      } catch (err) {
        if (err instanceof NotFoundError) {
          return textResult({ error: `Artifact ${artifactId} not found` });
        }
        throw err;
      }

      // Visibility check: private artifacts are only visible to their creator
      if (!service.isVisibleTo(artifact, ctx.userId)) {
        return textResult({ error: `Artifact ${artifactId} not found` });
      }

      // Return metadata (without files blob) + full file map separately
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
      description: `Update artifact metadata without re-reading files from disk. Use this to move an artifact to a different board, rename it, toggle visibility, archive it, or reposition its board placement.

Primary use case: move an artifact to a different board via \`boardId\` when you no longer have the original source folder on disk.

For file/content changes, use agor_artifacts_publish (which re-reads a folder and updates the stored files).

Placement (x, y, width, height) is preserved across board moves unless you explicitly override it — so a cross-board move keeps the artifact in the same relative layout.

Caller must own the artifact (or be an admin).`,
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
        },
        ctx.userId
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
      description: `Materialize an artifact's stored files to disk inside a worktree. Inverse of agor_artifacts_publish.

Use this when you want to tweak an artifact's code: land it into a worktree, edit the files locally, then call agor_artifacts_publish with the same artifactId to push the changes back.

Writes a sandpack.json manifest alongside the files so agor_artifacts_publish can read the template/dependencies/entry back in a round-trip.

Safety:
- Destination must be inside the target worktree (cannot escape via ".." or absolute paths).
- Default subpath is \`.agor/artifacts/<artifact-id>\` (inside the worktree). Pass a custom subpath if you want a different location.
- Refuses to write to an existing destination unless overwrite=true is passed (empty or not).
- overwrite=true removes the destination directory first (symlinks are unlinked, not followed).

Visibility: public artifacts are readable by anyone; private artifacts are only landable by their owner.`,
      inputSchema: z.object({
        artifactId: z.string().describe('Artifact ID to materialize (full UUID or short prefix)'),
        worktreeId: z.string().describe('Destination worktree ID (full UUID or short prefix)'),
        subpath: z
          .string()
          .optional()
          .describe(
            'Worktree-relative path for the destination folder. Default: .agor/artifacts/<artifact-id>. Must not be absolute or escape the worktree.'
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

      // Fetch artifact for visibility check.
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

      // Resolve worktree through the service layer (enforces `view` via RBAC
      // hooks). Landing an artifact writes to disk, so `view` is not enough —
      // require at least `session` (the same tier that lets a user create
      // sessions that could themselves write files in the worktree).
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
        instructions: `Artifact materialized to ${result.destinationPath}. Edit files there, then call agor_artifacts_publish with folderPath=${result.destinationPath} and artifactId=${artifactId} to push changes back.`,
      });
    }
  );

  // Tool 8: agor_artifacts_list
  server.registerTool(
    'agor_artifacts_list',
    {
      description:
        'List artifacts, optionally filtered by board. Respects visibility: shows public artifacts plus private artifacts owned by you.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        boardId: z.string().optional().describe('Filter by board ID'),
        limit: z.number().optional().describe('Maximum number of results (default: 50)'),
      }),
    },
    async (args) => {
      const service = ctx.app.service('artifacts') as unknown as ArtifactsService;
      const boardIdRaw = coerceString(args.boardId);
      const boardId = boardIdRaw ? await resolveBoardId(ctx, boardIdRaw) : undefined;
      const limit = typeof args.limit === 'number' ? args.limit : 50;

      let artifactsList: unknown[];
      if (boardId) {
        artifactsList = await service.findByBoardId(boardId as never, ctx.userId);
      } else {
        artifactsList = await service.findVisible(ctx.userId, { limit });
      }

      // Omit files blob from list results to avoid context bloat
      const stripped = (artifactsList as Record<string, unknown>[]).map(
        ({ files: _f, ...rest }) => rest
      );
      return textResult({
        total: stripped.length,
        data: stripped,
      });
    }
  );
}
