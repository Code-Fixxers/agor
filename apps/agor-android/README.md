# Agor Android

Native Android client for [Agor](https://agor.live) — browse boards, worktrees, and
sessions, chat with AI agents, approve permissions, answer questions, send prompts,
and browse files, all with real-time streaming and on-device voice mode.

Connects to the FeathersJS daemon using the same REST + WebSocket API as the web UI
and the iOS app. Built entirely against the existing API — no server-side changes
required.

This app mirrors the architecture of `apps/agor-ios/` (kept on the `add-iphone-native-app`
branch of `https://github.com/maroun2/agor` as design reference) but ships natively
in Kotlin + Jetpack Compose.

---

## Tech stack

| Concern | Library |
|---|---|
| UI | Jetpack Compose + Material 3 |
| State | `androidx.lifecycle.ViewModel` + `StateFlow` |
| HTTP | OkHttp + manual JSON via kotlinx.serialization |
| WebSocket | `io.socket:socket.io-client` |
| Markdown | `multiplatform-markdown-renderer` |
| Code highlighting | `dev.snipme:highlights` |
| Images | Coil |
| Secure storage | `androidx.security:security-crypto` |
| TTS | Android `TextToSpeech` |
| ASR | WhisperLiveKit streaming/OpenAI-compatible remote server; local whisper.cpp `base.en` fallback via NDK/JNI |
| Voice service | Foreground service (`microphone | mediaPlayback`) |

Min SDK 28 (Android 9), target SDK 35 (Android 15).

---

## Prerequisites

* **Android Studio Ladybug or newer**, or a CLI toolchain with:
  * JDK 17
  * Android SDK platform 35 + build-tools 35.0.0
  * NDK 27.x (for whisper.cpp; set `SKIP_WHISPER=1` to build without local transcription)
  * `cmake 3.22.1+`

The first build will download AGP 8.7.x, Gradle 8.11.1, Compose BOM 2024.12,
whisper.cpp, and the ignored `ggml-base.en.bin` model artifact unless
`SKIP_WHISPER=1` is set.

## Build & run

```bash
cd apps/agor-android
./gradlew :app:assembleHermesAgorDebug

# Or build the standalone Hermes app:
./gradlew :app:assembleHermesOnlyDebug

# Install over USB
adb install -r app/build/outputs/apk/hermesAgor/debug/app-hermesAgor-debug.apk

# Or use the wrapper:
./deploy.sh
```

For the emulator, Agor's daemon at `http://localhost:3030` on the host is reachable
from the emulator at `http://10.0.2.2:3030`.

### Product variants

The Android module publishes two side-by-side product flavors:

| Flavor | Package | Label | Purpose |
|---|---|---|---|
| `hermesAgor` | `live.agor.app` | Agor | Full app: Agor daemon login, boards, worktrees, sessions, plus Hermes orchestration. |
| `hermesOnly` | `live.agor.hermes` | Hermes | Standalone Hermes client: connects directly to Hermes/OpenAI-compatible endpoints and does not bootstrap Agor auth, sockets, navigation polling, or session recovery workers. |

Debug builds keep the usual `.debug` suffix, so both variants can be installed
next to release builds and next to each other. The legacy `assembleDebug` and
`testDebugUnitTest` Gradle aliases intentionally point at `hermesAgor` for
developer convenience; use explicit flavor task names when validating both
products.

### Network policy

Release builds use `app/src/main/res/xml/network_security_config.xml`, which
denies cleartext traffic and trusts only system certificate authorities. Use HTTPS
daemon URLs for production or release-candidate validation.

Debug builds overlay `@xml/network_security_config` from `app/src/debug/`, allowing
cleartext and user-installed CAs for local daemon development, emulator access to
`http://10.0.2.2:3030`, and trusted proxy/debug certificates.

### NixOS / Nix

The repo flake defines a fully-pinned Android build environment (SDK 35,
build-tools 35.0.0, NDK 27.1.12297006, CMake 3.22.1, JDK 17). On NixOS:

```bash
# One-shot build → drops agor-android-debug-<sha>.apk in the repo root
nix run .#build-agor-android-apk

# Or drop into a dev shell with all toolchains on PATH
nix develop .#android
cd apps/agor-android
./gradlew :app:assembleHermesAgorDebug
```

Set `SKIP_WHISPER=1` before `nix run` to skip vendoring `whisper.cpp` (faster
build, local voice transcription is unavailable unless remote Whisper is configured).

### CI (GitHub Actions)

Every push to `main` and every PR touching `apps/agor-android/**` (or this
workflow file) triggers `.github/workflows/build-android-apk.yml`, which builds a
debug APK and uploads it as a downloadable artifact named
`agor-android-debug-<short-sha>`. Open the Actions run, scroll to **Artifacts**,
download the zip, then `adb install -r` the APK inside.

## Release validation

Before publishing a release or release candidate:

* Build the intended variant and confirm the manifest does not allow cleartext
  traffic for release builds.
* Confirm release builds do not include the debug-only automation intent filters
  or broadcast receiver from `app/src/debug/AndroidManifest.xml`. Those paths are
  additionally guarded by `BuildConfig.DEBUG`, but they should remain absent from
  release manifests.
* Install a previous signed APK, then install the candidate over it with
  `adb install -r` to confirm the signing certificate and package name are
  update-compatible. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the device sees a
  different signing lineage and users will need an uninstall unless the signing
  path is fixed.
* Check that `versionCode` increases monotonically and the in-app update manifest
  points to an APK signed with the same certificate as the installed app.
* Exercise the in-app update flow on a clean device profile: download the APK,
  tap install, grant Android's per-app "Install unknown apps" permission when
  prompted, return to Agor, and tap install again. The permission is a system
  setting, not a runtime dialog, so users must be able to recover after leaving
  the app.
* Re-run the update flow after the install permission is already allowed and
  confirm it proceeds straight to the system package installer.
* Audit permissions before release. `REQUEST_INSTALL_PACKAGES` is used only by
  the in-app updater and must route through Android's per-app "Install unknown
  apps" settings plus the system package installer. `SYSTEM_ALERT_WINDOW` is
  currently declared for future floating voice/session affordances, but the app
  does not request overlay access or call overlay APIs yet; remove it before
  release unless that feature is actively shipped.

## Credential storage policy

Agor stores profile-scoped tokens, optional saved email/password credentials,
optional saved API keys, GitHub update tokens, Hermes tokens, and remote Whisper
tokens in app-private encrypted storage backed by Android security primitives.
Backup and data-extraction rules exclude the secure preferences file.

Saved passwords and API keys are allowed for this native client because they
support silent re-authentication, biometric unlock, and profile switching. Treat
them as long-lived secrets: only save them on a trusted personal device, prefer
API-key login when possible, require biometric/user authentication before
enabling biometric unlock, and use sign out to clear the current profile's saved
token and credential snapshot.

## Attachment URI handling

Prompt, Hermes, gallery, camera, crash-log, and diagnostics attachments are copied
into app-controlled memory or cache before upload. The app does not keep external
document URIs for later retries, so it does not request persistable URI
permissions today. If a future queued-upload feature stores external URIs across
process death or reboot, that feature must explicitly call
`takePersistableUriPermission` only for those retained URIs and release the grant
after the upload completes or is deleted.

## On-device transcription

Voice mode uses a local `ggml-base.en.bin` from `app/src/main/assets/whisper/`,
then copies it to app-private storage on first use. The Gradle build fetches
both whisper.cpp and that ignored model artifact by default; set
`SKIP_WHISPER=1` only when you intentionally want remote-only transcription.

```bash
# Optional: manually refresh whisper.cpp in the source tree
cd apps/agor-android
scripts/sync-whisper.sh

# Optional: manually refresh the ignored ggml model artifact
scripts/fetch-whisper-model.sh base.en

# Rebuild — the NDK toolchain will pick up the source tree
./gradlew :app:assembleHermesAgorDebug
```

If `SKIP_WHISPER=1` is set or the local assets are otherwise unavailable, the
JNI library still compiles as a no-op stub and the voice UI reports local
transcription as unavailable. Settings default remote transcription to
WhisperLiveKit at `http://100.101.157.56:8090`, using `/asr` for live
streaming and `/v1/audio/transcriptions` for final/fallback transcription.

Model downloads use HTTPS from the upstream project release/source locations:
the Whisper `ggml-base.en.bin` artifact is fetched from the `ggerganov/whisper.cpp`
Hugging Face repository, and the Silero VAD ONNX file is fetched from the
`snakers4/silero-vad` GitHub repository. Runtime downloads store files under
app-private `filesDir/voice-models/` and only accept non-empty files today. There
is no pinned checksum or signature verification yet, so release candidates should
either ship vetted bundled assets or add checksum validation before treating
runtime model refreshes as integrity-checked.

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/live/agor/app/
│   ├── AgorApplication.kt        # process-wide DI container, notification channels
│   ├── AppContainer.kt           # manual DI: services, clients, caches
│   ├── MainActivity.kt           # Compose entry point
│   │
│   ├── models/                   # API DTOs (Session, Worktree, Message, …)
│   ├── network/                  # AgorClient, SocketService, StreamingService
│   ├── auth/                     # AuthService, SecureTokenStore, ServerProfile
│   ├── data/                     # SidebarCache (1h TTL JSON file)
│   ├── voice/                    # VAD, TTS, AudioCapture, Whisper, foreground svc
│   │   └── jni/WhisperJni.kt
│   ├── notifications/
│   ├── viewmodels/               # AppVM, NavigationVM, ChatVM, FileBrowserVM
│   └── ui/                       # Jetpack Compose screens + theme
│       ├── theme/
│       ├── nav/                  # Sidebar
│       ├── chat/                 # ChatScreen, MessageBubble, PromptInputBar
│       ├── messageblocks/        # Tool/Result/Thinking/Permission/Image cards
│       ├── filebrowser/
│       └── settings/
├── cpp/
│   ├── CMakeLists.txt
│   └── whisper_jni.cpp           # JNI bridge to whisper.cpp
└── res/                          # icons, strings, themes
```

## API endpoints used

Same surface as the iOS app — see `apps/agor-ios/README.md` on the
`add-iphone-native-app` branch of upstream for the canonical list. Key routes:

| Endpoint | Purpose |
|---|---|
| `POST /authentication` | Login (email/password) |
| `POST /authentication-refresh` | Token refresh |
| `GET /users/me` | Current user |
| `GET /boards` | Boards |
| `GET /worktrees?board_id=…` | Worktrees per board |
| `GET /sessions?worktree_id=…` | Sessions per worktree |
| `GET /messages?session_id=…&$limit=…&$skip=…` | Paginated messages |
| `POST /sessions/:id/prompt` | Send a prompt |
| `POST /sessions/:id/permission-decision` | Approve / deny tool use |
| `POST /sessions/:id/input-response` | Answer agent question |
| `POST /sessions/:id/stop` | Stop running session |
| `PATCH /sessions/:id` | Archive / update |
| `GET /file?worktree_id=…` | File list |
| `GET /file/:path?worktree_id=…` | File content |
| `GET /health` | Reachability probe |

## WebSocket events

| Event | Purpose |
|---|---|
| `sessions patched` | Session status changes |
| `tasks created` / `tasks patched` | Task lifecycle |
| `messages created` / `messages patched` | Message updates / final text |
| `messages streaming:start/chunk/end/error` | Live streaming |
| `messages thinking:chunk` | Thinking pane updates |

After the transport connects, the client emits `create authentication` with the JWT
to join Feathers' authenticated channel — without this, real-time events do not flow.
