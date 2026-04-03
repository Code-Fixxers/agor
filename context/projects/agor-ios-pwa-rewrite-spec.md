# Agor iOS → Agor PWA Rewrite Specification

## Status

- **Authoring date:** April 2, 2026
- **Target:** Replace platform-specific iOS client with a standards-based Progressive Web App built on `apps/agor-ui`.
- **Source baseline:** `apps/agor-ios` from commit `2d193f2d8597a74f2cbf2cc673f5f976bef9c79b` in `maroun2/agor`.

## Goals

1. Preserve all high-value iOS workflows (navigation, chat, approval flows, file browsing, session management).
2. Reuse and extend the existing Agor React UI so behavior stays aligned with desktop/web releases.
3. Deliver installable app behavior on iOS, Android, macOS, Windows, and Linux without maintaining separate native codebases.
4. Keep Feathers REST + WebSocket APIs unchanged.

## Non-goals

- No backend API redesign for the migration.
- No attempt to fully replicate native-only device integrations (for example, deep iOS-only system APIs).
- No parallel long-term maintenance of a separate SwiftUI frontend.

## Feature Parity Matrix (iOS baseline → PWA target)

### 1) Navigation & Information Architecture

- **iOS baseline:** Sidebar tree (board → worktree → session), “Important Sessions”, “Needs Attention”.
- **PWA target:**
  - Keep worktree-centric navigation model from current Agor UI.
  - Mobile route `/m` remains primary compact mode.
  - Add fast filters for running/attention/favorites in mobile nav drawer.

### 2) Chat + Streaming

- **iOS baseline:** Markdown rendering, tool blocks, thinking blocks, task grouping, pagination.
- **PWA target:**
  - Reuse `ConversationView` and existing rich block renderers.
  - Keep streaming-first behavior and promote collapsible long output defaults on mobile.
  - Ensure prompt drafts persist across session switches.

### 3) Permission & Input Requests

- **iOS baseline:** Inline approval/deny and inline question responses with attention affordance.
- **PWA target:**
  - Keep existing inline blocks in conversation timeline.
  - Add mobile sticky “needs input/permission” shortcut that scrolls to pending block.

### 4) Session Operations

- **iOS baseline:** Archive, reset, run-state iconography.
- **PWA target:**
  - Expose archive/reset/favorite actions in mobile row context actions.
  - Keep status pills/icons shared with desktop UI.

### 5) File Browser

- **iOS baseline:** Virtual tree + text/image preview.
- **PWA target:**
  - Use existing file browsing APIs and renderers.
  - Add mobile-first file navigation path from session toolbar and worktree actions.

### 6) Notifications & Recovery

- **iOS baseline:** Local notifications for completion/attention, reconnect UX.
- **PWA target:**
  - Web Notifications API (opt-in) for completion and attention events.
  - Service worker for app-shell caching + resumable launch.
  - Reconnect banner and refresh pass on app foreground/focus.

## PWA Technical Requirements

1. **Installability**
   - Web app manifest with app identity, icons, theme/background colors, standalone display.
   - `beforeinstallprompt` capture and user-facing install CTA.
   - iOS “Add to Home Screen” guidance in Settings/About when prompt event is unavailable.

2. **Offline/Resilience**
   - Service worker app-shell caching (`index.html`, static assets, manifest).
   - Navigation fallback to cached shell when offline.
   - Network-first strategy for API requests and live data.

3. **Mobile UX Performance**
   - Initial interaction under 3 seconds on mid-tier mobile devices (warm daemon).
   - Keep heavy list rendering virtualized where possible.
   - Minimize layout shift on reconnect/update states.

4. **Security**
   - Require HTTPS in production (service workers + install + notifications).
   - Preserve token handling and auth behavior already used by web client.

## Implementation Plan

### Phase 0 — Foundation (done in this change)

- Add manifest and icon assets.
- Add service worker registration and shell cache behavior.
- Add mobile install CTA banner in the PWA UI.

### Phase 1 — Feature Completion

- Add quick “Important” and “Needs Attention” sections to mobile nav.
- Add mobile session context actions (favorite/archive/reset).
- Add notification permission UX and cross-session completion toasts.

### Phase 2 — iOS Decommission

- Freeze `apps/agor-ios` to archived status.
- Update docs to position `apps/agor-ui` PWA as canonical mobile app.
- Remove native release pipeline after a stabilization window.

## Acceptance Criteria

- Agor installs as a standalone app on iOS Safari (A2HS), Android Chrome, and desktop Chromium.
- Mobile user can:
  - browse board/worktree/session hierarchy,
  - stream and send prompts,
  - approve permissions and answer questions,
  - open file browser,
  - recover from connection interruptions.
- No server API changes required for parity.

## Risks

1. **iOS PWA limitations** (install prompt/event and background behavior vary by Safari version).
2. **Notification consistency** across browsers.
3. **Large conversation rendering cost** on lower-end devices.

## Mitigations

- Provide browser-specific install guidance fallback.
- Keep in-app reconnect and attention UX strong even when background notifications are limited.
- Use collapsible blocks and pagination defaults aggressively on mobile.

