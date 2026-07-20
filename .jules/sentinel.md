## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2025-05-13 - [SQL Injection Risk via sql.raw in JSON Extraction]
**Vulnerability:** A potential SQL injection vulnerability in `jsonExtract` inside `packages/core/src/db/database-wrapper.ts`. The code used `sql.raw` to interpolate JSON paths manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings bypasses Drizzle's parameterization, exposing the app to SQL injection if any path string is manipulated.
**Prevention:** To prevent SQL injection when working with PostgreSQL JSON operators (`->`, `->>`), always explicitly cast dynamic parameters to text (e.g., `sql\`->> (${key}::text)\``) to properly resolve the overloaded operator type and use `sql.join` to safely combine these parameterized template literals.
