import type { NormalizedSdkData } from '../base/normalizer.interface.js';

export type JunieApiType = 'OpenAIResponses' | 'OpenAICompletion';

export interface JunieModelProfileOptions {
  apiKey: string;
  baseUrl: string;
  model: string;
  fasterModel?: string;
  apiType?: JunieApiType;
}

export interface JunieModelProfile {
  apiType: JunieApiType;
  baseUrl: string;
  apiKey: string;
  id: string;
  primaryModel: {
    id: string;
  };
  fasterModel?: {
    id: string;
  };
}

export interface JunieRawResponse {
  result?: string;
  message?: string;
  text?: string;
  content?: string | Array<{ type?: string; text?: string }>;
  model?: string;
  usage?: {
    input_tokens?: number;
    output_tokens?: number;
    inputTokens?: number;
    outputTokens?: number;
  };
  stdout?: string;
  stderr?: string;
  exitCode?: number | null;
  normalized?: NormalizedSdkData;
  [key: string]: unknown;
}

export interface JunieArgsOptions {
  projectPath: string;
  sessionId?: string;
  profileId: string;
  modelDir: string;
  mcpDir: string;
  configPath: string;
  cacheDir: string;
  outputPath: string;
  prompt: string;
}
