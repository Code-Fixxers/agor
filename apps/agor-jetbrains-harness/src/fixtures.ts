export type SessionStatus = 'RUNNING' | 'IDLE' | 'COMPLETED' | 'FAILED' | 'QUEUED' | 'UNKNOWN';
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM' | 'UNKNOWN';

export interface BoardFixture {
  boardId: string;
  name: string;
}

export interface WorktreeFixture {
  worktreeId: string;
  repoId: string;
  boardId: string;
  name: string;
  ref: string;
  path: string;
}

export interface SessionFixture {
  sessionId: string;
  worktreeId: string;
  title: string;
  agenticTool: string;
  status: SessionStatus;
}

export interface MessageFixture {
  messageId: string;
  sessionId: string;
  taskId?: string;
  role: MessageRole;
  index: number;
  timestamp: string;
  text: string;
  status?: string;
}

export interface PermissionFixture {
  requestId: string;
  sessionId: string;
  taskId?: string;
  toolName: string;
  toolInputJson: string;
}

export interface SnapshotFixture {
  boards: BoardFixture[];
  worktrees: WorktreeFixture[];
  sessions: SessionFixture[];
  permissionRequests: PermissionFixture[];
}

export const snapshotFixture: SnapshotFixture = {
  boards: [
    { boardId: 'board-agor', name: 'Agor' },
    { boardId: 'board-jules', name: 'Jules' },
  ],
  worktrees: [
    {
      worktreeId: 'wt-jetbrains',
      repoId: 'repo-agor',
      boardId: 'board-agor',
      name: 'jetbrains-addon',
      ref: 'codex/jetbrains-agor-hermes-addon',
      path: '/home/daniel/Repositories/agor',
    },
    {
      worktreeId: 'wt-jules',
      repoId: 'repo-agor',
      boardId: 'board-jules',
      name: 'jules-orchestrator',
      ref: 'main',
      path: '/home/daniel/Repositories/agor/.worktrees/jules',
    },
  ],
  sessions: [
    {
      sessionId: 'sess-addon',
      worktreeId: 'wt-jetbrains',
      title: 'JetBrains addon polish',
      agenticTool: 'codex',
      status: 'RUNNING',
    },
    {
      sessionId: 'sess-transcript',
      worktreeId: 'wt-jules',
      title: 'Transcript loading regression',
      agenticTool: 'claude-code',
      status: 'IDLE',
    },
  ],
  permissionRequests: [
    {
      requestId: 'perm-1',
      sessionId: 'sess-addon',
      taskId: 'task-2',
      toolName: 'Bash',
      toolInputJson: '{"command":"./gradlew test"}',
    },
  ],
};

export const messageFixtures: Record<string, MessageFixture[]> = {
  'sess-addon': [
    {
      messageId: 'msg-1',
      sessionId: 'sess-addon',
      taskId: 'task-1',
      role: 'USER',
      index: 1,
      timestamp: '2026-05-18T00:00:00Z',
      text: 'Open this session and show the complete previous conversation.',
      status: 'complete',
    },
    {
      messageId: 'msg-2',
      sessionId: 'sess-addon',
      taskId: 'task-1',
      role: 'ASSISTANT',
      index: 2,
      timestamp: '2026-05-18T00:00:03Z',
      text: 'Loaded the prior messages from Agor and rendered them as selectable chat text.',
      status: 'complete',
    },
    {
      messageId: 'msg-3',
      sessionId: 'sess-addon',
      taskId: 'task-1',
      role: 'ASSISTANT',
      index: 3,
      timestamp: '2026-05-18T00:00:07Z',
      text: 'Tool use: Read\n{"file_path":"apps/agor-jetbrains/src/main/kotlin/live/agor/jetbrains/toolwindow/AgorToolWindowFactory.kt"}',
      status: 'complete',
    },
  ],
  'sess-transcript': [
    {
      messageId: 'msg-4',
      sessionId: 'sess-transcript',
      role: 'USER',
      index: 1,
      timestamp: '2026-05-17T22:15:00Z',
      text: 'The plugin only shows Session context.',
      status: 'complete',
    },
    {
      messageId: 'msg-5',
      sessionId: 'sess-transcript',
      role: 'ASSISTANT',
      index: 2,
      timestamp: '2026-05-17T22:15:02Z',
      text: 'That means the inspector is rendering metadata but never requesting /messages for the selected session.',
      status: 'complete',
    },
  ],
};
