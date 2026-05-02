import type { Config } from './config.js';
import type { ToolDefinition } from './llm-client.js';

/**
 * MCP client — connects to Agor's MCP server to expose `agor_*` tools to
 * Hermes. The Agor daemon's MCP server lives at `${agorMcpUrl}` and authenticates
 * via a personal API key (`agor_sk_*`) sent as a bearer token.
 *
 * For now this is a thin stub. The real implementation will use
 * `@modelcontextprotocol/sdk` with the streamable-HTTP client transport once
 * we wire the conversation loop in Phase H.2.
 */
export interface AgorMcpClient {
  /** List `agor_*` tools available to this caller, formatted for the LLM. */
  listTools(): Promise<ToolDefinition[]>;
  /** Call a tool by name. Args is the parsed JSON object the model emitted. */
  callTool(name: string, args: Record<string, unknown>): Promise<ToolCallResult>;
  close(): Promise<void>;
}

export interface ToolCallResult {
  ok: boolean;
  /** Stringified content the LLM gets back as the tool message body. */
  content: string;
}

export function makeAgorMcpClient(_config: Config): AgorMcpClient {
  // TODO(hermes-h2): instantiate StreamableHTTPClientTransport with bearer
  // auth, connect, cache the tools list, route callTool through it.
  return {
    async listTools() {
      return [];
    },
    async callTool(name, _args) {
      return { ok: false, content: `tool ${name} not implemented (mcp client stub)` };
    },
    async close() {
      /* noop */
    },
  };
}
