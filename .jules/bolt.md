## 2024-05-20 - [Performance] Optimized `syncUserSymlinks` N+1 Query in UnixIntegrationService
**Learning:** Checking ownership inside a `for` loop across globally returned worktrees leads to N+1 `isOwner` database queries, which causes significant performance degradation as the total number of worktrees increases.
**Action:** Replace the iterative querying approach with a direct `INNER JOIN` in the database repository (e.g., `findOwnedWorktrees`) to fetch only the necessary data in a single operation. This approach provided an ~850x speed increase for 1000 items in a benchmark.
