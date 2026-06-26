import { sql } from 'drizzle-orm';
import { jsonb, pgDialect, pgTable, text } from 'drizzle-orm/pg-core';

// Mock table
const users = pgTable('users', {
  data: jsonb('data'),
});

const column = users.data;
const parts = ['profile', 'name'];

// Old way
const objectPartsOld = parts.slice(0, -1).map((p) => sql.raw(`->'${p}'`));
const lastPartOld = parts[parts.length - 1];
const oldSql = sql`${column}${sql.join(objectPartsOld, sql``)}${sql.raw(`->>'${lastPartOld}'`)}`;

// New way
const objectPartsNew = parts.slice(0, -1).map((p) => sql`-> (${p}::text)`);
const lastPartNew = parts[parts.length - 1];
const newSql = sql`${column}${sql.join(objectPartsNew, sql``)} ->> (${lastPartNew}::text)`;

console.log('Old SQL:', oldSql);
console.log('New SQL:', newSql);
