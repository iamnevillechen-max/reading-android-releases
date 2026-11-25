import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {SourceStore, type SourceRow} from '../src/storage';
import {TestDatabase} from './d1';

/** Own generated data; no archived source, live provider, HTTP or private fixture. */
function fixture() {
  const db = new TestDatabase();
  const insert = db.raw.prepare(`INSERT INTO sources
    (kind,id,json,sha256,name,enabled,enabled_explore,supported,capabilities,status,updated_at)
    VALUES(?,?,?,?,?,1,0,?,?,?,100)`);
  db.raw.exec('BEGIN');
  for (let index = 0; index < 21_149; index++) {
    const kind = index < 20_706 ? 'book' : 'rss';
    const id = `https://owned-query-${String(index).padStart(5, '0')}.example.org/`;
    const json = JSON.stringify({[kind === 'book' ? 'bookSourceUrl' : 'sourceUrl']: id, enabled: true});
    const sha = createHash('sha256').update(json).digest('hex');
    const healthy = index % 713 === 0;
    insert.run(kind, id, json, sha, 'Owned query fixture', healthy ? 1 : 0,
      healthy ? '["search"]' : '[]', healthy ? 'healthy' : 'unsupported');
  }
  db.raw.exec('COMMIT');
  return db;
}

test('reassessment updates only indexed candidate identities on a 21k archive, with and without ANALYZE', async () => {
  for (const analyzed of [false, true]) for (const count of [1, 20, 50]) {
    const db = fixture();
    try {
      if (analyzed) db.raw.exec('ANALYZE');
      const incoming = db.raw.prepare("SELECT * FROM sources WHERE kind='book' AND status='unsupported' ORDER BY id LIMIT ?")
        .all(count) as unknown as SourceRow[];
      const captured: Array<{sql: string; params: unknown[]}> = [];
      const prepare = db.prepare.bind(db);
      db.prepare = (sql: string) => {
        const statement = prepare(sql);
        return {...statement, bind: (...params: unknown[]) => {
          captured.push({sql, params});
          return statement.bind(...params);
        }};
      };
      const result = await new SourceStore(db as unknown as D1Database).reassessSources('book',
        incoming.map(source => ({source, supported: true, capabilities: ['search']})));
      assert.equal(result.filter(row => row.changed).length, count);
      const update = captured.find(statement => /\bUPDATE\s+sources\b/i.test(statement.sql));
      assert.ok(update);
      const plan = db.raw.prepare('EXPLAIN QUERY PLAN ' + update.sql).all(...update.params as any[]) as Array<{detail: string}>;
      const accesses = plan.map(row => row.detail).filter(detail => /^(?:SCAN|SEARCH) (?:s|sources|sx)\b/.test(detail));
      const context = JSON.stringify({analyzed, count, plan});
      assert.ok(accesses.length >= 2, context);
      // A source kind range is still ~20k rows even if EXPLAIN calls it SEARCH.
      // Require both identity parts, or the bounded target rowid, on every source access.
      for (const detail of accesses) {
        assert.match(detail, /^SEARCH /, context);
        assert.ok(/\(rowid=\?\)/.test(detail) || /\(kind=\?.* AND id=\?\)/.test(detail), context);
      }
    } finally { db.raw.close(); }
  }
});
