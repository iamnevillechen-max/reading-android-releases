import test from 'node:test';
import assert from 'node:assert/strict';
import { createEngine, sourceCapabilities } from '../src/engine';

const root = 'https://owned-engine-xpath.example.org';
const source = () => ({ bookSourceUrl: root + '/', bookSourceName: '自寫XPath來源', searchUrl: '/search',
  ruleSearch: { bookList: '@XPath://ul[@id="books"]/li', name: './/h2/text()', author: './/span[@class="author"]/text()',
    bookUrl: './/a/@href', kind: '@XPath:/html/body/header/@data-kind' },
  ruleBookInfo: { name: '@XPath:normalize-space(//h1/text())', author: '.author@text', tocUrl: '@XPath://a[@rel="toc"]/@href' },
  ruleToc: { chapterList: '//ol/li', chapterName: './/a/text()', chapterUrl: './/a/@href', nextTocUrl: '@XPath://nav/a/@href' },
  ruleContent: { content: '@XPath://article', nextContentUrl: '@XPath://nav/a/@href' } });

test('XPath and CSS share the same row DOM, and XPath page URL lists retain every page', async () => {
  const pages: Record<string,string> = {
    [root + '/search']: '<header data-kind="自寫分類"><h2>不應套進空列的全頁標題</h2></header><ul id="books">'
      + '<li><a href="/book/empty">缺標題列</a></li><li><h2>自寫XPath作品</h2><span class="author">自寫作者</span><a href="/book/1">書籍</a></li></ul>',
    [root + '/book/1']: '<h1>自寫XPath作品</h1><span class="author">自寫作者</span><a rel="toc" href="/toc/1.html">目錄</a>',
    [root + '/toc/1.html']: '<ol><li><a href="/read/1.html">第一章</a></li></ol><nav><a href="1.html">1</a><a href="2.html">2</a></nav>',
    [root + '/toc/2.html']: '<ol><li><a href="/read/2.html">第二章</a></li></ol><nav><a href="1.html">1</a><a href="#">2</a></nav>',
    [root + '/read/1.html']: '<article><p>自行撰寫的第一頁。</p></article><nav><a href="1_2.html">下一頁</a></nav>',
    [root + '/read/1_2.html']: '<article><p>自行撰寫的第二頁。</p></article><nav><a href="2.html">下一章</a></nav>',
  };
  const calls: string[] = [];
  const engine = createEngine({ resolveHost: async () => true, fetch: async input => {
    const url = String(input); calls.push(url); assert.ok(Object.hasOwn(pages, url)); return new Response(pages[url]);
  } });
  const selected = source();
  assert.equal(sourceCapabilities(selected).supported, true);
  const books = await engine.search(selected, 'test');
  assert.equal(books.length, 1);
  assert.equal(books[0].name, '自寫XPath作品'); assert.equal(books[0].author, '自寫作者'); assert.equal(books[0].kind, '自寫分類');
  const mixed = await engine.search({ ...selected, ruleSearch: { ...selected.ruleSearch, bookList: '#books@li' } }, 'test');
  assert.equal(mixed.length, 1); assert.equal(mixed[0].name, books[0].name); assert.equal(mixed[0].kind, '自寫分類');
  const book = await engine.info(selected, books[0]);
  const toc = await engine.toc(selected, book);
  assert.deepEqual(toc.chapters.map(chapter => chapter.title), ['第一章', '第二章']);
  assert.equal((await engine.content(selected, toc.book, toc.chapters[0])).text, '自行撰寫的第一頁。\n自行撰寫的第二頁。');
  assert.ok(!calls.includes(root + '/read/2.html'), 'The next chapter is a boundary, never a content page');
});

test('XPath assessment rejects I/O or extension functions and does not mistake relative POST URLs for XPath', () => {
  const selected = source();
  for (const expression of ['@XPath:doc("https://owned-external.example.org/")', '@XPath:java:exec("owned")', '@XPath://a[$owned]']) {
    assert.equal(sourceCapabilities({ ...selected, ruleSearch: { ...selected.ruleSearch, name: expression } }).supported, false);
  }
  assert.equal(sourceCapabilities({ ...selected, searchUrl: '/search,{"method":"POST","body":"keyword={{key}}"}' }).supported, true);
});
