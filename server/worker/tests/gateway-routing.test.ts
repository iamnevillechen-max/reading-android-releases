import test from 'node:test';
import assert from 'node:assert/strict';
import { createGateway, createRuntimeReceiver, SOURCE_RUNTIME_NAME, type RuntimeEnv } from '../src/gateway-routing';
import { createApp, sha256, type EngineApi, type Env } from '../src/index';
import { SourceStore } from '../src/storage';
import { maintenanceStatus } from '../src/maintenance';
import { TestDatabase } from './d1';

const tokens = { ADMIN_TOKEN: 'owned-admin-'.repeat(4), MIGRATION_TOKEN: 'owned-migration-'.repeat(4), READ_TOKEN: 'owned-reader-'.repeat(4) };
function namespace(fetcher: (request: Request) => Promise<Response>) {
  const names: string[] = [], requests: Request[] = [];
  const binding = { idFromName(name: string) { names.push(name); return { name }; }, get() { return { fetch(request: Request) { requests.push(request); return fetcher(request); } }; } } as unknown as DurableObjectNamespace;
  return { binding, names, requests };
}
const event = { cron: '*/30 * * * *', scheduledTime: 1700000000000, noRetry() {} } as ScheduledController;

test('the gateway rejects unauthenticated/browser requests before constructing a DO and preserves public health', async () => {
  const target = namespace(async () => { throw new Error('Unauthorized request reached runtime'); });
  const env = { ...tokens, SOURCE_RUNTIME: target.binding } as RuntimeEnv, gateway = createGateway();
  const headerCases: Record<string,string>[] = [{}, { Authorization: 'Bearer wrong' }, { Authorization: 'Bearer ' + tokens.READ_TOKEN, Origin: 'https://owned-foreign.example.org' }];
  for (const headers of headerCases) {
    const request = new Request('https://reader.example/v1/status', { headers });
    const result = await gateway.fetch(request, env);
    assert.equal(result.status, 'Origin' in headers ? 403 : 401);
    const text = await result.text(); for (const secret of Object.values(tokens)) assert.equal(text.includes(secret), false);
  }
  for (const path of ['/health', '/health/']) assert.equal(await (await gateway.fetch(new Request('https://reader.example' + path), env)).text(), 'ok');
  assert.deepEqual(target.names, []);
});

test('authorized API bodies and response streams pass through without parsing, cloning or serialization in the gateway', async () => {
  let inputReads = 0, outputReads = 0;
  const input = new ReadableStream<Uint8Array>({ pull(controller) { inputReads++; controller.enqueue(new TextEncoder().encode('{"owned":"request"}')); controller.close(); } }, { highWaterMark: 0 });
  const output = new ReadableStream<Uint8Array>({ pull(controller) { outputReads++; controller.enqueue(new Uint8Array(256 * 1024).fill(65)); controller.close(); } }, { highWaterMark: 0 });
  const response = new Response(output, { status: 207, headers: { 'X-Owned-Contract': 'unchanged' } });
  const original = new Request('https://reader.example/v1/search?kind=book', { method: 'POST', headers: { Authorization: 'Bearer ' + tokens.READ_TOKEN }, body: input, duplex: 'half' } as RequestInit);
  const target = namespace(async request => { assert.equal(request, original); assert.equal(request.bodyUsed, false); return response; });
  const env = { ...tokens, SOURCE_RUNTIME: target.binding } as RuntimeEnv;
  const forwarded = await createGateway().fetch(original, env);
  assert.equal(forwarded, response); assert.equal(forwarded.status, 207); assert.equal(forwarded.headers.get('X-Owned-Contract'), 'unchanged');
  assert.equal(original.bodyUsed, false); assert.equal(forwarded.bodyUsed, false); assert.equal(inputReads, 0); assert.equal(outputReads, 0);
  assert.deepEqual(target.names, [SOURCE_RUNTIME_NAME]);
  assert.equal((await forwarded.arrayBuffer()).byteLength, 256 * 1024);
  await original.body?.cancel();
});

test('all roles share one runtime identity and public requests cannot invoke the private scheduled hop', async () => {
  const target = namespace(async () => new Response('owned'));
  const env = { ...tokens, SOURCE_RUNTIME: target.binding } as RuntimeEnv, gateway = createGateway();
  for (const token of Object.values(tokens)) assert.equal((await gateway.fetch(new Request('https://reader.example/v1/status', { headers: { Authorization: 'Bearer ' + token } }), env)).status, 200);
  assert.deepEqual(target.names, [SOURCE_RUNTIME_NAME, SOURCE_RUNTIME_NAME, SOURCE_RUNTIME_NAME]);
  const before = target.requests.length;
  assert.equal((await gateway.fetch(new Request('https://reader.example/_reader-runtime/scheduled', { method: 'POST', headers: { Authorization: 'Bearer ' + tokens.ADMIN_TOKEN } }), env)).status, 404);
  assert.equal(target.requests.length, before);
});

test('schedules forward to the same private runtime, remain configuration gated and require admin authentication inside', async () => {
  const invoked: ScheduledController[] = [];
  const app = { fetch: async () => new Response('application'), scheduled: async (controller: ScheduledController) => { invoked.push(controller); } };
  let env: RuntimeEnv;
  const target = namespace(async request => createRuntimeReceiver(app, env, {} as ExecutionContext).fetch(request));
  env = { ...tokens, SOURCE_RUNTIME: target.binding, MAINTENANCE_ENABLED: 'false' } as RuntimeEnv;
  const gateway = createGateway(); await gateway.scheduled(event, env); assert.equal(target.requests.length, 0);
  env.MAINTENANCE_ENABLED = 'true'; await gateway.scheduled(event, env);
  assert.equal(invoked.length, 1); assert.equal(invoked[0].cron, event.cron); assert.equal(invoked[0].scheduledTime, event.scheduledTime);
  assert.deepEqual(target.names, [SOURCE_RUNTIME_NAME]); assert.equal(target.requests[0].body, null);
  const receiver = createRuntimeReceiver(app, env, {} as ExecutionContext);
  for (const [origin, token] of [['https://reader-runtime.internal', tokens.READ_TOKEN], ['https://reader.example', tokens.ADMIN_TOKEN]]) {
    const result = await receiver.fetch(new Request(origin + '/_reader-runtime/scheduled', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'X-Reader-Scheduled-Time': String(event.scheduledTime) } }));
    assert.equal(result.status, 404);
  }
  assert.equal(invoked.length, 1);
});

test('scheduled work inside the runtime retains the real D1 two-source and hard 5000-write contract', async () => {
  const database = new TestDatabase(), db = database as unknown as D1Database, store = new SourceStore(db), probes: string[] = [];
  const engine: EngineApi = {
    assessSource: () => ({ supported: true }), categories: async () => [],
    search: async (source) => { probes.push(source.bookSourceUrl); return [{ bookUrl: source.bookSourceUrl + '/book', name: 'Owned fixture', author: 'Owned author' }]; },
    info: async (_source, book) => ({ ...book, tocUrl: book.bookUrl + '/toc' }),
    toc: async (_source, book) => ({ book, chapters: [{ url: book.bookUrl + '/chapter', title: 'Owned chapter', index: 0 }] }),
    content: async (_source, book) => ({ book, text: '自行編寫的排程測試正文。'.repeat(40) }), explore: async () => [],
    rssArticles: async () => ({ articles: [] }), rssContent: async () => '', verifyWebEntry: async () => ({ url: '', contentType: '' }),
  };
  let env: RuntimeEnv; const app = createApp(engine);
  const target = namespace(async request => createRuntimeReceiver(app, env, {} as ExecutionContext).fetch(request));
  env = { ...tokens, DB: db, SOURCE_RUNTIME: target.binding, MAINTENANCE_ENABLED: 'true', MAINTENANCE_DAILY_WRITES: '999999' };
  try {
    for (let index = 0; index < 6; index++) {
      const id = 'https://owned-maintenance.example.org/' + index, json = JSON.stringify({ bookSourceUrl: id });
      await store.importSources([{ kind: 'book', id, json, sha256: await sha256(json), name: 'Owned maintenance', enabled: true,
        enabledExplore: false, supported: true, capabilities: ['search','info','chapters','content'] }]);
    }
    const gateway = createGateway(); await gateway.scheduled(event, env);
    assert.equal(probes.length, 2);
    let status = await maintenanceStatus(db, true, 5000);
    assert.equal(status.lastProcessed, 2); assert.equal(status.lastTrigger, 'scheduled'); assert.equal(status.dailyWriteLimit, 5000); assert.ok(status.dailyReservedWrites <= 5000);
    const day = new Date().toISOString().slice(0, 10);
    database.raw.prepare('UPDATE maintenance_daily SET reserved_writes=4990 WHERE day=?').run(day);
    await gateway.scheduled(event, env); assert.equal(probes.length, 2, 'No source starts when the next reservation exceeds the daily cap');
    status = await maintenanceStatus(db, true, 5000); assert.equal(status.dailyReservedWrites, 4990);
    assert.deepEqual(target.names, [SOURCE_RUNTIME_NAME, SOURCE_RUNTIME_NAME]);
  } finally { database.raw.close(); }
});
