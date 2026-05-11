import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';
import type { AnythingLlmClient } from './anythingllm.js';

/**
 * MCP server exposing three tools to whichever LLM is wired to it. All return
 * a content-only message — no resources, no prompts.
 */
export function buildMcpServer(client: AnythingLlmClient, defaultWorkspace: string): McpServer {
  const server = new McpServer({
    name: 'anythingllm-mcp',
    version: '0.1.0',
  });

  server.registerTool(
    'anythingllm_query',
    {
      title: 'Query AnythingLLM memory',
      description:
        'Ask a workspace a question. Returns the textual answer plus a list of source snippets. ' +
        'Use this BEFORE answering anything that could plausibly have prior context.',
      inputSchema: {
        question: z.string().min(1).describe('Natural-language question to ask the workspace.'),
        workspace: z
          .string()
          .optional()
          .describe(`Workspace slug. Defaults to "${defaultWorkspace}".`),
      },
    },
    async ({ question, workspace }) => {
      const slug = workspace ?? defaultWorkspace;
      try {
        const r = await client.query(slug, question);
        const sourceText = r.sources.length
          ? '\n\nSources:\n' +
            r.sources
              .map((s, i) => `[${i + 1}] ${s.title}\n${s.snippet.slice(0, 400)}`)
              .join('\n\n')
          : '';
        return {
          content: [{ type: 'text', text: `${r.answer}${sourceText}` }],
        };
      } catch (e) {
        return {
          isError: true,
          content: [{ type: 'text', text: `query failed: ${(e as Error).message}` }],
        };
      }
    }
  );

  server.registerTool(
    'anythingllm_remember',
    {
      title: 'Save a note to AnythingLLM memory',
      description:
        'Persist a short text note as a document in a workspace. Use sparingly — only when ' +
        'the user makes a real decision worth keeping (architecture choice, "always do X" rule, ' +
        'a fact you should not forget). Do not save raw conversations.',
      inputSchema: {
        text: z.string().min(1).describe('The note body to save.'),
        title: z.string().optional().describe('Short human title for the note.'),
        workspace: z
          .string()
          .optional()
          .describe(`Workspace slug. Defaults to "${defaultWorkspace}".`),
        tags: z.array(z.string()).optional().describe('Optional keyword tags.'),
      },
    },
    async ({ text, title, workspace, tags }) => {
      const slug = workspace ?? defaultWorkspace;
      try {
        const r = await client.remember(slug, text, { title, tags });
        return {
          content: [
            {
              type: 'text',
              text: r.documentId
                ? `Saved to "${slug}" (id: ${r.documentId}).`
                : `Saved to "${slug}".`,
            },
          ],
        };
      } catch (e) {
        return {
          isError: true,
          content: [{ type: 'text', text: `remember failed: ${(e as Error).message}` }],
        };
      }
    }
  );

  server.registerTool(
    'anythingllm_list_workspaces',
    {
      title: 'List AnythingLLM workspaces',
      description:
        'Enumerate workspaces the API key can access. Use this when picking which ' +
        'workspace to query/remember if the default does not feel right.',
      inputSchema: {},
    },
    async () => {
      try {
        const ws = await client.listWorkspaces();
        const text = ws.length
          ? ws.map((w) => `- ${w.slug}\t${w.name}`).join('\n')
          : '(no workspaces)';
        return { content: [{ type: 'text', text }] };
      } catch (e) {
        return {
          isError: true,
          content: [{ type: 'text', text: `list failed: ${(e as Error).message}` }],
        };
      }
    }
  );

  return server;
}

export type McpTransportFactory = () => StreamableHTTPServerTransport;
