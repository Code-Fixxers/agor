## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2024-05-13 - [SQL Injection Risk via sql.raw in jsonExtract]
**Vulnerability:** A potential SQL injection vulnerability in `jsonExtract` inside `packages/core/src/db/database-wrapper.ts`. The code used `sql.raw` to interpolate JSON path parts into a PostgreSQL query manually without parameterization.
**Learning:** Using `sql.raw` to construct dynamic SQL paths bypasses Drizzle's parameterization, exposing the application to SQL injection if any path string is manipulated or maliciously constructed.
**Prevention:** Always use Drizzle ORM's built-in parameterization via `sql\`...\`` tagged templates for dynamic parts instead of `sql.raw()`.
