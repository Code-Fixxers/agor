import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { renderTemplate } from './handlebars-helpers';

const templatePath = fileURLToPath(new URL('./agor-system-prompt.md', import.meta.url));

describe('Agor system prompt template', () => {
  async function renderPrompt(context: Record<string, unknown>) {
    const template = await readFile(templatePath, 'utf-8');
    return renderTemplate(template, context);
  }

  function baseContext(agenticTool: string) {
    return {
      session: {
        session_id: 'session-1',
        agentic_tool: agenticTool,
        permission_config: {},
        created_at: '2026-05-12T00:00:00.000Z',
      },
      worktree: {
        worktree_id: 'worktree-1',
        name: 'feature-worktree',
        path: '/repo/worktree',
      },
      repo: {
        repo_id: 'repo-1',
        name: 'agor',
      },
    };
  }

  it('gives Codex a persistent remote-worker operating contract', async () => {
    const prompt = await renderPrompt(baseContext('codex'));

    expect(prompt).toContain('Persistent Remote Worker Contract');
    expect(prompt).toContain('Do not stop after a partial answer or first tool result');
    expect(prompt).toContain('verify the result with the narrowest relevant command');
    expect(prompt).toContain('Call `agor_sessions_get_current_context`');
    expect(prompt).toContain('Use `agor_sessions_update`');
    expect(prompt).toContain('Phase 2');
    expect(prompt).toContain('Hermes');
  });

  it('gives Gemini CLI a stronger remote-worker operating contract', async () => {
    const prompt = await renderPrompt(baseContext('gemini'));

    expect(prompt).toContain('Gemini CLI Remote Worker Contract');
    expect(prompt).toContain('Do not stop after a plan, diagnosis, first tool result');
    expect(prompt).toContain('Use a tight execution loop');
    expect(prompt).toContain('Call `agor_sessions_get_current_context`');
    expect(prompt).toContain('Use `agor_sessions_update`');
    expect(prompt).toContain('GEMINI.md');
  });

  it.each([
    ['opencode', 'OpenCode'],
    ['copilot', "GitHub Copilot's agentic runtime"],
    ['junie', 'Junie headless'],
  ])('gives %s a non-Claude Agor worker contract', async (agenticTool, label) => {
    const prompt = await renderPrompt(baseContext(agenticTool));

    expect(prompt).toContain(label);
    expect(prompt).toContain('implemented, verified, or blocked');
    expect(prompt).toContain('what changed, what was verified');
  });

  it('does not add the non-Claude worker contracts to Claude Code', async () => {
    const prompt = await renderPrompt(baseContext('claude-code'));

    expect(prompt).not.toContain('Persistent Remote Worker Contract');
    expect(prompt).not.toContain('Gemini CLI Remote Worker Contract');
    expect(prompt).not.toContain('Agor Remote Worker Contract');
    expect(prompt).not.toContain('Junie Headless Remote Worker Contract');
  });
});
