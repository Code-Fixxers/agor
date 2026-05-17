# Hermes Connected Chat App Design System

## Purpose

Define a reusable UI language and shell pattern for chat-first apps connected to
Hermes, Agor, and future agent/chat backends.

This spec generalizes the approved **B: Hermes Shell + Agor Chat** direction:
future apps should share the same visual grammar, navigation structure, and chat
surface behavior while keeping their domain-specific data models and backend APIs.

## Product Principle

These apps are not marketing sites and should not feel like generic mobile chat
skins. They are compact agent consoles for repeated daily work:

- fast scanning,
- dense but calm information,
- visible session/workspace context,
- clear tool and permission states,
- one primary chat surface that can host different backends.

## Core Layout

Every connected chat app should be composed from three persistent regions.

### 1. Rail

The rail is a narrow vertical navigation strip for top-level surfaces.

Use it for:

- Chat / sessions,
- workspaces / worktrees / projects,
- files or artifacts,
- integrations,
- settings.

Behavior:

- active item uses a softly filled rounded square,
- icons are compact and mostly monochrome,
- labels are avoided in the rail itself,
- settings sits at the bottom when present.

### 2. Object List

The object list sits next to the rail and shows the domain's browsable objects.

Examples:

- Hermes: conversations, assignment filters, prompt queues,
- Agor: boards, worktrees, sessions, tasks,
- future apps: channels, agents, notebooks, repos, environments.

Required elements:

- section title,
- search/filter input,
- pill filters,
- grouped rows with small metadata,
- empty/loading/error states in-place.

Rows should be compact, readable, and optimized for scanning. Avoid card-heavy
layouts here.

### 3. Main Chat Surface

The main pane hosts the selected conversation or task.

Required elements:

- topbar with selected context and secondary status,
- scrollable message/tool area,
- docked rounded composer,
- optional inline banners for read-only modes, permissions, or connection state.

The main pane is the embed boundary: Hermes chat, Agor session chat, and future chat
clients should all be able to occupy this region without changing the outer shell.

## Visual Language

Use a shared dark console palette:

- base surface: near-black,
- elevated surface: subtly warmer dark gray,
- separator: low-contrast gray-purple borders,
- accent: muted rose/pink for active and primary affordances,
- success/warning/error: restrained semantic colors, never full-saturation blocks.

Recommended token direction:

- `--surface-base`: `#090911`
- `--surface-panel`: `#101015`
- `--surface-raised`: `#17151b`
- `--border-subtle`: `#27242c`
- `--border-strong`: `#302d35`
- `--text-primary`: `#f5eef3`
- `--text-secondary`: `#b7aeb8`
- `--text-muted`: `#8f8791`
- `--accent`: `#d6adc4`
- `--accent-muted`: `#735d69`

Typography:

- compact sans-serif for UI,
- monospace only for code/tool payloads,
- no oversized headings inside the app shell,
- no negative letter spacing.

Shape:

- rail active: 8-10px radius,
- list controls: 8px radius,
- pills: full radius,
- composer: 16px radius,
- modals/cards/tool panels: 10-14px radius.

## Shared Components

Future apps should converge on these reusable component concepts.

### Shell

`ConnectedChatShell` owns the rail, object list, and main pane layout. It should not
own domain data. Each app provides surface definitions, list content, and main pane
content.

### Rail Item

Compact icon button with active state, disabled state, and tooltip-ready label.

### Object List

Composable list with:

- header,
- search,
- filter pills,
- grouped rows,
- status metadata,
- optional create action.

### Chat Topbar

Shows current chat/task/session title, secondary context, badges, and compact action
buttons.

### Message Stack

Vertical content region for:

- user/assistant/system messages,
- streamed assistant text,
- thinking/reasoning panels,
- tool-use panels,
- tool-result panels,
- file/image attachments,
- permission cards,
- input-request cards.

### Composer

Rounded docked prompt input with:

- attach button,
- textarea,
- optional mode/action buttons,
- send button,
- disabled/read-only state.

The composer should be backend-agnostic. Apps pass callbacks for prompt submission,
attachments, voice, or mode toggles.

## Embedding Model

The shell should make chat embedding explicit:

```text
ConnectedChatShell
  Rail
  ObjectList
  MainPane
    ChatSurface backend="agor-session" | "hermes" | "future-client"
```

Each `ChatSurface` implementation must provide:

- title and context metadata,
- message rows,
- composer state,
- submit handler,
- loading/error/read-only state,
- optional tool/permission/input cards.

Backends should not leak layout decisions into the shell. The shell supplies the
frame; the chat surface supplies content and behavior.

## Agor First Pass

For the current Rust Agor app, the first implementation should:

- load CSS reliably in the web sideview,
- convert the authenticated app from bottom tabs/drawer to rail + object list + main
  pane,
- style login, sidebar, empty state, chat topbar, messages, tool panels, permission
  cards, input-request cards, and composer with this language,
- keep existing Agor state, networking, worktree/session models, and prompt behavior.

Hermes remains a separate surface initially. Later, its chat screen can be mounted
inside the same main pane contract.

## Out Of Scope

- Replacing Agor or Hermes data models.
- Adding new daemon APIs.
- Building a full cross-app Rust component crate before the first Agor pass.
- Designing every future connected app now.
- Mobile gesture polish beyond responsive sideview support.

## Quality Bar

The UI should pass these checks:

- visual parity with Hermes shell language,
- no raw unstyled HTML in web,
- no overlapping text at the sideview viewport,
- readable object list at narrow widths,
- composer remains docked and usable,
- tool/permission/input cards remain actionable and legible.

## Verification For Agor Implementation

Before finishing implementation work:

- `./scripts/web.sh build --locked` in `apps/agor-rust-android`
- `cargo test --workspace` in `apps/agor-rust-android`
- root `pnpm build`
- root `pnpm test`
- root `pnpm lint`
- browser verification at `http://127.0.0.1:6173`
