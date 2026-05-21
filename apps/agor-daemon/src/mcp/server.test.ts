import type { Request, Response } from 'express';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  buildLoadedDomainCacheKey,
  closeMcpTransportState,
  coerceJsonRecord,
  McpRequestTimeoutError,
  setupMCPRoutes,
  withTimeout,
} from './server.js';

describe('coerceJsonRecord', () => {
  it('passes through a plain object unchanged', () => {
    const obj = { boardId: '123', name: 'test' };
    expect(coerceJsonRecord(obj)).toBe(obj);
  });

  it('passes through undefined unchanged', () => {
    expect(coerceJsonRecord(undefined)).toBeUndefined();
  });

  it('passes through null unchanged', () => {
    expect(coerceJsonRecord(null)).toBeNull();
  });

  it('passes through a number unchanged', () => {
    expect(coerceJsonRecord(42)).toBe(42);
  });

  it('parses a JSON-stringified object back to an object', () => {
    const input = JSON.stringify({ boardId: '123', name: 'test' });
    expect(coerceJsonRecord(input)).toEqual({ boardId: '123', name: 'test' });
  });

  it('parses a complex stringified object with markdown content', () => {
    const obj = {
      worktreeId: 'abc-123',
      initialPrompt:
        '# Hello\n\nSome **markdown** with `backticks` and\n\n```ts\nconst x = 1;\n```',
    };
    expect(coerceJsonRecord(JSON.stringify(obj))).toEqual(obj);
  });

  it('returns "null" string parsed as null (Zod rejects downstream)', () => {
    expect(coerceJsonRecord('null')).toBeNull();
  });

  it('returns "[]" string parsed as array (Zod rejects downstream)', () => {
    expect(coerceJsonRecord('[]')).toEqual([]);
  });

  it('returns "42" string parsed as number (Zod rejects downstream)', () => {
    expect(coerceJsonRecord('42')).toBe(42);
  });

  it('returns empty string unchanged (not valid JSON)', () => {
    expect(coerceJsonRecord('')).toBe('');
  });

  it('returns malformed JSON string unchanged', () => {
    expect(coerceJsonRecord('{bad json')).toBe('{bad json');
  });

  it('returns non-JSON string unchanged', () => {
    expect(coerceJsonRecord('hello world')).toBe('hello world');
  });
});

/**
 * Capture the Express handler registered by setupMCPRoutes so the
 * token-source validation branches can be tested without spinning up
 * the full FeathersJS stack.
 */
function captureMcpHandler() {
  let handler: ((req: Request, res: Response) => Promise<unknown> | unknown) | null = null;
  const register = (_path: string, fn: typeof handler) => {
    handler = fn;
  };
  const app = {
    post: register,
    get: register,
    delete: register,
  } as unknown as Parameters<typeof setupMCPRoutes>[0];
  setupMCPRoutes(app, {} as never, /* toolSearchEnabled */ false);
  if (!handler) throw new Error('MCP handler was not registered');
  return handler;
}

function buildRes() {
  const res = {
    statusCode: 200,
    body: undefined as unknown,
    status(code: number) {
      this.statusCode = code;
      return this;
    },
    json(body: unknown) {
      this.body = body;
      return this;
    },
    on(_event: string, _cb: () => void) {
      return this;
    },
  };
  return res;
}

describe('POST /mcp token source', () => {
  afterEach(() => {
    // Restore any spies installed per-test (e.g. console.warn) so later
    // suites start from a clean slate.
    vi.restoreAllMocks();
  });

  it('rejects requests with ?sessionToken= query param (400)', async () => {
    const handler = captureMcpHandler();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const req = {
      method: 'POST',
      query: { sessionToken: 'leaky-token-value' },
      headers: {},
      body: { id: 7 },
      ip: '127.0.0.1',
      socket: { remoteAddress: '127.0.0.1' },
    } as unknown as Request;
    const res = buildRes();
    await handler(req, res as unknown as Response);
    expect(res.statusCode).toBe(400);
    const body = res.body as { error?: { message?: string }; id?: number };
    expect(body?.error?.message).toMatch(/no longer accepted/i);
    expect(body?.id).toBe(7);
    // The deprecation log must never include the token value.
    const logged = warn.mock.calls.flat().map(String).join(' ');
    expect(logged).not.toContain('leaky-token-value');
    warn.mockRestore();
  });

  it('rejects requests with no Authorization header (401)', async () => {
    const handler = captureMcpHandler();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const req = {
      method: 'POST',
      query: {},
      headers: {},
      body: { id: 8 },
      ip: '127.0.0.1',
      socket: { remoteAddress: '127.0.0.1' },
    } as unknown as Request;
    const res = buildRes();
    await handler(req, res as unknown as Response);
    expect(res.statusCode).toBe(401);
    const body = res.body as { error?: { message?: string } };
    expect(body?.error?.message).toMatch(/authorization: bearer/i);
  });

  it('rejects even when query has both ?sessionToken= and an Authorization header (query wins → 400)', async () => {
    const handler = captureMcpHandler();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const req = {
      method: 'POST',
      query: { sessionToken: 'qp' },
      headers: { authorization: 'Bearer header-token' },
      body: { id: 9 },
      ip: '127.0.0.1',
      socket: { remoteAddress: '127.0.0.1' },
    } as unknown as Request;
    const res = buildRes();
    await handler(req, res as unknown as Response);
    expect(res.statusCode).toBe(400);
  });

  it('logs the deprecation warning at most once per caller IP', async () => {
    const handler = captureMcpHandler();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    // Use a unique IP so the module-level Set isn't already populated for it.
    const uniqueIp = `10.9.8.${Math.floor(Math.random() * 255)}`;
    const makeReq = () =>
      ({
        method: 'POST',
        query: { sessionToken: 'x' },
        headers: {},
        body: { id: 1 },
        ip: uniqueIp,
        socket: { remoteAddress: uniqueIp },
      }) as unknown as Request;

    await handler(makeReq(), buildRes() as unknown as Response);
    const firstCount = warn.mock.calls.length;
    await handler(makeReq(), buildRes() as unknown as Response);
    await handler(makeReq(), buildRes() as unknown as Response);
    // Second and third calls from the same IP must not emit another warn.
    expect(warn.mock.calls.length).toBe(firstCount);

    // A different IP still warns.
    const otherIp = `10.9.7.${Math.floor(Math.random() * 255)}`;
    const otherReq = {
      method: 'POST',
      query: { sessionToken: 'x' },
      headers: {},
      body: { id: 2 },
      ip: otherIp,
      socket: { remoteAddress: otherIp },
    } as unknown as Request;
    await handler(otherReq, buildRes() as unknown as Response);
    expect(warn.mock.calls.length).toBe(firstCount + 1);
    warn.mockRestore();
  });
});

describe('Stateful MCP Transports and API Key Context', () => {
  it('scopes loaded domains by credential fingerprint, not only user/session', () => {
    const ctx = {
      userId: 'user-1',
      sessionId: undefined,
    } as Parameters<typeof buildLoadedDomainCacheKey>[0];

    expect(buildLoadedDomainCacheKey(ctx, 'key-a')).not.toBe(
      buildLoadedDomainCacheKey(ctx, 'key-b')
    );
  });

  it('runs timeout cleanup when request processing exceeds the deadline', async () => {
    vi.useFakeTimers();
    const cleanup = vi.fn();
    const pending = new Promise<string>(() => {});
    const timed = withTimeout(pending, 10, 'too slow', cleanup);
    const assertion = expect(timed).rejects.toBeInstanceOf(McpRequestTimeoutError);

    await vi.advanceTimersByTimeAsync(10);

    await assertion;
    expect(cleanup).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });

  it('closes both transport and server during MCP cleanup', async () => {
    const transport = { close: vi.fn(async () => {}) };
    const server = { close: vi.fn(async () => {}) };

    closeMcpTransportState({ transport, server });
    await Promise.resolve();

    expect(transport.close).toHaveBeenCalledTimes(1);
    expect(server.close).toHaveBeenCalledTimes(1);
  });

  it('swallows cleanup close failures so timeout handlers cannot crash', async () => {
    const transport = {
      close: vi.fn(() => {
        throw new Error('transport close failed');
      }),
    };
    const server = { close: vi.fn(async () => {}) };

    expect(() => closeMcpTransportState({ transport, server })).not.toThrow();
    await Promise.resolve();
    await Promise.resolve();

    expect(transport.close).toHaveBeenCalledTimes(1);
    expect(server.close).toHaveBeenCalledTimes(1);
  });

  it('closes an initialized stateful transport when initialize times out', async () => {
    vi.useFakeTimers();
    vi.resetModules();

    try {
      const transportClose = vi.fn(async () => {});
      const serverClose = vi.fn(async () => {});

      vi.doMock('@modelcontextprotocol/sdk/server/mcp.js', () => ({
        McpServer: vi.fn().mockImplementation(function MockMcpServer() {
          return {
            registerTool: vi.fn(),
            connect: vi.fn(async () => {}),
            close: serverClose,
            server: {
              setRequestHandler: vi.fn(),
              sendToolListChanged: vi.fn(async () => {}),
            },
          };
        }),
      }));

      vi.doMock('@modelcontextprotocol/sdk/server/streamableHttp.js', () => ({
        StreamableHTTPServerTransport: vi
          .fn()
          .mockImplementation(function MockStreamableHTTPServerTransport(options) {
            return {
              sessionId: undefined as string | undefined,
              close: transportClose,
              handleRequest() {
                this.sessionId = 'mcp-session-1';
                options.onsessioninitialized?.('mcp-session-1');
                return new Promise(() => {});
              },
            };
          }),
      }));

      vi.doMock('./tokens.js', () => ({
        validateSessionToken: vi.fn(async () => ({
          userId: 'user-1',
          sessionId: 'session-1',
        })),
      }));

      const { setupMCPRoutes: setupRoutesWithMocks } = await import('./server.js');
      let handler: ((req: Request, res: Response) => Promise<unknown> | unknown) | null = null;
      const app = {
        post: (_path: string, fn: typeof handler) => {
          handler = fn;
        },
        get: (_path: string, fn: typeof handler) => {
          handler = fn;
        },
        delete: (_path: string, fn: typeof handler) => {
          handler = fn;
        },
        service: (name: string) => {
          if (name === 'users') {
            return {
              get: vi.fn(async () => ({
                user_id: 'user-1',
                email: 'user@example.com',
                role: 'admin',
              })),
            };
          }
          return {};
        },
      } as unknown as Parameters<typeof setupRoutesWithMocks>[0];

      setupRoutesWithMocks(app, {} as never, false);
      if (!handler) throw new Error('MCP handler was not registered');

      const req = {
        method: 'POST',
        query: {},
        headers: { authorization: 'Bearer session-token' },
        body: { jsonrpc: '2.0', id: 1, method: 'initialize' },
        ip: '127.0.0.1',
        socket: { remoteAddress: '127.0.0.1' },
      } as unknown as Request;
      const res = buildRes();

      const pending = handler(req, res as unknown as Response);
      await vi.advanceTimersByTimeAsync(30_000);
      await pending;
      await Promise.resolve();
      await Promise.resolve();

      expect(res.statusCode).toBe(504);
      expect(transportClose).toHaveBeenCalledTimes(1);
      expect(serverClose).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
      vi.doUnmock('@modelcontextprotocol/sdk/server/mcp.js');
      vi.doUnmock('@modelcontextprotocol/sdk/server/streamableHttp.js');
      vi.doUnmock('./tokens.js');
      vi.resetModules();
    }
  });
});
