import { sql } from 'drizzle-orm';

const key = 'test';
console.log(
  sql`-> (${key}::text)`.toQuery({
    escapeString: (str) => str,
    escapeParam: (num, val) => `$${num}`,
  })
);
