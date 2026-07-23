import { sql } from 'drizzle-orm';

const key = 'test';
console.log(
  sql`->'${key}'`.toQuery({ escapeString: (str) => str, escapeParam: (num, val) => `$${num}` })
);
