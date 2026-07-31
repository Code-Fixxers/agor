## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.
## 2025-07-31 - [Command Injection Risk via execSync in ID Lookups]
**Vulnerability:** Command injection vulnerability in `packages/core/src/unix/id-lookups.ts`. Functions looking up user and group IDs used `execSync` with string interpolation for dynamic arguments (`username`, `groupName`). Even with double quotes, this allowed potential shell command injection.
**Learning:** Using `execSync` with dynamic arguments is inherently unsafe because it spawns a shell which evaluates metacharacters. Double quotes do not prevent execution of backticks or properly escaped commands.
**Prevention:** Always use `execFileSync` (or `spawn`/`execFile`) instead of `execSync` for dynamic arguments to avoid shell execution. Explicitly cast arguments to strings and use `--` to prevent argument injection vulnerabilities.
