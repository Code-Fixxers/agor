import { sql } from 'drizzle-orm';

const parts = ['genealogy', 'parent_session_id'];
const column = sql`sessions.data`;
const objectParts = parts.slice(0, -1).map((p) => sql`-> (${p}::text)`);
const lastPart = parts[parts.length - 1];
console.log(
  sql`${column} ${sql.join(objectParts, sql` `)} ->> (${lastPart}::text)`.toQuery({
    escapeString: (str) => str,
    escapeParam: (num, val) => `$${num}`,
  })
);
