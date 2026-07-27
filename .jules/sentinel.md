## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2025-07-27 - [Command Injection Risk via execSync in ID Lookups]
**Vulnerability:** Shell command injection vulnerability in `packages/core/src/unix/id-lookups.ts` caused by passing user-controlled usernames and group names to `execSync` using string interpolation (e.g., `execSync(\`id -u "${username}"\`)`).
**Learning:** Double quotes inside `execSync` do not fully protect against shell injection if the input contains backticks, escaped quotes, or other shell metacharacters. Additionally, without the `--` separator, inputs starting with a hyphen could be treated as command flags.
**Prevention:** Always use `execFileSync` instead of `execSync` for dynamic arguments, pass arguments as an array, and use `--` to explicitly terminate options processing.
