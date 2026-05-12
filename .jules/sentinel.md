## 2024-05-15 - SQL Injection via `sql.raw()` in Drizzle ORM
**Vulnerability:** Unsanitized arrays were formatted and passed using string interpolation inside `sql.raw()` in Drizzle ORM queries (e.g., `sql.raw(\`(${taskIds.map((id) => \`'\${id}'\`).join(',')})\`)`). This leads to severe SQL Injection if any element in the array is controlled by a user.
**Learning:** Drizzle ORM queries using `sql.raw` directly execute strings without parameterization. Passing unsanitized, interpolated strings into it creates vulnerabilities.
**Prevention:** Always use parameterized helpers provided by Drizzle ORM, such as `inArray(column, array)`, rather than `sql.raw()` to handle list parameters safely.
