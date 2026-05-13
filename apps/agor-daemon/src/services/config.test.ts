import { describe, expect, it, vi } from 'vitest';
import { fetchJunieModels } from './config';

describe('fetchJunieModels', () => {
  it('loads model IDs from an OpenAI-compatible models endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          data: [{ id: 'qwen-3.6-27b' }, { id: 'qwen-3.5-35b-a3b' }, { object: 'model' }],
        }),
        { status: 200 }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchJunieModels('https://llm.bitp.cz', 'sk-test')).resolves.toEqual([
      'qwen-3.6-27b',
      'qwen-3.5-35b-a3b',
    ]);

    expect(fetchMock).toHaveBeenCalledWith(
      'https://llm.bitp.cz/v1/models',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer sk-test',
        }),
      })
    );
  });

  it('normalizes endpoint URLs when users paste a completion endpoint', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ data: [{ id: 'model-a' }] }), { status: 200 })
      );
    vi.stubGlobal('fetch', fetchMock);

    await fetchJunieModels('https://llm.bitp.cz/v1/chat/completions', 'sk-test');

    expect(fetchMock).toHaveBeenCalledWith(
      'https://llm.bitp.cz/v1/models',
      expect.objectContaining({
        method: 'GET',
      })
    );
  });

  it('throws a clean error when the gateway rejects the request', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response('unauthorized', { status: 401, statusText: 'Unauthorized' })
        )
    );

    await expect(fetchJunieModels('https://llm.bitp.cz', 'bad-key')).rejects.toThrow(
      'Failed to load Junie models: 401 Unauthorized'
    );
  });
});
