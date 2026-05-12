/**
 * Config Service
 *
 * Provides REST + WebSocket API for configuration management.
 * Wraps @agor/core/config functions for UI access.
 */

import {
  type AgorConfig,
  type ApiKeyName,
  loadConfig,
  resolveApiKey,
  saveConfig,
} from '@agor/core/config';
import type { Database } from '@agor/core/db';
import type { Application } from '@agor/core/feathers';
import type { AgenticToolName, Params, TaskID, UserID } from '@agor/core/types';

/**
 * Mask API keys for secure display
 */
function maskApiKey(key: string | undefined): string | undefined {
  if (!key || typeof key !== 'string') return undefined;
  if (key.length <= 10) return '***';
  return `${key.substring(0, 10)}...`;
}

/**
 * Mask all credentials in config
 */
function maskCredentials(config: AgorConfig): AgorConfig {
  if (!config.credentials) return config;

  return {
    ...config,
    credentials: {
      ANTHROPIC_API_KEY: maskApiKey(config.credentials.ANTHROPIC_API_KEY),
      ANTHROPIC_AUTH_TOKEN: maskApiKey(config.credentials.ANTHROPIC_AUTH_TOKEN),
      ANTHROPIC_BASE_URL: config.credentials.ANTHROPIC_BASE_URL,
      OPENAI_API_KEY: maskApiKey(config.credentials.OPENAI_API_KEY),
      GEMINI_API_KEY: maskApiKey(config.credentials.GEMINI_API_KEY),
      COPILOT_GITHUB_TOKEN: maskApiKey(config.credentials.COPILOT_GITHUB_TOKEN),
      JUNIE_LITELLM_API_KEY: maskApiKey(config.credentials.JUNIE_LITELLM_API_KEY),
    },
  };
}

function resolveJunieModelsUrl(baseUrl: string): string {
  const normalized = baseUrl.trim().replace(/\/+$/, '');
  if (!normalized) {
    throw new Error('Junie LiteLLM base URL is required');
  }

  if (normalized.endsWith('/v1/models')) return normalized;
  if (normalized.endsWith('/v1/chat/completions')) {
    return `${normalized.slice(0, -'/chat/completions'.length)}/models`;
  }
  if (normalized.endsWith('/v1/responses')) {
    return `${normalized.slice(0, -'/responses'.length)}/models`;
  }
  if (normalized.endsWith('/v1')) return `${normalized}/models`;
  return `${normalized}/v1/models`;
}

export async function fetchJunieModels(baseUrl: string, apiKey: string): Promise<string[]> {
  const response = await fetch(resolveJunieModelsUrl(baseUrl), {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      Accept: 'application/json',
    },
    signal: AbortSignal.timeout(15_000),
  });

  if (!response.ok) {
    throw new Error(`Failed to load Junie models: ${response.status} ${response.statusText}`);
  }

  const body = (await response.json()) as { data?: unknown };
  if (!Array.isArray(body.data)) {
    throw new Error('Failed to load Junie models: gateway response did not contain a data array');
  }

  return body.data
    .map((model) => (model && typeof model === 'object' ? (model as { id?: unknown }).id : null))
    .filter((id): id is string => typeof id === 'string' && id.trim().length > 0);
}

/**
 * Config service class
 */
export class ConfigService {
  private db: Database;
  /** App reference injected after registration for cross-service calls */
  app?: Application;

  constructor(db: Database) {
    this.db = db;
  }

  /**
   * Get full config (masked)
   */
  async find(_params?: Params): Promise<AgorConfig> {
    const config = await loadConfig();
    return maskCredentials(config);
  }

  /**
   * Get specific config section or value
   */
  async get(id: string, _params?: Params): Promise<unknown> {
    const config = await loadConfig();
    const masked = maskCredentials(config);

    // Support dot notation (e.g., "credentials.ANTHROPIC_API_KEY")
    const parts = id.split('.');
    let value: unknown = masked;

    for (const part of parts) {
      if (value && typeof value === 'object' && part in value) {
        value = (value as Record<string, unknown>)[part];
      } else {
        return undefined;
      }
    }

    return value;
  }

  /**
   * Custom method: Resolve API key for a task
   *
   * This allows executors to request API key resolution without direct database access.
   * The service handles the precedence: user-level > config > env > native auth.
   *
   * Called via: client.service('config').resolveApiKey({ taskId, keyName })
   */
  async resolveApiKey(data: {
    taskId: TaskID;
    keyName: string;
    /**
     * Restrict the per-user lookup to this tool's credential bucket. Executors
     * always pass this; absent it, the resolver falls back to a cross-tool
     * sweep (legacy behavior preserved for non-SDK callers).
     */
    tool?: AgenticToolName;
  }): Promise<{
    apiKey: string | null;
    source: 'user' | 'config' | 'env' | 'native';
    useNativeAuth: boolean;
    decryptionFailed?: boolean;
  }> {
    const { taskId, keyName, tool } = data;

    // Fetch task to get creator user ID
    let userId: UserID | undefined;
    try {
      const tasksService = this.app?.service('tasks');
      if (tasksService) {
        const task = await tasksService.get(taskId, { provider: undefined });
        userId = task?.created_by;
      }
    } catch (err) {
      console.warn(`[Config.resolveApiKey] Failed to fetch task ${taskId}:`, err);
    }

    // Use core resolveApiKey with database access
    const result = await resolveApiKey(keyName as ApiKeyName, {
      userId,
      db: this.db,
      tool,
    });

    // Map KeyResolutionResult to service response type
    return {
      apiKey: result.apiKey ?? null,
      source: result.source === 'none' ? 'native' : result.source,
      useNativeAuth: result.useNativeAuth,
      ...(result.decryptionFailed && { decryptionFailed: true }),
    };
  }

  async loadJunieModels(data: { litellmBaseUrl?: string; apiKey?: string }): Promise<{
    models: string[];
  }> {
    const config = await loadConfig();
    const litellmBaseUrl = data.litellmBaseUrl?.trim() || config.junie?.litellmBaseUrl?.trim();
    if (!litellmBaseUrl) {
      throw new Error('Junie LiteLLM base URL is required');
    }

    const apiKey =
      data.apiKey?.trim() ||
      (
        await resolveApiKey('JUNIE_LITELLM_API_KEY', {
          db: this.db,
        })
      ).apiKey;
    if (!apiKey) {
      throw new Error('Junie LiteLLM API key is not configured');
    }

    return {
      models: await fetchJunieModels(litellmBaseUrl, apiKey),
    };
  }

  /**
   * Update config values
   *
   * SECURITY: Only allow updating credentials and opencode sections from UI
   */
  async patch(_id: null, data: Partial<AgorConfig>, _params?: Params): Promise<AgorConfig> {
    // Log patch keys without values to avoid leaking secrets
    const patchSections = Object.keys(data);
    const credentialKeys = data.credentials ? Object.keys(data.credentials) : [];
    console.log(
      `[Config Service] Patch received: sections=[${patchSections}] credential_keys=[${credentialKeys}]`
    );
    const config = await loadConfig();

    // Only allow updating credentials section for security
    if (data.credentials) {
      // Initialize credentials if not present
      if (!config.credentials) {
        config.credentials = {};
      }

      // Update or delete credential keys
      for (const [key, value] of Object.entries(data.credentials)) {
        if (value === undefined || value === null) {
          // Explicitly delete the key when value is undefined or null
          delete config.credentials[key as keyof typeof config.credentials];
        } else {
          // Set the key
          (config.credentials as Record<string, string>)[key] = value;
        }
      }
    }

    // Allow updating opencode configuration
    if (data.opencode) {
      // Initialize opencode if not present
      if (!config.opencode) {
        config.opencode = {};
      }

      // Update opencode settings
      if (data.opencode.enabled !== undefined) {
        config.opencode.enabled = data.opencode.enabled;
      }
      if (data.opencode.serverUrl !== undefined) {
        config.opencode.serverUrl = data.opencode.serverUrl;
      }
    }

    // Allow updating Junie configuration
    if (data.junie) {
      if (!config.junie) {
        config.junie = {};
      }
      const juniePatch = data.junie as Record<string, unknown>;

      if (juniePatch.executable !== undefined) {
        if (juniePatch.executable === null || juniePatch.executable === '') {
          delete config.junie.executable;
        } else if (typeof juniePatch.executable === 'string') {
          config.junie.executable = juniePatch.executable;
        } else {
          throw new Error('junie.executable must be a string');
        }
      }
      if (juniePatch.litellmBaseUrl !== undefined) {
        if (juniePatch.litellmBaseUrl === null || juniePatch.litellmBaseUrl === '') {
          delete config.junie.litellmBaseUrl;
        } else if (typeof juniePatch.litellmBaseUrl === 'string') {
          config.junie.litellmBaseUrl = juniePatch.litellmBaseUrl;
        } else {
          throw new Error('junie.litellmBaseUrl must be a string');
        }
      }
      if (juniePatch.defaultModel !== undefined) {
        if (juniePatch.defaultModel === null || juniePatch.defaultModel === '') {
          delete config.junie.defaultModel;
        } else if (typeof juniePatch.defaultModel === 'string') {
          config.junie.defaultModel = juniePatch.defaultModel;
        } else {
          throw new Error('junie.defaultModel must be a string');
        }
      }
      if (juniePatch.fasterModel !== undefined) {
        if (juniePatch.fasterModel === null || juniePatch.fasterModel === '') {
          delete config.junie.fasterModel;
        } else if (typeof juniePatch.fasterModel === 'string') {
          config.junie.fasterModel = juniePatch.fasterModel;
        } else {
          throw new Error('junie.fasterModel must be a string');
        }
      }
      if (juniePatch.apiType !== undefined) {
        if (juniePatch.apiType === null || juniePatch.apiType === '') {
          delete config.junie.apiType;
        } else if (
          juniePatch.apiType === 'OpenAIResponses' ||
          juniePatch.apiType === 'OpenAICompletion'
        ) {
          config.junie.apiType = juniePatch.apiType;
        } else {
          throw new Error('junie.apiType must be OpenAIResponses or OpenAICompletion');
        }
      }
    }

    // Allow updating onboarding configuration
    if (data.onboarding) {
      if (!config.onboarding) {
        config.onboarding = {};
      }
      if (data.onboarding.assistantPending !== undefined) {
        config.onboarding.assistantPending = data.onboarding.assistantPending;
      }
      // Backward compat: also handle legacy field name
      if (data.onboarding.persistedAgentPending !== undefined) {
        config.onboarding.assistantPending = data.onboarding.persistedAgentPending;
      }
      if (data.onboarding.frameworkRepoUrl !== undefined) {
        config.onboarding.frameworkRepoUrl = data.onboarding.frameworkRepoUrl;
      }
    }

    await saveConfig(config);
    console.log('[Config Service] Config saved successfully');

    // Propagate credentials to process.env for hot-reload
    // Precedence rule: config.yaml (UI) > environment variables
    if (data.credentials) {
      for (const [key, value] of Object.entries(data.credentials)) {
        if (value === undefined || value === null) {
          // Delete from process.env if credential was cleared
          delete process.env[key];
        } else {
          // Update process.env (UI takes precedence)
          process.env[key] = value;
        }
      }
    }

    // Return masked config
    return maskCredentials(config);
  }
}

/**
 * Service factory function
 */
export function createConfigService(db: Database): ConfigService {
  return new ConfigService(db);
}
