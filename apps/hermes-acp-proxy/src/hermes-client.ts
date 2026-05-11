import { fetch } from 'undici';
import type { HermesMessage } from './types';

export interface HermesClient {
  streamChat(messages: HermesMessage[]): AsyncIterable<string>;
}

export interface HermesHttpClientOptions {
  baseUrl: string;
  token: string;
  model: string;
}

interface ChatCompletionChunk {
  choices?: Array<{
    delta?: {
      content?: string;
    };
  }>;
}

export class HermesHttpClient implements HermesClient {
  private readonly baseUrl: string;
  private readonly token: string;
  private readonly model: string;

  constructor(options: HermesHttpClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.token = options.token;
    this.model = options.model;
  }

  async *streamChat(messages: HermesMessage[]): AsyncIterable<string> {
    const response = await fetch(`${this.baseUrl}/v1/chat/completions`, {
      method: 'POST',
      headers: {
        accept: 'text/event-stream',
        authorization: `Bearer ${this.token}`,
        'content-type': 'application/json; charset=utf-8',
      },
      body: JSON.stringify({
        model: this.model,
        messages,
        stream: true,
      }),
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`Hermes ${response.status}: ${text.slice(0, 400)}`);
    }

    if (!response.body) {
      throw new Error('Hermes response did not include a stream body');
    }

    const decoder = new TextDecoder();
    let buffer = '';
    for await (const chunk of response.body as AsyncIterable<Uint8Array>) {
      buffer += decoder.decode(chunk, { stream: true });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        const content = parseSseLine(line);
        if (content === null) continue;
        if (content === '[DONE]') return;
        const parsed = safeJsonParse<ChatCompletionChunk>(content);
        const delta = parsed?.choices?.[0]?.delta?.content;
        if (delta) yield delta;
      }
    }

    buffer += decoder.decode();
    const content = parseSseLine(buffer);
    if (content && content !== '[DONE]') {
      const parsed = safeJsonParse<ChatCompletionChunk>(content);
      const delta = parsed?.choices?.[0]?.delta?.content;
      if (delta) yield delta;
    }
  }
}

function parseSseLine(line: string): string | null {
  const trimmed = line.trim();
  if (!trimmed.startsWith('data:')) return null;
  return trimmed.slice('data:'.length).trim();
}

function safeJsonParse<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}
