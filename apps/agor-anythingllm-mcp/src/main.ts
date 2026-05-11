import { timingSafeEqual } from 'node:crypto';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import Fastify from 'fastify';
import { z } from 'zod';
import { makeAnythingLlmClient } from './anythingllm.js';
import { buildMcpServer } from './mcp.js';

const ConfigSchema = z.object({
  host: z.string().default('0.0.0.0'),
  port: z.coerce.number().int().positive().default(13031),
  logLevel: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),

  anythingllmBaseUrl: z.string().url(),
  anythingllmApiKey: z.string().min(1),
  defaultWorkspace: z.string().default('agor'),

  /** Bearer that callers (Hermes) must present on /mcp. ≥16 chars. */
  bearerToken: z.string().min(16),
});

type Config = z.infer<typeof ConfigSchema>;

function loadConfig(): Config {
  return ConfigSchema.parse({
    host: process.env.ANYTHINGLLM_MCP_HOST,
    port: process.env.ANYTHINGLLM_MCP_PORT,
    logLevel: process.env.ANYTHINGLLM_MCP_LOG_LEVEL,

    anythingllmBaseUrl: process.env.ANYTHINGLLM_BASE_URL,
    anythingllmApiKey: process.env.ANYTHINGLLM_API_KEY,
    defaultWorkspace: process.env.ANYTHINGLLM_DEFAULT_WORKSPACE,
    bearerToken: process.env.ANYTHINGLLM_MCP_BEARER,
  });
}

async function main() {
  const config = loadConfig();
  const fastify = Fastify({ logger: { level: config.logLevel } });

  const expectedAuth = Buffer.from(`Bearer ${config.bearerToken}`);
  const requireAuth = async (req: { headers: { authorization?: string } }) => {
    const got = Buffer.from(req.headers.authorization ?? '');
    if (got.length !== expectedAuth.length || !timingSafeEqual(got, expectedAuth)) {
      const err = new Error('unauthorized');
      (err as Error & { statusCode?: number }).statusCode = 401;
      throw err;
    }
  };

  fastify.get('/health', async () => ({
    ok: true,
    upstream: config.anythingllmBaseUrl,
    defaultWorkspace: config.defaultWorkspace,
  }));

  // Streamable HTTP MCP — one server per request, transport closed on response end.
  // The MCP SDK's StreamableHTTPServerTransport speaks the official spec; Hermes
  // Agent's MCP client (or any other) connects with a POST to /mcp.
  fastify.post('/mcp', async (req, reply) => {
    await requireAuth(req as { headers: { authorization?: string } });

    const client = makeAnythingLlmClient({
      baseUrl: config.anythingllmBaseUrl,
      apiKey: config.anythingllmApiKey,
    });
    const server = buildMcpServer(client, config.defaultWorkspace);
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => crypto.randomUUID(),
    });
    await server.connect(transport);
    reply.hijack();
    transport.handleRequest(req.raw, reply.raw, req.body as unknown);
  });

  fastify.setErrorHandler((err, _req, reply) => {
    const e = err as Error & { statusCode?: number };
    const code = e.statusCode ?? 500;
    reply.code(code).send({ error: e.message });
  });

  const shutdown = async (signal: string) => {
    fastify.log.info({ signal }, 'shutting down');
    try {
      await fastify.close();
    } finally {
      process.exit(0);
    }
  };
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));

  await fastify.listen({ host: config.host, port: config.port });
  fastify.log.info(
    { port: config.port, upstream: config.anythingllmBaseUrl },
    'anythingllm-mcp listening'
  );
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('fatal: anythingllm-mcp failed to start', err);
  process.exit(1);
});
