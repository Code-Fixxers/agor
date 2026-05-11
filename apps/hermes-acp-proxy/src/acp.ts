import type { HermesClient } from './hermes-client';
import type { HermesMessage, JsonRpcRequest, JsonRpcResponse } from './types';

type Notify = (message: JsonRpcRequest) => void;

interface AcpServerOptions {
  hermes: HermesClient;
  notify: Notify;
}

interface AcpSession {
  id: string;
  messages: HermesMessage[];
}

interface PromptContent {
  type?: string;
  text?: string;
}

export class AcpServer {
  private readonly hermes: HermesClient;
  private readonly notify: Notify;
  private readonly sessions = new Map<string, AcpSession>();
  private nextSession = 1;

  constructor(options: AcpServerOptions) {
    this.hermes = options.hermes;
    this.notify = options.notify;
  }

  async handle(message: JsonRpcRequest): Promise<JsonRpcResponse | null> {
    if (message.id === undefined) {
      await this.handleNotification(message);
      return null;
    }

    try {
      switch (message.method) {
        case 'initialize':
          return success(message.id, {
            protocolVersion: 1,
            agentCapabilities: {
              loadSession: true,
              promptCapabilities: {
                image: false,
                embeddedContext: true,
              },
              _meta: {
                'agor.dev/hermes': {
                  proxy: true,
                },
              },
            },
          });
        case 'authenticate':
          return success(message.id, {});
        case 'session/new':
          return success(message.id, this.createSession());
        case 'session/load':
          return success(message.id, this.loadSession(message.params));
        case 'session/prompt':
          return success(message.id, await this.prompt(message.params));
        default:
          return failure(message.id, -32601, `Method not found: ${message.method}`);
      }
    } catch (error) {
      if (error instanceof AcpError) {
        return failure(message.id, error.code, error.message);
      }
      return failure(
        message.id,
        -32000,
        error instanceof Error ? error.message : 'Unexpected ACP proxy error'
      );
    }
  }

  private async handleNotification(message: JsonRpcRequest): Promise<void> {
    if (message.method === 'session/cancel') {
      return;
    }
  }

  private createSession(): { sessionId: string } {
    const id = `hermes-${this.nextSession++}`;
    this.sessions.set(id, { id, messages: [] });
    return { sessionId: id };
  }

  private loadSession(params: unknown): { sessionId: string } {
    const sessionId = readString(params, 'sessionId');
    if (!sessionId || !this.sessions.has(sessionId)) {
      throw new AcpError(-32001, `Unknown Hermes ACP session: ${sessionId ?? '<missing>'}`);
    }
    return { sessionId };
  }

  private async prompt(params: unknown): Promise<{ stopReason: string }> {
    const sessionId = readString(params, 'sessionId');
    if (!sessionId) throw new AcpError(-32602, 'sessionId is required');
    const session = this.sessions.get(sessionId);
    if (!session) throw new AcpError(-32001, `Unknown Hermes ACP session: ${sessionId}`);

    const content = readPrompt(params);
    const userMessage: HermesMessage = { role: 'user', content };
    session.messages.push(userMessage);

    let assistant = '';
    const requestMessages = [...session.messages];
    for await (const chunk of this.hermes.streamChat(requestMessages)) {
      assistant += chunk;
      this.notify({
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId,
          update: {
            sessionUpdate: 'agent_message_chunk',
            content: { type: 'text', text: chunk },
          },
        },
      });
    }

    if (assistant) {
      session.messages.push({ role: 'assistant', content: assistant });
    }
    return { stopReason: 'end_turn' };
  }
}

class AcpError extends Error {
  constructor(
    readonly code: number,
    message: string
  ) {
    super(message);
  }
}

function success(id: string | number | null, result: unknown): JsonRpcResponse {
  return { jsonrpc: '2.0', id, result };
}

function failure(id: string | number | null, code: number, message: string): JsonRpcResponse {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function readString(params: unknown, key: string): string | null {
  if (!params || typeof params !== 'object') return null;
  const value = (params as Record<string, unknown>)[key];
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function readPrompt(params: unknown): string {
  if (!params || typeof params !== 'object') {
    throw new AcpError(-32602, 'params object is required');
  }
  const prompt = (params as Record<string, unknown>).prompt;
  if (typeof prompt === 'string') return prompt;
  if (!Array.isArray(prompt)) return '';

  return prompt
    .map((item: PromptContent) => {
      if (item?.type === 'text' && typeof item.text === 'string') return item.text;
      return '';
    })
    .filter(Boolean)
    .join('\n');
}
