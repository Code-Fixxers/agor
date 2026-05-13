import { EventEmitter } from 'node:events';
import * as fs from 'node:fs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { spawnMock } = vi.hoisted(() => ({
  spawnMock: vi.fn(),
}));

vi.mock('node:child_process', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:child_process')>();
  return {
    ...actual,
    spawn: spawnMock,
  };
});

vi.mock('@agor/core/git', () => ({
  getGitState: vi.fn().mockResolvedValue('test-sha'),
}));

function makeChildProcess(stdoutText: string, stderrText = '', exitCode = 0) {
  const child = new EventEmitter() as EventEmitter & {
    stdout: EventEmitter;
    stderr: EventEmitter;
  };
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();

  setTimeout(() => {
    if (stdoutText) child.stdout.emit('data', Buffer.from(stdoutText));
    if (stderrText) child.stderr.emit('data', Buffer.from(stderrText));
    child.emit('close', exitCode);
  }, 0);

  return child;
}

describe('executeJunieTask', () => {
  beforeEach(() => {
    spawnMock.mockReset();
    spawnMock.mockImplementation((_command: string, args: string[]) => {
      const outputPath = args[args.indexOf('--json-output-file') + 1];
      fs.writeFileSync(outputPath, JSON.stringify({ result: 'JUNIE_OK', sessionId: 'junie-123' }));
      return makeChildProcess('');
    });
  });

  it('does not duplicate the daemon-written user message', async () => {
    const { executeJunieTask } = await import('./junie.js');
    const messagesCreate = vi.fn().mockResolvedValue({});
    const tasksPatch = vi.fn().mockResolvedValue({});
    const sessionsPatch = vi.fn().mockResolvedValue({});
    const resolveApiKeyCreate = vi.fn().mockResolvedValue({ apiKey: 'sk-test' });

    const client = {
      service: (name: string) => {
        switch (name) {
          case 'sessions':
            return {
              get: vi.fn().mockResolvedValue({
                session_id: 'session-1',
                worktree_id: 'worktree-1',
                agentic_tool: 'junie',
                model_config: {},
              }),
              patch: sessionsPatch,
            };
          case 'worktrees':
            return {
              get: vi.fn().mockResolvedValue({
                worktree_id: 'worktree-1',
                path: '/tmp',
              }),
            };
          case 'config':
            return {
              get: vi.fn().mockResolvedValue({
                openaiCompatibleBaseUrl: 'https://openai-compatible.example.com',
                defaultModel: 'qwen',
              }),
            };
          case 'config/resolve-api-key':
            return {
              create: resolveApiKeyCreate,
            };
          case 'messages':
            return {
              find: vi.fn().mockResolvedValue({
                data: [
                  {
                    type: 'user',
                    role: 'user',
                    task_id: 'task-1',
                    content: 'already written by daemon',
                  },
                ],
              }),
              create: messagesCreate,
            };
          case 'tasks':
            return {
              get: vi.fn().mockResolvedValue({ task_id: 'task-1' }),
              patch: tasksPatch,
            };
          default:
            throw new Error(`Unexpected service: ${name}`);
        }
      },
    };

    await executeJunieTask({
      client: client as never,
      sessionId: 'session-1' as never,
      taskId: 'task-1' as never,
      prompt: 'Reply exactly JUNIE_OK',
      abortController: new AbortController(),
    });

    expect(resolveApiKeyCreate).toHaveBeenCalledWith({
      taskId: 'task-1',
      keyName: 'JUNIE_OPENAI_COMPATIBLE_API_KEY',
      tool: 'junie',
    });
    expect(messagesCreate).toHaveBeenCalledTimes(1);
    expect(messagesCreate.mock.calls[0][0]).toMatchObject({
      type: 'assistant',
      role: 'assistant',
      index: 1,
      content: 'JUNIE_OK',
    });
    expect(sessionsPatch).toHaveBeenCalledWith('session-1', { sdk_session_id: 'junie-123' });
  });
});
