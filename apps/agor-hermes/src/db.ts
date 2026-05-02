import { mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import Database from 'better-sqlite3';

/**
 * Conversation persistence — Hermes' own SQLite, isolated from Agor.
 *
 * Stores conversation turns (one row per assistant/user message) and tool calls.
 * Schema is intentionally minimal; we expand it as the conversation loop grows.
 */
export interface ConversationStore {
  appendTurn(turn: ConversationTurn): void;
  recentTurns(conversationId: string, limit: number): ConversationTurn[];
  close(): void;
}

export type Role = 'user' | 'assistant' | 'tool' | 'system';

export interface ConversationTurn {
  conversationId: string;
  ts: number;
  role: Role;
  content: string;
  toolCalls?: string;
  toolCallId?: string;
}

export async function openConversationStore(dbPath: string): Promise<ConversationStore> {
  await mkdir(dirname(dbPath), { recursive: true });
  const db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  db.exec(`
    CREATE TABLE IF NOT EXISTS turns (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      conversation_id TEXT NOT NULL,
      ts INTEGER NOT NULL,
      role TEXT NOT NULL,
      content TEXT NOT NULL,
      tool_calls TEXT,
      tool_call_id TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_turns_conv_ts ON turns(conversation_id, ts);
  `);

  const insert = db.prepare(
    'INSERT INTO turns (conversation_id, ts, role, content, tool_calls, tool_call_id) VALUES (?, ?, ?, ?, ?, ?)'
  );
  const select = db.prepare(
    'SELECT conversation_id as conversationId, ts, role, content, tool_calls as toolCalls, tool_call_id as toolCallId FROM turns WHERE conversation_id = ? ORDER BY ts DESC LIMIT ?'
  );

  return {
    appendTurn(turn) {
      insert.run(
        turn.conversationId,
        turn.ts,
        turn.role,
        turn.content,
        turn.toolCalls ?? null,
        turn.toolCallId ?? null
      );
    },
    recentTurns(conversationId, limit) {
      const rows = select.all(conversationId, limit) as ConversationTurn[];
      return rows.reverse();
    },
    close() {
      db.close();
    },
  };
}
