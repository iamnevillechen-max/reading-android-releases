import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createEngine, sourceCapabilities } from '../src/engine';
import { sourceForExecution, canonicalSourceIdForExecution, sourceExecutionHosts, sourceVerificationHold } from '../src/source-adapters';

const canonicalId = 'https://www.heiyan.com/#reader-verified-20261026';
function aliasSource(extra: Record<string, any> = {}) {
  return { bookSourceUrl: 'https://www.heiyan.com/?owned-source=fixture', bookSourceName: '自寫來源名稱',
    bookSourceGroup: '自寫來源分組', bookSourceType: 0, enabled: true, enabledExplore: true, customOrder: 77, weight: 12,
    header: '{"User-Agent":"Owned original fixture"}',
    searchUrl: '/old-search', exploreUrl: '[{"title":"舊分類","url":"/old-route"}]',
    ruleSearch: { bookList: '@js: globalThis.unreviewedFamilyScriptRan = true;', name: 'old-name' },
    ruleExplore: { bookList: '@js: globalThis.unreviewedFamilyScriptRan = true;' },
    ruleBookInfo: { name: 'old-info' }, ruleToc: { chapterList: 'old-toc' }, ruleContent: { content: 'old-content' }, ...extra };
}

test('reviewed family execution restores every book operation for an alias ID without modifying archived source bytes', async () => {
  const source = aliasSource(), original = JSON.stringify(source);
  const before = createHash('sha256').update(original).digest('hex');
  const root = 'https://www.heiyan.com', bookUrl = root + '/book/123', chapterUrl = root + '/book/123/1';
  const calls: string[] = [];
  const pages: Record<string,string> = {
    [root + '/']: `<dl><dt><a href="${bookUrl}"><img src="/cover.jpg">" width="120" alt="錯誤殘片"/></a></dt>
      <dd><a href="${bookUrl}"><h3>自寫家族作品</h3></a><a href="${root}/people/42">自寫作者</a></dd></dl>`,
    ['https://search.heiyan.com/web/search?queryString=fixture&highlight=false&page=1&f=f']:
      JSON.stringify({ data: { content: [{ id: 123, name: '自寫家族作品', authorname: '自寫作者' }] } }),
    [bookUrl]: '<meta property="og:title" content="自寫家族作品"><meta property="og:novel:author" content="自寫作者">',
    [root + '/chapter/123']: `<ul><li id="chapter1"><a class="name" href="${chapterUrl}">自寫第一章</a></li></ul>`,
    [chapterUrl]: '<div class="page-content"><p>這是自行撰寫的公開測試正文。</p></div>',
  };
  const engine = createEngine({ resolveHost: async () => true, fetch: async (input) => {
    const url = String(input); calls.push(url); assert.ok(Object.hasOwn(pages, url), 'Only reviewed public endpoints are requested');
    return new Response(pages[url], { headers: { 'content-type': 'text/html;charset=utf-8' } });
  } });
  assert.equal(sourceCapabilities(source).supported, true);
  const categories = await engine.categories(source);
  assert.equal(categories[0].url, root + '/');
  const found = await engine.search(source, 'fixture');
  assert.equal(found[0].origin, source.bookSourceUrl);
  assert.equal(found[0].originName, source.bookSourceName);
  assert.equal(found[0].originOrder, 77);
  const discovered = await engine.explore(source, categories[0].url);
  assert.equal(discovered[0].name, '自寫家族作品');
  assert.equal(discovered[0].author, '自寫作者');
  const detail = await engine.info(source, found[0]);
  assert.equal(detail.tocUrl, root + '/chapter/123');
  assert.equal(detail.origin, source.bookSourceUrl);
  const toc = await engine.toc(source, detail);
  assert.equal(toc.chapters[0].url, chapterUrl);
  assert.equal((await engine.content(source, toc.book, toc.chapters[0])).text, '這是自行撰寫的公開測試正文。');
  assert.equal((globalThis as any).unreviewedFamilyScriptRan, undefined);
  assert.equal(JSON.stringify(source), original);
  assert.equal(createHash('sha256').update(JSON.stringify(source)).digest('hex'), before);
  const adapted = sourceForExecution(source);
  assert.equal(adapted.bookSourceUrl, source.bookSourceUrl);
  assert.equal(adapted.bookSourceName, source.bookSourceName);
  assert.equal(adapted.bookSourceGroup, source.bookSourceGroup);
  assert.equal(adapted.customOrder, 77);
  assert.equal(adapted.weight, 12);
  assert.ok(!calls.some(url => url.includes('old-search') || url.includes('old-route')));
});

test('a family is not inferred for authentication, private headers, non-text types or another provider domain', () => {
  for (const mutation of [
    { bookSourceType: 1 }, { bookSourceType: 2 },
    { loginUrl: 'https://www.heiyan.com/login' }, { loginUi: 'owned-login-fixture' }, { loginCheckJs: 'owned-login-check' },
    { injectJs: 'owned-injection' }, { shouldOverrideUrlLoading: 'owned-navigation-rule' },
    { header: '{"Cookie":"not-a-real-cookie"}' }, { header: '{"Authorization":"not-a-real-credential"}' },
    { header: '{"User-Agent":"@js:unreviewed()"}' }, { header: 'not JSON' },
    { bookSourceUrl: 'https://owned-reader.example.org/' },
    { bookSourceUrl: 'https://www.heiyan.com.evil.example.org/' },
    { bookSourceUrl: 'https://github.com/' },
    { bookSourceUrl: 'https://owner:password@www.heiyan.com/' },
    { bookSourceUrl: 'https://www.heiyan.com:8443/' },
  ]) {
    const source = aliasSource(mutation);
    assert.equal(sourceForExecution(source), source, 'A guarded source remains unmodified, without a family override');
    assert.equal(canonicalSourceIdForExecution(source), undefined);
    assert.equal(sourceExecutionHosts(source), undefined);
    assert.equal(sourceCapabilities(source).supported, false);
  }
  const alteredPublic = aliasSource({ bookSourceUrl: canonicalId });
  assert.equal(sourceCapabilities(alteredPublic).supported, false, 'A changed reviewed-ID script remains quarantined rather than silently replaced');
});

test('approved family aliases share a verification/provider key while identity, disabled flags and immutable templates stay separate', () => {
  const sources = [aliasSource(), aliasSource({ bookSourceUrl: 'https://www.heiyan.com/' }),
    aliasSource({ bookSourceUrl: 'https://search.heiyan.com/' })];
  assert.deepEqual([...new Set(sources.map(canonicalSourceIdForExecution))], [canonicalId]);
  for (const source of sources) {
    const adapted = sourceForExecution(source);
    assert.equal(adapted.bookSourceUrl, source.bookSourceUrl);
    assert.equal(adapted.bookSourceName, source.bookSourceName);
  }
  const disabled = aliasSource({ enabled: false, enabledExplore: false });
  const adapted = sourceForExecution(disabled);
  assert.equal(adapted.enabled, false); assert.equal(adapted.enabledExplore, false);
  assert.equal(sourceCapabilities(disabled).explore, false);
  adapted.ruleContent.content = 'owned-test-mutation';
  assert.equal(sourceForExecution(disabled).ruleContent.content, '.page-content@html');
  const hosts = sourceExecutionHosts(sources[0])!; hosts.push('owned-unapproved.example.org');
  assert.ok(!sourceExecutionHosts(sources[0])!.includes('owned-unapproved.example.org'));
  assert.deepEqual(sourceExecutionHosts(sources[0]), ['search.heiyan.com', 'www.heiyan.com']);
});


test('verification readiness policy matches only the exact reviewed book hostname and leaves execution capabilities intact', () => {
  const source = { bookSourceUrl: 'https://www.ciweimao.com/#owned-rule', bookSourceName: 'Owned public source',
    bookSourceType: 0, searchUrl: '/search?q={{key}}', ruleSearch: { bookList: 'a.book', name: 'text', bookUrl: 'href' }, ruleContent: { content: 'article@html' } };
  const original = JSON.stringify(source);
  assert.equal(sourceVerificationHold(source), 'PUBLIC_INTERFACE_REPAIR_PENDING');
  assert.equal(sourceCapabilities(source).supported, true); assert.equal(sourceCapabilities(source).content, true);
  assert.equal(sourceForExecution(source), source); assert.equal(JSON.stringify(source), original);
  for (const bookSourceUrl of ['https://m.ciweimao.com/', 'https://www.ciweimao.com.owned.example.org/', 'https://owned.example.org/', 'not a URL']) {
    assert.equal(sourceVerificationHold({ ...source, bookSourceUrl }), undefined);
  }
  assert.equal(sourceVerificationHold({ sourceUrl: 'https://www.ciweimao.com/', sourceName: 'Owned RSS identity' }), undefined);
});

test('Gson-reordered canonical rule members still receive the reviewed repair without accepting altered scripts', async () => {
  const { reviewedSources } = await import('../src/compiled-sources');
  const source = structuredClone(reviewedSources[canonicalId]);
  const rule = source.ruleExplore;
  source.ruleExplore = { bookList: rule.bookList, bookUrl: rule.bookUrl, name: rule.name };
  assert.notEqual(JSON.stringify(source.ruleExplore), JSON.stringify(rule), 'Fixture must reproduce the real Gson member order');
  const original = JSON.stringify(source), root = 'https://www.heiyan.com', bookUrl = root + '/book/123';
  const html = `<dl><dt><a href="${bookUrl}"><img src="/cover.jpg">" width="120" alt="污染殘片"/></a></dt>
    <dd><a href="${bookUrl}"><h3>自寫真實標題</h3></a><a href="${root}/people/42">自寫作者</a></dd></dl>`;
  const engine = createEngine({resolveHost:async()=>true,fetch:async()=>new Response(html,{headers:{'content-type':'text/html;charset=utf-8'}})});
  const books = await engine.explore(source, root + '/');
  assert.deepEqual(books.map(book => [book.bookUrl, book.name, book.author]), [[bookUrl, '自寫真實標題', '自寫作者']]);
  assert.equal(JSON.stringify(source), original, 'Original bytes and member order are immutable');
  source.ruleExplore.bookList += '\nunknownMemberOrderScript();';
  assert.equal(sourceCapabilities(source).supported, false);
  assert.equal(sourceForExecution(source), source, 'A genuinely different script must remain quarantined');
});
