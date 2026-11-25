import test from 'node:test';
import assert from 'node:assert/strict';
import { parseCiweimaoFreeChapter, decodeCiweimaoFreeChapter, extractCiweimaoFreeText, CiweimaoPublicError } from '../src/ciweimao-public';

const page = (paid = 0, reader = 0, extra = '') => `<html><script>
HB.book = {book_id: 7, chapter_id: 11, up_reader_id: 9, is_paid: ${paid}};
HB.userinfo = {reader_id: ${reader}, reader_name: 'guest', license: '', avatar_thumb_url: 'https://fixture.example/a.png'};
HB.config.bookread.useImage = 0; ${extra}
</script></html>`;
const url = 'https://www.ciweimao.com/chapter/11';
const encode = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));

async function frame(text: string, bytes: Uint8Array<ArrayBuffer>, ivByte: number) {
  const iv = new Uint8Array(16).fill(ivByte);
  const key = await crypto.subtle.importKey('raw', bytes, 'AES-CBC', false, ['encrypt']);
  const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CBC', iv }, key, new TextEncoder().encode(text)));
  const result = new Uint8Array(iv.length + cipher.length); result.set(iv); result.set(cipher, iv.length);
  return encode(result);
}

test('normal free guest context is bound to the requested official chapter', () => {
  const context = parseCiweimaoFreeChapter(page(), url);
  assert.deepEqual(context, { bookId: '7', chapterId: '11', chapterUrl: url, anonymous: true, isPaid: 0, imageMode: false });
  assert.equal(Object.isFrozen(context), true);
});

test('paid, authenticated, foreign-host and mismatched chapter contexts fail closed', () => {
  for (const [html, target] of [[page(1), url], [page(0, 2), url], [page(), 'https://www.ciweimao.com/chapter/12'],
                               [page(), 'https://www.ciweimao.com.evil.example/chapter/11']]) {
    assert.throws(() => parseCiweimaoFreeChapter(html, target), CiweimaoPublicError);
  }
});

test('literal parser rejects duplicate scope assignments and executable expressions without running them', () => {
  assert.throws(() => parseCiweimaoFreeChapter(page(0, 0, 'HB.book = {book_id:7,chapter_id:11,is_paid:0};'), url), CiweimaoPublicError);
  const executable = page().replace('is_paid: 0', 'is_paid: (()=>{throw new Error("must never execute")})()');
  assert.throws(() => parseCiweimaoFreeChapter(executable, url), CiweimaoPublicError);
  assert.throws(() => parseCiweimaoFreeChapter(page().replace('reader_id: 0,', 'reader_id: 2, fake:"reader_id:0",'), url), CiweimaoPublicError);
});

test('two-layer normal guest display format round-trips independently authored fixture text', async () => {
  const keyBytes = [new Uint8Array(32).fill(17), new Uint8Array(32).fill(29), new Uint8Array(32).fill(43)];
  const access = 'public-fixture-access-A';
  const order = [access.charCodeAt(access.length - 1) % keyBytes.length, access.charCodeAt(0) % keyBytes.length];
  const original = '<div><p>這是一段自行編寫的公開章節測試內容，沒有使用網站小說正文。</p></div>';
  const inside = await frame(original, keyBytes[order[1]], 31);
  const outside = await frame(inside, keyBytes[order[0]], 47);
  const result = await decodeCiweimaoFreeChapter(parseCiweimaoFreeChapter(page(), url),
    { code: 100000, encryt_keys: keyBytes.map(encode), chapter_content: outside }, access);
  assert.equal(result, original);
});

test('denied API, invalid frames and forged paid context never produce reading text', async () => {
  const context = parseCiweimaoFreeChapter(page(), url);
  await assert.rejects(decodeCiweimaoFreeChapter(context, { code: 200000 }, 'fixture-access'), CiweimaoPublicError);
  await assert.rejects(decodeCiweimaoFreeChapter(context,
    { code: 100000, encryt_keys: ['invalid'], chapter_content: 'AAAA' }, 'fixture-access'), CiweimaoPublicError);
  await assert.rejects(decodeCiweimaoFreeChapter({ ...context, isPaid: 1 } as any,
    { code: 100000, encryt_keys: [], chapter_content: '' }, 'fixture-access'), CiweimaoPublicError);
});

test('reviewed presentation markers are excluded while authored paragraphs and word count remain exact', () => {
  const expected = ['這是自行編寫的第一段。', '另一段正常測試正文。'];
  const count = Array.from(expected.join('')).length;
  const context = parseCiweimaoFreeChapter(page() + `<div class="read-hd">本章字数：${count}</div>`, url);
  const html = expected.map(text => `<p class="chapter">　　${text}<span>wmA123</span></p>`).join('\n');
  const result = extractCiweimaoFreeText(context, html);
  assert.equal(result.text, expected.join('\n'));
  assert.equal(result.paragraphs, 2);
  assert.equal(result.normalisedCharacters, count);
  assert.equal(result.removedMarkerCharacters, 12);
});

test('visible inline text, notices and changed word counts are not silently removed or accepted', () => {
  const context = parseCiweimaoFreeChapter(page(), url);
  for (const markup of ['<p class="chapter">正文<span>實際強調的文字</span></p>',
                        '<p class="chapter">正文<span>Copyright notice</span></p>',
                        '<p class="chapter">前<span>wmA123</span>後</p>']) {
    assert.throws(() => extractCiweimaoFreeText(context, markup), CiweimaoPublicError);
  }
  assert.throws(() => extractCiweimaoFreeText({ ...context, declaredWordCount: 4 }, '<p class="chapter">正文。</p>'),
    CiweimaoPublicError);
});
