import { sql } from 'drizzle-orm';
import { describe, expect, it } from 'vitest';
import { isPostgresDatabase, jsonExtract } from './database-wrapper';

describe('database-wrapper', () => {
  it('should use ->> with explicit text cast instead of sql.raw for json keys', () => {
    // We can mock a PostgreSQL db
    const mockDb = { execute: () => {} } as any;

    // Test jsonExtract
    const column = sql`data`;
    const extracted = jsonExtract(mockDb, column, 'user.profile.name');

    const query = extracted.toQuery({
      escapeName: () => '',
      escapeParam: (num: number) => `$${num + 1}`,
      escapeString: () => '',
      casing: {
        format: (val: string) => val,
      } as any,
    });

    expect(query.sql).toContain('-> ($1::text)');
    expect(query.sql).toContain('->> ($3::text)');
    expect(query.params).toEqual(['user', 'profile', 'name']);
  });
});
