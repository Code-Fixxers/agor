## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2025-05-24 - [CRITICAL] Command Injection Risk in Shell Command Interpolation
**Vulnerability:** In `apps/agor-daemon/src/services/terminals.ts`, the `writeEnvFile` function dynamically evaluated the `chownTo` variable directly into a `execSync` bash string: `execSync(\`sudo -n chown "${chownTo}" "${envFile}"\`)`. If a malicious actor could control or bypass validation on `chownTo`, they could inject arbitrary bash commands because `execSync` invokes `/bin/sh` which interprets shell metacharacters.
**Learning:** Using `execSync` with JavaScript template literals to build dynamic shell commands is inherently prone to command injection, as `/bin/sh` performs parameter expansion and command substitution before invoking the process.
**Prevention:** Avoid shell evaluation entirely for dynamic inputs. Use `execFileSync` instead of `execSync`, passing the executable and arguments explicitly as an array. Use the double dash `--` parameter to signal the end of command line options, ensuring variables like user input are strictly treated as positional arguments (e.g., `execFileSync('sudo', ['-n', 'chown', '--', String(chownTo), String(envFile)])`).
