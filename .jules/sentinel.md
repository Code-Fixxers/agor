## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2025-06-06 - [CRITICAL] Fix command injection in id-lookups.ts via execSync
**Vulnerability:** Command injection vulnerability identified in `packages/core/src/unix/id-lookups.ts` where user-controlled inputs (`username`, `groupName`) were directly interpolated into strings executed by `child_process.execSync`. Since `execSync` executes within a shell (`/bin/sh`), an attacker could append shell commands (e.g., `"; touch /tmp/pwn"`) to execute arbitrary code.
**Learning:** `execSync` and `exec` are inherently dangerous when handling dynamic user inputs.
**Prevention:** Use `execFileSync` or `execFile` exclusively with dynamic arguments, as they do not invoke a shell. Pass dynamic arguments as array elements instead of string interpolation, and place the `--` sequence before dynamic inputs to prevent argument/flag injection.
