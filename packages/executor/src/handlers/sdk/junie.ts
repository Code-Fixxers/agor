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
  signal: AbortSignal
): Promise<{
  exitCode: number | null;
  stdout: string;
  stderr: string;
}> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: process.env,
      signal,
    });
    let stdout = '';
    let stderr = '';

    child.stdout?.on('data', (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr?.on('data', (chunk) => {
      stderr += chunk.toString();
    });
    child.on('error', reject);
    child.on('close', (exitCode) => resolve({ exitCode, stdout, stderr }));
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

    const processResult = await runJunieProcess(
      executable,
      args,
      worktree.path,
      params.abortController.signal
    );
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
      const message = processResult.stderr.trim() || processResult.stdout.trim() || 'Junie failed';
      throw new Error(message);
    }

    const assistantText = extractJunieAssistantText(rawSdkResponse, processResult.stdout);
    await client.service('messages').create({
      message_id: generateId() as MessageID,
      session_id: sessionId,
      task_id: taskId,
      type: 'assistant',
      role: MessageRole.ASSISTANT,
      index: nextIndex,
      timestamp: new Date().toISOString(),
      content_preview: assistantText.substring(0, 200),
      content: assistantText,
    });

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
