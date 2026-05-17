# Hermes Connected Chat App Implementation Plan

> Spec: `docs/superpowers/specs/2026-05-17-hermes-connected-chat-app-design.md`

## Goal

Move the Rust Android/Web Agor client toward the approved Hermes-connected chat design: dark compact workspace, left icon rail, persistent object list, chat-first main pane, and reusable visual tokens for future Hermes-connected apps.

## Constraints

- Keep the existing Rust/Dioxus architecture and runtime behavior.
- Make the web app usable in the in-app browser for fast iteration.
- Avoid adding fake navigation or inert app features beyond existing Agor/Hermes surfaces.
- Verify with a Rust/web build and browser inspection.

## Task 1: Load App CSS In Web

Files:
- `apps/agor-rust-android/apps/agor-hermes/src/main.rs`

Steps:
- Add a Dioxus asset reference for `/assets/main.css`.
- Render `document::Stylesheet` for both authenticated and unauthenticated states.
- Browser-check that the login and app shell are no longer raw unstyled HTML.

## Task 2: Replace Bottom Tabs With Hermes Rail

Files:
- `apps/agor-rust-android/apps/agor-hermes/src/main.rs`
- `apps/agor-rust-android/crates/agor/src/ui/app_shell.rs`

Steps:
- Replace the bottom tab bar with a compact left rail.
- Keep Agor as the default primary surface.
- Keep Hermes chat and Hermes setup reachable from the rail.
- Make the Agor surface a two-column workspace: object list plus main chat pane.

## Task 3: Apply Hermes Design Tokens And Surfaces

Files:
- `apps/agor-rust-android/apps/agor-hermes/assets/main.css`
- `apps/agor-rust-android/crates/agor/src/ui/sidebar.rs`
- `apps/agor-rust-android/crates/agor/src/ui/chat/prompt_input.rs`

Steps:
- Replace the Material-like visual theme with Hermes-style tokens: near-black base, elevated panels, subtle dividers, rose accent, compact radii.
- Restyle the Agor sidebar as the object list.
- Restyle chat bubbles, composer, empty states, buttons, cards, and Hermes setup.
- Keep mobile responsive behavior with the rail and list stacking cleanly.

## Task 4: Verify

Commands:
- `cd apps/agor-rust-android && ./scripts/web.sh build --locked`
- `pnpm biome check apps/agor-rust-android/apps/agor-hermes/assets/main.css apps/agor-rust-android/apps/hermes-only/assets/main.css`

Browser:
- Open the local Dioxus dev URL in the in-app browser.
- Confirm CSS loads, the rail/list/main layout appears, and Agor/Hermes surfaces are reachable.

## Task 5: Commit And Push

Steps:
- Review `git diff`.
- Commit the verified implementation.
- Push the current branch.
