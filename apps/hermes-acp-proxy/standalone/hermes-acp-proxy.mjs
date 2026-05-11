#!/usr/bin/env node
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';

const sessions = new Map();
let nextSession = 1;

const config = {
  hermesUrl: requireEnv('HERMES_URL').replace(/\/+$/, ''),
  hermesToken:
    process.env.HERMES_TOKEN ||
    tokenFromCommand(process.env.HERMES_TOKEN_COMMAND) ||
    tokenFromFile(process.env.HERMES_TOKEN_FILE) ||
    fail('HERMES_TOKEN, HERMES_TOKEN_COMMAND, or HERMES_TOKEN_FILE is required'),
  hermesModel: process.env.HERMES_MODEL || 'hermes',
};

const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', async (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
    const response = await handle(message);
    if (response) write(response);
  } catch (error) {
    write({
      jsonrpc: '2.0',
      id: message?.id ?? null,
      error: {
        code: error.code || -32000,
        message: error.message || 'Hermes ACP proxy error',
      },
    });
  }
});

async function handle(message) {
  if (message.id === undefined) return null;
  switch (message.method) {
    case 'initialize':
      return ok(message.id, {
        protocolVersion: 1,
        agentCapabilities: {
          loadSession: true,
          promptCapabilities: { image: false, embeddedContext: true },
          _meta: { 'agor.dev/hermes': { proxy: true } },
        },
      });
    case 'authenticate':
      return ok(message.id, {});
    case 'session/new': {
      const sessionId = `hermes-${nextSession++}`;
      sessions.set(sessionId, []);
      return ok(message.id, { sessionId });
    }
    case 'session/load': {
      const sessionId = message.params?.sessionId;
      if (!sessions.has(sessionId))
        throw rpcError(-32001, `Unknown Hermes ACP session: ${sessionId || '<missing>'}`);
      return ok(message.id, { sessionId });
    }
    case 'session/prompt':
      return ok(message.id, await prompt(message.params));
    default:
      throw rpcError(-32601, `Method not found: ${message.method}`);
  }
}

async function prompt(params) {
  const sessionId = params?.sessionId;
  const messages = sessions.get(sessionId);
  if (!messages) throw rpcError(-32001, `Unknown Hermes ACP session: ${sessionId || '<missing>'}`);

  const content = Array.isArray(params.prompt)
    ? params.prompt
        .filter((part) => part?.type === 'text')
        .map((part) => part.text || '')
        .join('\n')
    : String(params.prompt || '');
  messages.push({ role: 'user', content });

  let assistant = '';
  for await (const chunk of streamHermes([...messages])) {
    assistant += chunk;
    write({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId,
        update: {
          sessionUpdate: 'agent_message_chunk',
          content: { type: 'text', text: chunk },
        },
      },
    });
  }
  if (assistant) messages.push({ role: 'assistant', content: assistant });
  return { stopReason: 'end_turn' };
}

async function* streamHermes(messages) {
  const response = await fetch(`${config.hermesUrl}/v1/chat/completions`, {
    method: 'POST',
    headers: {
      accept: 'text/event-stream',
      authorization: `Bearer ${config.hermesToken}`,
      'content-type': 'application/json; charset=utf-8',
    },
    body: JSON.stringify({ model: config.hermesModel, messages, stream: true }),
  });
  if (!response.ok)
    throw new Error(`Hermes ${response.status}: ${(await response.text()).slice(0, 400)}`);
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const bytes of response.body) {
    buffer += decoder.decode(bytes, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || '';
    for (const line of lines) {
      const payload = parseData(line);
      if (!payload) continue;
      if (payload === '[DONE]') return;
      const parsed = JSON.parse(payload);
      const text = parsed.choices?.[0]?.delta?.content;
      if (text) yield text;
    }
  }
}

function parseData(line) {
  const trimmed = line.trim();
  return trimmed.startsWith('data:') ? trimmed.slice(5).trim() : null;
}

function ok(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function write(value) {
  process.stdout.write(`${JSON.stringify(value)}\n`);
}

function requireEnv(name) {
  return process.env[name] || fail(`${name} is required`);
}

function tokenFromCommand(command) {
  return command ? execSync(command, { encoding: 'utf8', shell: '/bin/sh' }).trim() : null;
}

function tokenFromFile(path) {
  return path ? readFileSync(path, 'utf8').trim() : null;
}

function fail(message) {
  throw new Error(message);
}

function rpcError(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}
