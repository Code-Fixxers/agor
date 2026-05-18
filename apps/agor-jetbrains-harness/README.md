# Agor JetBrains Browser Harness

This is a local browser harness for validating the JetBrains Agor plugin flow in
the Codex sideview browser. It does not replace IntelliJ Platform tests or a
real IDE run. It gives agents a fast visual target for the plugin's navigation,
conversation loading, copyable chat text, and streaming update behavior.

Run it with:

```bash
pnpm --filter @agor/jetbrains-harness dev
```

Then open the printed localhost URL in the sideview browser.

The harness uses deterministic fixtures and mirrors the plugin's current shell:

- board -> worktree -> session navigation
- previous conversation loading on session open
- start/end conversation scroll controls
- selectable chat text
- prompt send with live streaming chunks
- background socket refresh that preserves the selected session
- horizontal/vertical layout switching

Build and test:

```bash
pnpm --filter @agor/jetbrains-harness build
pnpm --filter @agor/jetbrains-harness test
```
