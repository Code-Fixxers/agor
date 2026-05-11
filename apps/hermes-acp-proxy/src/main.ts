#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { AcpServer } from './acp';
import { loadConfig } from './config';
import { HermesHttpClient } from './hermes-client';
import type { JsonRpcRequest, JsonRpcResponse } from './types';

const config = loadConfig();
const hermes = new HermesHttpClient({
  baseUrl: config.hermesUrl,
  token: config.hermesToken,
  model: config.hermesModel,
});

const server = new AcpServer({
  hermes,
  notify: writeJson,
});

const rl = createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

rl.on('line', async (line) => {
  if (!line.trim()) return;
  try {
    const request = JSON.parse(line) as JsonRpcRequest;
    const response = await server.handle(request);
    if (response) writeJson(response);
  } catch (error) {
    writeJson({
      jsonrpc: '2.0',
      id: null,
      error: {
        code: -32700,
        message: error instanceof Error ? error.message : 'Invalid JSON-RPC request',
      },
    });
  }
});

function writeJson(message: JsonRpcRequest | JsonRpcResponse): void {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}
