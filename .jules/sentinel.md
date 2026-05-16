## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.
## 2025-05-16 - [Command Injection Risk via execSync in Unix Lookups]
**Vulnerability:** A critical command injection vulnerability existed in `packages/core/src/unix/id-lookups.ts` and `apps/agor-cli/src/commands/admin/sync-unix.ts` where unvalidated user input (`username`, `groupName`, `sudoUser`) was concatenated into a string passed to `execSync`.
**Learning:** Functions that execute shell commands using `execSync` (`node:child_process`) are vulnerable to command injection if unvalidated strings are embedded within them, because `execSync` spins up a shell process.
**Prevention:** Avoid `execSync` or `exec` completely when handling dynamic variables. Always use `execFileSync` or `spawnSync` instead, passing the executable name and an array of distinct arguments. This prevents the command from being run inside an interactive shell, mitigating shell metacharacter injection.
