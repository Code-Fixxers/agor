## 2023-10-27 - [Fix N+1 query fetching last session messages]
**Learning:** When retrieving the latest associated row (e.g., max index) for a list of parent IDs in Drizzle, avoid N+1 queries with loops using `.limit(1)`. This causes significant latency. Instead, use a batched approach by first querying `max(index)` grouped by the parent ID in a subquery, and then joining that subquery back to the main table. Be aware that simple syntax strings like `$1` might conflict with variables in tools like esbuild during testing but not Drizzle syntax when done correctly.
**Action:** Always batch aggregate child queries when enriching list-type responses by utilizing SQL `max()` or equivalent within a subquery joined on the original table to minimize round-trips.
## 2026-05-16 - [Batch FeathersJS user fetches with $in operator to fix N+1 query]
**Learning:** FeathersJS allows passing `$in` clauses through the query parameter (e.g. `user_id: { $in: ownerIds }`). When writing custom Feathers service logic, you can easily parse this array and pass it to Drizzle's `inArray()` to perform a batched query, instead of looping over `service.get(id)` causing N+1 database roundtrips.
**Action:** When implementing or updating custom Feathers `find()` methods, extract and parse the `$in` parameters to support batched Drizzle `inArray()` lookups, and always replace `Promise.all(ids.map(id => service.get(id)))` with a single batched `find()` call.
## 2026-06-09 - O(N) Disk I/O bottleneck in Feathers hooks
**Learning:**  parsed YAML from disk directly. Running  inside  with  generated massive disk I/O load for bulk insert hooks, causing unnecessary performance bottlenecks.
**Action:** Always load configuration dependencies like  *before*  loops and pass the result as an argument synchronously to mapping helpers.
## 2025-06-09 - O(N) Disk I/O bottleneck in Feathers hooks
**Learning:** `loadConfig()` parsed YAML from disk directly. Running `loadConfig()` inside `.map()` with `Promise.all` generated massive disk I/O load for bulk insert hooks, causing unnecessary performance bottlenecks.
**Action:** Always load configuration dependencies like `loadConfig()` *before* `.map()` loops and pass the result as an argument synchronously to mapping helpers.
