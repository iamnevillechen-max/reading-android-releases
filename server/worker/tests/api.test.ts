import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp, sha256, TOC_INSTALL_CONTRACT, type EngineApi, type Env } from '../src/index';
import { classify, taxonomy } from '../src/taxonomy';
import { SourceStore } from '../src/storage';
import { assessSource, createEngine, ENGINE_RULE_CONTRACT_VERSION, ENGINE_IMPLEMENTATION_VERSION } from '../src/engine';
import { CONTENT_QUALITY_VERSION } from '../src/content-quality';
import { BOOK_METADATA_VERSION } from '../src/metadata-quality';
import { sourceAdapterRevision } from '../src/source-adapters';
import { TestDatabase } from './d1';
function harness(overrides: Partial<EngineApi> = {}) {
  const database = new TestDatabase();
  const calls: Array<{ action: string; url?: string; page?: number }> = [];
  const engine: EngineApi = {
    assessSource: (_kind, source) => ({ supported: !source.unsupported, capabilities: ['search', 'info', 'toc', 'content', 'explore'] }),
    categories: async () => [{ title: '都市小说', url: 'https://fixture.example/urban1' }, { title: '都市', url: 'https://fixture.example/urban2' }, { title: '玄幻', url: 'https://fixture.example/fantasy' }],
    search: async (_source, key) => { calls.push({ action: 'search' }); return [{ bookUrl: 'https://fixture.example/book', name: key, author: 'Fixture Author' }]; },
    info: async (_source, book) => ({ ...book, tocUrl: 'https://fixture.example/toc' }),
    toc: async (_source, book) => ({ book: { ...book, variable: '{"fixture":true}' }, chapters: [{ url: 'https://fixture.example/chapter1', title: '第一章', index: 0 }] }),
    content: async (_source, book) => ({ text: '這是自行編寫的測試正文。'.repeat(30), book }),
    explore: async (_source, url, currentPage) => { calls.push({ action: 'explore', url, page: currentPage }); return [{ bookUrl: 'https://fixture.example/book', name: 'Fixture Book' }]; },
    rssArticles: async () => ({ articles: [{ link: 'https://fixture.example/article', title: 'Fixture Article' }], nextPageUrl: 'https://fixture.example/feed?page=2' }),
    rssContent: async () => '自行編寫的訂閱測試內文。'.repeat(10),
    verifyWebEntry: async source => ({ url: source.sourceUrl, contentType: 'text/html; charset=utf-8' }),
    ...overrides,
  };
  const env = { DB: database as unknown as D1Database, ADMIN_TOKEN: 'a'.repeat(40), READ_TOKEN: 'r'.repeat(40), MIGRATION_TOKEN: 'm'.repeat(40) } satisfies Env;
  const app = createApp(engine);
  async function request(path: string, body?: unknown, role = 'read', headers: Record<string, string> = {}) {
    const token = role === 'read' ? env.READ_TOKEN : role === 'migration' ? env.MIGRATION_TOKEN : role === 'admin' ? env.ADMIN_TOKEN : role;
    return app.fetch(new Request(`https://reader.example${path}`, { method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...headers }, body: body === undefined ? undefined : JSON.stringify(body) }), env);
  }
  async function importSource(source = { bookSourceUrl: 'https://fixture.example/#opaque-id', bookSourceName: 'Fixture', enabled: true, enabledExplore: true, exploreUrl: 'https://fixture.example/genres' }, sourceKind = 'book') {
    const json = ` ${JSON.stringify(source)}\n`;
    const id = (source as any).bookSourceUrl ?? (source as any).sourceUrl;
    const hash = await sha256(json);
    const result = await request('/v1/import', { kind: sourceKind, sources: [{ id, json, sha256: hash }] }, 'migration');
    assert.equal(result.status, 200, await result.clone().text());
    return { id, json, hash, result: await result.json() as any };
  }
  return { app, env, database, calls, request, importSource };
}

test('authentication separates read, migration and manager permissions; errors reveal no secrets', async () => {
  const h = harness();
  assert.equal((await h.app.fetch(new Request('https://reader.example/health'), h.env)).status, 200);
  const rejected = await h.request('/v1/status', undefined, 'bad-token');
  assert.equal(rejected.status, 401);
  assert.equal((await h.request('/v1/status', undefined, 'read', { Origin: 'https://foreign.example' })).status, 403);
  assert.equal((await h.request('/v1/import', { kind: 'book', sources: [] })).status, 403);
  assert.equal((await h.request('/v1/verify', { sourceId: 'missing' })).status, 403);
  assert.equal((await h.request('/v1/status', undefined, '')).status, 401);
  const text = await rejected.text();
  for (const secret of [h.env.ADMIN_TOKEN, h.env.READ_TOKEN, h.env.MIGRATION_TOKEN]) assert.ok(!text.includes(secret));
  h.database.raw.close();
});

test('batched receipts preserve opaque IDs and kind, verify saved bytes with one indexed read, and return no rules or writes',async()=>{
  const h=harness({assessSource});
  const ids=['',' \t','https://fixture.example/\u0000original','https://fixture.example/normal'];
  const expected=new Map<string,string>();
  for(const id of ids){
    const value=await h.importSource({bookSourceUrl:id,bookSourceName:'Owned exact archive'}as any);
    expected.set(id,value.hash);
  }
  const rss=await h.importSource({sourceUrl:ids[3],sourceName:'Owned separate RSS identity'}as any,'rss');
  h.database.raw.exec(`WITH RECURSIVE n(i) AS(SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<20000)
    INSERT INTO sources(kind,id,json,sha256,name,enabled,enabled_explore,supported,status,updated_at)
    SELECT 'book','owned-unrequested-'||i,'{}','archive','Owned archived fixture',1,0,0,'unsupported',0 FROM n`);
  const requested=[ids[3],'unseen opaque id',ids[2],ids[0],ids[1]];
  const changes=(h.database.raw.prepare('SELECT total_changes() AS n').get()as any).n;
  const before=h.database.preparedCount;
  const result=await h.request('/v1/source-receipts',{kind:'book',ids:requested,sha256:'0'.repeat(64)});
  assert.equal(result.status,200);
  assert.equal(h.database.preparedCount-before,1);
  const sql=h.database.preparedSql.at(-1)!;
  const plan=h.database.raw.prepare('EXPLAIN QUERY PLAN '+sql).all(JSON.stringify(requested),'book');
  assert.match(JSON.stringify(plan),/SEARCH s USING INDEX sqlite_autoindex_sources_1/);
  assert.ok(!JSON.stringify(plan).includes('SCAN s'),'Do not scan the 20,000-source archive');
  const receipts=(await result.json()as any).data.receipts;
  assert.deepEqual(receipts.map((r:any)=>r.id),requested);
  for(const receipt of receipts){
    if(!expected.has(receipt.id)){assert.deepEqual(receipt,{id:receipt.id,stored:false});continue;}
    assert.equal(receipt.stored,true);assert.equal(receipt.sha256,expected.get(receipt.id));
    assert.ok(!Object.hasOwn(receipt,'json'));assert.ok(Array.isArray(receipt.capabilities));
  }
  const rssResult=(await(await h.request('/v1/source-receipts',{kind:'rss',ids:[ids[3]]})).json()as any).data.receipts;
  assert.equal(rssResult[0].sha256,rss.hash);assert.notEqual(rssResult[0].sha256,expected.get(ids[3]));
  assert.equal((h.database.raw.prepare('SELECT total_changes() AS n').get()as any).n,changes);
  h.database.raw.close();
});

test('batched receipt feature is advertised and read-only authorization/input limits remain strict',async()=>{
  const h=harness();
  assert.equal((await(await h.request('/v1/status')).json()as any).data.features.batchedSourceReceipts,true);
  const changes=(h.database.raw.prepare('SELECT total_changes() AS n').get()as any).n;
  for(const role of ['read','migration','admin']){
    const response=await h.request('/v1/source-receipts',{kind:'book',ids:['']},role);
    assert.equal(response.status,200);assert.deepEqual((await response.json()as any).data.receipts,[{id:'',stored:false}]);
  }
  assert.equal((await h.request('/v1/source-receipts',{kind:'book',ids:['']},'bad-token')).status,401);
  assert.equal((await h.request('/v1/source-receipts',{kind:'book',ids:['']},'read',{Origin:'https://foreign.example'})).status,403);
  const invalid=[{}, {kind:'book'}, {kind:'books',ids:['x']},{kind:null,ids:['x']}, {kind:'book',ids:[]},
    {kind:'book',ids:['','']},{kind:'book',ids:[null]},{kind:'book',ids:[5]},
    {kind:'book',ids:['x'.repeat(16385)]},{kind:'book',ids:Array.from({length:101},(_,i)=>String(i))}];
  const queries=h.database.preparedCount;
  for(const input of invalid)assert.equal((await h.request('/v1/source-receipts',input)).status,400);
  assert.equal((await h.request('/v1/source-receipts',{kind:'book',ids:['x'],padding:'x'.repeat(1024*1024)})).status,413);
  assert.equal(h.database.preparedCount,queries,'Malformed requests must not query storage');
  const accepted=await h.request('/v1/source-receipts',{kind:'book',ids:Array.from({length:100},(_,i)=>String(i))});
  assert.equal(accepted.status,200);assert.equal((await accepted.json()as any).data.receipts.length,100);
  assert.equal((h.database.raw.prepare('SELECT total_changes() AS n').get()as any).n,changes);
  h.database.raw.close();
});

test('one damaged stored rule makes batched acknowledgement fail without trusting a claimed client hash or issuing partial success',async()=>{
  const h=harness(),source=await h.importSource();
  h.database.raw.prepare('UPDATE sources SET json=? WHERE kind=? AND id=?').run('{"owned":"corrupt fixture"}','book',source.id);
  const before=(h.database.raw.prepare('SELECT total_changes() AS n').get()as any).n;
  const response=await h.request('/v1/source-receipts',{kind:'book',ids:['unknown',source.id],sha256:source.hash});
  assert.equal(response.status,503);
  const envelope=await response.json()as any;
  assert.equal(envelope.error.code,'STORAGE_MISMATCH');assert.ok(!Object.hasOwn(envelope,'data'));
  assert.equal((h.database.raw.prepare('SELECT total_changes() AS n').get()as any).n,before);
  h.database.raw.close();
});

test('imports preserve exact UTF8 JSON and opaque ID, independently read back; changed rules lose healthy status', async () => {
  const h = harness();
  const stored = await h.importSource();
  assert.equal(stored.result.data.receipts[0].status, 'pending');
  const row = h.database.raw.prepare('SELECT * FROM sources').get() as any;
  assert.equal(row.json, stored.json);
  assert.equal(row.id, stored.id);
  const acknowledgement = await h.request(`/v1/source-receipt?kind=book&id=${encodeURIComponent(stored.id)}`);
  assert.deepEqual((await acknowledgement.json() as any).data, { id: stored.id, sha256: stored.hash, stored: true, supported: true, status: 'pending',
    capabilities: ['search', 'info', 'toc', 'content', 'explore'], webUrl: null });
  const verified = await h.request('/v1/verify', { kind: 'book', sourceId: stored.id, key: 'Fixture Search' }, 'migration');
  assert.equal((await verified.json() as any).data.status, 'healthy');
  await h.importSource();
  assert.equal((h.database.raw.prepare('SELECT status FROM sources').get() as any).status, 'healthy');
  await h.importSource({ bookSourceUrl: stored.id, bookSourceName: 'Changed', enabled: true, enabledExplore: true, exploreUrl: 'https://fixture.example/genres' });
  assert.equal((h.database.raw.prepare('SELECT status FROM sources').get() as any).status, 'pending');
  const catalogue = (await (await h.request('/v1/catalogue')).json() as any).data;
  assert.deepEqual(catalogue.routes, []);
  h.database.raw.close();
});

test('hash mismatch, source ID mismatch and duplicate batches never write rows', async () => {
  const h = harness();
  const json = JSON.stringify({ bookSourceUrl: 'https://fixture.example/' });
  const source = { id: 'https://fixture.example/', json, sha256: await sha256(json) };
  assert.equal((await h.request('/v1/import', { kind: 'book', sources: [{ ...source, sha256: '0'.repeat(64) }] }, 'migration')).status, 400);
  assert.equal((await h.request('/v1/import', { kind: 'book', sources: [{ ...source, id: 'https://other.example/' }] }, 'migration')).status, 400);
  assert.equal((await h.request('/v1/import', { kind: 'book', sources: [source, source] }, 'migration')).status, 400);
  assert.equal((h.database.raw.prepare('SELECT COUNT(*) AS n FROM sources').get() as any).n, 0);
  await h.importSource();
  h.database.raw.prepare("UPDATE sources SET json='{}'").run();
  const ack = await h.request(`/v1/source-receipt?id=${encodeURIComponent('https://fixture.example/#opaque-id')}`);
  assert.equal(ack.status, 503);
  h.database.raw.close();
});

test('all real reading stages must succeed before health; metadata cannot forge healthy', async () => {
  const h = harness({ content: async () => ({ text: '获取正文失败' }) });
  const source = await h.importSource();
  const verified = (await (await h.request('/v1/verify', { sourceId: source.id, status: 'healthy', stages: { content: true } }, 'migration')).json() as any).data;
  assert.equal(verified.verified, false);
  assert.equal(verified.status, 'pending');
  assert.equal(verified.stages.search, true);
  assert.equal(verified.stages.toc, true);
  assert.equal(verified.stages.content, false);
  assert.deepEqual((await (await h.request('/v1/catalogue')).json() as any).data.routes, []);
  h.database.raw.close();
});

test('catalogue merges to fixed categories and virtual category pages visit every raw subroute', async () => {
  const h = harness();
  const source = await h.importSource();
  await h.request('/v1/verify', { sourceId: source.id }, 'migration');
  const catalogue = (await (await h.request('/v1/catalogue?kind=books')).json() as any).data;
  assert.deepEqual(catalogue.categories.map((value: any) => value.id).sort(), ['fantasy', 'urban']);
  assert.equal(catalogue.routes.length, 2);
  assert.equal((h.database.raw.prepare('SELECT COUNT(*) AS n FROM catalogue_routes').get() as any).n, 3);
  assert.equal(catalogue.complete, true);
  h.calls.length = 0; // Verification has already checked one real discovery page.
  for (let currentPage = 1; currentPage <= 4; currentPage++) {
    const result = await h.request('/v1/books/explore', { sourceId: source.id, url: 'reader-cloud://category/urban', page: currentPage });
    assert.equal(result.status, 200, await result.clone().text());
  }
  assert.deepEqual(h.calls.filter(call => call.action === 'explore'), [
    { action: 'explore', url: 'https://fixture.example/urban1', page: 1 },
    { action: 'explore', url: 'https://fixture.example/urban2', page: 1 },
    { action: 'explore', url: 'https://fixture.example/urban1', page: 2 },
    { action: 'explore', url: 'https://fixture.example/urban2', page: 2 },
  ]);
  assert.equal((await h.request('/v1/explore', { sourceId: source.id, url: 'http://169.254.169.254/', page: 1 })).status, 400);
  h.database.raw.close();
});

test('finite source backoff prevents repeated failed fetches; cached success reuses parsed results', async () => {
  let attempts = 0;
  const h = harness({ search: async () => { attempts++; throw new Error('private stack and token'); } });
  const source = await h.importSource();
  const payload = { sourceId: source.id, key: 'Fixture' };
  assert.equal((await h.request('/v1/search', payload)).status, 502);
  assert.equal((await h.request('/v1/search', payload)).status, 502);
  const rejected = await h.request('/v1/search', payload);
  assert.equal(rejected.status, 503);
  assert.equal(attempts, 2);
  assert.ok(!(await rejected.text()).includes('private stack'));
  h.database.raw.close();
  const working = harness();
  const good = await working.importSource();
  await working.request('/v1/search', { sourceId: good.id, key: 'Fixture' });
  await working.request('/v1/search', { sourceId: good.id, key: 'Fixture' });
  assert.equal(working.calls.filter(call => call.action === 'search').length, 1);
  working.database.raw.close();
});

test('source list contains only thin summaries, unsupported sources remain stored and never become active', async () => {
  const h = harness();
  const source = await h.importSource({ bookSourceUrl: 'https://fixture.example/#unsupported', bookSourceName: 'Unsupported', unsupported: true, enabled: true, enabledExplore: true, exploreUrl: '' } as any);
  const result = (await (await h.request('/v1/sources')).json() as any).data;
  assert.equal(result.sources.length, 1);
  assert.equal(result.sources[0].id, source.id);
  assert.equal(result.sources[0].status, 'unsupported');
  assert.equal(result.sources[0].json, undefined);
  assert.equal((await h.request('/v1/verify', { sourceId: source.id }, 'migration')).status, 422);
  h.database.raw.close();
});

test('RSS verifies real articles/body and exposes native result shapes', async () => {
  const h = harness();
  const source = await h.importSource({ sourceUrl: 'https://fixture.example/feed', sourceName: 'Fixture RSS', enabled: true } as any, 'rss');
  const verified = (await (await h.request('/v1/verify', { kind: 'rss', sourceId: source.id }, 'migration')).json() as any).data;
  assert.equal(verified.verified, true);
  const articles = (await (await h.request('/v1/rss/articles', { sourceId: source.id })).json() as any).data;
  assert.equal(articles.articles.length, 1);
  assert.equal(articles.nextUrl, 'https://fixture.example/feed?page=2');
  const content = (await (await h.request('/v1/rss/content', { sourceId: source.id, article: articles.articles[0] })).json() as any).data;
  assert.ok(content.content.length > 40);
  h.database.raw.close();
});

test('taxonomy is bounded and preserves orthogonal filters without female/male false matches', () => {
  assert.equal(taxonomy.length, 11);
  assert.equal(classify('女频都市').id, 'urban');
  assert.deepEqual(classify('female urban').filterIds, ['female']);
  assert.equal(classify('都市同人', '玄幻').id, 'fanfic');
  assert.equal(classify('未知分類').id, 'other');
  assert.deepEqual(classify('topology').filterIds, []);
});

test('50-source import uses a constant three SQL queries including one aggregate invalidation; pages lose no sources', async () => {
  const h = harness({ assessSource: () => ({ supported: true, capabilities: { supported: true, search: true, explore: true }, explore: true }) });
  for (let chunk = 0; chunk < 3; chunk++) {
    const sources = await Promise.all(Array.from({ length: 50 }, async (_, index) => {
      const id = `https://fixture.example/#${String(chunk * 50 + index).padStart(4, '0')}`;
      const json = JSON.stringify({ bookSourceUrl: id, bookSourceName: 'Fixture', enabled: true, enabledExplore: true, exploreUrl: 'https://fixture.example/genres' });
      return { id, json, sha256: await sha256(json) };
    }));
    const before = h.database.preparedCount;
    assert.equal((await h.request('/v1/import', { kind: 'book', sources }, 'migration')).status, 200);
    assert.equal(h.database.preparedCount - before, 3);
  }
  const first = (await (await h.request('/v1/sources')).json() as any).data;
  const second = (await (await h.request(`/v1/sources?cursor=${first.nextCursor}`)).json() as any).data;
  assert.equal(first.sources.length, 100);
  assert.equal(second.sources.length, 50);
  assert.equal(new Set([...first.sources, ...second.sources].map(source => source.id)).size, 150);
  assert.deepEqual(first.sources[0].capabilities, ['search', 'explore']);
  h.database.raw.exec("UPDATE sources SET status='healthy'");
  const catalogue = (await (await h.request('/v1/catalogue')).json() as any).data;
  assert.ok(catalogue.nextCursor);
  const final = (await (await h.request(`/v1/catalogue?cursor=${catalogue.nextCursor}`)).json() as any).data;
  assert.equal(final.nextCursor, null);
  h.database.raw.close();
});

test('hundreds of original category routes are preserved with a constant five-query health commit', async () => {
  const h = harness();
  const source = await h.importSource();
  const store = new SourceStore(h.env.DB);
  const row = (await store.get('book', source.id))!;
  const routes = Array.from({ length: 300 }, (_, index) => ({ label: `都市子類${index}`, url: `https://fixture.example/urban/${index}`,
    categoryId: 'urban', categoryLabel: '都市／現實', filterIds: [] }));
  const before = h.database.preparedCount;
  assert.equal(await store.markHealthy(row, routes), true);
  assert.equal(h.database.preparedCount - before, 5);
  assert.equal((await store.sourceRoutes(row)).length, 300);
  const visible = (await (await h.request('/v1/catalogue')).json() as any).data;
  assert.equal(visible.routes.length, 1);
  h.database.raw.close();
});

test('exact rule export is restricted to the manager and verifies stored bytes', async () => {
  const h = harness();
  const source = await h.importSource();
  const path = `/v1/source-export?id=${encodeURIComponent(source.id)}`;
  assert.equal((await h.request(path)).status, 403);
  assert.equal((await h.request(path, undefined, 'migration')).status, 403);
  const exported = (await (await h.request(path, undefined, 'admin')).json() as any).data;
  assert.equal(exported.json, source.json);
  assert.equal(exported.sha256, source.hash);
  h.database.raw.close();
});

test('regular active sync selects healthy enabled rows in SQL without downloading 20,000 inactive summaries', async () => {
  const h = harness();
  h.database.raw.exec(`WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<20000)
    INSERT INTO sources(kind,id,json,sha256,name,status,supported,enabled,updated_at)
    SELECT 'book','https://fixture.example/#inactive-'||x,'{}','unused','Fixture','unsupported',0,1,1 FROM n`);
  const source = await h.importSource();
  h.database.raw.prepare("UPDATE sources SET status='healthy' WHERE id=?").run(source.id);
  const result = (await (await h.request('/v1/sources?active=true')).json() as any).data;
  assert.equal(result.sources.length, 1);
  assert.equal(result.sources[0].id, source.id);
  assert.equal(result.nextCursor, null);
  assert.equal(JSON.stringify(result).length < 1000, true);
  h.database.raw.prepare('UPDATE sources SET enabled=0 WHERE id=?').run(source.id);
  assert.deepEqual((await (await h.request('/v1/sources?active=true')).json() as any).data.sources, []);
  h.database.raw.close();
});

test('single-source legacy cloud markers resolve only to verified routes and preserve page rotation', async () => {
  const rssUrls: Array<{ url: string; page?: number }> = [];
  const h = harness({ rssArticles: async (_source, url, currentPage) => {
    rssUrls.push({ url, page: currentPage });
    return { articles: [{ title: 'Fixture article', link: 'https://fixture.example/article' }] };
  } });
  const book = await h.importSource();
  await h.request('/v1/verify', { sourceId: book.id }, 'migration');
  h.calls.length = 0;
  for (let currentPage = 1; currentPage <= 4; currentPage++) {
    const result = await h.request('/v1/books/explore', { sourceId: book.id, url: 'reader-cloud://explore', page: currentPage });
    assert.equal(result.status, 200);
  }
  assert.deepEqual(h.calls.filter(call => call.action === 'explore'), [
    { action: 'explore', url: 'https://fixture.example/fantasy', page: 1 },
    { action: 'explore', url: 'https://fixture.example/urban1', page: 1 },
    { action: 'explore', url: 'https://fixture.example/urban2', page: 1 },
    { action: 'explore', url: 'https://fixture.example/fantasy', page: 2 },
  ]);
  const rss = await h.importSource({ sourceUrl: 'https://fixture.example/feed', sourceName: 'RSS', enabled: true } as any, 'rss');
  await h.request('/v1/verify', { sourceId: rss.id, kind: 'rss' }, 'migration');
  const result = await h.request('/v1/rss/articles', { sourceId: rss.id, url: 'reader-cloud://articles', page: 2 });
  assert.equal(result.status, 200);
  assert.deepEqual(rssUrls.at(-1), { url: 'https://fixture.example/feed', page: 2 });
  assert.equal((await h.request('/v1/rss/articles', { sourceId: rss.id, url: 'reader-cloud://arbitrary' })).status, 400);
  h.database.raw.close();
});

test('a single source larger than the normal 256KiB chunk is fully archived and read back unchanged', async () => {
  const h = harness();
  const original = { bookSourceUrl: 'https://fixture.example/#large-original', bookSourceName: 'Fixture large source',
    jsLib: 'var fixtureLargeOriginal="' + 'x'.repeat(315_000) + '";', enabled: true, enabledExplore: false };
  const stored = await h.importSource(original as any);
  assert.ok(new TextEncoder().encode(stored.json).length > 256 * 1024);
  const exported = (await (await h.request(`/v1/source-export?id=${encodeURIComponent(stored.id)}`, undefined, 'admin')).json() as any).data;
  assert.equal(exported.json, stored.json);
  assert.equal(exported.sha256, await sha256(stored.json));
  h.database.raw.close();
});

test('runtime diagnostics are bounded and management-only while migration gets a fixed safe code', async () => {
  const message = 'Unsupported runtime request option';
  const h = harness({ search: async () => { throw new TypeError(message); } });
  const source = await h.importSource();
  const migration = (await (await h.request('/v1/verify', { sourceId: source.id }, 'migration')).json() as any).data;
  assert.equal(migration.reasonCode, 'RUNTIME_TYPE_ERROR');
  assert.equal(migration.managementDiagnostics, undefined);
  assert.ok(!JSON.stringify(migration).includes(message));
  const manager = (await (await h.request('/v1/verify', { sourceId: source.id }, 'admin')).json() as any).data;
  assert.equal(manager.managementDiagnostics.message, message);
  assert.ok(manager.managementDiagnostics.location.length <= 3);
  h.database.raw.close();
});

test('verified static website entries remain visible separately from feeds and cannot masquerade as readable articles', async () => {
  let feedCalls = 0;
  const h = harness({ assessSource, rssArticles: async () => { feedCalls++; return { articles: [] }; } });
  const website = await h.importSource({ sourceUrl: 'https://fixture.example/news', sourceName: 'Owned static website',
    sourceGroup: '都市', singleUrl: true, enabled: true, enableJs: true } as any, 'rss');
  assert.equal(website.result.data.receipts[0].supported, true);
  const verified = (await (await h.request('/v1/verify', { kind: 'rss', sourceId: website.id }, 'migration')).json() as any).data;
  assert.equal(verified.status, 'healthy');
  assert.equal(verified.verificationKind, 'web');
  assert.equal(verified.webUrl, website.id);
  assert.deepEqual(verified.stages, { website: true });
  assert.equal(feedCalls, 0);
  const catalogue = (await (await h.request('/v1/catalogue?kind=rss')).json() as any).data;
  assert.deepEqual(catalogue.routes, []);
  assert.equal(catalogue.webEntries.length, 1);
  assert.equal(catalogue.webEntries[0].sourceId, website.id);
  assert.equal(catalogue.webEntries[0].url, 'https://fixture.example/news');
  assert.equal(catalogue.webEntries[0].categoryId, 'urban');
  assert.equal(catalogue.categories.length, 1);
  const sources = (await (await h.request('/v1/sources?kind=rss&active=true')).json() as any).data.sources;
  assert.deepEqual(sources[0].capabilities, ['web']);
  assert.equal(sources[0].webUrl, website.id);
  const rejected = await h.request('/v1/rss/articles', { sourceId: website.id, url: website.id });
  assert.equal(rejected.status, 422);
  assert.equal((await rejected.json() as any).error.code, 'WEBSITE_ENTRY');
  h.database.raw.close();
});

test('website GET failures never grant healthy status and injected website sources remain archived unsupported', async () => {
  const h = harness({ assessSource, verifyWebEntry: async () => { throw new Error('private network failure'); } });
  const website = await h.importSource({ sourceUrl: 'https://fixture.example/news', sourceName: 'Owned website', singleUrl: true } as any, 'rss');
  const failed = (await (await h.request('/v1/verify', { kind: 'rss', sourceId: website.id }, 'migration')).json() as any).data;
  assert.equal(failed.verified, false);
  assert.equal(failed.status, 'pending');
  assert.equal(failed.verificationKind, 'web');
  assert.deepEqual((await (await h.request('/v1/catalogue?kind=rss')).json() as any).data.webEntries, []);
  const injected = await h.importSource({ sourceUrl: 'https://fixture.example/scripted', sourceName: 'Owned injected website',
    singleUrl: true, injectJs: 'document.body.innerHTML="not allowed"' } as any, 'rss');
  assert.equal(injected.result.data.receipts[0].supported, false);
  assert.equal(injected.result.data.receipts[0].status, 'unsupported');
  assert.equal((await h.request('/v1/verify', { kind: 'rss', sourceId: injected.id }, 'migration')).status, 422);
  h.database.raw.close();
});

test('a previously healthy website serving a 200 challenge, soft-404, parked or closed page is withdrawn on its first recheck',async()=>{
  let html='<title>Owned public website</title><main><h1>Public headlines</h1><p>Normal public articles.</p><nav>Login CAPTCHA</nav></main>';
  const live=createEngine({resolveHost:async()=>true,fetch:async()=>new Response(html,{headers:{'content-type':'text/html'}})});
  const h=harness({assessSource,verifyWebEntry:live.verifyWebEntry});
  const website=await h.importSource({sourceUrl:'https://fixture.example/news',sourceName:'Owned static website',
    sourceGroup:'都市',singleUrl:true,enabled:true,enableJs:true}as any,'rss');
  const normal=html;
  for(const [code,bad] of [
    ['WEBSITE_BLOCKED','<title>Just a moment...</title><main>Browser verification</main>'],
    ['WEBSITE_NOT_FOUND','<title>404 Not Found</title><main>Not here.</main>'],
    ['WEBSITE_PARKED','<title>Domain for sale</title><main>Contact the seller.</main>'],
    ['WEBSITE_CLOSED','<title>网站已关闭</title><main>Thank you.</main>'],
  ]){
    html=normal;
    const healthy=(await(await h.request('/v1/verify',{kind:'rss',sourceId:website.id},'migration')).json()as any).data;
    assert.equal(healthy.verified,true);assert.equal(healthy.status,'healthy');
    const before=(await(await h.request('/v1/catalogue?kind=rss')).json()as any).data;
    assert.equal(before.webEntries.length,1);
    html=bad;
    const rejected=(await(await h.request('/v1/verify',{kind:'rss',sourceId:website.id},'admin')).json()as any).data;
    assert.equal(rejected.verified,false);assert.equal(rejected.reasonCode,code);assert.equal(rejected.status,'pending');
    const stored=h.database.raw.prepare('SELECT json,sha256,failures,status FROM sources WHERE kind=? AND id=?').get('rss',website.id)as any;
    assert.equal(stored.failures,1,'Known bad HTML is quarantined on the first failed recheck');
    assert.equal(stored.json,website.json);assert.equal(stored.sha256,website.hash);
    const after=(await(await h.request('/v1/catalogue?kind=rss')).json()as any).data;
    assert.deepEqual(after.webEntries,[]);assert.notEqual(after.revision,before.revision);
    assert.deepEqual((await(await h.request('/v1/sources?kind=rss&active=true')).json()as any).data.sources,[]);
  }
  h.database.raw.close();
});

test('identical archive retries read back receipts without spending another source/index write', async () => {
  const h = harness();
  const first = await h.importSource();
  const original = h.database.raw.prepare('SELECT updated_at FROM sources').get() as any;
  const before = (h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n;
  const second = await h.importSource();
  const after = (h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n;
  assert.equal(second.hash, first.hash);
  assert.equal(second.result.data.receipts[0].stored, true);
  assert.equal(after - before, 0);
  assert.equal((h.database.raw.prepare('SELECT updated_at FROM sources').get() as any).updated_at, original.updated_at);
  h.database.raw.close();
});

test('category corrections apply to all merged raw routes, survive verification and use a monotonic revision without touching original rules', async () => {
  const h = harness();
  const source = await h.importSource();
  await h.request('/v1/verify',{sourceId:source.id},'migration');
  const initial = (await (await h.request('/v1/catalogue')).json() as any).data;
  const original = h.database.raw.prepare('SELECT json,sha256,status FROM sources').get();
  const correction = {kind:'book',sourceId:source.id,fromCategoryId:'urban',categoryId:'fantasy'};
  const applied = (await (await h.request('/v1/category-override',correction,'migration')).json() as any).data;
  assert.equal(applied.changed,true);
  assert.notEqual(applied.revision,initial.revision);
  assert.equal((h.database.raw.prepare("SELECT COUNT(*) AS n FROM catalogue_routes WHERE category_id='fantasy'").get() as any).n,3);
  assert.deepEqual(h.database.raw.prepare('SELECT json,sha256,status FROM sources').get(),original);
  const before = (h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n;
  const retried = (await (await h.request('/v1/category-override',correction,'migration')).json() as any).data;
  assert.equal(retried.changed,false); assert.equal(retried.revision,applied.revision);
  assert.equal((h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n,before);
  const retarget = { ...correction,fromCategoryId:'fantasy',categoryId:'history' };
  const next = (await (await h.request('/v1/category-override',retarget,'migration')).json() as any).data;
  assert.equal(next.changed,true); assert.notEqual(next.revision,applied.revision);
  assert.equal((await h.request('/v1/verify',{sourceId:source.id},'migration')).status,200);
  const catalogue = (await (await h.request('/v1/catalogue')).json() as any).data;
  assert.deepEqual(catalogue.categories.map((category:any)=>category.id),['history']);
  assert.equal((h.database.raw.prepare("SELECT COUNT(*) AS n FROM catalogue_routes WHERE category_id='history'").get() as any).n,3);
  const noChange = (await (await h.request('/v1/category-override',retarget,'migration')).json() as any).data;
  assert.equal(noChange.changed,false);
  h.database.raw.close();
});

test('category overrides reject unknown labels, read tokens, unverified sources and missing source routes', async () => {
  const h = harness();
  const source = await h.importSource();
  const payload = {kind:'book',sourceId:source.id,fromCategoryId:'urban',categoryId:'history'};
  assert.equal((await h.request('/v1/category-override',payload)).status,403);
  assert.equal((await h.request('/v1/category-override',{...payload,categoryId:'unbounded-new-category'},'migration')).status,400);
  assert.equal((await h.request('/v1/category-override',payload,'migration')).status,409);
  await h.request('/v1/verify',{sourceId:source.id},'migration');
  assert.equal((await h.request('/v1/category-override',{...payload,fromCategoryId:'romance'},'migration')).status,409);
  assert.equal((h.database.raw.prepare('SELECT COUNT(*) AS n FROM category_overrides').get() as any).n,0);
  h.database.raw.close();
});

test('a stale error-template content cache cannot bypass quality checks and ordinary short prose remains readable', async () => {
  let contentCalls = 0;
  const h = harness({ content: async (_source,book) => { contentCalls++; return {text:'這是正常的一段短章正文。',book}; } });
  const source = await h.importSource();
  const store = new SourceStore(h.env.DB);
  const row = (await store.get('book',source.id))!;
  const book = {bookUrl:'https://fixture.example/book',name:'Owned fixture'};
  const chapter = {url:'https://fixture.example/chapter',title:'第一章'};
  const key = await sha256(JSON.stringify(['book',source.id,source.hash,'content',[book,chapter,null],CONTENT_QUALITY_VERSION,
    [BOOK_METADATA_VERSION,sourceAdapterRevision,ENGINE_RULE_CONTRACT_VERSION,ENGINE_IMPLEMENTATION_VERSION]]));
  await store.cache(key,row,{content:'获取正文失败\n原书源响应超时，未取得正文。'},60_000);
  const first = (await (await h.request('/v1/books/content',{sourceId:source.id,book,chapter})).json() as any).data;
  assert.equal(first.content,'這是正常的一段短章正文。'); assert.equal(contentCalls,1);
  const second = (await (await h.request('/v1/books/content',{sourceId:source.id,book,chapter})).json() as any).data;
  assert.equal(second.content,first.content); assert.equal(contentCalls,1);
  h.database.raw.close();
});

test('empty, control-character and non-HTTP legacy IDs archive exactly as unsupported without aborting a full import', async () => {
  const h = harness({ assessSource });
  const ids = ['', ' ', 'https://fixture.example/\noriginal', 'https://fixture.example/\u0000original',
    'https://fixture.example/\toriginal', 'legacy-source:opaque-original'];
  for (const sourceKind of ['book','rss']) {
    const sources = await Promise.all(ids.map(async id => {
      const rule = sourceKind === 'book' ? {bookSourceUrl:id,bookSourceName:'Owned archived legacy'} : {sourceUrl:id,sourceName:'Owned archived legacy'};
      const json = JSON.stringify(rule);
      return {id,json,sha256:await sha256(json)};
    }));
    const imported = await h.request('/v1/import',{kind:sourceKind,sources},'migration');
    assert.equal(imported.status,200,await imported.clone().text());
    const receipts = (await imported.json() as any).data.receipts;
    assert.equal(receipts.length,ids.length);
    assert.ok(receipts.every((receipt:any)=>receipt.stored && !receipt.supported && receipt.status==='unsupported'));
    for (const source of sources) {
      const query = `?kind=${sourceKind}&id=${encodeURIComponent(source.id)}`;
      const receipt = (await (await h.request('/v1/source-receipt'+query)).json() as any).data;
      assert.equal(receipt.id,source.id); assert.equal(receipt.sha256,source.sha256);
      const exported = (await (await h.request('/v1/source-export'+query,undefined,'admin')).json() as any).data;
      assert.equal(exported.json,source.json); assert.equal(exported.id,source.id);
    }
    const listed = (await (await h.request(`/v1/sources?kind=${sourceKind}`)).json() as any).data.sources;
    assert.deepEqual(new Set(listed.map((source:any)=>source.id)),new Set(ids));
    assert.equal((await h.request(`/v1/source-receipt?kind=${sourceKind}`)).status,400);
  }
  const badJson = JSON.stringify({bookSourceUrl:''});
  assert.equal((await h.request('/v1/import',{kind:'book',sources:[{json:badJson,sha256:await sha256(badJson)}]},'migration')).status,400);
  h.database.raw.close();
});

test('revalidating 1,000 unchanged routes renews health without rewriting the route table or its indexes', async () => {
  const h = harness();
  const source = await h.importSource();
  const store = new SourceStore(h.env.DB);
  const row = (await store.get('book',source.id))!;
  const routes = Array.from({length:1000},(_,index)=>({label:`Owned route ${index}`,url:`https://fixture.example/genre/${index}`,
    categoryId:'urban',categoryLabel:'都市／現實',filterIds:[]}));
  await store.markHealthy(row,routes);
  const before = (h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n;
  await store.markHealthy(row,routes);
  const after = (h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n;
  assert.equal(after-before,1,'Only the source verification timestamp may be renewed');
  const changed = routes.slice(1).map((route,index)=>index===0?{...route,label:'Owned changed label'}:route);
  changed.push({...routes[0],url:'https://fixture.example/genre/new'});
  const prior = (h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n;
  await store.markHealthy(row,changed);
  assert.equal((h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n-prior,5,
    'One renewed source, three route differences and one small catalogue revision');
  assert.equal((await store.sourceRoutes(row)).length,1000);
  h.database.raw.close();
});

test('scheduled verification reuses a successful probe, is gated by configuration, and manual runs require management', async () => {
  const keys:string[]=[];
  const h=harness({search:async(_source,key)=>{keys.push(key);return [{bookUrl:'https://fixture.example/book',name:key}];}});
  const source=await h.importSource();
  await h.request('/v1/verify',{sourceId:source.id,key:'Owned previous successful probe'},'migration');
  h.database.raw.prepare('UPDATE sources SET verified_at=?').run(Date.now()-7*60*60_000);
  await h.app.scheduled({} as ScheduledController,h.env,{} as ExecutionContext);
  assert.equal(keys.length,1);
  await h.app.scheduled({} as ScheduledController,{...h.env,MAINTENANCE_ENABLED:'true'},{} as ExecutionContext);
  assert.deepEqual(keys,['Owned previous successful probe','Owned previous successful probe']);
  assert.equal((await h.request('/v1/maintenance/run',{},'read')).status,403);
  assert.equal((await h.request('/v1/maintenance/run',{},'migration')).status,403);
  const status=(await (await h.request('/v1/status')).json() as any).data.maintenance;
  assert.equal(status.lastTrigger,'scheduled');assert.equal(status.lastHealthy,1);
  h.database.raw.close();
});

test('a known public source uses its appropriate public verification sample when no prior probe exists', async () => {
  const keys:string[]=[];
  const h=harness({search:async(_source,key)=>{keys.push(key);return [{bookUrl:'https://fixture.example/book',name:key}];}});
  const source=await h.importSource({bookSourceUrl:'https://www.jjwxc.net/#reader-verified-20261026',bookSourceName:'Owned fixture',
    enabled:true,enabledExplore:true,exploreUrl:'https://fixture.example/genres'});
  const result=(await (await h.request('/v1/verify',{sourceId:source.id},'migration')).json() as any).data;
  assert.equal(result.verified,true);assert.deepEqual(keys,['开端']);
  h.database.raw.close();
});

test('two durable statistics rows avoid scanning 20,000 archived sources on every catalogue refresh',async()=>{
  const h=harness();
  h.database.raw.exec(`WITH RECURSIVE n(i) AS(SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<20000)
    INSERT INTO sources(kind,id,json,sha256,name,enabled,enabled_explore,supported,status,updated_at)
    SELECT 'book','owned-archive-'||i,'{}','archive','Owned archived fixture',1,0,0,'unsupported',0 FROM n`);
  const aggregates=()=>h.database.preparedSql.filter(sql=>sql.includes('SELECT COUNT(*) AS total')).length;
  for(let refresh=0;refresh<200;refresh++){
    const result=(await (await h.request('/v1/catalogue')).json() as any).data;
    assert.equal(result.totalSources,20000);assert.equal(result.checkedSources,20000);
  }
  assert.equal(aggregates(),1);
  const store=new SourceStore(h.env.DB),source=await h.importSource();
  assert.equal((await store.counts('book')).total,20001);assert.equal(aggregates(),2);
  await h.importSource();await store.counts('book');assert.equal(aggregates(),2,'Exact import retries retain the aggregate');
  await h.request('/v1/verify',{sourceId:source.id},'migration');
  assert.equal((await store.counts('book')).healthy,1);assert.equal(aggregates(),3);
  await h.request('/v1/verify',{sourceId:source.id},'migration');
  await store.counts('book');assert.equal(aggregates(),3,'Unchanged healthy rechecks retain the aggregate');
  const row=(await store.get('book',source.id))!;
  await store.failure(row);await store.counts('book');assert.equal(aggregates(),3,'First healthy-source failure does not change membership');
  await store.failure(row);assert.equal((await store.counts('book')).healthy,0);assert.equal(aggregates(),4);
  await store.failure(row);await store.counts('book');assert.equal(aggregates(),4,'Repeated quarantine does not invalidate the aggregate');
  const quarantined=(await store.get('book',source.id))!;
  await store.success(quarantined);
  assert.ok((await store.get('book',source.id))!.failures>0,'A successful chapter does not erase the full-chain quarantine/checked state');
  await store.counts('book');assert.equal(aggregates(),4);
  assert.equal((h.database.raw.prepare('SELECT COUNT(*) AS n FROM source_stats').get() as any).n,2);
  h.database.raw.close();
});

test('malformed image attributes in discovery names or authors cannot certify a source as healthy', async () => {
  const fragment='" width="120" height="168" alt="Owned fixture"/>';
  for (const badField of ['name','author']) {
    const h=harness({explore:async()=>[{bookUrl:'https://fixture.example/book',name:'Owned fixture',author:'Owned author',[badField]:fragment}]});
    const source=await h.importSource();
    const response=await h.request('/v1/verify',{sourceId:source.id},'admin');
    const result=(await response.json()as any).data;
    assert.equal(result.verified,false);assert.equal(result.reasonCode,'BOOK_METADATA_INVALID');
    assert.equal(result.stages.explore,false);assert.equal(result.status,'pending');
    const saved=h.database.raw.prepare('SELECT json,sha256 FROM sources').get()as any;
    assert.equal(saved.json,source.json);assert.equal(saved.sha256,source.hash);
    assert.deepEqual((await(await h.request('/v1/catalogue')).json()as any).data.routes,[]);
    h.database.raw.close();
  }
});

test('discovery verification continues after an empty or unavailable first category',async()=>{
  const h=harness({explore:async(_source,url)=>{
    if(url.endsWith('urban1'))throw new Error('temporary unavailable');
    if(url.endsWith('urban2'))return [];
    return [{bookUrl:'https://fixture.example/book',name:'Owned fixture'}];
  }});
  const source=await h.importSource();
  const result=(await(await h.request('/v1/verify',{sourceId:source.id},'migration')).json()as any).data;
  assert.equal(result.verified,true);assert.equal(result.stages.explore,true);
  h.database.raw.close();
});

test('malformed current and legacy metadata caches cannot leak to clients, and fresh malformed metadata is rejected',async()=>{
  let invalid=false,calls=0;
  const h=harness({search:async()=>{calls++;return [{bookUrl:'https://fixture.example/book',name:invalid?'" width="120" alt="wrong"/>':'Clean title',author:'Clean author'}];}});
  const source=await h.importSource();
  const store=new SourceStore(h.env.DB),row=(await store.get('book',source.id))!;
  const key='Owned query';
  const leaked={books:[{bookUrl:'https://fixture.example/book',name:'" width="120" alt="wrong"/>'}]};
  for(const suffix of [[0],[0,[BOOK_METADATA_VERSION,sourceAdapterRevision,ENGINE_RULE_CONTRACT_VERSION,ENGINE_IMPLEMENTATION_VERSION]]]){
    const cacheKey=await sha256(JSON.stringify(['book',source.id,source.hash,'search',[key,1],...suffix]));
    await store.cache(cacheKey,row,leaked,60_000);
  }
  const response=await h.request('/v1/search',{sourceId:source.id,key,page:1});
  assert.equal(response.status,200);assert.equal((await response.json()as any).data.books[0].name,'Clean title');
  assert.equal(calls,1);
  invalid=true;
  const rejected=await h.request('/v1/search',{sourceId:source.id,key:'Uncached query'});
  assert.equal(rejected.status,502);assert.equal((await rejected.json()as any).error.diagnosticCode,'BOOK_METADATA_INVALID');
  h.database.raw.close();
});

test('only corrupted input metadata is refreshed from the exact same book before ordinary reading',async()=>{
  let details=0,wrongIdentity=false,contentCalls=0;
  const h=harness({
    info:async(_source,book)=>{details++;return {...book,name:'Clean title',author:'Clean author',bookUrl:wrongIdentity?'https://fixture.example/other':book.bookUrl};},
    content:async(_source,book)=>{contentCalls++;return {text:'自行撰寫的正常正文。'.repeat(30),book};},
  });
  const source=await h.importSource();
  const original={bookUrl:'https://fixture.example/book',origin:source.id,name:'" width="120" alt="Owned fixture"/>',author:'Clean author',variable:'owned-variable'};
  const chapter={url:'https://fixture.example/chapter',title:'Owned chapter'};
  const repaired=await h.request('/v1/content',{sourceId:source.id,book:original,chapter});
  assert.equal(repaired.status,200);
  const value=(await repaired.json()as any).data.book;
  assert.equal(value.name,'Clean title');assert.equal(value.author,'Clean author');assert.equal(value.bookUrl,original.bookUrl);
  assert.equal(value.variable,original.variable);assert.equal(details,1);
  const ordinary=await h.request('/v1/content',{sourceId:source.id,book:{...original,name:'Existing valid title'},chapter});
  assert.equal(ordinary.status,200);assert.equal(details,1,'No detail request for ordinary names');
  assert.equal((await ordinary.json()as any).data.book.name,'Existing valid title');
  wrongIdentity=true;
  const rejected=await h.request('/v1/toc',{sourceId:source.id,tocInstallContract:TOC_INSTALL_CONTRACT,book:{...original,name:'<img alt="different"/>'}});
  assert.equal(rejected.status,502);assert.equal(details,2);assert.equal(contentCalls,2);
  h.database.raw.close();
});

test('static unsupported assessment is not reported as a real website probe',async()=>{
  const h=harness();
  const valid=await h.importSource();
  await h.request('/v1/verify',{sourceId:valid.id},'migration');
  await h.importSource({bookSourceUrl:'https://unprobed.example/',bookSourceName:'Owned pending',enabled:true}as any);
  await h.importSource({bookSourceUrl:'https://unsupported.example/',bookSourceName:'Owned unsupported',enabled:true,unsupported:true}as any);
  const counts=(await(await h.request('/v1/status')).json()as any).data.books;
  assert.equal(counts.total,3);assert.equal(counts.healthy,1);assert.equal(counts.pending,1);
  assert.equal(counts.unsupported,1);assert.equal(counts.probed,1);assert.equal(counts.checked,2);
  h.database.raw.close();
});

test('a reviewed provider alias inherits its successful small public verification profile instead of an unrelated default keyword',async()=>{
  const keys:string[]=[];
  const h=harness({search:async(_source,key)=>{keys.push(key);return [{bookUrl:'https://fixture.example/book',name:key}];}});
  const canonical=await h.importSource({bookSourceUrl:'https://www.heiyan.com/#reader-verified-20261026',bookSourceName:'Owned public canonical'}as any);
  assert.equal((await(await h.request('/v1/verify',{sourceId:canonical.id,key:'Owned small public work'},'migration')).json()as any).data.verified,true);
  const alias=await h.importSource({bookSourceUrl:'https://www.heiyan.com/',bookSourceName:'Owned old identifier'}as any);
  assert.equal((await(await h.request('/v1/verify',{sourceId:alias.id},'migration')).json()as any).data.verified,true);
  assert.deepEqual(keys,['Owned small public work','Owned small public work']);
  const saved=h.database.raw.prepare('SELECT json,sha256 FROM sources WHERE kind=? AND id=?').get('book',alias.id)as any;
  assert.equal(saved.json,alias.json);assert.equal(saved.sha256,alias.hash);
  h.database.raw.close();
});

test('reviewed provider aliases remain readable individually without duplicating new search, discovery or archived identities',async()=>{
  const h=harness();
  const alias=await h.importSource({bookSourceUrl:'https://www.heiyan.com/',bookSourceName:'Owned old identifier'}as any);
  await h.request('/v1/verify',{sourceId:alias.id,key:'Owned public work'},'migration');
  let active=(await(await h.request('/v1/sources?active=true')).json()as any).data.sources;
  assert.deepEqual(active.map((row:any)=>row.id),[alias.id]);
  const canonical=await h.importSource({bookSourceUrl:'https://www.heiyan.com/#reader-verified-20261026',bookSourceName:'Owned canonical'}as any);
  await h.request('/v1/verify',{sourceId:canonical.id,key:'Owned public work'},'migration');
  active=(await(await h.request('/v1/sources?active=true')).json()as any).data.sources;
  assert.deepEqual(active.map((row:any)=>row.id),[canonical.id]);
  const all=(await(await h.request('/v1/sources')).json()as any).data.sources;
  assert.equal(all.length,2,'The original aliases are still archived and visible to management');
  assert.equal((await h.request('/v1/search',{sourceId:alias.id,key:'Owned direct reading'})).status,200);
  const old=h.database.raw.prepare('SELECT json,sha256 FROM sources WHERE kind=? AND id=?').get('book',alias.id)as any;
  assert.equal(old.json,alias.json);assert.equal(old.sha256,alias.hash);
  h.database.raw.close();
});

test('content boundary hints are passed to the engine and separated in complete-content caches',async()=>{
  const hints:Array<string|undefined>=[];
  const h=harness({content:async(_source,book,_chapter,next)=>{hints.push(next);return {text:'自行撰寫的完整一章正文。'.repeat(20),book};}});
  const source=await h.importSource();
  const book={bookUrl:'https://fixture.example/book',name:'Owned title'},chapter={url:'https://fixture.example/chapter1',title:'Owned chapter'};
  for(const nextChapterUrl of ['https://fixture.example/chapter2','https://fixture.example/chapter3',null]){
    const response=await h.request('/v1/content',{sourceId:source.id,book,chapter,nextChapterUrl});
    assert.equal(response.status,200);const data=(await response.json()as any).data;
    assert.equal(data.complete,true);assert.equal(data.contractVersion,ENGINE_RULE_CONTRACT_VERSION);
    assert.equal(data.sourceId,source.id);assert.equal(data.bookUrl,book.bookUrl);assert.equal(data.chapterUrl,chapter.url);
    assert.equal(data.bodySha256,await sha256(data.content));
  }
  assert.deepEqual(hints,['https://fixture.example/chapter2','https://fixture.example/chapter3',undefined]);
  assert.equal((await h.request('/v1/content',{sourceId:source.id,book,chapter,nextChapterUrl:3})).status,400);
  h.database.raw.close();
});

test('complete directory metadata retains paid titles while source health probes only request a free chapter',async()=>{
  const requested:string[]=[];
  const h=harness({
    toc:async(_source,book)=>({book,chapters:[
      {url:'https://fixture.example/paid',title:'Owned paid title',isVip:true},
      {url:'https://fixture.example/free',title:'Owned free title',isVip:false},
      {url:'https://fixture.example/next-paid',title:'Owned next title',isVip:true},
    ]}),
    content:async(_source,book,chapter,next)=>{requested.push(chapter.url,next!);return {text:'自行撰寫的正常免費章節。'.repeat(20),book};},
  });
  const source=await h.importSource();
  const result=(await(await h.request('/v1/verify',{sourceId:source.id},'migration')).json()as any).data;
  assert.equal(result.verified,true);assert.deepEqual(requested,['https://fixture.example/free','https://fixture.example/next-paid']);
  const directory=(await(await h.request('/v1/toc',{sourceId:source.id,tocInstallContract:TOC_INSTALL_CONTRACT,book:{bookUrl:'https://fixture.example/book',name:'Owned title'}})).json()as any).data;
  assert.equal(directory.chapters.length,3);assert.equal(directory.chapters[0].isVip,true);
  h.database.raw.close();
});

test('complete directory requests require a client that can preserve chapter identity',async()=>{
  let calls=0;
  const h=harness({toc:async(_source,book)=>{calls++;return {book,chapters:[{url:'https://fixture.example/chapter',title:'Owned chapter'}]};}});
  const source=await h.importSource(),book={bookUrl:'https://fixture.example/book',name:'Owned title'};
  const approved={sourceId:source.id,book,tocInstallContract:TOC_INSTALL_CONTRACT};
  const valid=await h.request('/v1/books/toc',approved);
  assert.equal(valid.status,200);const data=(await valid.json()as any).data;
  assert.equal(data.complete,true);assert.equal(data.contractVersion,ENGINE_RULE_CONTRACT_VERSION);
  assert.equal(data.sourceId,source.id);assert.equal(data.bookUrl,book.bookUrl);
  assert.equal(calls,1);
  for(const tocInstallContract of [undefined,'older-contract',3]){
    const response=await h.request('/v1/books/toc',{sourceId:source.id,book,tocInstallContract});
    assert.equal(response.status,409);
    assert.equal((await response.json()as any).error.code,'CLIENT_UPDATE_REQUIRED');
  }
  assert.equal(calls,1,'Old clients cannot receive a new or already cached reordered directory');
  h.database.raw.close();
});

test('status and every catalogue page announce the supported content and TOC contracts',async()=>{
  const h=harness();
  const status=(await(await h.request('/v1/status')).json()as any).data;
  assert.equal(status.features.contentContractVersion,ENGINE_RULE_CONTRACT_VERSION);
  assert.equal(status.features.tocInstallContract,TOC_INSTALL_CONTRACT);
  for(const kind of ['book','rss']){
    const data=(await(await h.request('/v1/catalogue?kind='+kind)).json()as any).data;
    assert.equal(data.contentContractVersion,ENGINE_RULE_CONTRACT_VERSION);
  }
  h.database.raw.close();
});

test('a known incomplete public interface cannot be promoted by a nonempty preview chain, while direct reads and other sources remain available', async () => {
  const execution: string[] = [];
  const ownedBook = (source: any) => ({ bookUrl: new URL('/book/7', source.bookSourceUrl).href, name: 'Owned preview book', author: 'Owned author' });
  const h = harness({
    categories: async source => { execution.push('categories'); return [{ title: 'Owned genre', url: new URL('/genre', source.bookSourceUrl).href }]; },
    search: async source => { execution.push('search'); return Array.from({ length: 20 }, (_unused, index) => ({ ...ownedBook(source), name: 'Owned preview ' + index })); },
    info: async (_source, book) => { execution.push('info'); return { ...book, tocUrl: book.bookUrl + '/toc' }; },
    toc: async (_source, book) => { execution.push('toc'); return { book, chapters: Array.from({ length: 20 }, (_unused, index) => ({ url: book.bookUrl + '/chapter/' + index, title: 'Owned preview chapter ' + index, index })) }; },
    content: async (_source, book) => { execution.push('content'); return { book, text: '自行編寫的公開章節測試內容。'.repeat(40) }; },
    explore: async source => { execution.push('explore'); return [ownedBook(source)]; },
  });
  try {
    const held = await h.importSource({ bookSourceUrl: 'https://www.ciweimao.com/#owned-readiness-fixture', bookSourceName: 'Owned incomplete interface', enabled: true, enabledExplore: true, exploreUrl: 'https://www.ciweimao.com/genre' });
    const before = (h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n;
    const verified = (await (await h.request('/v1/verify', { sourceId: held.id }, 'migration')).json() as any).data;
    assert.equal(verified.verified, false);
    assert.equal(verified.reasonCode, 'PUBLIC_INTERFACE_REPAIR_PENDING');
    assert.deepEqual(verified.stages, {}); assert.equal(verified.stored, true); assert.equal(verified.supported, true); assert.equal(verified.sha256, held.hash);
    assert.deepEqual(execution, [], 'Readiness is checked before any search, preview, category or body HTTP operation');
    assert.equal((h.database.raw.prepare('SELECT total_changes() AS n').get() as any).n, before, 'A semantic hold is not a failed HTTP probe or a health update');
    const store = new SourceStore(h.env.DB), row = (await store.get('book', held.id))!;
    assert.equal(row.status, 'pending'); assert.equal(row.verified_at, 0); assert.equal(row.failures, 0); assert.equal(row.json, held.json); assert.equal(row.sha256, held.hash);
    assert.equal((await store.counts('book')).probed, 0);
    const direct = await h.request('/v1/content', { sourceId: held.id, book: ownedBook({ bookSourceUrl: held.id }), chapter: { url: 'https://www.ciweimao.com/chapter/11', title: 'Owned free chapter' } });
    assert.equal(direct.status, 200); assert.deepEqual(execution, ['content']);
    assert.equal((await store.get('book', held.id))!.status, 'pending', 'An individual free chapter does not certify the whole public interface');
    execution.length = 0;
    const other = await h.importSource({ bookSourceUrl: 'https://owned-ready.example.org/', bookSourceName: 'Owned complete interface', enabled: true, enabledExplore: true, exploreUrl: 'https://owned-ready.example.org/genre' });
    const normal = (await (await h.request('/v1/verify', { sourceId: other.id }, 'migration')).json() as any).data;
    assert.equal(normal.verified, true); assert.equal((await store.get('book', other.id))!.status, 'healthy');
    assert.deepEqual(execution, ['categories','search','info','toc','content','explore']);
  } finally { h.database.raw.close(); }
});
