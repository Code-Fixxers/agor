## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2025-05-14 - [CRITICAL] Fix SQL injection risk via sql.raw in jsonExtract
**Vulnerability:** The `jsonExtract` function in PostgreSQL mode was using `sql.raw()` to dynamically interpolate JSON paths (e.g. `->>'field'`), opening it up to SQL injection vulnerabilities if path variables come from user input.
**Learning:** Using `sql.raw()` bypasses Drizzle's parameterization and allows arbitrary SQL string insertion. For PostgreSQL JSON operators (`->`, `->>`), dynamic parameters need explicit text casts (e.g. `->(${key}::text)`) so the database engine can resolve the overloaded operator type.
**Prevention:** Always use parameterized helpers and template literals instead of raw SQL string interpolation. For JSON paths, explicitly cast dynamic parameters to text (`::text`) when using Postgres JSON operators.
