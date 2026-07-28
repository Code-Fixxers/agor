import { sql } from 'drizzle-orm';
import { PgDialect } from 'drizzle-orm/pg-core';
import { SQLiteSyncDialect } from 'drizzle-orm/sqlite-core';

const pgDialect = new PgDialect();
const sqliteDialect = new SQLiteSyncDialect();

const column = sql`my_column`;
const path = "path.to.my'key";
const parts = path.split('.');

console.log('Postgres 1 part:');
const p1Result = sql`${column}->>(${parts[0]}::text)`;
console.log(pgDialect.sqlToQuery(p1Result));

console.log('Postgres multi parts:');
const objectParts = parts.slice(0, -1).map((p) => sql`->(${p}::text)`);
const lastPart = parts[parts.length - 1];
const p2Result = sql`${column}${sql.join(objectParts, sql``)}->>(${lastPart}::text)`;
console.log(pgDialect.sqlToQuery(p2Result));
