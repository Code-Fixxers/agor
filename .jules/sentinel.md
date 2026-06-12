## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.
## 2025-06-12 - [Security] Prevent permission leakage during upload directory creation
**Vulnerability:** In `apps/agor-daemon/src/utils/upload.ts`, upload destination directories were being created using `fs.mkdir(dest, { recursive: true })` without specifying explicit directory permissions. This causes the directory to fall back to the process's default umask (often resulting in `0o755`), potentially allowing local users to browse uploaded files.
**Learning:** Whenever creating directories to hold sensitive files, such as uploads or configuration data, default node filesystem settings may inadvertently grant read access to unauthorized local users.
**Prevention:** Always provide a restrictive `mode: 0o700` argument in `fs.mkdir` (e.g., `fs.mkdir(dest, { recursive: true, mode: 0o700 })`) for sensitive directories to enforce owner-only access.
