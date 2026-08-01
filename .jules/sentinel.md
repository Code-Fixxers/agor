## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2024-05-22 - [CRITICAL] Fix command injection vulnerability in unix system queries
**Vulnerability:** The daemon used `execSync` from `node:child_process` in `packages/core/src/unix/system-queries.ts` to execute shell commands like `getent` and `id` which took dynamic parameters like `username` or `groupName`. Due to the usage of double quotes around parameters being interpreted by a shell environment, this resulted in a command injection vulnerability.
**Learning:** Avoid using `execSync` with string interpolation for dynamic arguments (like usernames, group names, or paths). Always use `execFileSync` to prevent shell command injection, as double quoting in `execSync` does not fully protect against backticks or escaped quotes.
**Prevention:** Always use `execFileSync` with its arguments array whenever possible instead of formatting command strings for the implicit shell environment to process.
