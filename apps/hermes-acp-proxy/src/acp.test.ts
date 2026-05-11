import { describe, expect, it, vi } from 'vitest';
import { AcpServer } from './acp';
import type { HermesClient } from './hermes-client';

function createServer(client: Partial<HermesClient> = {}) {
  const notifications: unknown[] = [];
  const hermes = client as HermesClient;
  const server = new AcpServer({
    hermes,
    notify: (message) => notifications.push(message),
  });
  return { server, notifications };
}

describe('AcpServer', () => {
  it('answers initialize with Hermes ACP capabilities', async () => {
    const { server } = createServer();

    const response = await server.handle({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: { protocolVersion: 1 },
    });

    expect(response).toEqual({
      jsonrpc: '2.0',
      id: 1,
      result: {
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
      },
    });
  });

  it('creates and loads stable local ACP sessions', async () => {
    const { server } = createServer();

    const created = await server.handle({
      jsonrpc: '2.0',
      id: 'new',
      method: 'session/new',
      params: { cwd: '/tmp/project' },
    });
    const sessionId = (created as { result: { sessionId: string } }).result.sessionId;

    const loaded = await server.handle({
      jsonrpc: '2.0',
      id: 'load',
      method: 'session/load',
      params: { sessionId },
    });

    expect(sessionId).toMatch(/^hermes-/);
    expect(loaded).toEqual({
      jsonrpc: '2.0',
      id: 'load',
      result: { sessionId },
    });
  });

  it('streams Hermes chunks as ACP session updates', async () => {
    const streamChat = vi.fn(async function* () {
      yield 'Planning';
      yield ' done.';
    });
    const { server, notifications } = createServer({ streamChat });
    const created = await server.handle({
      jsonrpc: '2.0',
      id: 1,
      method: 'session/new',
      params: {},
    });
    const sessionId = (created as { result: { sessionId: string } }).result.sessionId;

    const response = await server.handle({
      jsonrpc: '2.0',
      id: 2,
      method: 'session/prompt',
      params: {
        sessionId,
        prompt: [{ type: 'text', text: 'What is happening?' }],
      },
    });

    expect(streamChat).toHaveBeenCalledWith([{ role: 'user', content: 'What is happening?' }]);
    expect(notifications).toEqual([
      {
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId,
          update: {
            sessionUpdate: 'agent_message_chunk',
            content: { type: 'text', text: 'Planning' },
          },
        },
      },
      {
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId,
          update: {
            sessionUpdate: 'agent_message_chunk',
            content: { type: 'text', text: ' done.' },
          },
        },
      },
    ]);
    expect(response).toEqual({
      jsonrpc: '2.0',
      id: 2,
      result: { stopReason: 'end_turn' },
    });
  });

  it('returns JSON-RPC errors for unknown sessions and methods', async () => {
    const { server } = createServer();

    await expect(
      server.handle({
        jsonrpc: '2.0',
        id: 1,
        method: 'session/prompt',
        params: { sessionId: 'missing', prompt: [] },
      })
    ).resolves.toMatchObject({
      jsonrpc: '2.0',
      id: 1,
      error: { code: -32001 },
    });

    await expect(
      server.handle({
        jsonrpc: '2.0',
        id: 2,
        method: 'nope',
      })
    ).resolves.toMatchObject({
      jsonrpc: '2.0',
      id: 2,
      error: { code: -32601 },
    });
  });
});
