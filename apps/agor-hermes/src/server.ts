import websocket from '@fastify/websocket';
import Fastify from 'fastify';
import { makeBearerAuth } from './auth.js';
import type { Config } from './config.js';
import { sendMessage } from './conversation.js';
import type { ConversationStore } from './db.js';
import type { LlmClient } from './llm-client.js';
import type { AgorMcpClient } from './mcp-client.js';
import type { MemoryClient } from './memory-client.js';
import { loadPersona } from './persona.js';

export interface ServerDeps {
  config: Config;
  store: ConversationStore;
  llm: LlmClient;
  mcp: AgorMcpClient;
  memory: MemoryClient;
}

export async function buildServer(deps: ServerDeps) {
  const fastify = Fastify({
    logger: { level: deps.config.logLevel },
  });
  await fastify.register(websocket);

  const auth = makeBearerAuth(deps.config.bearerToken);

  // Health is unauthenticated — useful for liveness probes.
  fastify.get('/health', async () => ({
    ok: true,
    memoryEnabled: deps.memory.enabled,
    defaultModel: deps.config.defaultModel,
  }));

  // Authenticated chat endpoint — unary for now; streaming via WS lands in H.2.
  fastify.post('/chat', { preHandler: auth }, async (req, reply) => {
    const body = req.body as { conversationId?: string; text?: string; model?: string } | undefined;
    if (!body?.conversationId || !body.text) {
      reply.code(400);
      return { error: 'conversationId and text are required' };
    }
    const persona = await loadPersona(deps.config.personaPath);
    const result = await sendMessage(
      {
        llm: deps.llm,
        mcp: deps.mcp,
        memory: deps.memory,
        store: deps.store,
        persona,
        logger: fastify.log,
      },
      { conversationId: body.conversationId, text: body.text, model: body.model }
    );
    return result;
  });

  // WebSocket endpoint (streaming) — wired in H.2; placeholder for now.
  fastify.register(async (instance) => {
    instance.get('/chat/stream', { websocket: true, preHandler: auth }, (socket) => {
      socket.send(
        JSON.stringify({ type: 'unsupported', message: 'streaming not yet implemented' })
      );
      socket.close();
    });
  });

  return fastify;
}
