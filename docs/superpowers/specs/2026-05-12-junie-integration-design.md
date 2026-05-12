# Junie Agent Integration Design

## Summary

Add JetBrains Junie as a first-class Agor agentic tool using Junie's headless CLI.
The initial integration is BYOK-only and targets the user's LiteLLM gateway through a
Junie custom model profile. Agor will not support JetBrains account login or Junie
API tokens in this first version.

## Goals

- Add `junie` anywhere Agor enumerates supported agentic tools.
- Run Junie against Agor worktrees from the executor process.
- Use a separate LiteLLM credential path so Junie does not reuse Codex's
  `OPENAI_API_KEY`.
- Default Junie to LiteLLM's OpenAI Responses-compatible endpoint.
- Preserve Agor session continuity by storing Junie's CLI session id in
  `session.sdk_session_id`.
- Surface Junie in the UI with model/gateway configuration and basic execution
  status.
- Keep the first implementation narrow: normal prompt execution only.

## Non-Goals

- Do not implement JetBrains account login, Junie API token auth, or browser-based
  auth flows.
- Do not implement ACP mode in the first pass.
- Do not treat Junie as an SDK integration unless JetBrains exposes a stable SDK
  later.
- Do not expose Junie's merge/rebase task modes in the first UI pass.
- Do not add session import from Junie's local storage in the first pass.
- Do not run project builds during implementation unless the user asks or watch mode
  reports a compilation problem.

## External Behavior

Users can select Junie when creating a session, choose a LiteLLM model profile, and
send prompts the same way they do for Claude Code, Codex, Gemini, OpenCode, and
Copilot. Junie runs in the selected worktree and writes changes directly to that
worktree.

Junie uses:

- `JUNIE_LITELLM_API_KEY` for the LiteLLM bearer token.
- `junie.litellmBaseUrl` for the LiteLLM gateway URL.
- `junie.defaultModel` for the primary LiteLLM model.
- `junie.fasterModel` for Junie's helper/internal model, when configured.
- `junie.apiType`, defaulting to `OpenAIResponses`, with `OpenAICompletion` as an
  escape hatch.
- `junie.executable`, defaulting to `junie`, for non-standard installations.

The generated Junie custom model profile should default to:

```json
{
  "apiType": "OpenAIResponses",
  "baseUrl": "<litellm-base-url>/v1/responses",
  "apiKey": "<resolved JUNIE_LITELLM_API_KEY>",
  "id": "<junie.defaultModel>",
  "primaryModel": {
    "id": "<junie.defaultModel>"
  },
  "fasterModel": {
    "id": "<junie.fasterModel>"
  }
}
```

When `junie.fasterModel` is absent, omit `fasterModel` and let Junie inherit the
top-level model settings.

## Architecture

### Core Type and Config Layer

Add `junie` to the canonical `AgenticToolName` union and related static
capability maps in `packages/core/src/types/agentic-tool.ts`.

Add Junie configuration to `packages/core/src/config/types.ts`:

```ts
export interface AgorJunieSettings {
  executable?: string;
  litellmBaseUrl?: string;
  defaultModel?: string;
  fasterModel?: string;
  apiType?: 'OpenAIResponses' | 'OpenAICompletion';
}
```

Add `JUNIE_LITELLM_API_KEY` to:

- `CredentialKey`
- `AgorCredentials`
- `ApiKeyName`
- key resolver tests
- config masking
- config hot-reload to `process.env`
- user API key status/update types
- user repository/service encryption paths
- executor secret environment handoff

The resolver precedence remains:

1. Per-user encrypted key.
2. Global `~/.agor/config.yaml`.
3. Process environment.
4. No native auth fallback for Junie.

For Junie specifically, an unresolved key should produce a clear user-facing failure
that asks for `JUNIE_LITELLM_API_KEY`; it should not attempt JetBrains native auth.

### Executor Integration

Add `junie` to:

- `packages/executor/src/payload-types.ts`
- `packages/executor/src/handlers/sdk/tool-registry.ts`
- executor CLI tool choices
- normalizer factory

Create `packages/executor/src/handlers/sdk/junie.ts`. Junie is process-backed
rather than SDK-backed, so implement a Junie-specific executor helper instead of
forcing it through `executeToolTask`.

Create `packages/executor/src/sdk-handlers/junie/`:

- `index.ts` exports Junie handler pieces.
- `junie-tool.ts` checks installation, builds command args, spawns Junie, streams
  stdout/stderr progress, stores messages, and updates tasks.
- `profile.ts` creates the temp custom model profile.
- `config.ts` creates the temp Junie config file.
- `mcp-config.ts` creates the temp MCP config folder and `mcp.json`.
- `normalizer.ts` converts Junie JSON/stdout/stderr into Agor's normalized raw
  response shape.
- `types.ts` defines Junie-specific output and config types.

The command should be built without putting secrets in argv. The API key belongs in
the generated profile file or an env file owned by the target Unix user, not in
command-line flags.

Expected command shape:

```bash
junie \
  --project <worktree-path> \
  --session-id <sdk-session-id> \
  --model custom:<profile-id> \
  --model-location <temp-model-dir> \
  --mcp-location <temp-mcp-dir> \
  --config-location <temp-config.json> \
  --output-format json \
  --json-output-file <temp-output.json> \
  --skip-update-check \
  --task <prompt>
```

For a new Agor session with no `sdk_session_id`, generate a deterministic Junie
session id from the Agor session id, store it on the Agor session before execution,
and pass it to Junie. This avoids needing to infer Junie's generated id from output.

Use this exact format:

```ts
const junieSessionId = `agor-${agorSessionId}`;
```

### Filesystem and Isolation

Use a per-session temp root under Agor's data home or OS temp directory:

```text
<tmp>/agor-junie-<session-id>/
  config.json
  models/
    agor-litellm.json
  mcp/
    mcp.json
  output/
    task-<task-id>.json
  cache/
```

Files containing secrets must be mode `0600`, and directories must be mode `0700`.
When Unix impersonation is active, files must be owned or readable by the executor
user.

### Message and Streaming Behavior

Junie CLI output should be treated as process progress, not token streaming, until
its JSON output format proves stable enough for finer-grained events.

Execution should:

1. Create the Agor user message.
2. Broadcast a streaming/progress assistant placeholder.
3. Append useful stdout/stderr chunks as progress events.
4. Read `--json-output-file` on process exit.
5. Create the final assistant message from Junie's JSON result, or from stdout
   fallback if JSON is missing or invalid.
6. Store raw Junie output on the task response metadata for later debugging.
7. Capture git state at task end.

Token usage is unknown in the first pass unless Junie's JSON file exposes stable
usage fields. If usage is absent, leave token fields undefined rather than storing
zero.

### MCP Handling

Junie supports MCP config discovery through MCP folders. Agor should generate a
session-scoped `.junie`-compatible `mcp.json` from the MCP servers selected for the
Agor session.

The generated file should use Junie's documented structure:

```json
{
  "mcpServers": {
    "ServerName": {
      "command": "command",
      "args": ["arg"],
      "env": {}
    }
  }
}
```

Remote MCP servers should use:

```json
{
  "mcpServers": {
    "RemoteServer": {
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer token"
      }
    }
  }
}
```

Secrets resolved from Agor templates must be injected at runtime into the temp MCP
file and never written to project-scoped `.junie/` files.

### Permission Model

Junie docs do not expose an Agor-style approval policy matrix. The first version
should expose a simple permission state:

- `default`: Junie-managed approvals and behavior.

Map other incoming Agor permission modes to `default` for Junie. The UI should avoid
offering misleading auto/allow-all controls until Junie documents equivalent
headless flags.

### Version-Control Modes

Junie documents:

- `--review`
- `--merge <branch-or-commit>`
- `--rebase <branch-or-commit>`

First pass:

- Expose normal prompt execution.
- Do not expose `review`, `merge`, or `rebase` in the UI yet.
- Keep the adapter internals structured so `--review` can be added later without
  changing the normal prompt path.
- Keep merge and rebase hidden for a later follow-up, because they require more
  UI shape and clearer failure handling.

### UI Scope

Add Junie to:

- Agent selection grid and New Session modal.
- Tool icon map with a `J` text fallback in the first pass. A checked-in Junie logo
  asset can be added later.
- Settings > Agentic Tools credential UI.
- Per-user API key UI.
- Onboarding wizard.
- Model selector.
- Permission selector.
- Session panel metadata and controls.
- Zone trigger agent selector.

The Junie model selector should collect:

- Primary model.
- Optional faster model.
- API type, default `OpenAIResponses`.

Settings > Agentic Tools should collect:

- LiteLLM base URL.
- Default primary model.
- Optional default faster model.
- API type, default `OpenAIResponses`.
- Optional Junie executable path.

The secret should remain in API key fields, not in the model selector. The model
selector should only override session-level `model_config` values and should not
edit global `junie.*` settings.

### Error Handling

Installation errors should say:

```text
Junie CLI was not found. Install it with Homebrew, npm, or JetBrains' install script,
then set junie.executable if it is not on PATH.
```

Missing LiteLLM key should say:

```text
Junie requires JUNIE_LITELLM_API_KEY. Add it in Settings > Agentic Tools or your
user profile API keys.
```

Missing LiteLLM URL should say:

```text
Junie requires junie.litellmBaseUrl. Add your LiteLLM gateway URL in Settings.
```

If Junie exits non-zero, store stdout/stderr previews on the failed task and create
a concise assistant/system failure message.

If JSON output parsing fails but the process exits successfully, store the stdout
fallback and mark the task completed with a warning in metadata.

Temp Junie files should be retained on failure for debugging and cleaned up after
successful execution unless debug logging is enabled.

### Testing

Add focused unit tests rather than broad builds:

- Core type/config tests for `JUNIE_LITELLM_API_KEY` resolution.
- User service/repository tests for encrypted Junie key status and update.
- Executor payload schema tests accepting `junie`.
- Tool registry tests registering Junie.
- Junie profile generation tests for `OpenAIResponses` and `OpenAICompletion`.
- Junie command construction tests ensuring secrets are not in argv.
- Junie normalizer tests for JSON output and stdout fallback.
- UI tests or type coverage for agent lists/model selector where existing tests
  already cover similar agents.

Manual smoke test after implementation:

```bash
junie --version
```

Then create a Junie session against a tiny worktree and prompt:

```text
Read the README and summarize the project in one paragraph. Do not edit files.
```

This should verify installation, LiteLLM profile generation, BYOK auth, task
storage, and session continuity without creating code changes.

## References

- Junie headless mode: https://junie.jetbrains.com/docs/junie-headless.html
- Junie CLI reference: https://junie.jetbrains.com/docs/parameters.html
- Junie custom LLM models: https://junie.jetbrains.com/docs/custom-llm-models.html
- Junie MCP configuration: https://junie.jetbrains.com/docs/junie-cli-mcp-configuration.html
- LiteLLM documentation: https://docs.litellm.ai/
