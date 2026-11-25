import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';

/** Real SQLite underneath the small D1 adapter; SQL semantics are not mocked. */
export class TestDatabase {
  raw = new DatabaseSync(':memory:');
  preparedCount = 0;
  preparedSql:string[]=[];
  constructor() {
    for (const migration of ['0001_sources.sql','0002_category_overrides.sql','0003_maintenance.sql']) {
      this.raw.exec(readFileSync(new URL(`../migrations/${migration}`, import.meta.url), 'utf8'));
    }
  }
  prepare(sql: string) {
    this.preparedCount++;
    this.preparedSql.push(sql);
    const statement = this.raw.prepare(sql);
    const create = (params: any[] = []): any => ({
      bind: (...values: any[]) => create(values),
      first: async (column?: string) => { const row = statement.get(...params) as any; return column ? row?.[column] ?? null : row ?? null; },
      all: async () => ({ success: true, results: statement.all(...params), meta: { changes: 0 } }),
      run: async () => ({ success: true, results: [], meta: statement.run(...params) }),
      execute: () => statement.columns().length ? ({ success: true, results: statement.all(...params), meta: { changes: 0 } }) : ({ success: true, results: [], meta: statement.run(...params) }),
    });
    return create();
  }
  async batch(statements: any[]) {
    this.raw.exec('BEGIN');
    try { const results = statements.map(statement => statement.execute()); this.raw.exec('COMMIT'); return results; }
    catch (error) { this.raw.exec('ROLLBACK'); throw error; }
  }
}
