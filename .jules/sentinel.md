## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2024-05-24 - [Command Injection Risk via execSync in ID Lookups]
**Vulnerability:** Command injection vulnerability in `getGidFromGroupName`, `getUidFromUsername`, `getHomedirFromUsername`, and `unixUserExists`. The code used `execSync` with string interpolation for dynamic inputs like `username` and `groupName`, executing within a shell.
**Learning:** Using `execSync` with string interpolation for dynamic inputs executes within a shell, exposing the application to command injection if an input string is manipulated (e.g., passing a username with shell metacharacters).
**Prevention:** Always use `execFileSync` passing arguments as an array instead of `execSync` with string interpolation. Further, prefix dynamic arguments with a double-dash `--` to prevent argument injection vulnerabilities. For commands that discard output, pass `{ stdio: 'ignore' }`.
