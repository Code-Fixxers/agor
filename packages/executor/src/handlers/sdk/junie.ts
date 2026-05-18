import { spawn } from 'node:child_process';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import { generateId } from '@agor/core';
import { getGitState } from '@agor/core/git';
import { renderAgorSystemPrompt } from '@agor/core/templates/session-context';
import type { MessageID, MessageSource, PermissionMode, SessionID, TaskID } from '@agor/core/types';
import { MessageRole } from '@agor/core/types';
import {
  buildJunieArgs,
  buildJunieModelProfile,
  extractJunieAssistantText,
  getJunieSessionId,
  JUNIE_PROFILE_ID,
  type JunieApiType,
  type JunieRawResponse,
} from '../../sdk-handlers/junie/index.js';
import { normalizeRawSdkResponse } from '../../sdk-handlers/normalizer-factory.js';
import type { AgorClient } from '../../services/feathers-client.js';

interface JunieSettings {
  executable?: string;
  openaiCompatibleBaseUrl?: string;
  defaultModel?: string;
  fasterModel?: string;
  apiType?: JunieApiType;
}

interface JunieOutputLine {
  stream: 'stdout' | 'stderr' | 'log';
  line: string;
}

const MAX_JUNIE_PROGRESS_LINES = 8;
const JUNIE_LOG_POLL_MS = 1000;
const JUNIE_ABORT_KILL_GRACE_MS = 5000;
const JUNIE_FATAL_LOG_PATTERNS = [
  /LLMConnectionFailed/i,
  /Network error while calling LLM/i,
  /Request timeout has expired/i,
  /Failed to build 'issue\.md\.junie_standalone'/i,
  /ERROR\s+JunieRunner/i,
];

function redactJunieOutputLine(line: string): string {
  return line
    .replace(/sk-[A-Za-z0-9_-]+/g, 'sk-...')
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer ...');
}

function formatElapsed(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  if (seconds < 60) {
    return `${seconds}s`;
  }
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function formatJunieRunningMessage(params: {
  model: string;
  startedAt: number;
  lines: JunieOutputLine[];
}): string {
  const messageLines = [
    'Junie is running...',
    '',
    `Model: ${params.model}`,
    `Elapsed: ${formatElapsed(Date.now() - params.startedAt)}`,
  ];

  const recentLines = params.lines.slice(-MAX_JUNIE_PROGRESS_LINES);
  if (recentLines.length === 0) {
    return [...messageLines, '', 'Waiting for Junie output...'].join('\n');
  }

  return [
    ...messageLines,
    '',
    'Recent Junie output:',
    ...recentLines.map((entry) => {
      const prefix =
        entry.stream === 'stderr' ? '[stderr] ' : entry.stream === 'log' ? '[junie log] ' : '';
      return `${prefix}${entry.line}`;
    }),
  ].join('\n');
}

function getJunieLogPath(): string {
  const homeDir = process.env.HOME || os.homedir();
  return path.join(homeDir, '.junie', 'logs', 'junie.log');
}

function extractFatalJunieDiagnostic(text: string): string | undefined {
  const lines = text
    .split(/\r?\n/)
    .map((line) => redactJunieOutputLine(line.trim()))
    .filter(Boolean);
  if (lines.length === 0) return undefined;

  const fatalIndex = lines.findIndex((line) =>
    JUNIE_FATAL_LOG_PATTERNS.some((pattern) => pattern.test(line))
  );
  if (fatalIndex === -1) return undefined;

  const start = Math.max(0, fatalIndex - 2);
  const end = Math.min(lines.length, fatalIndex + 4);
  return lines.slice(start, end).join('\n');
}

async function createJunieLogWatcher(
  logPath: string,
  onOutputLine: (line: JunieOutputLine) => void
): Promise<{ stop: () => Promise<void>; readOutput: () => string }> {
  let offset = 0;
  let lineBuffer = '';
  const outputLines: string[] = [];
  let polling = false;

  try {
    offset = (await fs.stat(logPath)).size;
  } catch {
    offset = 0;
  }

  const emitLines = (text: string, flush = false) => {
    lineBuffer += text;
    const lines = lineBuffer.split(/\r?\n/);
    const completeLines = flush ? lines : lines.slice(0, -1);
    lineBuffer = flush ? '' : lines.at(-1) || '';

    for (const line of completeLines) {
      const redacted = redactJunieOutputLine(line.trim());
      if (redacted) {
        outputLines.push(redacted);
        onOutputLine({ stream: 'log', line: redacted });
      }
    }
  };

  const poll = async () => {
    if (polling) return;
    polling = true;
    try {
      const stat = await fs.stat(logPath);
      if (stat.size < offset) {
        offset = 0;
      }
      if (stat.size <= offset) return;

      const length = stat.size - offset;
      let handle: fs.FileHandle | undefined;
      try {
        handle = await fs.open(logPath, 'r');
        const buffer = Buffer.alloc(length);
        await handle.read(buffer, 0, length, offset);
        offset = stat.size;
        emitLines(buffer.toString('utf8'));
      } finally {
        await handle?.close().catch(() => undefined);
      }
    } catch {
      // Junie creates this log lazily; absence while starting is normal.
    } finally {
      polling = false;
    }
  };

  const interval = setInterval(() => {
    void poll();
  }, JUNIE_LOG_POLL_MS);
  interval.unref?.();

  return {
    stop: async () => {
      clearInterval(interval);
      await poll();
      emitLines('', true);
    },
    readOutput: () => outputLines.join('\n'),
  };
}

function buildJunieFailureMessage(params: {
  stdout: string;
  stderr: string;
  logTail: string;
  fallback: string;
}): string {
  const logDiagnostic = extractFatalJunieDiagnostic(params.logTail);
  if (logDiagnostic) return logDiagnostic;

  const stderr = params.stderr.trim();
  if (stderr) return stderr;

  const stdout = params.stdout.trim();
  if (stdout) return stdout;

  return params.fallback;
}

async function resolveJunieApiKey(client: AgorClient, taskId: TaskID): Promise<string> {
  const result = (await client.service('config/resolve-api-key').create({
    taskId,
    keyName: 'JUNIE_OPENAI_COMPATIBLE_API_KEY',
    tool: 'junie',
  })) as { apiKey?: string | null; decryptionFailed?: boolean };

  if (result.decryptionFailed) {
    throw new Error(
      'Junie OpenAI-compatible API key could not be decrypted. Re-enter JUNIE_OPENAI_COMPATIBLE_API_KEY in Settings.'
    );
  }

  if (!result.apiKey) {
    throw new Error(
      'Junie requires JUNIE_OPENAI_COMPATIBLE_API_KEY. Add it in Settings > Agentic Tools or your user profile API keys.'
    );
  }

  return result.apiKey;
}

async function getMessageState(
  client: AgorClient,
  sessionId: SessionID,
  taskId: TaskID
): Promise<{ nextIndex: number; hasUserMessageForTask: boolean }> {
  const existingMessages = await client.service('messages').find({
    query: {
      session_id: sessionId,
      $sort: { index: 1 },
    },
  });
  const messages = Array.isArray(existingMessages) ? existingMessages : existingMessages.data;
  return {
    nextIndex: messages?.length || 0,
    hasUserMessageForTask:
      messages?.some((message) => {
        const candidate = message as {
          task_id?: string | null;
          type?: string | null;
          role?: string | null;
        };
        return (
          candidate.task_id === taskId &&
          (candidate.type === 'user' || candidate.type === 'system') &&
          candidate.role === 'user'
        );
      }) ?? false,
  };
}

async function writeJsonFile(filePath: string, value: unknown, mode = 0o600): Promise<void> {
  await fs.writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode });
  await fs.chmod(filePath, mode).catch(() => undefined);
}

async function prepareJunieFiles(params: {
  sessionId: SessionID;
  taskId: TaskID;
  apiKey: string;
  settings: JunieSettings;
  model: string;
  fasterModel?: string;
}): Promise<{
  rootDir: string;
  modelDir: string;
  mcpDir: string;
  configPath: string;
  cacheDir: string;
  outputPath: string;
}> {
  const rootDir = path.join(os.tmpdir(), `agor-junie-${params.sessionId}-${params.taskId}`);
  const modelDir = path.join(rootDir, 'models');
  const mcpDir = path.join(rootDir, 'mcp');
  const outputDir = path.join(rootDir, 'output');
  const cacheDir = path.join(rootDir, 'cache');
  await fs.mkdir(modelDir, { recursive: true, mode: 0o700 });
  await fs.mkdir(mcpDir, { recursive: true, mode: 0o700 });
  await fs.mkdir(outputDir, { recursive: true, mode: 0o700 });
  await fs.mkdir(cacheDir, { recursive: true, mode: 0o700 });

  await writeJsonFile(
    path.join(modelDir, `${JUNIE_PROFILE_ID}.json`),
    buildJunieModelProfile({
      apiKey: params.apiKey,
      baseUrl: params.settings.openaiCompatibleBaseUrl || '',
      model: params.model,
      fasterModel: params.fasterModel,
      apiType: params.settings.apiType,
    })
  );
  await writeJsonFile(path.join(mcpDir, 'mcp.json'), { mcpServers: {} });
  const configPath = path.join(rootDir, 'config.json');
  await writeJsonFile(configPath, {});

  return {
    rootDir,
    modelDir,
    mcpDir,
    configPath,
    cacheDir,
    outputPath: path.join(outputDir, `task-${params.taskId}.json`),
  };
}

function runJunieProcess(
  command: string,
  args: string[],
  cwd: string,
  signal: AbortSignal,
  onOutputLine?: (line: JunieOutputLine) => void
): Promise<{
  exitCode: number | null;
  signal: NodeJS.Signals | null;
  stdout: string;
  stderr: string;
  aborted: boolean;
}> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: process.env,
    });
    let stdout = '';
    let stderr = '';
    let stdoutLineBuffer = '';
    let stderrLineBuffer = '';
    let closed = false;
    let abortKillTimer: NodeJS.Timeout | undefined;

    function abortChild() {
      if (closed || child.killed) return;
      child.kill('SIGTERM');
      abortKillTimer = setTimeout(() => {
        if (!closed && !child.killed) {
          child.kill('SIGKILL');
        }
      }, JUNIE_ABORT_KILL_GRACE_MS);
      abortKillTimer.unref?.();
    }

    const cleanup = () => {
      signal.removeEventListener('abort', abortChild);
      if (abortKillTimer) {
        clearTimeout(abortKillTimer);
      }
    };

    const emitBufferedLines = (stream: 'stdout' | 'stderr', text: string, flush = false) => {
      let buffer = stream === 'stdout' ? stdoutLineBuffer : stderrLineBuffer;
      buffer += text;
      const lines = buffer.split(/\r?\n/);
      const completeLines = flush ? lines : lines.slice(0, -1);
      const nextBuffer = flush ? '' : lines.at(-1) || '';

      for (const line of completeLines) {
        const redacted = redactJunieOutputLine(line.trim());
        if (redacted) {
          onOutputLine?.({ stream, line: redacted });
        }
      }

      if (stream === 'stdout') {
        stdoutLineBuffer = nextBuffer;
      } else {
        stderrLineBuffer = nextBuffer;
      }
    };

    if (signal.aborted) {
      abortChild();
    } else {
      signal.addEventListener('abort', abortChild, { once: true });
    }

    child.stdout?.on('data', (chunk) => {
      const text = chunk.toString();
      stdout += text;
      emitBufferedLines('stdout', text);
    });
    child.stderr?.on('data', (chunk) => {
      const text = chunk.toString();
      stderr += text;
      emitBufferedLines('stderr', text);
    });
    child.on('error', (error) => {
      cleanup();
      if (signal.aborted) {
        resolve({ exitCode: null, signal: null, stdout, stderr, aborted: true });
        return;
      }
      reject(error);
    });
    child.on('close', (exitCode, signalName) => {
      closed = true;
      cleanup();
      emitBufferedLines('stdout', '', true);
      emitBufferedLines('stderr', '', true);
      resolve({ exitCode, signal: signalName, stdout, stderr, aborted: signal.aborted });
    });
  });
}

async function readJunieOutput(outputPath: string): Promise<unknown | undefined> {
  try {
    return JSON.parse(await fs.readFile(outputPath, 'utf8')) as unknown;
  } catch {
    return undefined;
  }
}

async function buildJunieTaskPrompt(
  client: AgorClient,
  sessionId: SessionID,
  prompt: string
): Promise<string> {
  const agorContext = await renderAgorSystemPrompt(sessionId, {
    sessions: {
      findById: async (id) => client.service('sessions').get(id),
    },
    worktrees: {
      findById: async (id) => client.service('worktrees').get(id),
    },
    repos: {
      findById: async (id) => client.service('repos').get(id),
    },
    users: {
      findById: async (id) => client.service('users').get(id),
    },
  });

  return `${agorContext.trim()}\n\n---\n\n## User Task\n\n${prompt}`;
}

export async function executeJunieTask(params: {
  client: AgorClient;
  sessionId: SessionID;
  taskId: TaskID;
  prompt: string;
  permissionMode?: PermissionMode;
  abortController: AbortController;
  messageSource?: MessageSource;
}): Promise<void> {
  const { client, sessionId, taskId, prompt } = params;
  const session = await client.service('sessions').get(sessionId);
  const worktree = await client.service('worktrees').get(session.worktree_id);
  const settings = ((await client.service('config').get('junie')) || {}) as JunieSettings;
  const apiKey = await resolveJunieApiKey(client, taskId);
  const openaiCompatibleBaseUrl = settings.openaiCompatibleBaseUrl?.trim();
  const sessionModelConfig = session.model_config as
    | { model?: string; fasterModel?: string }
    | undefined;

  if (!openaiCompatibleBaseUrl) {
    throw new Error(
      'Junie requires junie.openaiCompatibleBaseUrl. Add your OpenAI-compatible gateway URL in Settings.'
    );
  }

  const sessionModel =
    sessionModelConfig?.model && sessionModelConfig.model !== 'default'
      ? sessionModelConfig.model
      : undefined;
  const model = sessionModel || settings.defaultModel;
  if (!model) {
    throw new Error(
      'Junie requires a model. Configure junie.defaultModel or select a session model.'
    );
  }

  const junieSessionId = session.sdk_session_id
    ? getJunieSessionId(sessionId, session.sdk_session_id)
    : undefined;

  const files = await prepareJunieFiles({
    sessionId,
    taskId,
    apiKey,
    settings: { ...settings, openaiCompatibleBaseUrl },
    model,
    fasterModel: sessionModelConfig?.fasterModel || settings.fasterModel,
  });

  try {
    const messageState = await getMessageState(client, sessionId, taskId);
    let nextIndex = messageState.nextIndex;
    if (!messageState.hasUserMessageForTask) {
      await client.service('messages').create({
        message_id: generateId() as MessageID,
        session_id: sessionId,
        task_id: taskId,
        type: 'user',
        role: MessageRole.USER,
        index: nextIndex,
        timestamp: new Date().toISOString(),
        content_preview: prompt.substring(0, 200),
        content: prompt,
        metadata: params.messageSource ? { source: params.messageSource } : undefined,
      });
      nextIndex += 1;
    }

    const executable = settings.executable?.trim() || 'junie';
    const juniePrompt = await buildJunieTaskPrompt(client, sessionId, prompt);
    const args = buildJunieArgs({
      projectPath: worktree.path,
      sessionId: junieSessionId,
      profileId: JUNIE_PROFILE_ID,
      modelDir: files.modelDir,
      mcpDir: files.mcpDir,
      configPath: files.configPath,
      cacheDir: files.cacheDir,
      outputPath: files.outputPath,
      prompt: juniePrompt,
    });

    const assistantMessageId = generateId() as MessageID;
    const startedAt = Date.now();
    const outputLines: JunieOutputLine[] = [];
    const messagesService = client.service('messages');
    let progressPatchChain = Promise.resolve();

    const patchAssistantMessage = async (content: string): Promise<void> => {
      await messagesService.patch(assistantMessageId, {
        content_preview: content.substring(0, 200),
        content,
      });
    };

    const queueProgressPatch = () => {
      const content = formatJunieRunningMessage({
        model,
        startedAt,
        lines: outputLines,
      });
      progressPatchChain = progressPatchChain
        .then(() => patchAssistantMessage(content))
        .catch((error) => {
          console.warn('[junie] Failed to update running message:', error);
        });
      return progressPatchChain;
    };

    await messagesService.create({
      message_id: assistantMessageId,
      session_id: sessionId,
      task_id: taskId,
      type: 'assistant',
      role: MessageRole.ASSISTANT,
      index: nextIndex,
      timestamp: new Date().toISOString(),
      content_preview: 'Junie is running...',
      content: formatJunieRunningMessage({ model, startedAt, lines: outputLines }),
    });

    const heartbeat = setInterval(() => {
      void queueProgressPatch();
    }, 30_000);
    heartbeat.unref?.();

    let processResult: Awaited<ReturnType<typeof runJunieProcess>>;
    let fatalJunieDiagnostic: string | undefined;
    const processAbortController = new AbortController();
    const abortFromParent = () => {
      processAbortController.abort(params.abortController.signal.reason);
    };
    if (params.abortController.signal.aborted) {
      abortFromParent();
    } else {
      params.abortController.signal.addEventListener('abort', abortFromParent, { once: true });
    }
    const handleJunieOutputLine = (line: JunieOutputLine) => {
      outputLines.push(line);
      const diagnostic = extractFatalJunieDiagnostic(line.line);
      if (diagnostic && !fatalJunieDiagnostic) {
        fatalJunieDiagnostic = diagnostic;
        processAbortController.abort(new Error(diagnostic));
      }
      void queueProgressPatch();
    };
    const logWatcher = await createJunieLogWatcher(getJunieLogPath(), handleJunieOutputLine);
    try {
      processResult = await runJunieProcess(
        executable,
        args,
        worktree.path,
        processAbortController.signal,
        handleJunieOutputLine
      );
    } finally {
      clearInterval(heartbeat);
      params.abortController.signal.removeEventListener('abort', abortFromParent);
      await logWatcher.stop();
      await progressPatchChain;
    }

    const junieLogOutput = logWatcher.readOutput();
    const abortedByJunieDiagnostic =
      processResult.aborted && !params.abortController.signal.aborted && fatalJunieDiagnostic;
    if (abortedByJunieDiagnostic) {
      const message = buildJunieFailureMessage({
        stdout: processResult.stdout,
        stderr: processResult.stderr,
        logTail: junieLogOutput,
        fallback: fatalJunieDiagnostic ?? 'Junie failed',
      });
      await patchAssistantMessage(`Junie failed.\n\n${message}`);
      throw new Error(message);
    }

    const parsedOutput = await readJunieOutput(files.outputPath);
    const rawSdkResponse: JunieRawResponse = {
      ...((parsedOutput && typeof parsedOutput === 'object' ? parsedOutput : {}) as Record<
        string,
        unknown
      >),
      stdout: processResult.stdout,
      stderr: processResult.stderr,
      exitCode: processResult.exitCode,
      model: `custom:${JUNIE_PROFILE_ID}`,
    };
    const returnedSessionId =
      typeof rawSdkResponse.sessionId === 'string' ? rawSdkResponse.sessionId.trim() : '';
    if (!session.sdk_session_id && returnedSessionId) {
      await client.service('sessions').patch(sessionId, { sdk_session_id: returnedSessionId });
    }

    if (processResult.exitCode !== 0) {
      const message = buildJunieFailureMessage({
        stdout: processResult.stdout,
        stderr: processResult.stderr,
        logTail: junieLogOutput,
        fallback:
          processResult.aborted && params.abortController.signal.aborted
            ? 'Junie was stopped.'
            : 'Junie failed',
      });
      await patchAssistantMessage(`Junie failed.\n\n${message}`);
      throw new Error(message);
    }

    const assistantText = extractJunieAssistantText(rawSdkResponse, processResult.stdout);
    await patchAssistantMessage(assistantText);

    const shaAtEnd = await getGitState(worktree.path).catch(() => undefined);
    const normalized = normalizeRawSdkResponse('junie', rawSdkResponse);
    const currentTask = await client.service('tasks').get(taskId);
    const gitState = shaAtEnd
      ? currentTask.git_state
        ? { ...currentTask.git_state, sha_at_end: shaAtEnd }
        : undefined
      : undefined;

    await client.service('tasks').patch(taskId, {
      status: 'completed',
      completed_at: new Date().toISOString(),
      raw_sdk_response: rawSdkResponse,
      normalized_sdk_response: normalized,
      model: normalized?.primaryModel || `custom:${JUNIE_PROFILE_ID}`,
      ...(gitState
        ? {
            git_state: gitState,
          }
        : {}),
    });
  } finally {
    await fs.rm(files.rootDir, { recursive: true, force: true }).catch(() => undefined);
  }
}
