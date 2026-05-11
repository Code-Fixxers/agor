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
./gradlew :app:assembleDebug

# Install over USB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or use the wrapper:
./deploy.sh
```

For the emulator, Agor's daemon at `http://localhost:3030` on the host is reachable
from the emulator at `http://10.0.2.2:3030`.

### NixOS / Nix

The repo flake defines a fully-pinned Android build environment (SDK 35,
build-tools 35.0.0, NDK 27.1.12297006, CMake 3.22.1, JDK 17). On NixOS:

```bash
# One-shot build → drops agor-android-debug-<sha>.apk in the repo root
nix run .#build-agor-android-apk

# Or drop into a dev shell with all toolchains on PATH
nix develop .#android
cd apps/agor-android
./gradlew :app:assembleDebug
```

Set `SKIP_WHISPER=1` before `nix run` to skip vendoring `whisper.cpp` (faster
build, local voice transcription is unavailable unless remote Whisper is configured).

### CI (GitHub Actions)

Every push to `main` and every PR touching `apps/agor-android/**` (or this
workflow file) triggers `.github/workflows/build-android-apk.yml`, which builds a
debug APK and uploads it as a downloadable artifact named
`agor-android-debug-<short-sha>`. Open the Actions run, scroll to **Artifacts**,
download the zip, then `adb install -r` the APK inside.

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
./gradlew :app:assembleDebug
```

If `SKIP_WHISPER=1` is set or the local assets are otherwise unavailable, the
JNI library still compiles as a no-op stub and the voice UI reports local
transcription as unavailable. Settings default remote transcription to
WhisperLiveKit at `http://100.101.157.56:8090`, using `/v1/listen` for live
streaming and `/v1/audio/transcriptions` for final/fallback transcription.

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
