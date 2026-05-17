## 2025-02-14 - ⚡ Bolt: N+1 Query in Unix Integration Service
**Learning:** Looping over database records to fetch children queries leads to N+1 query problems. In the case of mapping Worktrees to their owners, calling `worktreeRepo.getOwners()` for each worktree is highly inefficient.
**Action:** Use batched operations with parameterized arrays (like `IN` clauses) and a bulk mapper (like `bulkLoadOwners`) instead of individual fetch loops.
