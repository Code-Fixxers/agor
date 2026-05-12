import type {
  INormalizer,
  NormalizedSdkData,
  NormalizedTokenUsage,
} from '../base/normalizer.interface.js';
import type { JunieRawResponse } from './types.js';

function asText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

export function extractJunieAssistantText(raw: unknown, stdoutFallback?: string): string {
  if (!raw || typeof raw !== 'object') {
    return stdoutFallback?.trim() ?? '';
  }

  const response = raw as JunieRawResponse;
  const direct =
    asText(response.result) ??
    asText(response.message) ??
    asText(response.text) ??
    asText(response.content);
  if (direct) return direct;

  if (Array.isArray(response.content)) {
    const text = response.content
      .map((part) => (part.type === undefined || part.type === 'text' ? part.text : undefined))
      .filter((part): part is string => !!part?.trim())
      .join('\n')
      .trim();
    if (text) return text;
  }

  return stdoutFallback?.trim() ?? asText(response.stdout) ?? '';
}

export function normalizeJunieRawResponse(raw: unknown): NormalizedSdkData | undefined {
  if (!raw || typeof raw !== 'object') {
    return undefined;
  }

  const response = raw as JunieRawResponse;
  if (response.normalized) {
    return response.normalized;
  }

  const inputTokens = response.usage?.input_tokens ?? response.usage?.inputTokens ?? 0;
  const outputTokens = response.usage?.output_tokens ?? response.usage?.outputTokens ?? 0;
  const tokenUsage: NormalizedTokenUsage = {
    inputTokens,
    outputTokens,
    totalTokens: inputTokens + outputTokens,
    cacheReadTokens: 0,
    cacheCreationTokens: 0,
  };

  return {
    tokenUsage,
    contextWindowLimit: 0,
    primaryModel: response.model,
  };
}

export class JunieNormalizer implements INormalizer<JunieRawResponse> {
  normalize(raw: JunieRawResponse): NormalizedSdkData {
    return (
      normalizeJunieRawResponse(raw) ?? {
        tokenUsage: {
          inputTokens: 0,
          outputTokens: 0,
          totalTokens: 0,
          cacheReadTokens: 0,
          cacheCreationTokens: 0,
        },
        contextWindowLimit: 0,
      }
    );
  }
}
