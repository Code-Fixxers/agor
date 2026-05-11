## 2025-03-02 - Prevent SQL Injection with parameterized helpers

**Vulnerability:** Found a critical SQL injection vulnerability in `packages/core/src/db/repositories/tasks.ts`. `sql.raw()` was used to interpolate an array of strings directly into an SQL string instead of using parameterized variables, allowing for SQL injection if user input was unfiltered.

**Learning:** `sql.raw()` disables Drizzle ORM's built-in parameterization and protection against SQL injection, executing the raw string exactly as passed. It should only ever be used for safe static values or internal variables we fully control, and never for dynamically mapped IDs from API requests.

**Prevention:** Always use parameterized helpers, such as `inArray(column, values)`, instead of `sql.raw()` string interpolation when dealing with dynamic collections or values in Drizzle queries.
