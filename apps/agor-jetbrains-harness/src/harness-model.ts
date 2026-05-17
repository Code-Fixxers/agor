import {
  type MessageFixture,
  messageFixtures,
  type SnapshotFixture,
  snapshotFixture,
} from './fixtures.js';

export type LayoutMode = 'side-by-side' | 'stacked';

export interface HarnessState {
  snapshot: SnapshotFixture;
  messagesBySession: Record<string, MessageFixture[]>;
  selectedSessionId: string;
  layout: LayoutMode;
  search: string;
  live?: LiveMessage;
  syncCount: number;
}

export interface LiveMessage {
  sessionId: string;
  messageId: string;
  text: string;
  thinking: string;
  finished: boolean;
}

export function createInitialState(): HarnessState {
  return {
    snapshot: snapshotFixture,
    messagesBySession: structuredClone(messageFixtures),
    selectedSessionId: 'sess-addon',
    layout: 'side-by-side',
    search: '',
    syncCount: 0,
  };
}

export function selectedSession(state: HarnessState) {
  return state.snapshot.sessions.find((session) => session.sessionId === state.selectedSessionId);
}

export function messagesForSelectedSession(state: HarnessState): MessageFixture[] {
  return [...(state.messagesBySession[state.selectedSessionId] ?? [])].sort(
    (a, b) => a.index - b.index
  );
}

export function selectSession(state: HarnessState, sessionId: string): HarnessState {
  if (!state.snapshot.sessions.some((session) => session.sessionId === sessionId)) return state;
  return { ...state, selectedSessionId: sessionId, live: undefined };
}

export function sendPrompt(state: HarnessState, prompt: string): HarnessState {
  const text = prompt.trim();
  if (!text) return state;
  const current = messagesForSelectedSession(state);
  const index = current.at(-1)?.index ?? 0;
  const userMessage: MessageFixture = {
    messageId: `local-user-${index + 1}`,
    sessionId: state.selectedSessionId,
    role: 'USER',
    index: index + 1,
    timestamp: new Date('2026-05-18T00:10:00Z').toISOString(),
    text,
    status: 'queued',
  };
  return {
    ...state,
    messagesBySession: {
      ...state.messagesBySession,
      [state.selectedSessionId]: [...current, userMessage],
    },
    live: {
      sessionId: state.selectedSessionId,
      messageId: `live-${index + 2}`,
      text: '',
      thinking: '',
      finished: false,
    },
  };
}

export function appendStreamingChunk(
  state: HarnessState,
  text: string,
  thinking = false
): HarnessState {
  if (!state.live) return state;
  return {
    ...state,
    live: {
      ...state.live,
      text: thinking ? state.live.text : state.live.text + text,
      thinking: thinking ? state.live.thinking + text : state.live.thinking,
    },
  };
}

export function finishStreaming(state: HarnessState): HarnessState {
  if (!state.live) return state;
  const current = messagesForSelectedSession(state);
  const index = current.at(-1)?.index ?? 0;
  const persisted: MessageFixture = {
    messageId: state.live.messageId,
    sessionId: state.live.sessionId,
    role: 'ASSISTANT',
    index: index + 1,
    timestamp: new Date('2026-05-18T00:10:04Z').toISOString(),
    text: state.live.text || 'Done.',
    status: 'complete',
  };
  return {
    ...state,
    live: undefined,
    messagesBySession: {
      ...state.messagesBySession,
      [state.selectedSessionId]: [...current, persisted],
    },
  };
}

export function applyBackgroundSync(state: HarnessState): HarnessState {
  return {
    ...state,
    syncCount: state.syncCount + 1,
  };
}

export function visibleBoards(state: HarnessState) {
  const query = state.search.trim().toLowerCase();
  if (!query) return state.snapshot.boards;
  const matchingWorktreeIds = new Set(
    state.snapshot.worktrees
      .filter(
        (worktree) =>
          worktree.name.toLowerCase().includes(query) || worktree.ref.toLowerCase().includes(query)
      )
      .map((worktree) => worktree.worktreeId)
  );
  const matchingBoardIds = new Set(
    state.snapshot.sessions
      .filter(
        (session) =>
          session.title.toLowerCase().includes(query) || matchingWorktreeIds.has(session.worktreeId)
      )
      .map(
        (session) =>
          state.snapshot.worktrees.find((worktree) => worktree.worktreeId === session.worktreeId)
            ?.boardId
      )
      .filter(Boolean)
  );
  state.snapshot.worktrees
    .filter((worktree) => matchingWorktreeIds.has(worktree.worktreeId))
    .forEach((worktree) => {
      matchingBoardIds.add(worktree.boardId);
    });
  state.snapshot.boards
    .filter((board) => board.name.toLowerCase().includes(query))
    .forEach((board) => {
      matchingBoardIds.add(board.boardId);
    });
  return state.snapshot.boards.filter((board) => matchingBoardIds.has(board.boardId));
}
