1. **Fix `loadConfig` bottleneck in `applyWorktreeCreateDefaults`**
   - Use `replace_with_git_merge_diff` on `apps/agor-daemon/src/services/worktrees.ts` to update the imports, modify `applyWorktreeCreateDefaults` to accept `config`, and update `create` to load config once.
   ```
<<<<<<< SEARCH
import { ENVIRONMENT, isWorktreeRbacEnabled, loadConfig, PAGINATION } from '@agor/core/config';
=======
import { type AgorConfig, ENVIRONMENT, isWorktreeRbacEnabled, loadConfig, PAGINATION } from '@agor/core/config';
>>>>>>> REPLACE
<<<<<<< SEARCH
  private async applyWorktreeCreateDefaults(data: Partial<Worktree>): Promise<Partial<Worktree>> {
    const config = await loadConfig();
    const defaults = config.worktrees;
=======
  private applyWorktreeCreateDefaults(data: Partial<Worktree>, config: AgorConfig): Partial<Worktree> {
    const defaults = config.worktrees;
>>>>>>> REPLACE
<<<<<<< SEARCH
  async create(
    data: Partial<Worktree> | Partial<Worktree>[],
    params?: WorktreeParams
  ): Promise<Worktree | Worktree[]> {
    if (Array.isArray(data)) {
      const withDefaults = await Promise.all(
        data.map((item) => this.applyWorktreeCreateDefaults(item))
      );
      return super.create(withDefaults, params) as Promise<Worktree[]>;
    }
    const withDefaults = await this.applyWorktreeCreateDefaults(data);
    return super.create(withDefaults, params) as Promise<Worktree>;
  }
=======
  async create(
    data: Partial<Worktree> | Partial<Worktree>[],
    params?: WorktreeParams
  ): Promise<Worktree | Worktree[]> {
    const config = await loadConfig();
    if (Array.isArray(data)) {
      const withDefaults = data.map((item) => this.applyWorktreeCreateDefaults(item, config));
      return super.create(withDefaults, params) as Promise<Worktree[]>;
    }
    const withDefaults = this.applyWorktreeCreateDefaults(data, config);
    return super.create(withDefaults, params) as Promise<Worktree>;
  }
>>>>>>> REPLACE
   ```

2. **Verify changes**
   - Run `run_in_bash_session` with `pnpm -w run lint:fix` to ensure code formatting.
   - Run `run_in_bash_session` with `NODE_OPTIONS="--max-old-space-size=4096" pnpm --filter @agor/daemon test` to ensure tests pass.

3. **Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.**
   - Call `pre_commit_instructions` tool to complete standard testing.

4. **Submit**
   - Call `submit` with Title: `⚡ Bolt: Optimize worktree bulk creation`, PR message, and appropriate branch name.
