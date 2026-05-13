import { describe, expect, it } from 'vitest';
import { buildJunieModelProfile, resolveJunieBaseUrl } from './profile.js';

describe('buildJunieModelProfile', () => {
  it('builds a Chat Completions profile for an OpenAI-compatible endpoint by default', () => {
    const profile = buildJunieModelProfile({
      apiKey: 'sk-junie',
      baseUrl: 'https://openai-compatible.example.com',
      model: 'gpt-5.4',
      fasterModel: 'gpt-5.4-mini',
    });

    expect(profile).toEqual({
      apiType: 'OpenAICompletion',
      baseUrl: 'https://openai-compatible.example.com/v1/chat/completions',
      apiKey: 'sk-junie',
      id: 'gpt-5.4',
      primaryModel: { id: 'gpt-5.4' },
      fasterModel: { id: 'gpt-5.4-mini' },
    });
  });

  it('builds a Chat Completions profile when requested', () => {
    const profile = buildJunieModelProfile({
      apiKey: 'sk-junie',
      apiType: 'OpenAICompletion',
      baseUrl: 'https://openai-compatible.example.com/v1',
      model: 'gpt-5.4',
    });

    expect(profile.baseUrl).toBe('https://openai-compatible.example.com/v1/chat/completions');
    expect(profile.apiType).toBe('OpenAICompletion');
    expect(profile).not.toHaveProperty('fasterModel');
  });
});

describe('resolveJunieBaseUrl', () => {
  it('does not duplicate endpoint suffixes', () => {
    expect(
      resolveJunieBaseUrl('https://openai-compatible.example.com/v1/responses', 'OpenAIResponses')
    ).toBe('https://openai-compatible.example.com/v1/responses');
    expect(
      resolveJunieBaseUrl(
        'https://openai-compatible.example.com/v1/chat/completions',
        'OpenAICompletion'
      )
    ).toBe('https://openai-compatible.example.com/v1/chat/completions');
  });
});
