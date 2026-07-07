## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2025-07-07 - [Command Injection via execSync with String Interpolation]
**Vulnerability:** Shell command injection vulnerability in `packages/core/src/unix/id-lookups.ts` caused by using `execSync` with dynamic inputs (like `groupName` and `username`) interpolated into strings (e.g., `execSync(\`id -u "${username}"\`)`).
**Learning:** `execSync` executes commands via a shell (like `/bin/sh`) which means variables injected via string interpolation can be manipulated to execute arbitrary commands if they contain backticks, escaped quotes, or shell variables, even if they are wrapped in double quotes.
**Prevention:** Always use `execFileSync` (or `execFile`) with explicit argument arrays, and separate arguments from command line flags using `--` to prevent flag injection (e.g., `execFileSync('id', ['-u', '--', String(username)])`).
