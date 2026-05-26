1. **Identify the Bottleneck**:
   - `loadConfig()` reads and parses the `~/.agor/config.yaml` file on every single call.
   - In `apps/agor-daemon/src/services/worktrees.ts`, the `create` method processes bulk inputs using `Promise.all(data.map(item => this.applyWorktreeCreateDefaults(item)))`.
   - Since `applyWorktreeCreateDefaults` internally calls `await loadConfig()`, this triggers N simultaneous disk I/O reads (N = number of items). This can cause major performance issues when processing large arrays.

2. **Implement the Optimization**:
   - Instead of calling `await loadConfig()` inside `applyWorktreeCreateDefaults` (which gets called in the map loop), we can load the config **once** outside the loop in the `create` method.
   - Update `applyWorktreeCreateDefaults` to accept the loaded `config` as an optional parameter (or always pass it from `create`).
   - If not passed, default to loading it once (to support other places that might use it).
   - We will modify `apps/agor-daemon/src/services/worktrees.ts`.

3. **Verify**:
   - Run tests for `@agor/daemon`.
   - Run linter/formatter.

4. **Pre-commit**:
   - Complete pre commit steps to ensure proper testing, verification, review, and reflection are done.

5. **Create PR**:
   - Branch: `perf/worktree-bulk-create`
   - Title: `⚡ Bolt: Optimize bulk worktree creation by hoisting loadConfig`
   - PR description explaining the N disk I/O reduction.
