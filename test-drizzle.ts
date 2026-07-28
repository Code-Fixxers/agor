import { sql } from 'drizzle-orm';
import { PgDialect } from 'drizzle-orm/pg-core';

const dialect = new PgDialect();
const column = sql`my_column`;
const p = 'key1';
const result = sql`${column} -> (${p}::text)`;
const query = dialect.sqlToQuery(result);
console.log(query);
