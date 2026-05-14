# Agor Android Native Client Working Task List

Status: working backlog for `cfx/android-hermes-client`
Last audited: 2026-05-12

This document tracks the current Android implementation against:

- `docs/agor-android-native-app-spec.md`
- `docs/agor-ios-native-app-spec.md`
- the current implementation in `apps/agor-android`

Legend:

- `[x]` implemented in the current Android branch
- `[ ] [partial]` implemented partly, but not yet spec-complete
- `[ ]` not implemented yet
- `[ ] [decision]` product or security decision still needed

## Current Snapshot

- [x] Android is a real native client: Gradle, Compose, Material 3, REST, Socket.IO, local persistence, chat, file browsing, prompt attachments, prompt dictation, Hermes, notifications, and APK updates exist.
- [x] Android is already better than the original iOS baseline in several places: API-key login, biometric API-key unlock, Hermes orchestration, Silero ONNX VAD, WhisperLiveKit streaming transcription, local Whisper fallback, in-app APK updates, and Hermes voice/TTS.
- [x] The highest-value parity work is implemented: task-centric chat loading, full regular-session voice mode, background recovery, crash logs, and broader QA coverage.
- [x] Android now ships as two side-by-side product variants: Hermes+Agor (`live.agor.app`) and Hermes-only (`live.agor.hermes`).

## Platform, Build, and Packaging

- [x] Native Android app exists at `apps/agor-android`.
- [x] Uses Kotlin, Jetpack Compose, Material 3, ViewModel/StateFlow, OkHttp, Socket.IO, DataStore, Coil, markdown rendering, security-crypto, ONNX Runtime, and Android TTS.
- [x] Declares `minSdk 28`, `targetSdk 35`, and `compileSdk 35`.
- [x] Declares the expected network, camera, microphone, foreground service, notification, overlay, vibration, and APK install permissions.
- [x] Registers notification channels for voice, sessions, and Hermes.
- [x] Includes NDK/CMake wiring for local whisper.cpp support.
- [x] Includes BuildConfig values for version, git SHA, and update metadata.
- [x] Includes an in-app APK update checker/downloader/installer.
- [x] Adds `hermesAgor` and `hermesOnly` product flavors with separate application IDs, app labels, update channels, and APK artifacts.
- [x] Keeps legacy `assembleDebug` and `testDebugUnitTest` aliases mapped to debug flavor tasks for developer compatibility.
- [x] Tighten cleartext networking policy for debug/dev versus production. Main/release denies cleartext and debug overlays the local-development policy.
- [x] Add release validation notes for signing, update compatibility, and install-permission UX.

## Authentication and Server Profiles

- [x] Email/password login against `/authentication`.
- [x] Personal API key login.
- [x] URL normalization and probing through the REST client, including `/health`.
- [x] JWT storage and authenticated request handling.
- [x] HTTP 401 refresh through `/authentication-refresh`.
- [x] Biometric unlock for saved email/password credentials.
- [x] Biometric unlock for saved API-key credentials.
- [x] Bootstrap restore from stored token or saved login credentials.
- [x] Silent re-authentication exists on bootstrap, REST calls refresh expired tokens, socket auth failures refresh/reconnect, and unrecoverable auth failures force login through the root auth state.
- [x] Server profiles persist through `ServerProfileManager`, appear in the drawer, support switching, can be added/edited/deleted/defaulted in Settings, and restore profile-scoped encrypted token/API-key/password snapshots.
- [x] Saved passwords and API keys are allowed for silent re-auth, biometric unlock, and profile switching on trusted personal devices; the policy is documented for users and remains profile-scoped/encrypted/clearable.
- [x] Add migration behavior for older local storage/profile formats if this app has pre-profile installs in the wild.
- [x] Add tests for URL normalization, HTTPS fallback, profile switching, and expired-token recovery.

## Main Navigation and Drawer

- [x] Compose navigation shell with modal drawer.
- [x] Cached sidebar startup rendering through `SidebarCache`.
- [x] Foreground navigation refresh and 45-second polling.
- [x] Boards load from `/boards`.
- [x] Worktrees load from `/worktrees`.
- [x] Sessions load broadly from `/sessions` and are grouped client-side by `worktree_id`.
- [x] Boards and worktrees are expandable in the drawer.
- [x] Favorite sessions persist locally through DataStore.
- [x] A drawer filter controls 7-day, 30-day, all, and archived session history.
- [x] Needs Attention includes awaiting-permission and awaiting-input sessions, excluding scheduled sessions.
- [x] Hermes drawer entries and Hermes chat shortcuts are present when configured.
- [x] Important includes locally favorited sessions, running sessions, ready-for-prompt sessions, and the three most recently updated titled sessions while excluding attention sessions.
- [x] Expanded board/worktree state persists across app launches.
- [x] Navigation search filters known sessions by title or session ID without requiring boards/worktrees to be expanded.
- [x] Add server chip row for switching saved profiles.
- [x] Add session/worktree context menus or bottom sheets for common actions.
- [x] Add navigation rail or two-pane adaptive layout for landscape, foldables, and tablets.
- [x] Resolve repository names from `/repos` where useful in drawer labels.

## Chat Loading and Timeline

- [x] Selecting a session opens chat and fetches session metadata.
- [x] Tasks are fetched for the session.
- [x] Latest messages are fetched from `/messages?session_id=...`.
- [x] A per-session chat cache restores session, tasks, and messages quickly.
- [x] "Load earlier" pagination exists for older session messages.
- [x] Chat rows use stable keys and a row flattener cache to reduce recomposition during streaming.
- [x] Task headers are rendered when message `task_id` changes.
- [x] User can send prompts through `POST /sessions/:id/prompt`.
- [x] Prompt queueing is allowed while a session is not stopping.
- [x] Stop session is wired.
- [x] Rename session is wired.
- [x] Archive session exists in `ChatViewModel`.
- [x] The chat model is task-centric with `messagesByTask`, `loadedTaskIds`, latest-task default expansion, collapsed task unloading, virtual tasks for taskless sessions, and "Show N older tasks".
- [x] Persist prompt drafts per session in server-scoped DataStore.
- [x] Clear `ready_for_prompt` when opening or viewing a session, using `PATCH /sessions/:id` with `ready_for_prompt=false`.
- [x] Add attention banner that scrolls to the newest pending permission or input card.
- [x] Add post-send delayed refresh of tasks/latest messages to cover missed socket events.
- [x] Add explicit near-bottom auto-scroll behavior that avoids fighting manual scrolling.
- [x] The top bar has menu, title, plan-mode badge, status, rename, stop, files, archive, MCP Servers, reset session, Session Settings, and close.
- [x] Collapsed task headers surface non-completed terminal and active statuses, including failed tasks, so collapsed failed work remains visible.
- [x] Add create-new-session-on-worktree flow.
- [x] Add reset-session flow that archives the current session and creates a new idle session on the same worktree.
- [x] Show plan-mode badge and explanatory read-only banner when `permission_config.mode == plan`.
- [x] Show model/provider/effort details when `model_config` is available.

## Message Rendering

- [x] Plain text messages render through a fast path.
- [x] Markdown messages render through the markdown renderer.
- [x] Session UUIDs in text and markdown are tappable links.
- [x] Text blocks, tool use, tool result, grouped tool traces, thinking blocks, image blocks, permission cards, and input request cards render.
- [x] Tool trace rows group adjacent tool use/result blocks and support expansion.
- [x] Base64 and URL image blocks render.
- [x] File browser recognizes common text, image, WebP, and GIF extensions.
- [x] Animated GIF/WebP display uses Coil GIF/ImageDecoder support for message images and file-browser previews, including base64 data URI loading.
- [x] Add tappable file/worktree path references in chat text and markdown.
- [x] Improve permission cards with tool icons and input previews beyond the current tool name/description.
- [x] Add richer input request rendering for headers, multiple questions, option descriptions, and markdown option content.
- [x] Add tests for message decoding across text, block, permission, input, image, and unknown content.

## Streaming and Socket Updates

- [x] Socket.IO connects with JWT in extra headers.
- [x] Socket emits Feathers `create authentication` after transport connection.
- [x] Socket uses polling plus WebSocket transport rather than WebSocket-only.
- [x] Consumes `sessions patched`, `tasks created`, `tasks patched`, `messages created`, and `messages patched`.
- [x] Consumes `messages streaming:start`, `messages streaming:chunk`, `messages streaming:end`, and `messages streaming:error`.
- [x] Consumes `messages thinking:chunk`.
- [x] Streaming state is owned by `StreamingService` and exposed as sampled StateFlow.
- [x] Persisted message creation finalizes matching live streams.
- [x] Socket connect auth failures attempt token refresh and reconnect.
- [x] Add `messages thinking:start` and `messages thinking:end` handlers.
- [x] Generic socket service calls cover `find`, `get`, `create`, `patch`, and `remove`.
- [x] Add robust acknowledgement parsing for `[null, result]`, `[errorObject]`, `["NO ACK"]`, and single-result fallback shapes.
- [x] Clear stale live streams on reconnect and when a session becomes idle.
- [x] Streaming sample interval matches the spec's 50 ms target.

## Permissions and Input Requests

- [x] Permission requests render inline in the chat.
- [x] Pending permission cards expose approve and deny buttons.
- [x] Non-pending permission cards show resolved status.
- [x] Input requests render inline in the chat.
- [x] Free-text, single-choice, and multi-choice input controls exist.
- [x] Input answers submit through `/sessions/:id/input-response`.
- [x] Permission decision payload aligns with the daemon/spec contract: request ID, optional task ID, allow flag, reason, remember flag, scope, and deciding user ID.
- [x] Input response payload aligns with the daemon/spec contract: request ID, optional task ID, answer map, and responding user ID.
- [x] Support multiple questions per input request instead of only the first question.
- [x] Support option labels/descriptions distinctly instead of treating options as plain strings, while preserving plain string option compatibility.
- [x] Add attention banner scroll-to-card behavior for new pending permissions/input.
- [x] Add tests for permission, input payload encoding, and input answer mapping.

## Session Management

- [x] Session selection is implemented.
- [x] Local favorites are implemented.
- [x] Stop active session is implemented.
- [x] Rename session is implemented.
- [x] Archive session is available in the view model.
- [x] Session status values include idle, running, stopping, awaiting permission, awaiting input, timed out, completed, and failed.
- [x] Agentic tools include Claude Code, Codex, Gemini, and OpenCode.
- [x] Permission mode model covers Claude, Codex, Gemini, and broader daemon modes.
- [x] Expose archive in the visible chat overflow/menu UI.
- [x] Implement reset session.
- [x] Implement create session from a worktree.
- [x] Implement changing per-session permission mode.
- [x] Implement mark-ready-for-prompt-viewed behavior.
- [x] Add session settings screen.
- [x] Add session MCP server management entry point.

## MCP Server Management

- [x] Android has an `MCPServer` model.
- [x] REST client can list `/mcp-servers`.
- [x] Add MCP screen for the selected session.
- [x] Load available workspace servers through `/mcp-servers`.
- [x] Load active session servers through `session-mcp-servers`.
- [x] Join active session servers against available workspace servers.
- [x] Add available server to a session.
- [x] Remove server from a session.
- [x] Enable and disable a session MCP server.
- [x] Add empty state pointing users to configure MCP servers in the web UI.
- [x] Add focused tests around MCP workspace server mapping, active relationship joining, and empty-state handling.
- [x] Add tests around MCP session mutation payloads.

## Files and Attachments

- [x] Worktree file browser exists.
- [x] File browser builds a virtual directory tree.
- [x] Text and image files can be opened.
- [x] Prompt attachments support arbitrary file picker input.
- [x] Prompt attachments support gallery images.
- [x] Prompt attachments support camera capture.
- [x] Prompt attachments support app log attachment.
- [x] Uploads use multipart `POST /sessions/:id/upload?destination=worktree`.
- [x] Uploads can notify the agent with a prompt template containing `{filepath}`.
- [x] Add tests for multipart file upload payloads and `{filepath}` notification templates.
- [x] Attach crash logs when available.
- [x] Add explicit uploaded-path chips or inserted `@path` references when the backend returns uploaded paths.
- [x] Persist URI permission only when required and document the behavior.
- [x] Add file linkification in chat so worktree paths open the file browser at the target file.

## Voice Mode

- [x] Regular Agor chat has one-shot prompt dictation through `PromptVoiceInputController`.
- [x] Prompt dictation uses microphone permission gating.
- [x] Prompt dictation uses AudioRecord capture, rolling buffer, Silero ONNX VAD, WhisperLiveKit streaming/remote transcription, and local whisper.cpp fallback.
- [x] Prompt dictation streams WhisperLiveKit `/asr` using full-state transcript replacement and WebM/Opus chunks when the server advertises MediaRecorder mode.
- [x] Prompt dictation strips common transcription artifacts.
- [x] Prompt dictation shows phase, live transcript, and audio level.
- [x] Whisper and VAD model download flows exist.
- [x] Hermes has app-scoped auto-listening through `HermesVoiceManager`.
- [x] Hermes voice supports transcript review, delayed auto-send, TTS status, streamed sentence TTS, skip TTS, foreground/background pause/resume, and local diagnostics.
- [x] `ContinuousVoiceService` exists for session-scoped foreground voice infrastructure.
- [x] Wire full regular Agor session voice mode to the chat UI and ChatViewModel through `ContinuousVoiceService`.
- [x] Add session-scoped voice ownership, background continuation, and a floating return button when viewing another session.
- [x] Replace the prompt bar with full voice controls while viewing the owning regular Agor session.
- [x] Pause regular Agor listening while the agent is running or awaiting permission/input, then resume when promptable.
- [x] Speak regular Agor status updates and assistant responses with Android TTS.
- [x] Add ready-to-listen and recording-start tones.
- [x] Add persisted voice settings: VAD sensitivity, silence-before-send, and reset-to-defaults.
- [x] Apply voice settings to active voice services.
- [x] Add tests for VAD threshold mapping, transcript cleanup, and voice state transitions.

## Notifications and Background Recovery

- [x] Android 13+ notification permission is declared.
- [x] Notification channels exist for sessions, Hermes, and voice.
- [x] Favorite session running-to-idle notifications are implemented from navigation state updates.
- [x] Session notifications deep-link back into the relevant chat session.
- [x] Hermes completion notifications are implemented.
- [x] Hermes foreground service shows ongoing work notification.
- [x] Voice foreground service notification exists.
- [x] [decision] Running-to-idle system notifications remain favorites-only to keep mobile notifications intentional; non-favorite transitions surface through in-app snackbars and recovery refreshes.
- [x] Track last notified status per session robustly so reconnects and polling do not duplicate or miss transitions.
- [x] Add cross-session snackbars/banners for awaiting permission, awaiting input, completed, and failed states.
- [x] Add foreground reconnect flow: reconnect socket, refresh navigation, refresh current chat, clear stale streams, check missed transitions, and briefly show an updated/reconnecting banner.
- [x] Add WorkManager polling for opportunistic missed transition detection.
- [x] Stop UI-only polling when backgrounded and restart it cleanly on foreground.

## Settings and Diagnostics

- [x] Settings show current user, connection state, server URL, drawer session filter, biometric controls, Hermes connection, Whisper settings, diagnostics, version/git SHA, GitHub token, update checks, and sign out.
- [x] Diagnostic logs can be exported/shared from Settings.
- [x] Diagnostic logs can be attached to a chat prompt.
- [x] App logging covers auth, network, socket, navigation, chat, voice, Hermes, transcription, and updates.
- [x] Add clear-log action.
- [x] Add "send logs to current session" action from Settings, not only through chat attachment.
- [x] Capture crash logs after crashes when possible.
- [x] Attach crash logs to prompts.
- [x] Add richer socket/auth/HTTP health details for debugging stuck mobile states.

## Hermes-Specific Work

- [x] Hermes connection setup screen exists.
- [x] Hermes client probes `/v1/models`.
- [x] Hermes supports OpenAI-compatible chat completions and streaming.
- [x] Hermes supports server-side tool/MCP behavior as an opaque assistant capability.
- [x] Hermes local session store persists turns by configured Hermes URL.
- [x] Hermes supports text, image, camera, file, and log attachments.
- [x] Hermes foreground service queues prompts per session and survives screen changes.
- [x] Hermes can import/sync stored remote conversations when supported by the server.
- [x] Hermes voice and TTS are substantially ahead of the original iOS spec.
- [x] Hermes-only app shell connects directly to Hermes without Agor authentication, Agor sockets, Agor session polling, or Agor drawer/navigation.
- [x] Add Hermes empty/error states for partially configured servers and expired tokens.
- [x] Add explicit retry/resume controls for failed Hermes turns.
- [x] Add tests for Hermes streaming parsing, queued prompts, and session persistence.

## Security and Privacy

- [x] Tokens and saved credentials use app-private storage and Android security primitives.
- [x] Biometric credential save requires user authentication.
- [x] Attachment imports copy content through app-controlled storage before upload/use.
- [x] Foreground service types are declared for microphone and Hermes background work.
- [x] Audit install-package permission, overlay permission, and remaining debug defaults before release.
- [x] Add a user-facing policy for saved password/API-key behavior.
- [x] Ensure logs redact tokens, API keys, and sensitive headers before export or attachment.
- [x] Document local Whisper/VAD model download source and integrity behavior.

## Tests and QA

- [x] Unit tests exist for sidebar flattener behavior.
- [x] Unit tests exist for transcription cleanup and WAV encoding.
- [x] Add URL normalization and profile migration tests.
- [x] Add auth refresh and expired-token recovery tests.
- [x] Add chat row/message decoding tests.
- [x] Add permission/input payload tests.
- [x] Add streaming state tests for thinking event decoding, sample interval, and stale cleanup.
- [x] Add file upload and `{filepath}` notification tests.
- [x] Add lifecycle tests or manual QA script for background/foreground recovery.
- [x] Add UI smoke tests or manual QA script for login, drawer navigation, chat prompt, attachments, file browser, settings, Hermes, and voice permission flow.
- [x] Add product-mode tests for Hermes+Agor versus Hermes-only behavior.
- [x] Add task-header status tests for failed collapsed tasks.

## Recommended Implementation Order

1. Finish the product-shell parity items: profile switching UI, drawer search, Important rules, persisted expansion.
2. Finish chat parity: per-session drafts, task-centric loading, top-bar overflow actions, plan-mode UI, ready-for-prompt clearing.
3. Verify daemon contracts: permission/input payloads, socket service ack shapes, MCP service routes.
4. Build MCP management.
5. Harden mobile recovery: stale stream clearing, foreground reconnect, missed-transition notifications, WorkManager polling.
6. Finish diagnostics and polish: crash logs, animated GIFs, file linkification, clear/send logs, test coverage.
7. Wire full regular Agor voice mode if voice should be first-class outside Hermes.
