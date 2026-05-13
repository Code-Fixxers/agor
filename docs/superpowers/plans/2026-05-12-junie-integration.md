# Junie Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JetBrains Junie as a BYOK-only Agor agent backed by an OpenAI-compatible custom model profile.

**Architecture:** The integration extends Agor's existing agent enum/config surfaces, then adds a process-backed executor adapter for Junie's headless CLI. Junie uses a separate `JUNIE_OPENAI_COMPATIBLE_API_KEY`, session-scoped temp profile/config files, and `session.sdk_session_id` for continuity.

**Tech Stack:** TypeScript, Vitest, Feathers-backed executor repositories, React/Ant Design UI.

---

## File Structure

- Modify `packages/core/src/types/agentic-tool.ts`: add `junie` and capabilities.
- Modify `packages/core/src/config/types.ts`: add Junie settings and `JUNIE_OPENAI_COMPATIBLE_API_KEY`.
- Modify `packages/core/src/config/key-resolver.ts`: resolve Junie key through the generic resolver.
- Modify `packages/core/src/config/config-manager.ts`: expose Junie credential lookup.
- Modify `packages/core/src/types/user.ts`: add Junie API key status/update fields.
- Modify `packages/core/src/db/schema.sqlite.ts` and `packages/core/src/db/schema.postgres.ts`: extend encrypted user key JSON type.
- Modify `packages/core/src/db/repositories/users.ts` and `apps/agor-daemon/src/services/users.ts`: encrypt, decrypt, and expose Junie key status.
- Modify `apps/agor-daemon/src/services/config.ts`: mask and hot-reload Junie credentials, allow Junie settings.
- Modify `apps/agor-daemon/src/utils/spawn-executor.ts`: include Junie secret in impersonated executor env.
- Modify `packages/executor/src/payload-types.ts`, `packages/executor/src/cli.ts`, and `packages/executor/src/index.ts`: accept `junie`.
- Modify `packages/executor/src/handlers/sdk/tool-registry.ts`: register Junie.
- Create `packages/executor/src/handlers/sdk/junie.ts`: executor entrypoint.
- Create `packages/executor/src/sdk-handlers/junie/profile.ts`: generate Junie model profiles.
- Create `packages/executor/src/sdk-handlers/junie/normalizer.ts`: parse Junie JSON/stdout fallback.
- Create `packages/executor/src/sdk-handlers/junie/junie-tool.ts`: command construction and process execution helpers.
- Create `packages/executor/src/sdk-handlers/junie/index.ts` and `types.ts`: exports and local types.
- Modify `packages/executor/src/sdk-handlers/normalizer-factory.ts` and `models.ts`: include Junie.
- Modify UI agent selectors, model selector, permission selector, key fields, settings, onboarding, and tool icon fallback.

## Task 1: Core Credential and Agent Types

**Files:**
- Modify: `packages/core/src/types/agentic-tool.ts`
- Modify: `packages/core/src/config/types.ts`
- Modify: `packages/core/src/config/key-resolver.ts`
- Modify: `packages/core/src/config/config-manager.ts`
- Modify: `packages/core/src/types/user.ts`
- Modify: `packages/core/src/utils/permission-mode-mapper.ts`
- Test: `packages/core/src/utils/permission-mode-mapper.test.ts`
- Test: `packages/core/src/config/config-manager.test.ts`

- [ ] **Step 1: Write failing tests**

Add tests that expect:

```ts
expect(mapPermissionMode('allow-all', 'junie')).toBe('default');
expect(mapPermissionMode('acceptEdits', 'junie')).toBe('default');
```

Add config-manager type coverage for `getCredential('JUNIE_OPENAI_COMPATIBLE_API_KEY')`.

- [ ] **Step 2: Run red tests**

Run:

```bash
pnpm --filter @agor/core test src/utils/permission-mode-mapper.test.ts src/config/config-manager.test.ts
```

Expected: fail because `junie` and `JUNIE_OPENAI_COMPATIBLE_API_KEY` are not recognized.

- [ ] **Step 3: Implement minimal core changes**

Add `junie` to agent unions/capabilities. Add `JUNIE_OPENAI_COMPATIBLE_API_KEY` and `AgorJunieSettings` to config types and credential lookup unions. Add Junie user API key status/update fields. Map all Junie permission modes to `default`.

- [ ] **Step 4: Run green tests**

Run the same command. Expected: all selected tests pass.

## Task 2: Daemon Credential Plumbing

**Files:**
- Modify: `packages/core/src/db/schema.sqlite.ts`
- Modify: `packages/core/src/db/schema.postgres.ts`
- Modify: `packages/core/src/db/repositories/users.ts`
- Modify: `apps/agor-daemon/src/services/users.ts`
- Modify: `apps/agor-daemon/src/services/config.ts`
- Modify: `apps/agor-daemon/src/setup/credentials.ts`
- Modify: `apps/agor-daemon/src/utils/spawn-executor.ts`

- [ ] **Step 1: Write failing tests**

Extend existing user/config/spawn tests to assert Junie key status is masked, encrypted, and included in impersonated secret env.

- [ ] **Step 2: Run red tests**

Run focused daemon/core tests that cover those files.

- [ ] **Step 3: Implement credential plumbing**

Thread `JUNIE_OPENAI_COMPATIBLE_API_KEY` through user encrypted storage, config masking/hot-reload, config patching, setup logging, and executor env handoff.

- [ ] **Step 4: Run green tests**

Run the same focused tests. Expected: all selected tests pass.

## Task 3: Junie Executor Adapter

**Files:**
- Modify: `packages/executor/src/payload-types.ts`
- Modify: `packages/executor/src/cli.ts`
- Modify: `packages/executor/src/index.ts`
- Modify: `packages/executor/src/handlers/sdk/tool-registry.ts`
- Modify: `packages/executor/src/sdk-handlers/normalizer-factory.ts`
- Modify: `packages/executor/src/sdk-handlers/models.ts`
- Create: `packages/executor/src/handlers/sdk/junie.ts`
- Create: `packages/executor/src/sdk-handlers/junie/index.ts`
- Create: `packages/executor/src/sdk-handlers/junie/types.ts`
- Create: `packages/executor/src/sdk-handlers/junie/profile.ts`
- Create: `packages/executor/src/sdk-handlers/junie/normalizer.ts`
- Create: `packages/executor/src/sdk-handlers/junie/junie-tool.ts`
- Test: `packages/executor/src/payload-types.test.ts`
- Test: `packages/executor/src/sdk-handlers/junie/profile.test.ts`
- Test: `packages/executor/src/sdk-handlers/junie/normalizer.test.ts`
- Test: `packages/executor/src/sdk-handlers/junie/junie-tool.test.ts`

- [ ] **Step 1: Write failing tests**

Tests should assert:

```ts
PromptPayloadSchema.parse({ command: 'prompt', sessionToken: 'jwt', params: { sessionId, taskId, prompt: 'hi', tool: 'junie', cwd: '/tmp/repo' } });
```

Profile generation produces `OpenAIResponses` with `/v1/responses`; command args include `--model custom:agor-openai-compatible` and do not include the API key; normalizer extracts assistant text from known JSON shapes and falls back to stdout.

- [ ] **Step 2: Run red tests**

Run:

```bash
pnpm --filter @agor/executor test src/payload-types.test.ts src/sdk-handlers/junie/profile.test.ts src/sdk-handlers/junie/normalizer.test.ts src/sdk-handlers/junie/junie-tool.test.ts
```

Expected: fail because Junie files and schema support do not exist.

- [ ] **Step 3: Implement adapter**

Add schema/tool registry support. Implement pure profile, normalizer, and command construction helpers first. Implement process execution wrapper with `node:child_process` and Feathers-backed message/task updates after pure helpers pass.

- [ ] **Step 4: Run green tests**

Run the same executor tests. Expected: all selected tests pass.

## Task 4: UI Exposure

**Files:**
- Modify: `apps/agor-ui/src/components/AgentSelectionGrid/availableAgents.ts`
- Modify: `apps/agor-ui/src/components/ToolIcon/ToolIcon.tsx`
- Modify: `apps/agor-ui/src/components/ApiKeyFields.tsx`
- Modify: `apps/agor-ui/src/components/SettingsModal/AgenticToolsTab.tsx`
- Modify: `apps/agor-ui/src/components/AgenticToolConfigForm/AgenticToolConfigForm.tsx`
- Modify: `apps/agor-ui/src/components/ModelSelector/ModelSelector.tsx`
- Modify: `apps/agor-ui/src/components/PermissionModeSelector/PermissionModeSelector.tsx`
- Modify: `apps/agor-ui/src/components/OnboardingWizard/OnboardingWizard.tsx`

- [ ] **Step 1: Write failing UI/type tests where existing coverage exists**

Add or update lightweight tests for agent option lists if present. If no local tests exist, rely on TypeScript for UI coverage.

- [ ] **Step 2: Implement UI changes**

Add Junie labels, key fields, settings fields for OpenAI-compatible URL/default models/API type/executable, model selector support, permission selector default-only behavior, and `J` icon fallback.

- [ ] **Step 3: Run UI typecheck**

Run:

```bash
pnpm --filter agor-ui typecheck
```

Expected: typecheck passes.

## Task 5: Final Verification

**Files:**
- Review all changed files.

- [ ] **Step 1: Run targeted verification**

Run:

```bash
pnpm --filter @agor/core test src/utils/permission-mode-mapper.test.ts src/config/config-manager.test.ts
pnpm --filter @agor/executor test src/payload-types.test.ts src/sdk-handlers/junie/profile.test.ts src/sdk-handlers/junie/normalizer.test.ts src/sdk-handlers/junie/junie-tool.test.ts
pnpm --filter agor-ui typecheck
```

- [ ] **Step 2: Manual install probe if Junie exists**

Run:

```bash
junie --version
```

Expected: version is printed if Junie is installed; if absent, report that smoke testing was skipped.

- [ ] **Step 3: Final diff review**

Run:

```bash
git diff --stat
git diff --check
```

Expected: no whitespace errors; changes match the approved design.
