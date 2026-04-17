# Agor MCP Server

**Status:** ✅ Implemented (Jan 2025)
**Related:** [[mcp-integration]], [[agent-integration]], [[worktrees]]

---

## Overview

Agor exposes itself as a **Model Context Protocol server** so agents can introspect worktrees, sessions, boards, and users without hard-coded CLI calls. The daemon mounts a JSON-RPC endpoint at `POST /mcp` that authenticates with either a session-scoped MCP token or a long-lived personal API key and routes requests through Feathers services.

The built-in toolset mirrors Agor's primitives:

- `agor_sessions_list/get/get_current/create/prompt/update/spawn`
- `agor_repos_list/get/create_remote/create_local`
- `agor_worktrees_list/get/create/update`
- `agor_boards_list/get/update`
- `agor_environment_start/stop/health/logs/open_app/nuke`
- `agor_tasks_list/get`
- `agor_users_list/get/get_current/update_current/create`

## Key Behaviors

- **Dual auth modes:**
  - Session token (`sessionToken`) for per-session MCP access.
  - Personal API key (`Authorization: Bearer agor_sk_...`) for long-lived external orchestrators.
- **Session context:** API-key mode must provide a session context via `?sessionId=<uuid>` or `X-Agor-Session-Id`.
- **Tool routing:** `apps/agor-daemon/src/mcp/server.ts` defines every MCP tool, validates params, and reuses repositories/services instead of duplicating logic.
- **Streaming aware:** Tools that trigger prompts stream thinking/output via the existing Socket.io channel so UI stays in sync.
- **Self-updating:** When sessions add/remove MCP servers, `session-mcp-servers` service broadcasts updates that tools can query immediately.

## Usage

1. Start the daemon (`pnpm dev` in `apps/agor-daemon`).
2. Choose an auth mode:
   - **Session token mode:** In the UI, open Session Settings → MCP Tokens → "Generate MCP Token".
   - **API key mode:** Create a personal key (`agor_sk_...`) via Settings/API Keys.
3. Configure your agent (Claude Desktop, Cursor MCP, Hermes, etc.) to hit `http://localhost:3030/mcp`:
   - Session token mode: send token as `sessionToken` query param (or Bearer token).
   - API key mode: send `Authorization: Bearer agor_sk_...` and include session context (`?sessionId=...` or `X-Agor-Session-Id`).
4. Call tools like:
   - `agor_repos_create_remote` to clone new repositories
   - `agor_worktrees_create` to create worktrees
   - `agor_boards_update` to create zones and organize boards
   - `agor_sessions_prompt` to continue work
   - `agor_environment_start` to manage environments

## Implementation References

- MCP router: `apps/agor-daemon/src/mcp/server.ts`
- Token helpers: `apps/agor-daemon/src/mcp/tokens.ts`
- Session/server repositories: `packages/core/src/db/repositories/{mcp-servers,session-mcp-servers}.ts`
- UI token controls: `apps/agor-ui/src/components/SessionSettingsModal/MCPSection.tsx`

_Read the original deep dive in `context/archives/agor-mcp-server.md` for research notes._
