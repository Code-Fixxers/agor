import { MockAgent, setGlobalDispatcher } from 'undici';
import { afterEach, describe, expect, it } from 'vitest';
import { HermesHttpClient } from './hermes-client';

describe('HermesHttpClient', () => {
  const mockAgent = new MockAgent();
  mockAgent.disableNetConnect();
  setGlobalDispatcher(mockAgent);

  afterEach(() => {
    mockAgent.assertNoPendingInterceptors();
  });

  it('reads OpenAI-compatible SSE chunks from Hermes', async () => {
    const pool = mockAgent.get('http://hermes.local');
    pool
      .intercept({
        path: '/v1/chat/completions',
        method: 'POST',
        headers: {
          authorization: 'Bearer secret',
        },
      })
      .reply(
        200,
        [
          'data: {"choices":[{"delta":{"content":"hello"}}]}',
          '',
          'data: {"choices":[{"delta":{"content":" world"}}]}',
          '',
          'data: [DONE]',
          '',
        ].join('\n'),
        { headers: { 'content-type': 'text/event-stream' } }
      );

    const client = new HermesHttpClient({
      baseUrl: 'http://hermes.local',
      token: 'secret',
      model: 'hermes-test',
    });

    const chunks: string[] = [];
    for await (const chunk of client.streamChat([{ role: 'user', content: 'hi' }])) {
      chunks.push(chunk);
    }

    expect(chunks).toEqual(['hello', ' world']);
  });

  it('throws a concise error when Hermes rejects the request', async () => {
    const pool = mockAgent.get('http://hermes.local');
    pool.intercept({ path: '/v1/chat/completions', method: 'POST' }).reply(401, 'bad token');

    const client = new HermesHttpClient({
      baseUrl: 'http://hermes.local',
      token: 'bad',
      model: 'hermes-test',
    });

    await expect(async () => {
      for await (const _chunk of client.streamChat([{ role: 'user', content: 'hi' }])) {
        // exhaust stream
      }
    }).rejects.toThrow('Hermes 401: bad token');
  });
});
