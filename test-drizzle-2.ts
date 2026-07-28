import { sql } from 'drizzle-orm';
import { PgDialect } from 'drizzle-orm/pg-core';

const dialect = new PgDialect();
const column = sql`my_column`;
const parts = ["user's key", 'another key'];
const objectParts = parts.slice(0, -1).map((p) => sql`->(${p}::text)`);
const lastPart = parts[parts.length - 1];
const result = sql`${column}${sql.join(objectParts, sql``)}->>(${lastPart}::text)`;
const query = dialect.sqlToQuery(result);
console.log(query);
