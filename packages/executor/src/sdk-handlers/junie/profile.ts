import type { JunieApiType, JunieModelProfile, JunieModelProfileOptions } from './types.js';

export const JUNIE_PROFILE_ID = 'agor-openai-compatible';

function stripTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '');
}

export function resolveJunieBaseUrl(
  baseUrl: string,
  apiType: JunieApiType = 'OpenAICompletion'
): string {
  const normalized = stripTrailingSlash(baseUrl.trim());
  if (apiType === 'OpenAIResponses') {
    if (normalized.endsWith('/v1/responses')) return normalized;
    if (normalized.endsWith('/v1')) return `${normalized}/responses`;
    return `${normalized}/v1/responses`;
  }

  if (normalized.endsWith('/v1/chat/completions')) return normalized;
  if (normalized.endsWith('/v1')) return `${normalized}/chat/completions`;
  return `${normalized}/v1/chat/completions`;
}

export function buildJunieModelProfile(options: JunieModelProfileOptions): JunieModelProfile {
  const apiType = options.apiType ?? 'OpenAICompletion';
  const profile: JunieModelProfile = {
    apiType,
    baseUrl: resolveJunieBaseUrl(options.baseUrl, apiType),
    apiKey: options.apiKey,
    id: options.model,
    primaryModel: {
      id: options.model,
    },
  };

  if (options.fasterModel?.trim()) {
    profile.fasterModel = {
      id: options.fasterModel.trim(),
    };
  }

  return profile;
}
