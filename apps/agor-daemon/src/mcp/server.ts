/**
 * MCP Server — Official SDK integration
 *
 * Creates an McpServer using @modelcontextprotocol/sdk and mounts it
 * at POST /mcp with JWT session-token auth.
 *
 * When tool search is enabled (mcpToolSearch config flag), only essential
 * tools appear in tools/list. Agents discover others via agor_search_tools.
 * All tools remain registered and callable regardless.
 *
 * DETERMINISM: The tools/list response and registry are built once on first
 * request and cached as module-level singletons. This ensures byte-identical
 * JSON across requests, which is critical for client-side KV prefix caching.
 */

import { randomUUID } from 'node:crypto';
import type { Database } from '@agor/core/db';
import { UserApiKeysRepository } from '@agor/core/db';
import type { Application } from '@agor/core/feathers';
import type { DaemonServicesConfig, ServiceGroupName, SessionID, UserID } from '@agor/core/types';
import { getServiceTier, SERVICE_GROUP_TO_MCP_DOMAINS, SERVICE_TIER_RANK } from '@agor/core/types';
import { NotFoundError } from '@agor/core/utils/errors';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import type { Request, Response } from 'express';
import { toJSONSchema } from 'zod/v4-mini';
import type { AuthenticatedParams, AuthenticatedUser } from '../declarations.js';
import { validateSessionToken } from './tokens.js';
import { ToolRegistry } from './tool-registry.js';
import { registerAnalyticsTools } from './tools/analytics.js';
import { registerArtifactTools } from './tools/artifacts.js';
import { registerBoardTools } from './tools/boards.js';
import { registerCardTypeTools } from './tools/card-types.js';
import { registerCardTools } from './tools/cards.js';
import { registerEnvironmentTools } from './tools/environment.js';
import { registerMcpServerTools } from './tools/mcp-servers.js';
import { registerMessageTools } from './tools/messages.js';
import { registerRepoTools } from './tools/repos.js';
import { registerSearchTools } from './tools/search.js';
import { registerSessionTools } from './tools/sessions.js';
import { registerTaskTools } from './tools/tasks.js';
import { registerUserTools } from './tools/users.js';
import { registerWorktreeTools } from './tools/worktrees.js';

/**
 * Shared context passed to every tool handler.
 */
export interface McpContext {
  app: Application;
  db: Database;
  userId: UserID;
  sessionId: SessionID;
  authenticatedUser: AuthenticatedUser;
  baseServiceParams: Pick<AuthenticatedParams, 'user' | 'authenticated' | 'provider'>;
}

/**
 * Helper: coerce unknown value to trimmed non-empty string or undefined.
 */
export function coerceString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/**
 * Helper: coerce a possibly-stringified JSON value to a Record, or return as-is.
 *
 * Some MCP clients double-serialize nested objects as JSON strings (especially
 * with large or complex content). This helper transparently parses those back.
 * Returns the original value unchanged if it's not a string or not valid JSON.
 */
export function coerceJsonRecord(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

/**
 * Helper: format a value as MCP text content response.
 */
export function textResult(data: unknown) {
  return {
    content: [{ type: 'text' as const, text: JSON.stringify(data) }],
  };
}

/** Server instructions shown to agents when tool search is enabled. */
const SERVER_INSTRUCTIONS = `Agor MCP uses progressive tool discovery. Two meta-tools are listed:
- agor_search_tools: browse/filter tools. No args = domains overview. Use detail:"full" to fetch inputSchema.
- agor_execute_tool: invoke any tool by name. Args go under \`arguments\` (or flattened at top level).`;

/**
 * Module-level cached registry and tools/list response.
 *
 * Built once on first request, reused for all subsequent requests.
 * The registry content is independent of user/session — only tool handlers
 * differ per request. This ensures deterministic, byte-identical tools/list
 * responses critical for client-side KV prefix caching.
 */
let cachedRegistry: ToolRegistry | null = null;
let cachedToolsList: { tools: Array<Record<string, unknown>> } | null = null;

/**
 * Build the tool registry by registering tools against a temporary server.
 * Captures metadata (name, description, JSON Schema, annotations, domain)
 * without creating real handlers. Called once, cached forever.
 */
function buildRegistry(servicesConfig?: DaemonServicesConfig): ToolRegistry {
  const registry = new ToolRegistry();

  // Create a throwaway server just to run the registration code.
  // We intercept registerTool to capture metadata only.
  const tempServer = new McpServer({ name: 'agor-registry-builder', version: '0.0.0' });
  const originalRegisterTool = tempServer.registerTool.bind(tempServer) as (
    ...args: unknown[]
  ) => ReturnType<typeof tempServer.registerTool>;

  // Override the registerTool method to intercept metadata.
  // Cast required because registerTool is an overloaded generic method — TypeScript
  // cannot represent the replacement function with the exact overload signature.
  (
    tempServer as unknown as {
      registerTool: (name: string, config: Record<string, unknown>, cb: unknown) => void;
    }
  ).registerTool = (name: string, config: Record<string, unknown>, cb: unknown) => {
    // Convert Zod schema to JSON Schema using Zod v4's built-in converter
    let jsonSchema: Record<string, unknown> = { type: 'object' };
    if (config.inputSchema) {
      try {
        jsonSchema = toJSONSchema(
          config.inputSchema as Parameters<typeof toJSONSchema>[0]
        ) as Record<string, unknown>;
      } catch {
        // Fallback: empty object schema if conversion fails
        jsonSchema = { type: 'object' };
      }
    }

    registry.register({
      name,
      description: (config.description as string) ?? '',
      inputSchema: jsonSchema,
      annotations:
        config.annotations as import('@modelcontextprotocol/sdk/types.js').ToolAnnotations,
    });

    // Still register with the temp server so Zod schemas are valid
    return originalRegisterTool(name, config, cb);
  };

  // Register all domain tools with domain tracking.
  // Handlers receive a dummy context — they won't be called.
  // Only register tools for enabled service domains.
  const dummyCtx = {} as McpContext;

  if (isDomainEnabled('sessions', servicesConfig)) {
    registry.setCurrentDomain('sessions');
    registerSessionTools(tempServer, dummyCtx);
    registerTaskTools(tempServer, dummyCtx);
    registerMessageTools(tempServer, dummyCtx);
  }

  if (isDomainEnabled('repos', servicesConfig)) {
    registry.setCurrentDomain('repos');
    registerRepoTools(tempServer, dummyCtx);
  }

  if (isDomainEnabled('worktrees', servicesConfig)) {
    registry.setCurrentDomain('worktrees');
    registerWorktreeTools(tempServer, dummyCtx);
    registry.setCurrentDomain('environment');
    registerEnvironmentTools(tempServer, dummyCtx);
  }

  if (isDomainEnabled('boards', servicesConfig)) {
    registry.setCurrentDomain('boards');
    registerBoardTools(tempServer, dummyCtx);
  }

  if (isDomainEnabled('cards', servicesConfig)) {
    registry.setCurrentDomain('cards');
    registerCardTools(tempServer, dummyCtx);
    registerCardTypeTools(tempServer, dummyCtx);
  }

  if (isDomainEnabled('artifacts', servicesConfig)) {
    registry.setCurrentDomain('artifacts');
    registerArtifactTools(tempServer, dummyCtx);
  }

  if (isDomainEnabled('users', servicesConfig)) {
    registry.setCurrentDomain('users');
    registerUserTools(tempServer, dummyCtx);
  }

  if (isDomainEnabled('analytics', servicesConfig)) {
    registry.setCurrentDomain('analytics');
    registerAnalyticsTools(tempServer, dummyCtx);
  }

  if (isDomainEnabled('mcp-servers', servicesConfig)) {
    registry.setCurrentDomain('mcp-servers');
    registerMcpServerTools(tempServer, dummyCtx);
  }

  // Search/execute tools always registered (meta-tools)
  registry.setCurrentDomain('discovery');
  registerSearchTools(tempServer, registry);

  return registry;
}

/**
 * Get or build the cached registry and tools/list response.
 */
function getRegistry(servicesConfig?: DaemonServicesConfig): {
  registry: ToolRegistry;
  toolsList: { tools: Array<Record<string, unknown>> };
} {
  if (!cachedRegistry) {
    cachedRegistry = buildRegistry(servicesConfig);
    // Pre-compute the tools/list response — frozen, deterministic
    cachedToolsList = {
      tools: cachedRegistry.getAlwaysVisible().map((entry) => ({
        name: entry.name,
        description: entry.description,
        inputSchema: entry.inputSchema,
        annotations: entry.annotations,
      })),
    };
  }
  return { registry: cachedRegistry, toolsList: cachedToolsList! };
}

/**
 * Create an McpServer with all tools registered for the given context.
 *
 * Tool handlers close over `ctx` for per-request user/session scope.
 * The registry and tools/list response are shared across all requests.
 */
/**
 * Check if a MCP domain should have tools registered based on service config.
 * Returns false for 'off' or 'internal' tiers, 'readonly' or 'full' otherwise.
 */
function getDomainAccess(
  domain: string,
  servicesConfig?: DaemonServicesConfig
): false | 'readonly' | 'full' {
  if (!servicesConfig) return 'full'; // default: all enabled

  // Find which service group owns this domain
  for (const [group, domains] of Object.entries(SERVICE_GROUP_TO_MCP_DOMAINS)) {
    if (domains?.includes(domain)) {
      const tier = getServiceTier(servicesConfig, group as ServiceGroupName);
      if (SERVICE_TIER_RANK[tier] < SERVICE_TIER_RANK.readonly) return false;
      return tier === 'on' ? 'full' : 'readonly';
    }
  }
  return 'full'; // unknown domain = full access
}

/** Backwards-compatible wrapper */
function isDomainEnabled(domain: string, servicesConfig?: DaemonServicesConfig): boolean {
  return getDomainAccess(domain, servicesConfig) !== false;
}

/**
 * Create a proxy McpServer that silently skips tools without
 * readOnlyHint: true. Uses Object.create so the original server is unmodified.
 */
function readOnlyProxy(server: McpServer): McpServer {
  const proxy = Object.create(server) as McpServer;
  const original = server.registerTool.bind(server);
  // Cast required: registerTool is an overloaded generic — TS can't represent the replacement.
  (proxy as unknown as Record<string, unknown>).registerTool = (
    name: string,
    config: Record<string, unknown>,
    cb: unknown
  ) => {
    const annotations = config.annotations as { readOnlyHint?: boolean } | undefined;
    if (annotations?.readOnlyHint === true) {
      return (original as (...args: unknown[]) => unknown)(name, config, cb);
    }
    // Skip mutating tools silently
  };
  return proxy;
}

function createMcpServer(
  ctx: McpContext,
  toolSearchEnabled: boolean,
  servicesConfig?: DaemonServicesConfig
): McpServer {
  const server = new McpServer(
    {
      name: 'agor',
      version: '0.14.3',
      ...(toolSearchEnabled && {
        description: 'Multiplayer canvas for orchestrating AI coding agents',
      }),
    },
    {
      capabilities: { tools: { listChanged: true }, logging: {} },
      ...(toolSearchEnabled && { instructions: SERVER_INSTRUCTIONS }),
    }
  );

  // Register domain tools conditionally based on service tier.
  // 'off' / 'internal': no MCP tools
  // 'readonly': only tools with readOnlyHint: true
  // 'on': all tools
  const domainRegister = (domain: string, fn: (s: McpServer, c: McpContext) => void) => {
    const access = getDomainAccess(domain, servicesConfig);
    if (!access) return;
    fn(access === 'readonly' ? readOnlyProxy(server) : server, ctx);
  };

  domainRegister('sessions', (s, c) => {
    registerSessionTools(s, c);
    registerTaskTools(s, c);
    registerMessageTools(s, c);
  });
  domainRegister('repos', registerRepoTools);
  domainRegister('worktrees', (s, c) => {
    registerWorktreeTools(s, c);
    registerEnvironmentTools(s, c);
  });
  domainRegister('boards', registerBoardTools);
  domainRegister('cards', (s, c) => {
    registerCardTools(s, c);
    registerCardTypeTools(s, c);
  });
  domainRegister('artifacts', registerArtifactTools);
  domainRegister('users', registerUserTools);
  domainRegister('analytics', registerAnalyticsTools);
  domainRegister('mcp-servers', registerMcpServerTools);

  if (toolSearchEnabled) {
    const { registry, toolsList } = getRegistry(servicesConfig);

    // Register search/execute tools with the shared cached registry
    registerSearchTools(server, registry);

    // Override tools/list with the pre-computed, deterministic response.
    // All tools remain registered and callable via tools/call.
    server.server.setRequestHandler(ListToolsRequestSchema, async () => toolsList);
  }

  return server;
}

/**
 * Setup MCP routes on FeathersJS app using the official SDK.
 *
 * @param toolSearchEnabled - When true, tools/list returns only essential tools
 *   and agents discover others via agor_search_tools. Default: true.
 */
export function setupMCPRoutes(
  app: Application,
  db: Database,
  toolSearchEnabled = true,
  servicesConfig?: DaemonServicesConfig
): void {
  // Eagerly build the registry at startup so first request isn't slower
  if (toolSearchEnabled) {
    getRegistry(servicesConfig);
    console.log(`✅ MCP tool registry built (${cachedRegistry!.size} tools cached)`);
  }

  // Stateful transports for streamable HTTP sessions (enables GET SSE + DELETE).
  const transports = new Map<
    string,
    {
      transport: StreamableHTTPServerTransport;
      server: McpServer;
      userId: UserID;
    }
  >();

  const isInitializeRequest = (body: unknown): boolean => {
    if (!body || typeof body !== 'object') return false;
    const maybeRequest = body as { method?: unknown };
    return maybeRequest.method === 'initialize';
  };

  const handler = async (req: Request, res: Response) => {
    try {
      console.log(`🔌 Incoming MCP request: ${req.method} /mcp`);

      // Extract credentials from query params or headers.
      // Supports:
      // - sessionToken query param (existing behavior)
      // - Authorization: Bearer <sessionToken|agor_sk_*>
      // - X-API-Key: agor_sk_*
      let credential = req.query.sessionToken as string | undefined;
      if (!credential) {
        const authHeader = req.headers.authorization;
        if (authHeader?.startsWith('Bearer ')) {
          credential = authHeader.slice(7);
        }
      }
      if (!credential) {
        const xApiKey = req.headers['x-api-key'];
        if (typeof xApiKey === 'string' && xApiKey.startsWith('agor_sk_')) {
          credential = xApiKey;
        }
      }

      if (!credential) {
        console.warn('⚠️  MCP request missing credentials');
        return res.status(401).json({
          jsonrpc: '2.0',
          id: (req.body as { id?: unknown })?.id,
          error: {
            code: -32001,
            message:
              'Authentication required: provide sessionToken, Authorization Bearer token, or X-API-Key',
          },
        });
      }

      // Support long-lived personal API keys for external orchestrators (Hermes, etc.).
      let authenticatedUser: AuthenticatedUser;
      let userId: UserID;
      let sessionId: SessionID;

      if (credential.startsWith('agor_sk_')) {
        const apiKeysRepo = new UserApiKeysRepository(db);
        const keyRow = await apiKeysRepo.verifyKey(credential);
        if (!keyRow) {
          console.warn('⚠️  Invalid MCP API key');
          return res.status(401).json({
            jsonrpc: '2.0',
            id: (req.body as { id?: unknown })?.id,
            error: {
              code: -32001,
              message: 'Invalid API key',
            },
          });
        }

        apiKeysRepo.updateLastUsed(keyRow.id).catch((err: unknown) => {
          console.warn('⚠️  Failed to update MCP API key last_used_at:', err);
        });

        userId = keyRow.user_id as UserID;
        authenticatedUser = await app.service('users').get(userId);

        // Session context for tools like agor_sessions_get_current/agor_sessions_spawn.
        // In API key mode, allow explicit session selection via query/header.
        const requestedSessionId =
          coerceString(req.query.sessionId as string | undefined) ||
          coerceString(
            typeof req.headers['x-agor-session-id'] === 'string'
              ? req.headers['x-agor-session-id']
              : undefined
          );

        sessionId = requestedSessionId as SessionID;
      } else {
        // Existing deterministic MCP session-token flow.
        const context = await validateSessionToken(app, credential);
        if (!context) {
          console.warn('⚠️  Invalid MCP session token');
          return res.status(401).json({
            jsonrpc: '2.0',
            id: (req.body as { id?: unknown })?.id,
            error: {
              code: -32001,
              message: 'Invalid or expired session token',
            },
          });
        }

        userId = context.userId;
        sessionId = context.sessionId;

        try {
          authenticatedUser = await app.service('users').get(userId);
        } catch (error) {
          if (error instanceof NotFoundError) {
            return res.status(401).json({
              jsonrpc: '2.0',
              id: (req.body as { id?: unknown })?.id,
              error: {
                code: -32001,
                message: 'Invalid or expired session token',
              },
            });
          }
          throw error;
        }
      }

      console.log(
        `🔌 MCP request authenticated (user: ${userId.substring(0, 8)}, session: ${sessionId?.substring(0, 8) || 'none'})`
      );

      // Sessionless access is permitted for personal API keys. Tools that need
      // a current session (e.g. agor_sessions_get_current, spawn) will surface
      // their own error if called without ?sessionId=/X-Agor-Session-Id set.

      const baseServiceParams: Pick<AuthenticatedParams, 'user' | 'authenticated' | 'provider'> = {
        user: {
          user_id: authenticatedUser.user_id,
          email: authenticatedUser.email,
          role: authenticatedUser.role,
        },
        authenticated: true,
        provider: 'mcp',
      };

      const mcpSessionId = coerceString(req.headers['mcp-session-id']);

      // ─────────────────────────────────────────────────────────────────────────────
      // Stateful mode (streamable HTTP sessions): supports GET /mcp SSE + DELETE /mcp
      // ─────────────────────────────────────────────────────────────────────────────
      if (req.method === 'GET' || req.method === 'DELETE' || mcpSessionId) {
        if (!mcpSessionId || !transports.has(mcpSessionId)) {
          return res.status(400).json({
            jsonrpc: '2.0',
            id: (req.body as { id?: unknown })?.id,
            error: {
              code: -32000,
              message: 'Bad Request: Invalid or missing MCP session ID',
            },
          });
        }

        const existing = transports.get(mcpSessionId)!;
        if (existing.userId !== userId) {
          return res.status(403).json({
            jsonrpc: '2.0',
            id: (req.body as { id?: unknown })?.id,
            error: {
              code: -32003,
              message: 'Forbidden: MCP session belongs to a different user',
            },
          });
        }

        await existing.transport.handleRequest(req, res, req.body);
        return;
      }

      // Initialize a new stateful streamable HTTP session
      if (req.method === 'POST' && isInitializeRequest(req.body)) {
        const mcpServer = createMcpServer(
          {
            app,
            db,
            userId,
            sessionId,
            authenticatedUser,
            baseServiceParams,
          },
          toolSearchEnabled,
          servicesConfig
        );

        const transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: () => randomUUID(),
          onsessioninitialized: (newSessionId) => {
            transports.set(newSessionId, { transport, server: mcpServer, userId });
          },
        });

        transport.onclose = () => {
          const sid = transport.sessionId;
          if (sid) transports.delete(sid);
          mcpServer.close().catch(() => {});
        };

        await mcpServer.connect(transport);
        await transport.handleRequest(req, res, req.body);
        return;
      }

      // ─────────────────────────────────────────────────────────────────────────────
      // Stateless fallback mode: preserves legacy behavior for direct POST usage
      // ─────────────────────────────────────────────────────────────────────────────
      if (req.method === 'POST') {
        const mcpServer = createMcpServer(
          {
            app,
            db,
            userId,
            sessionId,
            authenticatedUser,
            baseServiceParams,
          },
          toolSearchEnabled,
          servicesConfig
        );

        const transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: undefined,
        });

        await mcpServer.connect(transport);
        await transport.handleRequest(req, res, req.body);

        res.on('close', () => {
          transport.close().catch(() => {});
          mcpServer.close().catch(() => {});
        });
        return;
      }

      return res.status(405).json({
        jsonrpc: '2.0',
        id: (req.body as { id?: unknown })?.id,
        error: {
          code: -32005,
          message: `Method ${req.method} not allowed on /mcp`,
        },
      });
    } catch (error) {
      console.error('❌ MCP request failed:', error);
      if (!res.headersSent) {
        return res.status(500).json({
          error: 'Internal error',
          message: error instanceof Error ? error.message : String(error),
        });
      }
    }
  };

  // Register as Express POST route
  // @ts-expect-error - FeathersJS app extends Express
  app.post('/mcp', handler);
  // GET supports SSE stream in streamable HTTP transport
  // @ts-expect-error - FeathersJS app extends Express
  app.get('/mcp', handler);
  // DELETE supports streamable HTTP session termination
  // @ts-expect-error - FeathersJS app extends Express
  app.delete('/mcp', handler);

  console.log('✅ MCP routes registered at /mcp (POST + GET + DELETE)');
}
