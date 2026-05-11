import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

export interface ProxyConfig {
  hermesUrl: string;
  hermesToken: string;
  hermesModel: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ProxyConfig {
  const hermesUrl = env.HERMES_URL || env.AGOR_HERMES_URL;
  const hermesToken =
    env.HERMES_TOKEN ||
    env.AGOR_HERMES_TOKEN ||
    loadTokenFromCommand(env.HERMES_TOKEN_COMMAND || env.AGOR_HERMES_TOKEN_COMMAND) ||
    loadTokenFromFile(env.HERMES_TOKEN_FILE || env.AGOR_HERMES_TOKEN_FILE);
  const hermesModel = env.HERMES_MODEL || env.AGOR_HERMES_MODEL || 'hermes';

  if (!hermesUrl) {
    throw new Error('HERMES_URL is required');
  }
  if (!hermesToken) {
    throw new Error('HERMES_TOKEN is required');
  }

  return { hermesUrl, hermesToken, hermesModel };
}

function loadTokenFromCommand(command: string | undefined): string | undefined {
  if (!command?.trim()) return undefined;
  return execSync(command, {
    encoding: 'utf8',
    shell: '/bin/sh',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function loadTokenFromFile(path: string | undefined): string | undefined {
  if (!path?.trim()) return undefined;
  return readFileSync(path, 'utf8').trim();
}
