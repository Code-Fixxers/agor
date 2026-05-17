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

function makeControlledChildProcess() {
  const child = new EventEmitter() as EventEmitter & {
    stdout: EventEmitter;
    stderr: EventEmitter;
  };
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
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
    const messagesPatch = vi.fn().mockResolvedValue({});
    const tasksPatch = vi.fn().mockResolvedValue({});
    const sessionsPatch = vi.fn().mockResolvedValue({});

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
              create: vi.fn().mockResolvedValue({ apiKey: 'sk-test' }),
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
              patch: messagesPatch,
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

    expect(messagesCreate).toHaveBeenCalledTimes(1);
    expect(messagesCreate.mock.calls[0][0]).toMatchObject({
      type: 'assistant',
      role: 'assistant',
      index: 1,
    });
    expect(messagesCreate.mock.calls[0][0].content).toContain('Junie is running');
    expect(messagesPatch).toHaveBeenLastCalledWith(expect.any(String), {
      content_preview: 'JUNIE_OK',
      content: 'JUNIE_OK',
    });
    expect(sessionsPatch).toHaveBeenCalledWith('session-1', { sdk_session_id: 'junie-123' });

    const spawnArgs = spawnMock.mock.calls[0][1] as string[];
    const taskPrompt = spawnArgs[spawnArgs.indexOf('--task') + 1];
    expect(taskPrompt).toContain('Junie Headless Remote Worker Contract');
    expect(taskPrompt).toContain('## User Task');
    expect(taskPrompt).toContain('Reply exactly JUNIE_OK');
  });

  it('updates the running assistant message with Junie output before completion', async () => {
    const { executeJunieTask } = await import('./junie.js');
    const child = makeControlledChildProcess();
    let outputPath = '';
    spawnMock.mockImplementation((_command: string, args: string[]) => {
      outputPath = args[args.indexOf('--json-output-file') + 1];
      return child;
    });

    const messagesCreate = vi.fn().mockResolvedValue({});
    const messagesPatch = vi.fn().mockResolvedValue({});

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
              patch: vi.fn().mockResolvedValue({}),
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
              create: vi.fn().mockResolvedValue({ apiKey: 'sk-test' }),
            };
          case 'messages':
            return {
              find: vi.fn().mockResolvedValue({ data: [] }),
              create: messagesCreate,
              patch: messagesPatch,
            };
          case 'tasks':
            return {
              get: vi.fn().mockResolvedValue({ task_id: 'task-1' }),
              patch: vi.fn().mockResolvedValue({}),
            };
          default:
            throw new Error(`Unexpected service: ${name}`);
        }
      },
    };

    const task = executeJunieTask({
      client: client as never,
      sessionId: 'session-1' as never,
      taskId: 'task-1' as never,
      prompt: 'Do work',
      abortController: new AbortController(),
    });

    await vi.waitFor(() => {
      expect(messagesCreate).toHaveBeenCalledWith(
        expect.objectContaining({
          type: 'assistant',
          role: 'assistant',
          content: expect.stringContaining('Junie is running'),
        })
      );
    });

    child.stdout.emit('data', Buffer.from('Inspecting repository\n'));

    await vi.waitFor(() => {
      expect(messagesPatch).toHaveBeenCalledWith(
        expect.any(String),
        expect.objectContaining({
          content: expect.stringContaining('Inspecting repository'),
        })
      );
    });

    fs.writeFileSync(outputPath, JSON.stringify({ result: 'JUNIE_OK', sessionId: 'junie-123' }));
    child.emit('close', 0);
    await task;

    expect(messagesPatch).toHaveBeenLastCalledWith(expect.any(String), {
      content_preview: 'JUNIE_OK',
      content: 'JUNIE_OK',
    });
  });
});
