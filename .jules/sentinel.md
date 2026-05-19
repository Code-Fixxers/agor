## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.
## 2025-05-19 - [Command Injection Risk via execSync String Interpolation]
**Vulnerability:** A Command Injection vulnerability existed in `packages/core/src/unix/id-lookups.ts` where unvalidated user inputs (`username` and `groupName`) were interpolated directly into shell strings via `execSync` (e.g., `execSync(\`id -u "\${username}"\`)`).
**Learning:** Using `execSync` with template literals exposes the application to command injection because the input is evaluated by a shell (`/bin/sh`). Additionally, inputs starting with `-` could be executed as unintended flags (Argument Injection), and non-string inputs could cause runtime TypeErrors in stricter functions like `execFileSync`.
**Prevention:** Use `execFileSync` instead of `execSync` to bypass shell evaluation completely, explicitly cast dynamic inputs to strings (e.g., `String(username)`), and include `--` in the arguments array to denote the end of command options (e.g., `execFileSync('id', ['-u', '--', String(username)])`).
