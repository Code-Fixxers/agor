# Hermes Rust-Native UI

**Status:** Exploration
**Related:** [conversation-ui.md](../concepts/conversation-ui.md), [agentic-coding-tool-integrations.md](../concepts/agentic-coding-tool-integrations.md), [agor-mcp-server.md](../concepts/agor-mcp-server.md)

---

## Goal

Build a fast Rust-native Hermes experience inside `apps/agor-rust-android` instead of relying on the upstream Hermes WebUI as an embedded PWA. The first version should copy the parts that matter for mobile agent work: session browsing, chat, streaming, approvals, model/workspace selection, and enough configuration to point at a Hermes backend.

The upstream UI remains the reference, not the implementation target. We should consume stable backend contracts and reimplement the interaction model in Dioxus/Rust with the Agor/Hermes design language.

Reference checked locally:

- Upstream repo: `nesquena/hermes-webui`
- Local checkout: `/tmp/hermes-webui`
- Upstream commit: `0310fcc` (`v0.51.93`)
- Local comparison server: `http://127.0.0.1:8788`

---

## What Upstream Hermes WebUI Does

Hermes WebUI is a self-hosted browser UI for Hermes Agent. It is not only chat; it wraps session management, streaming, approvals, file browsing/editing, scheduled tasks, skills, memory, profiles, providers, onboarding, logs, kanban, updates, and mobile/PWA behavior.

Architecture:

- Python `ThreadingHTTPServer` backend.
- Vanilla JavaScript frontend.
- State under `~/.hermes/webui` by default.
- Static assets in `static/`.
- Backend route registration in `api/routes.py`.
- Session and API models in `api/models.py`.

Important upstream files:

- `/tmp/hermes-webui/README.md`
- `/tmp/hermes-webui/ARCHITECTURE.md`
- `/tmp/hermes-webui/static/index.html`
- `/tmp/hermes-webui/static/messages.js`
- `/tmp/hermes-webui/static/panels.js`
- `/tmp/hermes-webui/static/ui.js`
- `/tmp/hermes-webui/api/routes.py`
- `/tmp/hermes-webui/api/streaming.py`
- `/tmp/hermes-webui/api/onboarding.py`
- `/tmp/hermes-webui/api/config.py`

---

## Screens And Navigation

The upstream surface has these major screens:

- **Chat:** session sidebar, search, projects/tags, main transcript, composer, model/profile/workspace/toolset/reasoning controls.
- **Workspace files:** right panel tree, breadcrumbs, preview, edit/save/create/delete/rename, git badge.
- **Tasks:** cron job list, create/edit/run/pause/resume/delete, history/output.
- **Kanban:** board/task CRUD, filters, bulk status, dispatcher preview/run, comments/logs.
- **Skills:** list/search, preview, create/edit/delete, linked file viewer.
- **Memory:** view/edit memory sections.
- **Spaces:** workspace list, add/rename/remove/switch.
- **Profiles:** create/switch/delete agent profiles, clone config, optional endpoint/API key fields.
- **Todos:** current session task list.
- **Insights:** usage/activity period view.
- **Logs:** agent/errors/gateway logs with tail/severity/wrap/copy.
- **Settings:** conversation, appearance, preferences, providers, plugins, system.
- **Login/onboarding:** password login and first-run provider/workspace/password wizard.
- **Mobile/PWA:** hamburger sidebar, slide-over files panel, 44px touch targets, manifest, service worker.

We should not copy this navigation wholesale into Android. The first Rust-native UI should expose a smaller, agent-first hierarchy:

1. Hermes chat.
2. Session list.
3. Workspace picker.
4. Model/profile picker.
5. Settings.
6. Approvals/clarifications as inline runtime cards.

---

## Core User Flows

### First Run

Upstream bootstraps Hermes Agent, checks provider config, chooses workspace/model, optionally enables password protection, and writes Hermes/WebUI settings.

Rust-native MVP should only do:

- Configure Hermes WebUI URL.
- Configure auth if required.
- Test `/health`.
- Fetch settings/models/workspaces if available.
- Persist server config in the existing Agor Rust settings store.

### Chat

Upstream flow:

1. Create or select a session.
2. Upload pending files.
3. `POST /api/chat/start`.
4. Attach `EventSource` to `/api/chat/stream`.
5. Render tokens, tool calls, reasoning, usage, errors, and final canonical session payload.

Rust-native MVP should implement this flow directly using Rust HTTP/SSE code. The current direct OpenAI-compatible Hermes client can stay as a fallback, but the main Hermes mode should target the WebUI API because it exposes sessions and runtime control.

### Mid-Run Control

Upstream supports:

- Cancel stream.
- Approve dangerous actions.
- Answer clarification prompts.
- Queue, interrupt, or steer follow-up input while busy.

For mobile, approvals and clarifications are required early. Without them, the app can start a run that later waits forever for user input.

### Session Management

Upstream supports new, rename, pin, archive, duplicate, delete, fork, clear, truncate, retry, undo, export/import, and CLI-session import.

Rust-native MVP should support:

- List sessions.
- Load session.
- Create session.
- Rename session.
- Delete/archive session.
- Refresh after run completion.

Fork/export/import/retry/undo can wait.

### Workspace

Upstream supports workspace switching and full file browser mutation.

Rust-native MVP should support:

- Read workspace list.
- Choose active workspace for new chat.
- Show current workspace in composer chrome.

Read-only file browsing is the next step. File mutation should wait until we have a clear mobile workflow.

---

## Configuration

Relevant upstream environment variables:

- `HERMES_WEBUI_AGENT_DIR`
- `HERMES_WEBUI_PYTHON`
- `HERMES_WEBUI_HOST`
- `HERMES_WEBUI_PORT`
- `HERMES_WEBUI_STATE_DIR`
- `HERMES_WEBUI_DEFAULT_WORKSPACE`
- `HERMES_WEBUI_DEFAULT_MODEL`
- `HERMES_WEBUI_PASSWORD`
- `HERMES_HOME`
- `HERMES_CONFIG_PATH`
- `HERMES_WEBUI_BOT_NAME`
- `HERMES_WEBUI_EXTENSION_DIR`
- `HERMES_WEBUI_EXTENSION_SCRIPT_URLS`
- `HERMES_WEBUI_EXTENSION_STYLESHEET_URLS`

Rust settings should model only the client-facing subset:

```rust
struct HermesWebUiSettings {
    web_ui_url: String,
    auth_mode: HermesAuthMode,
    default_workspace: Option<String>,
    default_model: Option<String>,
    default_profile: Option<String>,
    allow_direct_openai_fallback: bool,
}

enum HermesAuthMode {
    None,
    Password { username: Option<String> },
    Token,
}
```

Secrets should use the same protected storage direction as Agor saved login credentials.

---

## Backend API Contract To Copy First

Essential endpoints:

- `GET /health`
- `GET /api/auth/status`
- `POST /api/auth/login`
- `GET /api/settings`
- `POST /api/settings`
- `GET /api/sessions`
- `GET /api/session?session_id=...`
- `POST /api/session/new`
- `POST /api/session/rename`
- `POST /api/session/delete`
- `POST /api/chat/start`
- `GET /api/chat/stream?stream_id=...`
- `GET /api/chat/stream/status`
- `GET /api/chat/cancel`
- `GET /api/models`
- `GET /api/models/live`
- `GET /api/providers`
- `GET /api/workspaces`

Important SSE event names:

- `token`
- `interim_assistant`
- `reasoning`
- `tool`
- `tool_complete`
- `approval`
- `clarify`
- `title`
- `goal`
- `done`
- `stream_end`
- `compressing`
- `compressed`
- `metering`
- `apperror`
- `warning`
- `error`
- `cancel`

Auxiliary APIs to defer:

- Files: `/api/list`, `/api/file`, `/api/file/raw`, `/api/file/save`, `/api/file/create`, `/api/file/rename`, `/api/file/delete`, `/api/upload`
- Approvals/clarify streams: `/api/approval/*`, `/api/clarify/*`
- Profiles: `/api/profiles`, `/api/profile/*`
- Cron: `/api/crons*`
- Skills/memory: `/api/skills*`, `/api/memory`
- Kanban: `/api/kanban/*`
- Terminal: `/api/terminal/*`
- Health/logs/updates/rollback/MCP/dashboard/plugin/provider quota endpoints.

---

## Rust MVP Slice

### Client

Add a Hermes WebUI client mode beside the current direct OpenAI-compatible client:

- Typed request/response models for compact sessions and full session messages.
- `GET /api/sessions`.
- `GET /api/session`.
- `POST /api/session/new`.
- `POST /api/chat/start`.
- SSE parser for `token`, `reasoning`, `tool`, `done`, `apperror`, `cancel`, and `stream_end`.
- `GET /api/chat/cancel`.

### UI

Initial screens:

- Hermes session list.
- Transcript.
- Composer.
- Model/workspace chips.
- Running/cancel/error states.
- Inline reasoning/tool cards with compact collapsed defaults.

Follow immediately with:

- Approval cards.
- Clarification cards.
- Session rename/delete/archive.
- Profile/model/workspace pickers.

### Design Direction

Keep the current Agor/Hermes visual language:

- Dark, crisp shell.
- Compact left rail.
- Dense session list.
- Strong composer affordance.
- Pink accent only for active/primary states.
- Avoid the slower embedded-PWA feel and the upstream UI's broad admin-dashboard surface.

---

## Defer Deliberately

Do not copy these in the first Rust-native pass:

- Python bootstrap/install/update mechanics.
- Docker/self-update UI.
- Full Kanban.
- Full cron editor.
- Skills editor.
- Memory editor.
- Terminal.
- Extension injection.
- Full themes/skins/i18n parity.
- Full file mutation workflow.

These are useful later, but they are not needed to prove that the Rust-native Hermes chat loop is faster and clearer than the PWA.

---

## Open Questions

- Should Android talk only to Hermes WebUI, or keep direct OpenAI-compatible chat as a simple/offline fallback?
- Is upstream password-cookie auth enough for Android, or should Hermes expose token auth for mobile clients?
- Which backend becomes canonical when Agor and Hermes overlap: upstream Hermes WebUI Python, Agor daemon, or a Rust compatibility service?
- Do we need mobile file mutation, or is read-only browse plus attach enough?
- Should cron/kanban/skills/memory stay desktop-first?
- How should SSE reconnect/replay map to Android lifecycle when the app is backgrounded?
