## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2025-05-13 - [Command Injection Risk via execSync in ID Lookups]
**Vulnerability:** A potential command injection vulnerability in `packages/core/src/unix/id-lookups.ts`. The code used `execSync` with string interpolation (e.g., `execSync(\`getent group "${groupName}"\`)`), allowing malicious input in usernames or group names to execute arbitrary shell commands.
**Learning:** Double quotes in `execSync` do not completely protect against injection if the input contains backticks or escaped quotes.
**Prevention:** Always use `execFileSync` from `node:child_process` with explicitly parameterized arguments instead of `execSync` to prevent the shell from parsing user inputs as commands. Ensure dynamic arguments are cast to strings and preceded by `--` to stop flag parsing.
