## 2026-05-10 - Prevent SQL Injection with inArray()
**Vulnerability:** Found raw SQL string interpolation via `sql.raw` used for `IN` clauses within Drizzle ORM queries in `TaskRepository`.
**Learning:** Constructing raw query strings directly allows SQL injection if values are manipulated, bypassing parameterized execution that Drizzle relies on.
**Prevention:** Use Drizzle's built-in `inArray` function when executing queries with multiple matching identifiers instead of string interpolation.
