import type { ConversationStore, ConversationTurn } from './db.js';
import type { ChatMessage, LlmClient, ToolDefinition } from './llm-client.js';
import type { AgorMcpClient } from './mcp-client.js';
import type { MemoryClient } from './memory-client.js';

/** Minimal logger surface — both pino and fastify's logger satisfy it. */
export interface ConversationLogger {
  warn(obj: object, msg?: string): void;
  info(obj: object, msg?: string): void;
}

/**
 * Single-turn conversation orchestrator.
 *
 * The flow per user message:
 *   1. Append user turn to store.
 *   2. (Future) optional memory_query for RAG context.
 *   3. Compose messages = [system: persona, ...history, user: prompt].
 *   4. Loop:
 *        - Ask LLM with tool list.
 *        - If response is tool_calls, run them via MCP, append tool turns,
 *          continue the loop.
 *        - If response is content, append assistant turn and return it.
 */
export interface ConversationDeps {
  llm: LlmClient;
  mcp: AgorMcpClient;
  memory: MemoryClient;
  store: ConversationStore;
  persona: string;
  logger: ConversationLogger;
  maxToolHops?: number;
}

export interface SendMessageInput {
  conversationId: string;
  text: string;
  model?: string;
}

export interface SendMessageResult {
  reply: string;
  toolHops: number;
}

export async function sendMessage(
  deps: ConversationDeps,
  input: SendMessageInput
): Promise<SendMessageResult> {
  const { llm, mcp, store, persona, logger } = deps;
  const maxHops = deps.maxToolHops ?? 6;

  store.appendTurn({
    conversationId: input.conversationId,
    ts: Date.now(),
    role: 'user',
    content: input.text,
  });

  const tools: ToolDefinition[] = await mcp.listTools();
  const messages: ChatMessage[] = composeMessages(
    persona,
    store.recentTurns(input.conversationId, 50)
  );

  for (let hop = 0; hop <= maxHops; hop++) {
    const result = await llm.chat({ model: input.model, messages, tools });

    if (result.toolCalls.length === 0) {
      store.appendTurn({
        conversationId: input.conversationId,
        ts: Date.now(),
        role: 'assistant',
        content: result.content,
      });
      return { reply: result.content, toolHops: hop };
    }

    // Tool calls — execute, append tool messages, loop.
    messages.push({
      role: 'assistant',
      content: result.content,
      toolCalls: result.toolCalls,
    });
    store.appendTurn({
      conversationId: input.conversationId,
      ts: Date.now(),
      role: 'assistant',
      content: result.content,
      toolCalls: JSON.stringify(result.toolCalls),
    });

    for (const call of result.toolCalls) {
      let parsed: Record<string, unknown> = {};
      try {
        parsed = JSON.parse(call.arguments) as Record<string, unknown>;
      } catch (e) {
        logger.warn({ err: e, args: call.arguments }, 'tool call arguments not JSON');
      }
      const toolResult = await mcp.callTool(call.name, parsed);
      messages.push({
        role: 'tool',
        content: toolResult.content,
        toolCallId: call.id,
      });
      store.appendTurn({
        conversationId: input.conversationId,
        ts: Date.now(),
        role: 'tool',
        content: toolResult.content,
        toolCallId: call.id,
      });
    }
  }

  return { reply: '(reached max tool hops without a final answer)', toolHops: maxHops };
}

function composeMessages(persona: string, history: ConversationTurn[]): ChatMessage[] {
  const out: ChatMessage[] = [{ role: 'system', content: persona }];
  for (const turn of history) {
    if (turn.role === 'tool') {
      out.push({ role: 'tool', content: turn.content, toolCallId: turn.toolCallId });
      continue;
    }
    if (turn.role === 'assistant' && turn.toolCalls) {
      const toolCalls = JSON.parse(turn.toolCalls) as Array<{
        id: string;
        name: string;
        arguments: string;
      }>;
      out.push({ role: 'assistant', content: turn.content, toolCalls });
      continue;
    }
    if (turn.role === 'system' || turn.role === 'user' || turn.role === 'assistant') {
      out.push({ role: turn.role, content: turn.content });
    }
  }
  return out;
}
