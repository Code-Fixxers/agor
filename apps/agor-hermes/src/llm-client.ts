import OpenAI from 'openai';
import type { Config } from './config.js';

/**
 * LLM client — talks to LiteLLM proxy via the OpenAI SDK.
 *
 * LiteLLM is OpenAI-compatible and handles the per-backend translation, including
 * tool calling. Hermes only needs to pick a model codename (`qwen3.6`,
 * `gemma-4`, …) and send standard OpenAI-shaped requests.
 */
export interface LlmClient {
  chat(params: ChatParams): Promise<ChatResult>;
  // TODO(hermes-h2): add streaming variant once we wire WebSocket.
}

export interface ChatParams {
  model?: string;
  messages: ChatMessage[];
  tools?: ToolDefinition[];
  temperature?: number;
}

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string;
  toolCalls?: ToolCall[];
  toolCallId?: string;
  name?: string;
}

export interface ToolDefinition {
  name: string;
  description: string;
  // JSON Schema object — kept loose to avoid a hard dep on the MCP types here.
  inputSchema: Record<string, unknown>;
}

export interface ToolCall {
  id: string;
  name: string;
  // Stringified JSON arguments as returned by the model.
  arguments: string;
}

export interface ChatResult {
  content: string;
  toolCalls: ToolCall[];
  finishReason: 'stop' | 'tool_calls' | 'length' | 'content_filter' | string;
}

export function makeLlmClient(config: Config): LlmClient {
  const openai = new OpenAI({
    baseURL: config.litellmBaseUrl,
    apiKey: config.litellmApiKey,
  });

  return {
    async chat(params) {
      const model = params.model ?? config.defaultModel;
      const completion = await openai.chat.completions.create({
        model,
        messages: toOpenAiMessages(params.messages),
        tools: params.tools?.map(toOpenAiTool),
        tool_choice: params.tools?.length ? 'auto' : undefined,
        temperature: params.temperature ?? 0.7,
      });
      const choice = completion.choices[0];
      const toolCalls: ToolCall[] = (choice?.message?.tool_calls ?? []).map((tc) => ({
        id: tc.id,
        name: tc.function.name,
        arguments: tc.function.arguments,
      }));
      return {
        content: choice?.message?.content ?? '',
        toolCalls,
        finishReason: choice?.finish_reason ?? 'stop',
      };
    },
  };
}

function toOpenAiMessages(messages: ChatMessage[]): OpenAI.ChatCompletionMessageParam[] {
  return messages.map((m) => {
    if (m.role === 'tool') {
      return {
        role: 'tool',
        content: m.content,
        tool_call_id: m.toolCallId ?? '',
      } satisfies OpenAI.ChatCompletionToolMessageParam;
    }
    if (m.role === 'assistant' && m.toolCalls?.length) {
      return {
        role: 'assistant',
        content: m.content,
        tool_calls: m.toolCalls.map((tc) => ({
          id: tc.id,
          type: 'function',
          function: { name: tc.name, arguments: tc.arguments },
        })),
      } satisfies OpenAI.ChatCompletionAssistantMessageParam;
    }
    if (m.role === 'system') {
      return {
        role: 'system',
        content: m.content,
      } satisfies OpenAI.ChatCompletionSystemMessageParam;
    }
    if (m.role === 'user') {
      return { role: 'user', content: m.content } satisfies OpenAI.ChatCompletionUserMessageParam;
    }
    return {
      role: 'assistant',
      content: m.content,
    } satisfies OpenAI.ChatCompletionAssistantMessageParam;
  });
}

function toOpenAiTool(t: ToolDefinition): OpenAI.ChatCompletionTool {
  return {
    type: 'function',
    function: {
      name: t.name,
      description: t.description,
      parameters: t.inputSchema as OpenAI.FunctionParameters,
    },
  };
}
