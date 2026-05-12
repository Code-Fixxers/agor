# Agor Android Manual QA

Run these checks on a debug APK against a live Agor daemon. Keep the daemon and UI
watch processes under the user's control; this script is for device behavior only.

## Lifecycle and Background Recovery

- Sign in, open a favorite session, and confirm the drawer, chat title, and socket
  indicator load.
- Start a prompt, press Home while the session is running, wait until the daemon
  reports the session idle, then reopen Agor. Confirm the app reconnects, refreshes
  navigation, refreshes the current chat, and does not leave stale streaming text.
- Repeat with a session entering awaiting-permission and awaiting-input states.
  Confirm a foreground snackbar appears after return and the attention banner jumps
  to the newest pending card.
- With notifications allowed, favorite a session, background Agor, let it move from
  running to idle, and confirm one system notification deep-links to that chat.
- Repeat the same transition with a non-favorite session. Confirm it does not emit
  a running-to-idle system notification, matching the favorites-only policy.
- Lock the app with biometric unlock enabled, background/foreground it, and confirm
  the socket disconnects while locked and reconnects after successful unlock.

## UI Smoke Script

- Login: connect with password credentials, sign out, reconnect with a personal API
  key, and verify the saved server profile can be switched from the drawer.
- Drawer navigation: expand boards/worktrees, search by session title and ID,
  toggle 7-day/30-day/all filters, favorite a session, and create a session from a
  worktree.
- Chat prompt: send a text prompt, stop a running session, rename it, change
  permission mode, archive it, reset it, and verify the reset opens a fresh session.
- Attachments: attach a document, gallery image, camera capture, app logs, and crash
  log when available. Confirm uploaded path chips appear and `{filepath}` notify
  prompts resolve to worktree paths.
- File browser: open a worktree, preview text, image, GIF/WebP, and use a chat path
  link to jump directly to the matching file.
- Settings: edit server profiles, default/delete a profile, export logs, send logs
  to the current session, clear logs, update the Whisper endpoint, configure Hermes,
  and check the update row.
- Hermes: create/open a Hermes conversation, send text/image/file/log attachments,
  retry a failed turn, resume queued foreground work, and verify streamed responses.
- Voice permission flow: deny and then grant microphone permission for prompt
  dictation, confirm live transcript/audio level phases, then run regular-session
  continuous voice if enabled for the session.

Record device model, Android version, APK version/Git SHA, daemon URL, and whether
the test was Wi-Fi, VPN, or emulator before filing issues.
