## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-21 - [CRITICAL] Fix config.yaml file permissions
**Vulnerability:** The daemon configuration file (`~/.agor/config.yaml`) and its parent directory (`~/.agor`) were created with default file permissions (e.g., `0o755`/`0o644`), which made them readable by other users on the system. This file stores extremely sensitive information such as API keys and master JWT secrets.
**Learning:** Default Node.js filesystem operations (`fs.writeFile` and `fs.mkdir`) do not enforce strict permissions unless explicitly specified with a `mode` parameter. When handling sensitive files, relying on the system `umask` is insufficient.
**Prevention:** Always specify `mode: 0o600` for sensitive files and `mode: 0o700` for their parent directories. Additionally, use `fs.chmod` to retroactively secure existing files and directories that might have been created with permissive defaults.

## 2025-05-14 - [SQL Injection Risk via sql.raw in PostgreSQL JSON extraction]
**Vulnerability:** A potential SQL injection vulnerability in `jsonExtract` inside `packages/core/src/db/database-wrapper.ts`. The code used `sql.raw` to construct JSON path access string dynamically.
**Learning:** Using `sql.raw` with unparameterized variables makes the app susceptible to SQL injection. When generating dynamic JSON access paths for PostgreSQL `->` and `->>` operators, explicit cast to text (e.g. `sql\`->> (${key}::text)\``) is required so that Drizzle correctly handles the overloaded JSON operator without syntax errors, allowing us to safely parameterize paths.
**Prevention:** Never use `sql.raw()` to interpolate dynamic variables. Always cast dynamic parameters to text (`::text`) when using Postgres JSON operators to maintain parameterization while avoiding operator overload ambiguity.
