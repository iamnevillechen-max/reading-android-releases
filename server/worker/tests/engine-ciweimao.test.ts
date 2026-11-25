import test from 'node:test';
import assert from 'node:assert/strict';
import { createEngine, sourceCapabilities } from '../src/engine';

const origin = 'https://www.ciweimao.com', chapterUrl = origin + '/chapter/11';
const book = { bookUrl: origin + '/book/7', tocUrl: origin + '/chapter-list/7', name: '自寫測試作品' };
const source = { bookSourceUrl: origin, bookSourceName: '自寫測試來源', bookSourceType: 0,
  header: JSON.stringify({ 'User-Agent': 'Owned-source-fixture', Referer: 'https://owned-source.example.org/private-placeholder' }),
  ruleContent: { content: '#J_BookCnt@html' } };
const text = '這是自行編寫的公開章節測試。';
const count = Array.from(text.replace(/\s/gu, '')).length;
const page = (paid = 0, reader = 0, words: number | undefined = count) => `<html><div class="read-hd">${words === undefined ? '' : '字數：' + words}</div><script>
HB.book = {book_id:7,chapter_id:11,is_paid:${paid}};
HB.userinfo = {reader_id:${reader}};
HB.config.bookread.useImage = 0;
</script><div id="J_BookCnt"></div></html>`;
const sessionUrl = origin + '/chapter/ajax_get_session_code', detailUrl = origin + '/chapter/get_book_chapter_detail_info';
const encode = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
async function detail(content = `<p class="chapter">${text}<span>abc123</span></p>`) {
  const keys = [new Uint8Array(32).fill(17), new Uint8Array(32).fill(29), new Uint8Array(32).fill(43)];
  const access = 'fixture-only+&key';
  const order = [access.charCodeAt(access.length - 1) % keys.length, access.charCodeAt(0) % keys.length];
  let encoded = content;
  for (const [round, index] of [...order].reverse().entries()) {
    const iv = new Uint8Array(16).fill(31 + round);
    const key = await crypto.subtle.importKey('raw', keys[index], 'AES-CBC', false, ['encrypt']);
    const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CBC', iv }, key, new TextEncoder().encode(encoded)));
    const frame = new Uint8Array(iv.length + cipher.length); frame.set(iv); frame.set(cipher, iv.length); encoded = encode(frame);
  }
  return { access, body: { code: 100000, encryt_keys: keys.map(encode), chapter_content: encoded } };
}

test('reviewed Ciweimao content uses the normal three-call anonymous flow and returns only counted visible reading text', async () => {
  const proof = await detail(), calls: Array<{url:string;headers:Headers;method:unknown;body:unknown}> = [];
  const engine = createEngine({ resolveHost: async () => true, fetch: async (input, init) => {
    const url = String(input), headers = new Headers(init?.headers);
    calls.push({ url, headers, method: init?.method, body: init?.body });
    assert.equal(init?.redirect, 'manual');
    assert.notEqual(headers.get('user-agent'), 'Owned-source-fixture');
    assert.equal(headers.has('authorization'), false);
    if (url === chapterUrl) {
      assert.equal(headers.has('cookie'), false, 'Every chapter read starts a new guest session');
      assert.equal(headers.has('referer'), false, 'Imported private headers cannot enter this flow');
      return new Response(page(), { headers: { 'Set-Cookie': 'owned_guest=fixture-first; Path=/; Secure' } });
    }
    assert.equal(headers.get('x-requested-with'), 'XMLHttpRequest');
    assert.equal(headers.get('origin'), origin);
    assert.equal(headers.get('referer'), chapterUrl);
    assert.equal(headers.get('content-type'), 'application/x-www-form-urlencoded;charset=UTF-8');
    assert.equal(init?.method, 'POST');
    const fields = new URLSearchParams(String(init?.body));
    assert.equal(fields.get('chapter_id'), '11');
    if (url === sessionUrl) {
      assert.equal(headers.get('cookie'), 'owned_guest=fixture-first');
      assert.deepEqual([...fields.keys()], ['chapter_id']);
      return Response.json({ code: 100000, chapter_access_key: proof.access }, { headers: { 'Set-Cookie': 'owned_guest=fixture-rotated; Path=/; Secure' } });
    }
    assert.equal(url, detailUrl);
    assert.equal(fields.get('chapter_access_key'), proof.access);
    assert.equal(headers.get('cookie'), 'owned_guest=fixture-rotated');
    return Response.json(proof.body);
  } });
  assert.equal(sourceCapabilities(source).supported, true);
  for (let run = 0; run < 2; run++) {
    const result = await engine.content(source, book, { url: chapterUrl });
    assert.equal(result.text, text);
    assert.equal(result.book.variable, undefined, 'Session access metadata must never enter returned book state');
  }
  assert.deepEqual(calls.map(call => call.url), [chapterUrl, sessionUrl, detailUrl, chapterUrl, sessionUrl, detailUrl]);
});

test('paid and authenticated pages, mismatched books and missing completeness proof stop before session/detail APIs', async () => {
  for (const [body, inputBook, code] of [
    [page(1), book, 'PUBLIC_CHAPTER_LOCKED'], [page(0, 9), book, 'PUBLIC_CHAPTER_AUTH'],
    [page(), { ...book, bookUrl: origin + '/book/8' }, 'PUBLIC_CHAPTER_IDENTITY'],
    [page().replace('字數：' + count, ''), book, 'PUBLIC_CHAPTER_INCOMPLETE'],
  ] as const) {
    const calls: string[] = [];
    const engine = createEngine({ resolveHost: async () => true, fetch: async input => { calls.push(String(input)); return new Response(body); } });
    await assert.rejects(engine.content(source, inputBook, { url: chapterUrl }), { code });
    assert.deepEqual(calls, [chapterUrl]);
  }
});

test('client/source credentials, paid flags, unknown scripts and foreign endpoints cannot grant a guest reading request', async () => {
  const calls: string[] = [], engine = createEngine({ resolveHost: async () => true, fetch: async input => {
    calls.push(String(input)); throw new Error('Unreviewed request must never be sent');
  } });
  await assert.rejects(engine.content(source, book, { url: chapterUrl, isVip: true, isPay: true }), { code: 'LOCKED_CHAPTER' });
  await assert.rejects(engine.content({ ...source, header: '{"Cookie":"fixture-not-real"}' }, book, { url: chapterUrl }), { code: 'UNSUPPORTED_SOURCE' });
  await assert.rejects(engine.content({ ...source, ruleContent: { content: '@js: globalThis.ownerCookie' } }, book, { url: chapterUrl }), { code: 'UNSUPPORTED_SOURCE' });
  await assert.rejects(engine.content(source, book, { url: 'https://other.example.org/chapter/11' }), { code: 'PUBLIC_CHAPTER_URL' });
  assert.deepEqual(calls, []);
});

test('guest API denial and redirects fail without fetching a new chapter or returning partial content', async () => {
  const denied: string[] = [];
  const engine = createEngine({ resolveHost: async () => true, fetch: async input => {
    const url = String(input); denied.push(url);
    if (url === chapterUrl) return new Response(page());
    assert.equal(url, sessionUrl); return Response.json({ code: 200000 });
  } });
  await assert.rejects(engine.content(source, book, { url: chapterUrl }), { code: 'PUBLIC_CHAPTER_DENIED' });
  assert.deepEqual(denied, [chapterUrl, sessionUrl]);
  const redirects: string[] = [], redirected = createEngine({ resolveHost: async () => true, fetch: async input => {
    redirects.push(String(input)); return new Response(null, { status: 302, headers: { location: origin + '/chapter/12' } });
  } });
  await assert.rejects(redirected.content(source, book, { url: chapterUrl }), { code: 'PUBLIC_GUEST_REDIRECT' });
  assert.deepEqual(redirects, [chapterUrl]);
});

test('the three guest calls share the existing total-byte and absolute-time budgets', async () => {
  const padding = ' '.repeat(1_500_000), proof = await detail();
  const engine = createEngine({ resolveHost: async () => true, fetch: async input => {
    const url = String(input);
    return new Response(padding + (url === chapterUrl ? page() : JSON.stringify(url === sessionUrl ? { code: 100000, chapter_access_key: proof.access } : proof.body)));
  } });
  await assert.rejects(engine.content(source, book, { url: chapterUrl }), { code: 'PAGINATION_BYTES_LIMIT' });
  const slow = createEngine({ deadlineMillis: 15, resolveHost: async () => true, fetch: async (input, init) => {
    if (String(input) === chapterUrl) return new Response(page());
    return new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('aborted'))));
  } });
  await assert.rejects(slow.content(source, book, { url: chapterUrl }), { code: 'SOURCE_TIMEOUT' });
});
