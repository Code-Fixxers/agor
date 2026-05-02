import { z } from 'zod';

/**
 * Hermes runtime configuration.
 *
 * All values come from environment variables — the container is the unit of
 * deployment, not a user-editable yaml file. See README.md for the full env list.
 */
const ConfigSchema = z.object({
  // HTTP server
  host: z.string().default('0.0.0.0'),
  port: z.coerce.number().int().positive().default(4040),
  logLevel: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),

  // Auth — bearer token shared with the Android app
  bearerToken: z.string().min(16),

  // LiteLLM proxy (OpenAI-compatible)
  litellmBaseUrl: z.string().url(),
  litellmApiKey: z.string().min(1),
  defaultModel: z.string().default('qwen3.6'),
  fallbackModel: z.string().default('gemma-4'),

  // Agor daemon (called via MCP for tool surface)
  agorMcpUrl: z.string().url(),
  agorPersonalApiKey: z.string().startsWith('agor_sk_'),

  // AnythingLLM (RAG memory)
  anythingllmBaseUrl: z.string().url().optional(),
  anythingllmApiKey: z.string().optional(),
  anythingllmWorkspace: z.string().default('agor'),

  // SQLite location for conversation persistence
  dbPath: z.string().default('/var/lib/hermes/hermes.db'),

  // Persona file location (hot-reloaded on SIGHUP)
  personaPath: z.string().default('/etc/hermes/persona.md'),
});

export type Config = z.infer<typeof ConfigSchema>;

export function loadConfig(): Config {
  return ConfigSchema.parse({
    host: process.env.HERMES_HOST,
    port: process.env.HERMES_PORT,
    logLevel: process.env.HERMES_LOG_LEVEL,

    bearerToken: process.env.HERMES_BEARER_TOKEN,

    litellmBaseUrl: process.env.LITELLM_BASE_URL,
    litellmApiKey: process.env.LITELLM_API_KEY,
    defaultModel: process.env.HERMES_DEFAULT_MODEL,
    fallbackModel: process.env.HERMES_FALLBACK_MODEL,

    agorMcpUrl: process.env.AGOR_MCP_URL,
    agorPersonalApiKey: process.env.AGOR_PERSONAL_API_KEY,

    anythingllmBaseUrl: process.env.ANYTHINGLLM_BASE_URL,
    anythingllmApiKey: process.env.ANYTHINGLLM_API_KEY,
    anythingllmWorkspace: process.env.ANYTHINGLLM_WORKSPACE,

    dbPath: process.env.HERMES_DB_PATH,
    personaPath: process.env.HERMES_PERSONA_PATH,
  });
}
