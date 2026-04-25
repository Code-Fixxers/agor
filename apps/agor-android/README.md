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
| ASR | whisper.cpp via NDK/JNI; `SpeechRecognizer` fallback |
| Voice service | Foreground service (`microphone | mediaPlayback`) |

Min SDK 28 (Android 9), target SDK 35 (Android 15).

---

## Prerequisites

* **Android Studio Ladybug or newer**, or a CLI toolchain with:
  * JDK 17
  * Android SDK platform 35 + build-tools 35.0.0
  * NDK 27.x (for whisper.cpp, only needed if you want on-device transcription)
  * `cmake 3.22.1+`

The first build will download AGP 8.7.x, Gradle 8.11.1, and Compose BOM 2024.12.

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

## On-device transcription (optional)

By default, voice mode uses Android's built-in `SpeechRecognizer`. For private,
on-device transcription via whisper.cpp:

```bash
# 1. Vendor whisper.cpp into the source tree
cd apps/agor-android
scripts/sync-whisper.sh

# 2. Download a ggml model (≈140MB for base.en)
scripts/fetch-whisper-model.sh base.en

# 3. Push the model to the device's app storage
adb push app/src/main/assets/whisper/ggml-base.en.bin /sdcard/Android/data/live.agor.app.debug/files/whisper/

# 4. Rebuild — the NDK toolchain will pick up the source tree
./gradlew :app:assembleDebug
```

If `whisper.cpp/` isn't present at build time, the JNI library still compiles as a
no-op stub and the app falls back to the platform recognizer at runtime — no
behaviour change for users without on-device support.

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
