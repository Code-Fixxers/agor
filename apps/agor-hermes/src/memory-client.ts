import { fetch } from 'undici';
import type { Config } from './config.js';

/**
 * AnythingLLM client — reads from and writes to a workspace.
 *
 * Soft-disabled when `anythingllmBaseUrl` isn't configured: the conversation
 * loop continues, just without RAG context. We never let memory failures take
 * Hermes down.
 */
export interface MemoryClient {
  enabled: boolean;
  query(question: string, workspace?: string): Promise<MemoryQueryResult>;
  remember(text: string, opts?: RememberOptions): Promise<void>;
}

export interface MemoryQueryResult {
  answer: string;
  sources: MemorySource[];
}

export interface MemorySource {
  title: string;
  snippet: string;
}

export interface RememberOptions {
  workspace?: string;
  title?: string;
  tags?: string[];
}

export function makeMemoryClient(config: Config): MemoryClient {
  if (!config.anythingllmBaseUrl || !config.anythingllmApiKey) {
    return disabledClient();
  }
  const base = config.anythingllmBaseUrl.replace(/\/+$/, '');
  const apiKey = config.anythingllmApiKey;
  const defaultWorkspace = config.anythingllmWorkspace;

  return {
    enabled: true,
    async query(question, workspace = defaultWorkspace) {
      // POST /api/v1/workspace/:slug/chat — see https://docs.anythingllm.com/
      const res = await fetch(`${base}/api/v1/workspace/${encodeURIComponent(workspace)}/chat`, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${apiKey}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify({ message: question, mode: 'query' }),
      });
      if (!res.ok) {
        throw new Error(`anythingllm query failed: ${res.status}`);
      }
      const body = (await res.json()) as {
        textResponse?: string;
        sources?: Array<{ title?: string; chunk?: string }>;
      };
      return {
        answer: body.textResponse ?? '',
        sources: (body.sources ?? []).map((s) => ({
          title: s.title ?? '',
          snippet: s.chunk ?? '',
        })),
      };
    },
    async remember(text, opts = {}) {
      // POST /api/v1/document/raw-text — uploads a single doc into a workspace.
      const workspace = opts.workspace ?? defaultWorkspace;
      const res = await fetch(`${base}/api/v1/document/raw-text`, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${apiKey}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify({
          textContent: text,
          metadata: {
            title: opts.title ?? `hermes-memory-${Date.now()}`,
            keywords: opts.tags?.join(',') ?? '',
          },
          addToWorkspaces: workspace,
        }),
      });
      if (!res.ok) {
        throw new Error(`anythingllm remember failed: ${res.status}`);
      }
    },
  };
}

function disabledClient(): MemoryClient {
  return {
    enabled: false,
    async query() {
      return { answer: '', sources: [] };
    },
    async remember() {
      /* noop */
    },
  };
}
