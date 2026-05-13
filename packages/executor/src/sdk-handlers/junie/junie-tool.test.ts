import { describe, expect, it } from 'vitest';
import { buildJunieArgs, getJunieSessionId } from './junie-tool.js';

describe('getJunieSessionId', () => {
  it('reuses existing SDK session id and generates deterministic ids otherwise', () => {
    expect(getJunieSessionId('agor-session-id', 'existing-junie-id')).toBe('existing-junie-id');
    expect(getJunieSessionId('550e8400-e29b-41d4-a716-446655440000')).toBe(
      'agor-550e8400-e29b-41d4-a716-446655440000'
    );
  });
});

describe('buildJunieArgs', () => {
  it('does not pass --session-id for a first Junie prompt', () => {
    const args = buildJunieArgs({
      projectPath: '/repo',
      profileId: 'agor-openai-compatible',
      modelDir: '/tmp/models',
      mcpDir: '/tmp/mcp',
      configPath: '/tmp/config.json',
      cacheDir: '/tmp/cache',
      outputPath: '/tmp/output.json',
      prompt: 'Do the thing',
    });

    expect(args).toEqual([
      '--project',
      '/repo',
      '--model',
      'custom:agor-openai-compatible',
      '--model-default-locations',
      'false',
      '--model-location',
      '/tmp/models',
      '--mcp-default-locations',
      'false',
      '--mcp-location',
      '/tmp/mcp',
      '--config-default-locations',
      'false',
      '--config-location',
      '/tmp/config.json',
      '--cache-dir',
      '/tmp/cache',
      '--output-format',
      'json',
      '--json-output-file',
      '/tmp/output.json',
      '--skip-update-check',
      '--task',
      'Do the thing',
    ]);
    expect(args.join(' ')).not.toContain('sk-junie');
  });

  it('passes --session-id only when resuming an existing Junie session', () => {
    const args = buildJunieArgs({
      projectPath: '/repo',
      sessionId: 'junie-session-123',
      profileId: 'agor-openai-compatible',
      modelDir: '/tmp/models',
      mcpDir: '/tmp/mcp',
      configPath: '/tmp/config.json',
      cacheDir: '/tmp/cache',
      outputPath: '/tmp/output.json',
      prompt: 'Follow up',
    });

    expect(args.slice(0, 6)).toEqual([
      '--project',
      '/repo',
      '--session-id',
      'junie-session-123',
      '--model',
      'custom:agor-openai-compatible',
    ]);
  });
});
