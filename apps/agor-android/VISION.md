# Agor Android — Vision

The Android app today is a native client to a self-hosted Agor daemon.
The plan below is where we want it to be: not "Agor on the phone", but the
**single mobile front-end** that ties three systems together so the user has
one place to think, talk, and act.

---

## The three tissues we're connecting

```
                        ┌──────────────────────────────────────┐
                        │            Android app               │
                        │   (this codebase, voice + text)      │
                        └──────────────────────────────────────┘
                                        │
                                  routes to
                                        │
                ┌───────────────────────┴───────────────────────┐
                │                                               │
                ▼                                               ▼
        ┌───────────────┐                              ┌────────────────┐
        │    Hermes     │  ─── reads / writes ───────▶│  AnythingLLM   │
        │  orchestrator │                              │  (RAG memory)  │
        │     agent     │                              └────────────────┘
        └───────┬───────┘
                │
       drives via MCP
                │
                ▼
        ┌───────────────┐
        │     Agor      │  (boards, worktrees, sessions, agents)
        │   daemon(s)   │
        └───────────────┘
```

1. **Agor** — substrate. Already exists. Boards × worktrees × sessions ×
   agents (Claude Code / Codex / Gemini). Real work happens here.
2. **Hermes** — the orchestrator. The user's daily driver inside the Android
   app. Talks to Agor through Agor's own MCP server, summarizes work across
   boards, decides which session to spawn or prompt next.
3. **AnythingLLM** — long-term memory. Self-hosted RAG. Hermes reads from
   it before responding and writes to it whenever something is worth
   remembering (session reports, decisions, daily journal).

The Android app is the only client a user needs in their pocket. Everything
else is a service Hermes calls on the user's behalf.

---

## Why this shape

Each of the three plays a different role we keep wanting and currently
splice together by hand:

| Need | What plays this role | Why nothing else fits |
|---|---|---|
| Doing work | Agor | The whole point of Agor — supervising AI sessions |
| Choosing what to do | Hermes | An agent per Agor session is too narrow; we need someone above |
| Remembering | AnythingLLM | Agor's DB is operational, not durable knowledge |

Hermes by itself isn't useful — it has no hands. Agor by itself loses the
plot between sessions. AnythingLLM by itself is an inert document store.
Together they're a real workflow.

---

## Layer 1 — Agor (where we already are)

No change to the substrate. The Android app already speaks the daemon's
REST + Socket.IO API and renders boards, worktrees, sessions, messages,
permission cards, file browser, voice mode.

What stays here:
- Direct session views (when the user explicitly opens one)
- Permission/approval cards
- File browser
- Manual prompt entry per session

What changes: this stops being the **default** view. New users land on
Hermes; Agor screens are reached "downward" from a Hermes conversation.

---

## Layer 2 — Hermes (the new piece)

Hermes is *one named agent* the user converses with. It's not a chatbot;
it has tools and runs work on the user's behalf.

### Where Hermes lives

We have three options. Recommended: **A**, escalate later if it pinches.

**A. Hermes is an Agor session in a dedicated worktree (`worktrees/hermes`).**
- Pros: zero new infrastructure. Reuses Agor auth, MCP, SDK plumbing,
  permission UI, message streaming, the whole thing. Scheduling Hermes is
  the same as scheduling any Agor session.
- Cons: we pay Agor's "session" abstraction (a session is supposed to be
  about a worktree of code) for something that is more like a control
  tower. We'll likely want the worktree to be empty or hold prompt/persona
  files only.

**B. Hermes is a standalone process (its own daemon).**
- Pros: clean separation; can be hosted elsewhere; can talk to multiple
  Agor instances.
- Cons: a second daemon to operate. Reimplements auth, streaming, model
  invocation. Worth it only after A clearly hits a wall.

**C. Hermes runs in-app, on-device (Android invokes the SDK directly).**
- Pros: no backend cost; works offline-ish.
- Cons: no shared state across devices, no background work, no scheduled
  runs, model keys live on the phone. Reject.

**Decision rule for switching A → B:** when Hermes needs to react to events
the user *isn't* watching (e.g. wake up at 8am and brief them, or react to
a webhook), and we feel ourselves bending Agor's session lifecycle to make
that work.

### How Hermes controls Agor

Through Agor's **own MCP server** (`apps/agor-daemon/src/mcp/server.ts`).
The user authorizes the Hermes session to use the `agor_*` tool surface.
That gives Hermes:
- `agor_sessions_*` — list / spawn / fork / prompt / stop
- `agor_worktrees_*` — list and (if exposed) create
- `agor_boards_*` — list, query
- `agor_messages_*` — read what other sessions are saying

Audit pass needed: list which Agor primitives are *not* MCP-exposed today
and decide which Hermes needs. Most likely additions:
- `agor_boards_pin_session` (set a session as "currently watched")
- `agor_reports_get` (pull the report markdown from a finished session)
- `agor_worktrees_status` (git state, dirty files)

### Permission posture

Hermes runs with one of:
- `unix_user_mode: simple` (everything as the daemon user — fine for personal)
- `unix_user_mode: strict` (Hermes session attributed to the user, runs as their UNIX account)

Tool-permission mode for Hermes itself is `default` (asks for everything)
on day one, with a curated allow-list rolled in over time:
- always allow `agor_sessions_list`, `agor_messages_list`, `agor_reports_get`
- ask once-per-session for `agor_sessions_spawn`, `agor_sessions_prompt`
- never auto-allow filesystem or shell tools — those are still per-child-session

### Hermes UI in the Android app

A new top-level destination: **Hermes**.
- Replaces `EmptyHome` as the post-login landing screen.
- A single conversation thread (Hermes is one continuous session — not
  per-board).
- Voice button is the primary input. Text input is secondary.
- Below the conversation, an "active work" strip shows running Agor sessions
  Hermes spawned, with a tap-to-open into the existing ChatScreen.
- Drawer gains a "Boards" group for the manual descent into raw Agor.

### Hermes persona

Lives in a checked-in file (e.g. `apps/agor-android/hermes/persona.md` or in
the daemon config) so it's source-controlled. Defines:
- Tone, defaults, escalation rules
- "Always do X without asking", "Always ask before Y"
- Memory write policy (what to commit to AnythingLLM, what to keep ephemeral)

---

## Layer 3 — AnythingLLM (memory)

AnythingLLM exposes a documented HTTP API (workspaces, document upload,
chat, query). It's already self-hosted, so we just need to configure the
URL + API key on the Agor side and wire the read/write paths.

### What gets stored

Two write paths: automatic and explicit.

**Automatic (hooked into the daemon):**
- Every Agor session report on completion → workspace document
- Daily summary written by Hermes at end-of-day → workspace document
- Approved permission decisions ("user said yes to X tool on Y session") if
  we want to learn user preferences over time

**Explicit (Hermes tool calls):**
- `memory_remember(text, tags)` — Hermes saves a snippet ("user prefers
  bundled PRs over split-stack PRs in this repo")
- `memory_link(session_id)` — pull a session's context into long-term memory

### What gets retrieved

One MCP tool exposed to Hermes (and only Hermes by default):
- `memory_query(question, workspace?)` — wraps AnythingLLM's
  `/api/v1/workspace/:slug/chat` or the simpler `/query` endpoint
- Returns top-K retrieved chunks + sources

Hermes is expected to call this *before* answering anything that could
plausibly have history. In practice we'd put that in the persona prompt.

### Where it plugs in

Two integration points:

1. **Daemon-side** — new `apps/agor-daemon/src/integrations/anythingllm/`
   that owns the HTTP client, retry policy, secrets. Exposed both:
   - As an MCP tool surface (so Hermes can call it)
   - As event hooks (so session-completed → upload report runs daemon-side
     without a Hermes round-trip)
2. **Config** — new `~/.agor/config.yaml` block:
   ```yaml
   anythingllm:
     enabled: true
     base_url: https://anythingllm.internal
     api_key_env: ANYTHINGLLM_API_KEY
     workspaces:
       default: agor
       hermes: hermes-memory
   ```

### Workspace strategy

Don't try to be clever on day one. One workspace per "scope of memory":
- `agor` — operational (every report, every task summary)
- `hermes` — Hermes' own notes about user preferences and patterns
- per-project workspaces only when a single project's memory grows large
  enough that `agor` retrieval pulls in noise

---

## Phased rollout

The phases are independent enough that we can stop at any of them and have
a useful product.

### Phase A — Polish the current Android app *(in flight)*
Goal: parity with iOS, no regressions, deployable APK in the user's hand.
- ~~First APK build, CI, Nix flake target~~
- ~~Cold-launch notification deep-link~~
- Catch up on the recent iOS commits worth porting:
  - silent JWT re-auth on 401 (replace the "soft logout" path)
  - audio-level visualization improvements
  - voice state-machine fixes (no listening during agent runs)
  - animated GIF rendering in chat (Coil's `gif` decoder, opt-in dep)
  - Silero VAD via ONNX Runtime as upgrade from the energy VAD (parity
    with iOS's FluidAudio adoption)
  - crash-log capture + attach-to-chat menu

Exit criterion: a normal day's use of Agor on the phone is not painful.

### Phase B — Hermes MVP
Goal: replace `EmptyHome` with a real Hermes conversation that can drive
Agor. Single user, single Agor instance.
- Reserve `worktrees/hermes` and a Hermes Agor session
- Add `HermesScreen` as the post-login default destination
- Curate the MCP allow-list for Hermes (read-mostly initially)
- Voice-first input, same VAD/STT/TTS stack as the existing chat screen
- Below-the-fold strip: live status of sessions Hermes is watching
- Persona file checked in

Exit criterion: "tell Hermes to spawn a session in repo X to fix bug Y, and
have it report back when done" works end-to-end.

### Phase C — AnythingLLM read path
Goal: Hermes' answers are informed by what we already know.
- Daemon: `integrations/anythingllm` HTTP client + config
- Daemon: `memory_query` MCP tool, exposed only to sessions whose persona
  includes a memory grant (default: just Hermes)
- Persona update: "before answering anything substantive, query memory"

Exit criterion: starting a fresh Hermes conversation, it correctly recalls
a fact from a prior session (e.g. "we decided last week to skip Redis").

### Phase D — AnythingLLM write path
Goal: nothing important is forgotten.
- Daemon hook: session completed → POST report to AnythingLLM
- Hermes tool: `memory_remember` for explicit saves
- Hermes nightly job: end-of-day summary, written to workspace

Exit criterion: a week of use produces a usable searchable history without
the user doing anything special.

### Phase E — Multi-device + sharing *(optional, much later)*
Only if Hermes becomes valuable enough to be worth sharing.
- Multiple users can converse with the same Hermes (still one session,
  Agor handles concurrent prompters already)
- Or: each user has their own Hermes that points at a shared `agor`
  workspace and a private one
- Wear OS / Android Auto for voice-only access

---

## Architecture choices, locked in vs open

### Locked in
- The substrate is Agor; we don't reimplement boards/sessions/worktrees.
- Hermes is *an agent*, not bespoke server code.
- Memory lives in AnythingLLM, not in a new bespoke vector DB.
- Android remains a thin native client over Agor's REST + Socket.IO API.

### Still open
- **Hermes model.** Claude Sonnet (cost) vs Opus (capability) vs a local
  model for privacy. Likely Sonnet for routine, Opus for hard judgment;
  Hermes itself can pick per turn.
- **Push notifications.** Right now we surface session-finished events as
  local notifications via socket presence. For a Hermes-driven flow we
  may want server-pushed notifications (FCM or a self-hosted gateway).
- **Privacy posture for AnythingLLM.** Self-hosted, on Tailscale, but we
  haven't decided on encryption-at-rest or backup policy yet.
- **Memory hygiene.** What never goes in (secrets, raw conversations
  with sensitive content)? A redaction step on the write path?
- **Whether Hermes is one or many.** A single Hermes today, but per-domain
  Hermeses later (work / personal / one per repo) is a natural extension.

### Explicit non-goals
- Building a generic chatbot UI. The whole value is the Agor + AnythingLLM
  glue; without those, the app is just another Claude wrapper.
- Replacing the web UI. The phone app is for the "talk and steer"
  workflow; the desktop UI keeps owning the deep canvas/board view.
- On-device LLM inference. Nice in theory; expensive in practice.
  Voice-mode STT (whisper.cpp) stays on-device; everything else is API.

---

## What this means for the next few branches

The Android app is the right place to start because it's the user's
on-ramp. Concretely, the next merge-worthy units of work are:

1. Finish the Phase-A iOS-parity catch-up so the app is durable.
2. Audit Agor's MCP surface and fill the gaps Hermes will need
   (probably 5–10 new tool handlers).
3. Stand up `worktrees/hermes` and the Hermes Agor session as a one-off
   experiment, with the Android `HermesScreen` behind a feature flag
   (`hermes.enabled` in user config).
4. Wire AnythingLLM as a daemon integration with **read only** to start.

That order keeps every step shippable on its own and never blocks the
existing single-session flow.
