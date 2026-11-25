import test from 'node:test';
import assert from 'node:assert/strict';
import { createEngine } from '../src/engine';

const root = 'https://owned-pages.example.org';
const book = { bookUrl: root + '/book/1', tocUrl: root + '/toc/1.html', name: '自寫分頁作品', author: '測試作者' };
const source = () => ({ bookSourceUrl: root + '/', bookSourceName: '自寫分頁來源',
  ruleToc: { chapterList: 'li.chapter', chapterName: 'a@text', chapterUrl: 'a@href', nextTocUrl: '.pages a@href' },
  ruleContent: { content: 'article@html', nextContentUrl: '.pages a@href' } });
const contentSource = () => ({ ...source(), ruleToc: {} });
const chapter = (url: string, title: string) => `<li class="chapter"><a href="${url}">${title}</a></li>`;
const links = (...urls: string[]) => '<div class="pages">' + urls.map(url => `<a href="${url}">頁碼</a>`).join('') + '</div>';
function fixture(pages: Record<string, string | Response>, extra: Record<string, any> = {}) {
  const calls: string[] = [];
  const engine = createEngine({ resolveHost: async () => true, ...extra, fetch: async (input, init) => {
    const url = String(input); calls.push(url); assert.equal(init?.redirect, 'manual');
    const value = pages[url]; assert.notEqual(value, undefined, 'No unlisted page or next chapter may be fetched');
    return value instanceof Response ? value : new Response(value, { headers: { 'content-type': 'text/html;charset=utf-8' } });
  } });
  return { engine, calls };
}

test('TOC chains resolve relative pages against each response and return every chapter only after completion', async () => {
  const { engine, calls } = fixture({
    [book.tocUrl]: chapter('/read/1', '第一章') + links('2.html'),
    [root + '/toc/2.html']: chapter('/read/2', '第二章') + links('3.html'),
    [root + '/toc/3.html']: chapter('/read/2', '第二章') + chapter('/read/3', '第三章') + links('#'),
  });
  const result = await engine.toc(source(), book);
  assert.deepEqual(result.chapters.map(item => [item.index, item.title]), [[0, '第一章'], [1, '第二章'], [2, '第三章']]);
  assert.deepEqual(calls, [book.tocUrl, root + '/toc/2.html', root + '/toc/3.html']);
});

test('enumerated TOC page lists ignore current, repeated and previous index links', async () => {
  const all = links('/toc/1.html', '/toc/2.html', '/toc/2.html', '/toc/3.html');
  const { engine, calls } = fixture({
    [book.tocUrl]: chapter('/read/1', '第一章') + all,
    [root + '/toc/2.html']: chapter('/read/2', '第二章') + all,
    [root + '/toc/3.html']: chapter('/read/3', '第三章') + all,
  });
  assert.equal((await engine.toc(source(), book)).chapters.length, 3);
  assert.equal(calls.length, 3);
  const fragmentIndex = fixture({
    [book.tocUrl]: chapter('/read/1', '第一章') + links('#', '2.html'),
    [root + '/toc/2.html']: chapter('/read/2', '第二章') + links('1.html', '#'),
  });
  assert.equal((await fragmentIndex.engine.toc(source(), book)).chapters.length, 2);
  assert.equal(fragmentIndex.calls.length, 2);
});

test('a genuine linear TOC cycle, failed later page or unsafe page never returns a first-page success', async () => {
  const cycle = fixture({ [book.tocUrl]: chapter('/read/1', '第一章') + links('2.html'),
    [root + '/toc/2.html']: chapter('/read/2', '第二章') + links('1.html') });
  await assert.rejects(cycle.engine.toc(source(), book), { code: 'PAGINATION_CYCLE' });
  const failure = fixture({ [book.tocUrl]: chapter('/read/1', '第一章') + links('2.html'),
    [root + '/toc/2.html']: new Response('failed', { status: 500 }) });
  await assert.rejects(failure.engine.toc(source(), book), { code: 'SOURCE_HTTP' });
  const unsafe = fixture({ [book.tocUrl]: chapter('/read/1', '第一章') + links('http://169.254.169.254/latest') });
  await assert.rejects(unsafe.engine.toc(source(), book), { code: 'UNSAFE_URL' });
  assert.equal(unsafe.calls.length, 1);
});

test('TOC limits fail closed on page count and total bytes instead of truncating', async () => {
  const pages: Record<string,string> = {};
  for (let page = 1; page <= 13; page++) pages[root + `/toc/${page}.html`] = chapter(`/read/${page}`, `自寫第${page}章`) + links(`${page + 1}.html`);
  const pageBound = fixture(pages);
  await assert.rejects(pageBound.engine.toc(source(), book), { code: 'PAGINATION_PAGE_LIMIT' });
  assert.equal(pageBound.calls.length, 12);
  const large = ' '.repeat(1_500_000), bytes = fixture({
    [book.tocUrl]: large + chapter('/read/1', '第一章') + links('2.html'),
    [root + '/toc/2.html']: large + chapter('/read/2', '第二章') + links('3.html'),
    [root + '/toc/3.html']: large + chapter('/read/3', '第三章'),
  });
  await assert.rejects(bytes.engine.toc(source(), book), { code: 'PAGINATION_BYTES_LIMIT' });
});

test('content joins all pages of the current chapter and stops before nextChapterUrl', async () => {
  const { engine, calls } = fixture({
    [root + '/read/1.html']: '<article>第一頁自寫正文。</article>' + links('1_2.html'),
    [root + '/read/1_2.html']: '<article>第二頁自寫正文。</article>' + links('2.html'),
  });
  const result = await engine.content(contentSource(), book, { url: root + '/read/1.html' }, '/read/2.html');
  assert.equal(result.text, '第一頁自寫正文。\n第二頁自寫正文。');
  assert.deepEqual(calls, [root + '/read/1.html', root + '/read/1_2.html']);
});

test('content enumerations retain page order and treat current-page or fragment links as terminal', async () => {
  const all = links('/read/1.html', '/read/1_2.html', '/read/1_3.html', '/read/2.html');
  const { engine, calls } = fixture({
    [root + '/read/1.html']: '<article>自寫第一頁。</article>' + all,
    [root + '/read/1_2.html']: '<article>自寫第二頁。</article>' + all,
    [root + '/read/1_3.html']: '<article>自寫第三頁。</article>' + all,
  });
  assert.equal((await engine.content(contentSource(), book, { url: root + '/read/1.html' }, '/read/2.html')).text,
    '自寫第一頁。\n自寫第二頁。\n自寫第三頁。');
  assert.equal(calls.length, 3);
  for (const end of ['#', '/read/1.html']) {
    const terminal = fixture({ [root + '/read/1.html']: '<article>自寫完整正文。</article>' + links(end) });
    assert.equal((await terminal.engine.content({ ...source(), ruleToc: {} }, book, { url: root + '/read/1.html' })).text, '自寫完整正文。');
    assert.equal(terminal.calls.length, 1);
  }
});

test('a redirect to the next chapter is rejected before the next chapter is fetched', async () => {
  const { engine, calls } = fixture({
    [root + '/read/1.html']: '<article>自寫第一頁。</article>' + links('1_2.html'),
    [root + '/read/1_2.html']: new Response(null, { status: 302, headers: { location: '/read/2.html' } }),
  });
  await assert.rejects(engine.content(contentSource(), book, { url: root + '/read/1.html' }, '/read/2.html'), { code: 'CONTENT_BOUNDARY' });
  assert.equal(calls.length, 2);
});

test('without a caller hint, a complete TOC supplies the next-chapter boundary', async () => {
  const { engine, calls } = fixture({
    [book.tocUrl]: chapter('/read/1.html', '第一章') + chapter('/read/2.html', '第二章'),
    [root + '/read/1.html']: '<article>自寫第一頁。</article>' + links('1_2.html'),
    [root + '/read/1_2.html']: '<article>自寫第二頁。</article>' + links('2.html'),
  });
  assert.equal((await engine.content(source(), book, { url: root + '/read/1.html' })).text, '自寫第一頁。\n自寫第二頁。');
  assert.equal(calls.length, 3);
  const missing = fixture({ [root + '/read/1.html']: '<article>自寫第一頁。</article>' + links('1_2.html') });
  await assert.rejects(missing.engine.content({ ...source(), ruleToc: {} }, book, { url: root + '/read/1.html' }), { code: 'MISSING_CHAPTER_BOUNDARY' });
  assert.equal(missing.calls.length, 1);
});

test('later content failures, gates and cycles discard the whole incomplete result', async () => {
  const first = root + '/read/1.html', second = root + '/read/1_2.html';
  for (const [body, code] of [[new Response('failed', { status: 500 }), 'SOURCE_HTTP'],
    ['<article>請登入以繼續閱讀</article>', 'CONTENT_GATE'],
    ['<article>自寫第二頁。</article>' + links('1.html'), 'PAGINATION_CYCLE']] as const) {
    const { engine } = fixture({ [first]: '<article>自寫第一頁。</article>' + links('1_2.html'), [second]: body });
    await assert.rejects(engine.content(contentSource(), book, { url: first }, '/read/2.html'), { code });
  }
});

test('content page and shared-time budgets reject incomplete chains', async () => {
  const pages: Record<string,string> = {};
  for (let page = 1; page <= 9; page++) pages[root + `/read/page${page}.html`] = '<article>自寫正文。</article>' + links(`page${page + 1}.html`);
  const bound = fixture(pages);
  await assert.rejects(bound.engine.content(contentSource(), book, { url: root + '/read/page1.html' }, '/read/next.html'), { code: 'PAGINATION_PAGE_LIMIT' });
  assert.equal(bound.calls.length, 8);
  const slow = createEngine({ deadlineMillis: 15, resolveHost: async () => true, fetch: async (input, init) => {
    if (String(input).endsWith('1.html')) return new Response('<article>自寫第一頁。</article>' + links('1_2.html'));
    return new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('aborted'))));
  } });
  await assert.rejects(slow.content(contentSource(), book, { url: root + '/read/1.html' }, '/read/2.html'), { code: 'SOURCE_TIMEOUT' });
});


test('complete TOC preserves paid entries, volume headings and stable indexes without requesting their bodies', async () => {
  const configured = { ...source(), ruleToc: { ...source().ruleToc,
    chapterName: 'a@text||.volume@text', isVolume: 'data-volume', isVip: 'data-vip' } };
  const { engine, calls } = fixture({
    [book.tocUrl]: '<li class="chapter" data-volume="true"><span class="volume">第一卷</span></li>' + chapter('/read/1.html', '第一章') + links('2.html'),
    [root + '/toc/2.html']: '<li class="chapter" data-vip="true"><a href="/read/2.html">付費章</a></li>' + chapter('/read/3.html', '公開尾章'),
    [root + '/read/1.html']: '<article>自寫第一頁。</article>' + links('1_2.html'),
    [root + '/read/1_2.html']: '<article>自寫第二頁。</article>' + links('2.html'),
  });
  const toc = await engine.toc(configured, book);
  assert.deepEqual(toc.chapters.map(item => [item.index, item.title, item.isVolume, item.isVip]),
    [[0, '第一卷', true, false], [1, '第一章', false, false], [2, '付費章', false, true], [3, '公開尾章', false, false]]);
  assert.equal(toc.chapters[0].url, '第一卷0');
  assert.equal((await engine.content(configured, book, toc.chapters[0])).text, '第一卷');
  assert.equal((await engine.content(configured, book, toc.chapters[1], toc.chapters[2].url)).text, '自寫第一頁。\n自寫第二頁。');
  await assert.rejects(engine.content(configured, book, toc.chapters[2]), { code: 'LOCKED_CHAPTER' });
  await assert.rejects(engine.content(configured, book, { ...toc.chapters[2], isVip: false, isPay: true }), { code: 'LOCKED_CHAPTER' });
  assert.equal(calls.filter(url => url.endsWith('/read/2.html')).length, 0);
});

test('only a volume proven by the complete directory can return its display title',async()=>{
  const configured={...source(),ruleToc:{...source().ruleToc,
    chapterName:'a@text||.volume@text',isVolume:'data-volume',isVip:'data-vip'}};
  const {engine,calls}=fixture({[book.tocUrl]:'<li class="chapter" data-volume="true"><span class="volume">第一卷</span></li>'+chapter('/read/1','第一章')});
  const volume={url:'第一卷0',title:'第一卷',isVolume:true};
  assert.equal((await engine.content(configured,book,volume)).text,'第一卷');
  assert.deepEqual(calls,[book.tocUrl],'Cold volume reads fetch only the directory, never a chapter body');
  for(const fake of [{...volume,title:'偽造卷名'},{url:root+'/read/1',title:'第一章',isVolume:true},
    {url:root+'/paid',title:'偽造付費卷',isVolume:true,isVip:true,isPay:true}]){
    await assert.rejects(engine.content(configured,book,fake),{code:'UNVERIFIED_VOLUME'});
  }
  assert.deepEqual(calls,[book.tocUrl]);
  await assert.rejects(engine.content({...configured,ruleContent:{content:'@js: unknownHost()'}},book,volume),{code:'UNSUPPORTED_SOURCE'});
  await assert.rejects(engine.content(contentSource(),book,volume),{code:'UNVERIFIED_VOLUME'});
  assert.deepEqual(calls,[book.tocUrl]);
});

test('a volume between chapters remains a display item while a cold content request uses the next real chapter boundary', async () => {
  const configured = { ...source(), ruleToc: { ...source().ruleToc,
    chapterName: 'a@text||.volume@text', isVolume: 'data-volume' } };
  const { engine, calls } = fixture({
    [book.tocUrl]: chapter('/read/1.html', '第一章') + '<li class="chapter" data-volume="true"><span class="volume">下卷</span></li>' + chapter('/read/2.html', '下一章'),
    [root + '/read/1.html']: '<article>本章自寫正文。</article>' + links('2.html'),
  });
  assert.equal((await engine.content(configured, book, { url: root + '/read/1.html' }, '下卷1')).text, '本章自寫正文。');
  assert.deepEqual(calls, [book.tocUrl, root + '/read/1.html']);
});

test('untrusted client isPay flags cannot authorize a paid body even before a TOC has been fetched', async () => {
  const { engine, calls } = fixture({});
  await assert.rejects(engine.content(contentSource(), book,
    { url: root + '/read/paid.html', isVip: true, isPay: true }), { code: 'LOCKED_CHAPTER' });
  assert.deepEqual(calls, []);
});
