# Agor Native iOS App Specification

## Status

- Authoring date: May 11, 2026
- Scope: As-built product and technical specification for the native SwiftUI iPhone app.
- Source branch: `maroun2/agor:add-iphone-native-app`
- Source baseline: commit `063606d04b696307f0d388614adc692c76c133fe`
- App path in source branch: `apps/agor-ios`
- Reference URL: https://github.com/maroun2/agor/tree/add-iphone-native-app/apps/agor-ios

## Summary

Agor iOS is a native iPhone client for Agor. It lets a user connect to one or more Agor daemons, browse boards, worktrees, and sessions, chat with AI coding agents, approve permission requests, answer input requests, manage per-session MCP servers, browse worktree files, attach files or photos to prompts, and use an optional hands-free voice mode.

The app is a client-only implementation. It is designed to use Agor's existing FeathersJS REST, Socket.IO, and service APIs without requiring server-side changes. Its primary value is mobile access to ongoing AI coding work: checking status, unblocking agents, sending follow-up prompts, reading results, and inspecting files from an iPhone.

## Goals

1. Provide a native iPhone experience for the core Agor workflow: board to worktree to session to chat.
2. Preserve the worktree-centric Agor model: boards contain worktrees, worktrees contain sessions.
3. Keep session chat usable on a small screen with task grouping, streaming, markdown, tool blocks, and lazy loading.
4. Let users unblock agents from mobile through permission and input request cards.
5. Support multiple daemon profiles for switching between local, remote, home, and work Agor servers.
6. Recover cleanly from iOS backgrounding, expired tokens, dropped sockets, and daemon restarts.
7. Add native-only affordances where useful: local notifications, microphone input, text-to-speech, file/photo picking, and crash/debug log sharing.

## Non-Goals

- No independent backend or iOS-specific daemon API.
- No board canvas editing, spatial dragging, zone configuration, or multiplayer cursor UI in the native app.
- No full repo editing experience or terminal.
- No App Store distribution assumptions; current build path supports personal device installation.
- No APNs push notifications; notifications are local and based on in-app or background-poll observed events.

## Target Users

- A developer who has agents running in Agor and wants to monitor or unblock work away from the desktop.
- A collaborator who needs to approve tool use, answer a prompt, or inspect recent output from a phone.
- A power user who wants voice-driven prompt entry and spoken agent responses for a selected session.

## Platform Requirements

- iOS 18.0 or later.
- Xcode 16.x project generation and build via XcodeGen.
- SwiftUI with `@Observable`.
- Socket.IO client compatible with the FeathersJS daemon.
- Network access to an Agor daemon, usually on port `3030` for development.
- Microphone permission for voice mode.
- Speech/audio background mode for continued voice interaction.
- Local notification permission for completion alerts.

## Product Surface

### App Launch and Authentication

The app starts in one of two states:

1. Authenticated: show `MainNavigationView`.
2. Unauthenticated: show `ConnectionSetupView`.

The login screen supports:

- daemon URL entry;
- email and password login via `/authentication`;
- profile name entry;
- selection from saved server profiles;
- prefill from the active server profile or legacy keychain data.

URL handling must normalize common inputs:

- trim whitespace and trailing slash;
- strip path components such as `/ui`;
- add `http://` when no scheme is provided;
- add `:3030` for HTTP URLs without an explicit port;
- preserve explicit ports;
- omit a default port for HTTPS;
- validate against `/health`;
- when HTTP validation fails, try HTTPS fallback with `:3030` stripped.

Successful login stores tokens, user metadata, profile URL, profile email, and password in profile-scoped Keychain keys so silent re-authentication can work later. A legacy flat-keychain session is migrated into a default server profile on first launch.

### Server Profiles

Server profiles are persisted in `UserDefaults` and profile-scoped credentials are persisted in Keychain.

Each profile has:

- UUID;
- display name;
- daemon URL;
- email;
- default flag.

Users can:

- switch server from the sidebar tab bar;
- manage servers from Settings;
- add, edit, delete, and set a default server;
- retain separate tokens per server.

Switching server must:

1. disconnect the existing socket;
2. save current tokens to the current profile;
3. set the target active profile;
4. update the API base URL;
5. restore target tokens if present;
6. reconnect socket and refresh data, or return to login when no token exists.

### Main Navigation

The main shell uses `NavigationSplitView`:

- sidebar: server tabs, search, attention sessions, important sessions, boards, worktrees, and sessions;
- detail: selected session chat, or an empty selection state.

Sidebar hierarchy:

```text
Server tabs
Search
Needs Attention
Important
Board
  Worktree
    Session
```

The sidebar must support:

- expandable boards;
- expandable worktrees;
- persisted collapsed state for boards and worktrees;
- favorites stored locally in `UserDefaults`;
- pull-to-refresh;
- cached startup rendering from `SidebarCache`;
- 45-second polling while foregrounded;
- context menus for session and worktree actions.

The app fetches:

- boards from `/boards`;
- worktrees per board from `/worktrees?board_id=...`;
- repositories from `/repos` to resolve repo names;
- all non-archived sessions from `/sessions` and groups them by `worktree_id` client-side.

The all-sessions fetch is intentional because the current backend filter behavior makes a single broad fetch more predictable than repeated filtered session requests.

### Needs Attention Section

The Needs Attention section contains sessions whose status is:

- `awaiting_permission`;
- `awaiting_input`.

Scheduled sessions are excluded. Tapping a row opens the session. In chat, an attention bar scrolls to the most recent pending permission or input card.

### Important Section

Important sessions are selected from:

- sessions marked `ready_for_prompt`;
- running sessions;
- locally favorited sessions;
- the three most recently updated sessions.

Sessions already in Needs Attention are excluded. Untitled or auto-generated sessions are hidden unless they are favorited.

### Search

The sidebar search filters known sessions by `displayTitle`. Selecting a result reveals that session in its board/worktree context and opens it.

## Chat Experience

### Session Loading

Selecting a session must:

1. stop polling for the previous session;
2. reset local chat state;
3. restore the prompt draft for the selected session;
4. fetch the session from `/sessions/:id`;
5. clear `ready_for_prompt` by patching the session when needed;
6. fetch tasks from `/tasks?session_id=...`;
7. load messages for the latest task, or fall back to virtual-task mode if the session has no server tasks;
8. start a 10-second task/session polling loop.

The app stores messages task-centrically:

- `tasks`: ordered task headers;
- `messagesByTask`: messages loaded for expanded tasks;
- `loadedTaskIds`: currently loaded task IDs;
- `virtualMessages`: fallback for sessions without task rows.

Collapsed real tasks unload their message arrays to reduce memory pressure. By default, only the latest task is expanded. When a session has more than 20 tasks, older task headers are hidden behind a "Show N older tasks" banner that reveals 20 more at a time. Virtual-task mode applies the same visible-task limit by grouping turns around user messages.

### Display Items

The chat list renders a normalized display union:

- task header;
- persisted message;
- active streaming message;
- older-tasks banner.

This lets task headers, normal messages, and in-flight streaming output coexist in one scroll view.

### Scrolling Behavior

The chat view must:

- start scrolled to the bottom;
- auto-scroll only when the user is near the bottom, with a short grace period for layout races;
- debounce rapid message events into one scroll;
- scroll to pending permission/input cards when a session enters an attention state;
- avoid competing scroll animations during explicit scroll-to-card actions;
- keep a 60-point bottom spacer so the last message can scroll above the input bar.

### Prompt Input

The prompt bar supports:

- multi-line text entry;
- per-session draft persistence in `UserDefaults`;
- send button enabled only when the session is promptable and text is non-empty;
- placeholder changes for running, awaiting permission, awaiting input, and idle states;
- file attachment menu;
- photo picker;
- voice mode toggle.

Prompts are sent with `POST /sessions/:id/prompt`. After a prompt is sent, the app clears the draft and proactively refreshes tasks and latest task messages after a short delay to cover missed socket events.

### Attachments

The attachment menu supports:

- attach debug log;
- attach crash log when one is available;
- attach photo via Photos picker;
- attach arbitrary file via file importer.

Uploads use multipart form data:

```text
POST /sessions/:id/upload?destination=worktree
```

The uploaded worktree path is inserted into the prompt as an `@path` reference.

### Session Toolbar

The chat toolbar should remain minimal:

- plan-mode badge when relevant;
- status badge, or stop button while the session is active;
- overflow menu.

The overflow menu exposes:

- Files;
- Session Settings;
- MCP Servers;
- Archive;
- Reset Session.

Reset archives the current session and creates a new idle session on the same worktree, preserving the agentic tool and title when available.

## Message Rendering

The message model supports:

- user, assistant, and system roles;
- text content;
- block content;
- permission request content;
- input request content;
- file history snapshot type;
- metadata such as model, tokens, original ID, parent ID, and source.

Supported content blocks:

- text;
- tool use;
- tool result;
- thinking;
- image;
- unknown block fallback.

Text rendering requirements:

- Markdown via Textual;
- tappable Markdown URLs;
- enhanced text link detection for file paths and session links;
- file path chips and session chips for recognized references;
- bare filename resolution against the cached worktree file list when unambiguous;
- exclusion of path-like matches inside URLs and domain names.

Code rendering requirements:

- syntax highlighting via Highlightr;
- monospaced fallback;
- copy/selectable behavior where available.

Tool block requirements:

- collapsible tool-use block with icon, tool name, and input preview;
- JSON input expansion;
- collapsible tool-result block with output preview;
- error result styling.

Thinking block requirements:

- collapsible display;
- support redacted thinking where only a signature is present;
- streaming thinking accumulation before final persisted messages arrive.

Image rendering requirements:

- support base64 image blocks;
- support URL image blocks where present;
- support inline file images from the file service;
- support common image types: png, jpg/jpeg, gif, webp.

## Streaming and Real-Time Updates

### Socket Authentication

The socket service connects to the daemon base URL with Socket.IO. It sends the JWT in extra headers and also emits a Feathers authentication create call after transport connection:

```text
create "authentication" { strategy: "jwt", accessToken }
```

Both steps are required:

- extra headers allow socket service calls to populate `params.user`;
- Feathers authentication joins the connection to authenticated broadcast channels.

The client intentionally does not force WebSocket-only transport. Socket.IO default polling-then-upgrade is used because it recovers better after iOS background transitions.

### Subscribed Events

The app listens for Feathers service events:

- `sessions patched`;
- `tasks created`;
- `tasks patched`;
- `messages created`;
- `messages patched`.

The app also listens for custom streaming events:

- `messages streaming:start`;
- `messages streaming:chunk`;
- `messages streaming:end`;
- `messages streaming:error`;
- `messages thinking:start`;
- `messages thinking:chunk`;
- `messages thinking:end`.

### Streaming State

`StreamingService` owns active streaming messages by message ID. It:

- creates a placeholder on stream start;
- appends chunks as they arrive;
- marks streams complete or errored;
- tracks thinking content;
- debounces UI updates every 50 ms;
- removes a stream when the persisted `messages created` event arrives;
- clears stale streams for a session on reconnect or when the session becomes idle.

Streaming text is rendered as fast plain text while in flight. The persisted message later renders through the full markdown/block pipeline.

### Socket Service Calls

The app uses generic Feathers service calls over Socket.IO for operations that match the web UI or need socket-authenticated services:

- `find`;
- `get`;
- `create`;
- `patch`;
- `remove`.

The service call helper must parse Feathers acknowledgements in these shapes:

- success: `[null, result]`;
- error: `[errorObject]`;
- timeout: `["NO ACK"]`;
- single result fallback.

Paginated Feathers responses are unwrapped from `{ data, total, skip, limit }` when a raw array is expected.

## Permission and Input Workflows

### Permission Cards

Permission requests render inline in the chat timeline. A permission card shows:

- status;
- tool display name;
- SF Symbol icon;
- tool input preview;
- approval controls while pending;
- resolved state after approval, denial, or timeout.

Supported statuses:

- `pending`;
- `approved`;
- `denied`;
- `timed_out`.

Supported scopes in the iOS model:

- `once`;
- `project`;
- `user`;
- `local`.

Approval sends:

```text
POST /sessions/:id/permission-decision
```

with request ID, optional task ID, allow flag, reason, remember flag, scope, and deciding user ID. Denial sends the same endpoint with `allow: false`, no remember, and scope `once`.

### Input Request Cards

Input requests render inline in the chat timeline. The model supports:

- one or more questions;
- header text;
- options with label, description, and optional markdown;
- multi-select flag;
- free-text or option-derived answers;
- pending, answered, and timed-out states.

Responses send:

```text
POST /sessions/:id/input-response
```

with request ID, optional task ID, answer map, and responding user ID.

## Session Management

The app supports:

- selecting sessions;
- favoriting sessions locally;
- marking ready-for-prompt sessions as viewed;
- stopping active sessions;
- archiving sessions;
- resetting sessions;
- creating new sessions on a worktree;
- changing per-session permission mode;
- viewing session model name when provided;
- managing session MCP servers.

Session status values:

- `idle`;
- `running`;
- `stopping`;
- `awaiting_permission`;
- `awaiting_input`;
- `timed_out`;
- `completed`;
- `failed`.

A session is promptable when it is idle or `ready_for_prompt` is true. A session is active when it is running, stopping, or needs attention.

Supported agentic tools:

- `claude-code`;
- `codex`;
- `gemini`;
- `opencode`.

Permission mode choices are agent-specific:

- Claude Code: Default, Accept Edits, Bypass Permissions, Plan Mode.
- Codex: Ask, Auto, On Failure, Allow All.
- Gemini: Default, Auto Edit, YOLO.
- Unknown/default agents: Default.

Plan mode must show both a toolbar badge and a banner explaining that the session is read-only and no tool execution is expected.

## MCP Server Management

The MCP sheet lets a user manage the MCP servers attached to the current session.

Data loading:

- active session servers via socket service `session-mcp-servers`;
- available workspace servers via socket service `mcp-servers`;
- resolve active server names/descriptions by joining against available servers client-side.

Actions:

- add available server to session: `POST /sessions/:id/mcp-servers`;
- remove server from session: `DELETE /sessions/:id/mcp-servers/:mcpServerId`;
- enable/disable server: `PATCH /sessions/:id/mcp-servers/:mcpServerId`;
- swipe-to-remove in active list;
- toggle active server enabled state.

If no servers are configured, the app shows an empty state directing users to configure MCP servers in the Agor web UI.

## File Browser

The file browser is worktree-scoped and uses the daemon `file` service over Socket.IO.

Capabilities:

- fetch flat worktree file list with `find "file" { worktree_id }`;
- build a virtual directory tree client-side;
- navigate into directories, up, and to root;
- fetch file detail with `get "file" path { worktree_id }`;
- display text content;
- display image content from base64;
- expose file paths to chat for link detection;
- open a file browser from a worktree context menu, session context menu, or chat toolbar;
- open a specific file from an enhanced text link.

File detail decoding must support:

- plain text content;
- base64 content;
- file name derivation from path.

## Voice Mode

Voice mode is session-scoped. When enabled, only the owning session receives voice-driven prompt updates and inline voice UI. If the user switches to another session, voice mode continues in the background and a floating button appears to jump back to the active voice session.

### Voice State Machine

Voice mode states:

- disabled;
- preparing;
- listening;
- paused;
- recording;
- transcribing;
- sending;
- speaking.

The prompt bar is replaced by voice controls while viewing the owning voice session. It shows:

- model download or warm-up state;
- preparing state;
- paused "waiting for agent" state;
- listening/recording audio level bar;
- transcription progress;
- sending progress;
- speaking state;
- skip TTS button while speaking;
- disable voice button.

### Voice Input Pipeline

The pipeline is:

```text
AVAudioSession + AVAudioEngine
  -> FluidAudio/Silero voice activity detection
  -> pre-roll AVAudioRecorder capture
  -> WhisperKit transcription
  -> cleaned text
  -> prompt draft
  -> delayed auto-send
```

Behavior requirements:

- play a short tone when ready to listen;
- keep a rolling pre-roll buffer up to roughly two seconds;
- start recording exactly when VAD detects speech;
- play a recording-start tone on transition to recording;
- wait for configured silence duration before ending speech;
- transcribe with WhisperKit model `openai_whisper-base.en`;
- strip Whisper special tokens such as `[BLANK_AUDIO]`;
- insert recognized text into the prompt draft for review;
- auto-send after roughly five seconds if unchanged;
- stop listening while the agent is running;
- resume listening after agent output/TTS is complete and the session is promptable.

### Voice Output Pipeline

The TTS pipeline uses `AVSpeechSynthesizer`.

Behavior requirements:

- select the best available English voice, preferring premium, then enhanced, then compact default;
- speak status updates such as "Working", "I need permission", "I need input", and "Stopped";
- speak streaming assistant output by complete sentence or paragraph chunks;
- avoid speaking code blocks;
- flush remaining stream buffer when the persisted final assistant message arrives;
- avoid double-speaking the same assistant message;
- allow user to skip active TTS without disabling voice mode.

### Voice Settings

Detection settings include:

- sensitivity slider mapped to VAD threshold;
- silence-before-send slider;
- reset to defaults.

Settings persist in `UserDefaults` and are applied to the active voice service.

## Notifications and Background Recovery

### Local Notifications

The app requests local notification permission on launch.

Notifications are fired for session transitions:

```text
running -> idle
```

Scheduled sessions are excluded. The app tracks the last notified status per session and uses stable notification identifiers to avoid duplicates. Tapping a notification navigates to the relevant session.

The current code fires local notifications for eligible running-to-idle transitions regardless of whether a session is favorited, although the README describes favorited-session completion notifications. This behavior should be treated as an implementation note to reconcile.

### Toasts

For sessions other than the currently selected one, the app shows in-app toasts for:

- awaiting permission;
- awaiting input;
- completed;
- failed.

Tapping a toast opens the target session.

### Background and Foreground Handling

On background:

- mark notification manager backgrounded;
- stop socket health checks;
- stop chat polling;
- stop navigation polling;
- schedule a background app refresh poll;
- keep voice mode running if user left it enabled.

On foreground after background:

1. reconnect socket if needed;
2. show reconnecting banner;
3. refresh navigation data;
4. restart polling;
5. refresh current session;
6. check missed running-to-idle transitions;
7. show "Updated" banner briefly.

`BackgroundSessionPoller` registers `com.agor.AgorApp.session-poll` and attempts a background refresh roughly every five minutes. It fetches recent non-archived sessions and asks `NotificationManager` to check missed transitions.

Implementation note: the source registers and schedules a background task, but the checked `project.yml` excerpt does not declare a `BGTaskSchedulerPermittedIdentifiers` Info.plist key. That should be verified before relying on background refresh in production.

## Settings and Diagnostics

Settings include:

- account summary with emoji/name/email;
- logout;
- active server and server management;
- connection status;
- debug log;
- clear session cache;
- voice detection settings;
- version;
- build git hash.

Debug log behavior:

- in-memory rolling log with max 500 entries;
- levels: info, warning, error, debug;
- categories such as HTTP, Auth, Socket, Nav, Chat, Voice, Notification;
- export as text;
- share via iOS share sheet;
- send as a prompt to a recent session;
- attach debug log to the current session prompt.

Crash log behavior:

- install MetricKit diagnostic subscriber;
- install uncaught Objective-C exception handler;
- write crash files to `Caches/CrashLogs`;
- expose latest crash log as an attachable file;
- clear crash logs after upload.

## Data Model Coverage

The app mirrors Agor's canonical API data with Swift `Codable` structs:

- `Board`
- `Worktree`
- `Repo`
- `Session`
- `AgorTask`
- `Message`
- `PermissionRequestContent`
- `InputRequestContent`
- `StreamingMessage`
- `FileListItem`
- `FileDetail`
- `User`
- `MCPServer`
- `SessionMCPServer`
- `ServerProfile`

The app must use snake-case coding keys matching the daemon payloads and custom decoding where payloads are polymorphic.

Important custom decoding cases:

- message content can be a string, a block array, permission request content, or input request content;
- tool result content can be a string or block array;
- content blocks route by `type`;
- unknown block types must not crash the app.

## API Contract

### REST Endpoints Used

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/health` | GET | Validate daemon reachability and health. |
| `/authentication` | POST | Login with local strategy or refresh JWT with jwt strategy. |
| `/users/:id` | GET | Fetch current user details. |
| `/boards` | GET | List boards. |
| `/repos` | GET | List repos for worktree repo-name resolution. |
| `/worktrees` | GET | List worktrees, usually filtered by `board_id`. |
| `/sessions` | GET | List sessions for navigation, diagnostics, and background polling. |
| `/sessions/:id` | GET | Fetch selected session. |
| `/sessions/:id` | PATCH | Archive session, clear `ready_for_prompt`, update session fields. |
| `/sessions/:id/prompt` | POST | Send prompt to session. |
| `/sessions/:id/stop` | POST | Stop active session. |
| `/sessions/:id/permission-decision` | POST | Approve or deny permission request. |
| `/sessions/:id/input-response` | POST | Answer agent input request. |
| `/sessions/:id/upload?destination=worktree` | POST | Upload file/photo/log into worktree and insert reference. |
| `/sessions/:id/mcp-servers` | POST | Add MCP server to session. |
| `/sessions/:id/mcp-servers/:mcpServerId` | PATCH | Enable or disable session MCP server. |
| `/sessions/:id/mcp-servers/:mcpServerId` | DELETE | Remove MCP server from session. |
| `/tasks` | GET | Fetch task list or count for a session. |
| `/messages` | GET | Fetch messages by task or session. |

### Socket Services Used

| Service | Method | Purpose |
| --- | --- | --- |
| `authentication` | create | Join authenticated Feathers channel after socket transport connects. |
| `file` | find | List worktree files. |
| `file` | get | Fetch file detail. |
| `sessions` | create | Create a new session on a worktree. |
| `sessions` | patch | Archive or update a session. |
| `session-mcp-servers` | find | List servers attached to session. |
| `mcp-servers` | find | List available MCP servers. |

### WebSocket Events Consumed

| Event | Consumer Behavior |
| --- | --- |
| `sessions patched` | Update selected session, navigation status, voice state, notifications, toasts. |
| `tasks created` | Append task header and load new task messages. |
| `tasks patched` | Update task header/status. |
| `messages created` | Append message if relevant and loaded; hand off from streaming state. |
| `messages patched` | Update permission/input cards or edited message state. |
| `messages streaming:start` | Create streaming placeholder. |
| `messages streaming:chunk` | Append live text and optionally speak chunks in voice mode. |
| `messages streaming:end` | Mark stream complete. |
| `messages streaming:error` | Mark stream failed. |
| `messages thinking:start` | Create or update thinking state. |
| `messages thinking:chunk` | Append thinking content. |
| `messages thinking:end` | End thinking state. |

## Persistence

Keychain:

- access token;
- refresh token;
- daemon URL legacy value;
- user ID;
- user email;
- per-profile access token;
- per-profile refresh token;
- per-profile user ID;
- per-profile user email;
- per-profile password for silent re-auth.

Keychain accessibility must use `kSecAttrAccessibleAfterFirstUnlock` so tokens remain available after reboot once the device has been unlocked.

UserDefaults:

- server profiles;
- active server ID;
- collapsed board IDs;
- collapsed worktree IDs;
- favorite session IDs;
- per-session prompt drafts;
- VAD config;
- VAD sensitivity.

Filesystem:

- sidebar cache JSON with TTL;
- crash logs under caches directory;
- temporary audio recordings for voice mode;
- generated `GitVersion.swift` during build.

## Security and Privacy

Security requirements:

- JWT access token on REST requests via `Authorization: Bearer ...`;
- token refresh on HTTP 401;
- socket auth failure detection and token refresh;
- soft logout clears expired tokens but preserves URL/email for re-login;
- hard logout clears profile tokens and legacy keychain items;
- debug logs should truncate large HTTP bodies;
- uploaded logs/crash files require explicit user action.

Security caveats:

- `project.yml` enables `NSAllowsArbitraryLoads`, appropriate for local daemon development but not ideal for production distribution.
- The app stores the user password in Keychain for silent re-auth. This improves recovery from token expiry but should be explicitly reviewed before wider release.
- Local notifications and background refresh do not provide server-pushed guarantees. They depend on local app lifecycle and iOS scheduling.
- Voice mode processes microphone audio locally for VAD/transcription, but it still sends final prompt text to the selected Agor daemon.

## Error Handling and Recovery

The app must handle:

- invalid URL;
- failed `/health`;
- bad credentials;
- expired access token;
- expired refresh token;
- socket auth rejection;
- socket disconnect;
- daemon restart;
- file service request before socket auth completes;
- message/task events missed while backgrounded;
- stale streaming states after reconnect;
- failed file upload;
- missing or malformed polymorphic message blocks;
- failed Whisper model download or CoreML warm-up;
- denied microphone permission;
- failed background task scheduling.

Recovery behaviors:

- retry HTTPS fallback during login;
- refresh token on 401;
- silent re-auth with stored credentials when token refresh fails;
- force logout if silent re-auth fails;
- reconnect socket when health checks fail and recover;
- refresh navigation/session data after foreground resume;
- clear stale streams when session becomes idle;
- retry file list load once socket reaches connected state.

## Build and Deployment

Build system:

- Swift Package manifest: `Package.swift`;
- XcodeGen project config: `project.yml`;
- generated Xcode project: `AgorApp.xcodeproj`;
- deploy helper: `deploy.sh`.

Primary dependencies:

- SwiftUI;
- socket.io-client-swift;
- Textual;
- Highlightr;
- WhisperKit;
- AVFoundation;
- UserNotifications;
- BackgroundTasks;
- MetricKit;
- PhotosUI;
- FluidAudio/Silero VAD as referenced by `VoiceActivityDetector`.

Implementation note: `VoiceActivityDetector.swift` imports `FluidAudio`, while `Package.swift` and `project.yml` list WhisperKit but not FluidAudio directly. Confirm whether FluidAudio is a transitive dependency in the resolved package graph or add it explicitly.

Build-time behavior:

- pre-build script writes `AgorApp/Generated/GitVersion.swift`;
- bundle ID: `com.agor.AgorApp`;
- display name: `Agor`;
- deployment target: iOS 18.0;
- microphone and speech recognition usage descriptions are configured;
- audio background mode is configured.

## Acceptance Criteria

### Authentication and Profiles

- User can log in with daemon URL, email, and password.
- URL normalization handles host-only, HTTP, HTTPS, explicit ports, and `/ui` suffixes.
- User can save, switch, edit, delete, and set default server profiles.
- Tokens are restored across app relaunch.
- Expired-token states do not leave the app in an authenticated-but-dead zombie UI.

### Navigation

- Cached sidebar data appears immediately on app launch when available.
- Boards load from the daemon and contain worktrees.
- Worktrees load sessions grouped by `worktree_id`.
- Attention and Important sections update from session status.
- Search opens matching sessions.
- Collapsed state and favorites persist across app launches.

### Chat

- Selecting a session loads session metadata, tasks, and latest messages.
- Prompt drafts persist by session.
- User can send prompts.
- Streaming text appears before final persisted markdown message.
- Task headers collapse/expand and older tasks can be revealed in batches.
- User can stop, archive, reset, and create sessions.
- Plan mode is visibly indicated.

### Permissions and Input

- Pending permission cards show actionable approve/deny controls.
- Permission decisions reach the daemon with request ID, task ID, scope, and user ID.
- Pending input cards accept answers and submit them to the daemon.
- Attention bar scrolls to the newest pending card.

### Files and Attachments

- User can browse a worktree's virtual directory tree.
- User can open text and image files.
- File/session references in chat become tappable.
- User can attach files, photos, debug logs, and crash logs to prompts.
- Uploaded file references are inserted into the prompt.

### MCP

- User can view active and available MCP servers for a session.
- User can add, remove, enable, and disable session MCP servers.

### Voice

- User can enable voice mode for a session.
- The app downloads/warms the transcription model and reports progress.
- The app detects speech, records, transcribes, and sends prompts.
- The app pauses listening while the agent is running.
- The app speaks status updates and assistant responses.
- The app keeps voice mode scoped to its owning session and shows a floating return button elsewhere.
- Detection settings persist.

### Notifications and Recovery

- User receives local notifications for eligible running-to-idle transitions.
- Tapping a notification opens the relevant session.
- Cross-session toasts appear for attention/completion/failure states.
- Returning from background reconnects, refreshes data, clears stale streams, and checks missed transitions.

### Diagnostics

- Debug log captures HTTP, auth, socket, navigation, chat, voice, notification, and background events.
- User can share, clear, attach, or send debug logs to a session.
- Crash logs are captured after crashes when iOS delivers diagnostics and can be attached to prompts.

## Known Gaps and Follow-Up Decisions

1. Reconcile README-stated notification behavior with implementation: README says favorite-session completion notifications, but current `shouldNotify` does not require favorites.
2. Verify `BGTaskSchedulerPermittedIdentifiers` is present in the generated Info.plist before relying on background refresh.
3. Confirm FluidAudio dependency resolution and add it explicitly if needed.
4. Review production security posture for arbitrary network loads and password storage.
5. Decide whether the native app remains a supported product or is superseded by the existing Agor iOS PWA rewrite specification in `context/projects/agor-ios-pwa-rewrite-spec.md`.
6. Add automated tests around message decoding, URL normalization, profile migration, task grouping, and permission/input payload encoding.

