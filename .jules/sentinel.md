## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2024-05-22 - [CRITICAL] SQL Injection Risk via sql.raw in PostgreSQL JSON Extraction
**Vulnerability:** The database wrapper's `jsonExtract` helper used `sql.raw` to construct JSON path access operations for PostgreSQL (e.g., `sql.raw("->'${key}'")`). This bypasses Drizzle's parameterization and exposes the application to SQL injection if any path components are derived from unvalidated user input.
**Learning:** For PostgreSQL JSON operators (`->`, `->>`), passing dynamic parameters directly using template literals without type casting can cause query errors because PostgreSQL cannot resolve the overloaded operator type. However, falling back to `sql.raw` creates a severe injection risk.
**Prevention:** When using Drizzle ORM to dynamically chain JSON path segments, always use parameterized template literals with explicit type casts to text (e.g., `sql\`->(\${p}::text)\`` or `sql\`->>(\${lastPart}::text)\``). This allows PostgreSQL to resolve the operator safely without raw string concatenation.
