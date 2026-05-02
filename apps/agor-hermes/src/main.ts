import { loadConfig } from './config.js';
import { openConversationStore } from './db.js';
import { makeLlmClient } from './llm-client.js';
import { makeAgorMcpClient } from './mcp-client.js';
import { makeMemoryClient } from './memory-client.js';
import { buildServer } from './server.js';

async function main() {
  const config = loadConfig();
  const store = await openConversationStore(config.dbPath);
  const llm = makeLlmClient(config);
  const mcp = makeAgorMcpClient(config);
  const memory = makeMemoryClient(config);
  const fastify = await buildServer({ config, store, llm, mcp, memory });

  const shutdown = async (signal: string) => {
    fastify.log.info({ signal }, 'shutting down');
    try {
      await fastify.close();
      await mcp.close();
      store.close();
    } finally {
      process.exit(0);
    }
  };
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));

  await fastify.listen({ host: config.host, port: config.port });
  fastify.log.info(
    { port: config.port, memoryEnabled: memory.enabled, defaultModel: config.defaultModel },
    'hermes listening'
  );
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('fatal: hermes failed to start', err);
  process.exit(1);
});
