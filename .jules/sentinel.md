## 2025-05-13 - [SQL Injection Risk via sql.raw in Bulk Insert Retrieval]
**Vulnerability:** A potential SQL injection vulnerability in `createMany` inside `packages/core/src/db/repositories/tasks.ts`. The code used `sql.raw` to interpolate task IDs into an `IN` clause manually without parameterization.
**Learning:** Using `sql.raw` to join dynamically generated strings (like UUID arrays) bypasses Drizzle's parameterization, exposing the app to SQL injection if any ID string is manipulated.
**Prevention:** Always use Drizzle ORM's built-in parameterization functions like `inArray` instead of manual string concatenation or `sql.raw`.

## 2025-05-15 - [SQL Injection Risk via sql.raw in jsonExtract]
**Vulnerability:** A potential SQL injection vulnerability in `jsonExtract` function located in `packages/core/src/db/database-wrapper.ts`. The code used `sql.raw` to directly interpolate dynamic paths and property keys into the Postgres JSON extraction string. Since path strings can originate from untrusted input, it opens up a potential vector for SQL injection when querying JSON fields.
**Learning:** `sql.raw` bypasses Drizzle's normal parameterization. Using it with anything dynamically generated based on user input exposes the application to SQL injection.
**Prevention:** Always use parameterized tags (`sql\`->\${param}\``) or appropriate query builder operators instead of concatenating raw SQL strings or relying on `sql.raw`.
