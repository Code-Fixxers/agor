## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2024-05-22 - [Fix Command Injection Vulnerability in execSync Calls]
**Vulnerability:** The codebase used `execSync` with string interpolation for dynamically generated input (e.g., executing `id` or `getent` commands with user/group names). This allows command injection if a malicious user provides crafted strings containing shell metacharacters.
**Learning:** Shell evaluation via `execSync` is inherently dangerous when processing user inputs, even when ostensibly wrapped in quotes.
**Prevention:** Avoid `execSync` or `exec` completely when handling user inputs. Instead, use `execFileSync` or `execFile` and pass arguments as an array alongside a double-dash `--` (e.g., `execFileSync('id', ['--', username])`) to bypass shell evaluation and prevent argument injection.
