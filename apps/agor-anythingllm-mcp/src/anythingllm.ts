import { fetch } from 'undici';

/**
 * Thin AnythingLLM REST client. We only call three endpoints:
 *
 *  - GET  /api/v1/workspaces                  → list workspaces
 *  - POST /api/v1/workspace/:slug/chat        → query a workspace (RAG read)
 *  - POST /api/v1/document/raw-text           → upload a raw-text doc into a workspace (write)
 *
 * AnythingLLM's API surface is much wider; we deliberately keep this client
 * narrow so the MCP tool list stays understandable to whichever LLM is calling it.
 */
export interface AnythingLlmClient {
  listWorkspaces(): Promise<WorkspaceSummary[]>;
  query(workspace: string, question: string): Promise<QueryResult>;
  remember(workspace: string, text: string, opts?: RememberOptions): Promise<RememberResult>;
}

export interface WorkspaceSummary {
  slug: string;
  name: string;
}

export interface QueryResult {
  answer: string;
  sources: { title: string; snippet: string }[];
}

export interface RememberOptions {
  title?: string;
  tags?: string[];
}

export interface RememberResult {
  documentId?: string;
}

export interface AnythingLlmConfig {
  baseUrl: string;
  apiKey: string;
}

export function makeAnythingLlmClient(config: AnythingLlmConfig): AnythingLlmClient {
  const base = config.baseUrl.replace(/\/+$/, '');
  const headers = {
    authorization: `Bearer ${config.apiKey}`,
    'content-type': 'application/json',
  };

  return {
    async listWorkspaces() {
      const res = await fetch(`${base}/api/v1/workspaces`, { headers });
      if (!res.ok) throw new Error(`anythingllm listWorkspaces failed: ${res.status}`);
      const body = (await res.json()) as { workspaces?: Array<{ slug: string; name: string }> };
      return (body.workspaces ?? []).map((w) => ({ slug: w.slug, name: w.name }));
    },

    async query(workspace, question) {
      const res = await fetch(`${base}/api/v1/workspace/${encodeURIComponent(workspace)}/chat`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ message: question, mode: 'query' }),
      });
      if (!res.ok) throw new Error(`anythingllm query failed: ${res.status}`);
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

    async remember(workspace, text, opts = {}) {
      const res = await fetch(`${base}/api/v1/document/raw-text`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          textContent: text,
          metadata: {
            title: opts.title ?? `hermes-memory-${Date.now()}`,
            keywords: opts.tags?.join(',') ?? '',
          },
          addToWorkspaces: workspace,
        }),
      });
      if (!res.ok) throw new Error(`anythingllm remember failed: ${res.status}`);
      const body = (await res.json().catch(() => ({}))) as { documentId?: string };
      return { documentId: body.documentId };
    },
  };
}
