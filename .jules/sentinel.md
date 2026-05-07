## 2024-05-07 - [SQL Injection Fix]
**Vulnerability:** Used sql.raw string interpolation for tasks querying via ID array.
**Learning:** Found usage of sql.raw where IDs were constructed in IN clause via string manipulation.
**Prevention:** Always use safe abstraction like inArray provided by Drizzle ORM when performing IN clause lookup.
