import test from 'node:test';
import assert from 'node:assert/strict';
import {createApp, sha256, type EngineApi, type Env} from '../src/index';
import {SourceStore} from '../src/storage';
import {TestDatabase} from './d1';

/** Metadata is deliberately injected; these fixtures test accounting, not D1 bill estimates. */
function fixture(meta?: (statement: number) => unknown) {
  const db = new TestDatabase();
  let supplied = 0;
  if (meta) {
    const prepare = db.prepare.bind(db);
    const wrap = (statement: any): any => ({...statement,
      bind: (...params: any[]) => wrap(statement.bind(...params)),
      all: async () => ({...await statement.all(), meta: meta(supplied++)}),
    });
    db.prepare = (sql: string) => wrap(prepare(sql));
    const batch = db.batch.bind(db);
    db.batch = async (statements: any[]) => (await batch(statements)).map(result => ({...result, meta: meta(supplied++)}));
  }
  let sourceIO = 0;
  const forbidden = async (): Promise<any> => { sourceIO++; throw Error('Assessment must not fetch a source'); };
  const engine: EngineApi = {assessSource: () => ({supported: true, capabilities: ['search']}),
    categories: forbidden, search: forbidden, info: forbidden, toc: forbidden, content: forbidden,
    explore: forbidden, rssArticles: forbidden, rssContent: forbidden, verifyWebEntry: forbidden};
  const env: Env = {DB: db as unknown as D1Database, ADMIN_TOKEN: 'a'.repeat(40),
    READ_TOKEN: 'r'.repeat(40), MIGRATION_TOKEN: 'm'.repeat(40)};
  const app = createApp(engine);
  const request = (sources: Array<{id: string; sha256: string}>, token = env.ADMIN_TOKEN) => app.fetch(new Request(
    'https://owned-reader.example.org/v1/reassess', {method: 'POST', headers: {
      Authorization: 'Bearer ' + token, 'Content-Type': 'application/json'}, body: JSON.stringify({kind: 'book', sources})}), env);
  async function seed() {
    const id = 'https://owned-usage.example.org/source/one';
    const raw = JSON.stringify({bookSourceUrl: id, enabled: true});
    const hash = await sha256(raw);
    db.raw.prepare(`INSERT INTO sources(kind,id,json,sha256,name,supported,status,updated_at)
      VALUES('book',?,?,?,'Owned usage fixture',0,'unsupported',100)`).run(id, raw, hash);
    return {id, sha256: hash};
  }
  return {db, env, request, seed, get sourceIO() { return sourceIO; }};
}
async function data(response: Response) {
  assert.equal(response.status, 200);
  return (await response.json() as any).data;
}
const known = (statement: number) => ({rows_read: statement === 0 ? 2 : statement === 1 ? 3 : 10,
  rows_written: statement < 2 ? 0 : 1});

test('admin assessment sums two all results and seven batch statements without changing receipts', async () => {
  const h = fixture(known);
  try {
    const expected = await h.seed();
    const result = await data(await h.request([expected]));
    assert.deepEqual(result.storageUsage, {queryCount: 9, rowsRead: 75, rowsWritten: 7, metaAvailable: true});
    assert.equal(result.receipts.length, 1);
    assert.equal(result.receipts[0].outcome, 'changed');
    assert.equal(result.receipts[0].status, 'pending');
    assert.equal('storageUsage' in result.receipts[0], false);
    assert.deepEqual(Object.keys(result.storageUsage).sort(), ['metaAvailable', 'queryCount', 'rowsRead', 'rowsWritten']);
    assert.equal(h.sourceIO, 0);
  } finally { h.db.raw.close(); }
});

test('missing legacy metadata is unknown, never substituted with result length or changes', async () => {
  const h = fixture();
  try {
    const result = await data(await h.request([await h.seed()]));
    assert.deepEqual(result.storageUsage, {queryCount: 9, rowsRead: null, rowsWritten: null, metaAvailable: false});
  } finally { h.db.raw.close(); }
});

test('one invalid field or overflowing aggregate invalidates only that metric', async () => {
  for (const invalid of [undefined, -1, '0', Infinity, NaN]) {
    const h = fixture(statement => ({...known(statement), ...(statement === 5 ? {rows_written: invalid} : {})}));
    try {
      const result = await data(await h.request([await h.seed()]));
      assert.deepEqual(result.storageUsage, {queryCount: 9, rowsRead: 75, rowsWritten: null, metaAvailable: false});
    } finally { h.db.raw.close(); }
  }
  const h = fixture(() => ({rows_read: Number.MAX_SAFE_INTEGER, rows_written: 0}));
  try {
    const result = await data(await h.request([await h.seed()]));
    assert.deepEqual(result.storageUsage, {queryCount: 9, rowsRead: null, rowsWritten: 0, metaAvailable: false});
  } finally { h.db.raw.close(); }
});

test('a missing identity uses only the metadata lookup and reports its actual available meta', async () => {
  const h = fixture(known);
  try {
    const result = await data(await h.request([{id: 'https://owned-usage.example.org/missing', sha256: 'f'.repeat(64)}]));
    assert.deepEqual(result.storageUsage, {queryCount: 1, rowsRead: 2, rowsWritten: 0, metaAvailable: true});
    assert.equal(result.receipts[0].outcome, 'stale');
  } finally { h.db.raw.close(); }
});

test('accounting is per Store instance and excludes ordinary methods', async () => {
  const h = fixture(known);
  try {
    const expected = await h.seed();
    const first = new SourceStore(h.db as unknown as D1Database);
    await first.get('book', expected.id);
    await first.list('book', null, 1);
    assert.deepEqual(first.assessmentStorageUsage(), {queryCount: 0, rowsRead: 0, rowsWritten: 0, metaAvailable: true});
    await first.assessmentMetadata('book', [expected.id]);
    assert.equal(first.assessmentStorageUsage().queryCount, 1);
    const second = new SourceStore(h.db as unknown as D1Database);
    assert.deepEqual(second.assessmentStorageUsage(), {queryCount: 0, rowsRead: 0, rowsWritten: 0, metaAvailable: true});
    const result = await data(await h.request([expected]));
    assert.equal(result.storageUsage.queryCount, 9, 'The request must not inherit an earlier Store accounting session');
  } finally { h.db.raw.close(); }
});

test('migration/read roles cannot access assessment or its storage accounting', async () => {
  const h = fixture(known);
  try {
    const expected = await h.seed();
    for (const token of [h.env.MIGRATION_TOKEN, h.env.READ_TOKEN]) {
      const response = await h.request([expected], token);
      assert.equal(response.status, 403);
      assert.equal(JSON.stringify(await response.json()).includes('storageUsage'), false);
    }
    assert.equal(h.db.preparedCount, 0);
  } finally { h.db.raw.close(); }
});
