import { describe, expect, it } from 'vitest';
import { extractJunieAssistantText, normalizeJunieRawResponse } from './normalizer.js';

describe('extractJunieAssistantText', () => {
  it('extracts text from common Junie JSON result shapes', () => {
    expect(extractJunieAssistantText({ result: 'Done from result' })).toBe('Done from result');
    expect(extractJunieAssistantText({ message: 'Done from message' })).toBe('Done from message');
    expect(
      extractJunieAssistantText({ content: [{ type: 'text', text: 'Done from content' }] })
    ).toBe('Done from content');
  });

  it('falls back to stdout when JSON does not contain assistant text', () => {
    expect(extractJunieAssistantText({ status: 'ok' }, 'stdout text')).toBe('stdout text');
  });
});

describe('normalizeJunieRawResponse', () => {
  it('normalizes usage and model when present', () => {
    const normalized = normalizeJunieRawResponse({
      model: 'custom:agor-openai-compatible',
      usage: {
        input_tokens: 10,
        output_tokens: 5,
      },
    });

    expect(normalized?.primaryModel).toBe('custom:agor-openai-compatible');
    expect(normalized?.tokenUsage.totalTokens).toBe(15);
  });
});
