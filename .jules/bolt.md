## 2024-06-25 - Drizzle ORM Index Filtering
**Learning:** Found an instance in `MessagesRepository.findByRange` where all messages for a session were being loaded into memory just to filter them by a numerical range.
**Action:** When filtering bounded continuous sets like numeric indices in Drizzle, always push the bounds directly into the SQL query using `gte` and `lte` within the `where` clause instead of relying on in-memory JavaScript `.filter()` to minimize DB network transport and memory pressure.
