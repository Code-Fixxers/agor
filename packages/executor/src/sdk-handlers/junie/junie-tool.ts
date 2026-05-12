import type { JunieArgsOptions } from './types.js';

export function getJunieSessionId(agorSessionId: string, sdkSessionId?: string | null): string {
  return sdkSessionId?.trim() || `agor-${agorSessionId}`;
}

export function buildJunieArgs(options: JunieArgsOptions): string[] {
  const args = [
    '--project',
    options.projectPath,
    '--model',
    `custom:${options.profileId}`,
    '--model-default-locations',
    'false',
    '--model-location',
    options.modelDir,
    '--mcp-default-locations',
    'false',
    '--mcp-location',
    options.mcpDir,
    '--config-default-locations',
    'false',
    '--config-location',
    options.configPath,
    '--cache-dir',
    options.cacheDir,
    '--output-format',
    'json',
    '--json-output-file',
    options.outputPath,
    '--skip-update-check',
    '--task',
    options.prompt,
  ];

  if (options.sessionId?.trim()) {
    args.splice(2, 0, '--session-id', options.sessionId.trim());
  }

  return args;
}
