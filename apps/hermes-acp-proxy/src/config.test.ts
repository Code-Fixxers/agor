import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { loadConfig } from './config';

describe('loadConfig', () => {
  it('loads bearer tokens from a token command for declarative ACP config', () => {
    const config = loadConfig({
      HERMES_URL: 'http://hermes:8642',
      HERMES_TOKEN_COMMAND: 'printf command-token',
      HERMES_MODEL: 'hermes-4',
    });

    expect(config).toEqual({
      hermesUrl: 'http://hermes:8642',
      hermesToken: 'command-token',
      hermesModel: 'hermes-4',
    });
  });

  it('loads bearer tokens from a token file', () => {
    const dir = mkdtempSync(join(tmpdir(), 'hermes-acp-'));
    const path = join(dir, 'token');
    writeFileSync(path, 'file-token\n');
    try {
      const config = loadConfig({
        HERMES_URL: 'http://hermes:8642',
        HERMES_TOKEN_FILE: path,
      });

      expect(config.hermesToken).toBe('file-token');
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
