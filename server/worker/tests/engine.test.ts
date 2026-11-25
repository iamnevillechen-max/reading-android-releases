import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createEngine, sourceCapabilities, isPublicHost, isPublicAddress } from '../src/engine';

const sources = JSON.parse(await readFile(new URL('../../../upstream/app/src/main/assets/reader-verified-sources.json', import.meta.url), 'utf8'));
const source = (domain: string) => sources.find((source: any) => source.bookSourceUrl.includes(domain));
function fixture(pages: Record<string, string>, extra: Record<string, any> = {}) {
  const calls: string[] = [];
  return { calls, engine:createEngine({ resolveHost:async()=>true,...extra, fetch:async (input: any, init: any) => {
    const url = String(input); calls.push(url);
    assert.equal(init.redirect,'manual');
    const body = pages[url];
    if (body === undefined) return new Response('missing',{status:404});
    return new Response(body,{ headers:{'content-type':'text/html;charset=utf-8'} });
  } }) };
}

test('all 17 reviewed rules compile without runtime eval; unknown JavaScript is quarantined', () => {
  assert.equal(sources.length,17);
  for (const source of sources) assert.equal(sourceCapabilities(source).supported,true,source.bookSourceName);
  const changed = structuredClone(source('qimao'));
  changed.ruleContent.content = '@js: fetch("https://evil.example/")';
  assert.equal(sourceCapabilities(changed).supported,false);
  assert.equal(sourceCapabilities({sourceUrl:'https://feeds.example.org/rss',injectJs:'alert(1)'}).supported,false);
});

test('JSON API fields, filters and static URL functions preserve original source identity', async () => {
  const selected = source('qimao'), root='https://www.qimao.com';
  const book = {bookUrl:root+'/shuku/123/',name:'測試作品',author:'測試作者'};
  const {engine,calls} = fixture({
    [root+'/qimaoapi/api/search/result?keyword=%E6%B8%AC%E8%A9%A6&page=1']:JSON.stringify({data:{search_list:[{title:'測試作品',author:'測試作者',book_id:123}]}}),
    [root+'/shuku/123/']:'<div class="title"><span class="txt">測試作品</span></div><div class="author-name"><a>測試作者</a></div>',
    [root+'/qimaoapi/api/book/chapter-list?book_id=123']:JSON.stringify({data:{chapters:[{id:'1',title:'第一章',is_vip:'0'},{id:'2',title:'付費章',is_vip:'1'}]}}),
    [root+'/shuku/123-1/']:'<div class="chapter-detail-article"><p>第一段正文。</p><p>第二段正文。</p></div>',
  });
  const results = await engine.search(selected,'測試');
  assert.equal(results[0].origin,selected.bookSourceUrl); assert.equal(results[0].bookUrl,book.bookUrl);
  assert.equal(results[0].type,8);
  const detail = await engine.info(selected,book);
  assert.equal(detail.tocUrl,root+'/qimaoapi/api/book/chapter-list?book_id=123');
  const toc = await engine.toc(selected,detail);
  assert.equal(toc.chapters.length,2); assert.equal(toc.chapters[1].isVip,true); assert.equal(toc.chapters[0].url,root+'/shuku/123-1/');
  assert.equal((await engine.content(selected,toc.book,toc.chapters[0])).text,'第一段正文。\n第二段正文。');
  await assert.rejects(engine.content(selected,toc.book,toc.chapters[1]),{code:'LOCKED_CHAPTER'});
  await assert.rejects(engine.content(selected,toc.book,{...toc.chapters[1],isVip:false,isPay:true}),{code:'LOCKED_CHAPTER'});
  assert.equal(calls.filter(url=>url.endsWith('/123-2/')).length,0);
});

test('Jsoup regex attributes, nested :has and matchesOwn work without category explosion', async () => {
  const selected=source('wikisource');
  const routes=await fixture({}).engine.categories(selected);
  const {engine}=fixture({[routes[0].url]:'<div id="mw-content-text"><div class="mw-parser-output"><a href="/wiki/西遊記">西遊記</a><a href="/wiki/Author:作者">西遊記</a><a href="/wiki/其他作品">其他作品</a><a href="/wiki/水滸傳">水滸傳</a></div></div>'});
  const books=await engine.explore(selected,routes[0].url);
  assert.deepEqual(books.map(book=>book.name),['西遊記','水滸傳']);
  assert.equal(books[0].bookUrl,'https://zh.wikisource.org/wiki/%E8%A5%BF%E9%81%8A%E8%A8%98');
});

test('chapter variables survive the TOC response; free-only guards refuse an unlisted chapter', async () => {
  const selected=source('zhulang'),book={bookUrl:'https://m.zhulang.com/321/index.html',tocUrl:'https://book.zhulang.com/321/',name:'測試',author:'作者'};
  const pages={
    [book.tocUrl]:'<div class="catalog-cnt"><ul class="chapter-list"><li><a href="https://book.zhulang.com/321/1.html">第一章</a></li><li><a href="https://book.zhulang.com/321/2.html">第二章[VIP]</a></li></ul></div>',
    'https://book.zhulang.com/321/1.html':'<div id="read-content"><p>可公開取得的原文。</p><script>bad()</script></div>',
    'https://book.zhulang.com/321/2.html':'<div id="read-content">不應取得的章節</div>',
  };
  const {engine,calls}=fixture(pages);
  const toc=await engine.toc(selected,book);
  assert.equal(toc.chapters.length,2); assert.equal(toc.chapters[1].isVip,true); assert.ok(toc.book.variable.includes('readerZhulangFreeChapters'));
  assert.equal((await engine.content(selected,toc.book,toc.chapters[0])).text,'可公開取得的原文。');
  assert.equal((await engine.content(selected,book,toc.chapters[0])).text,'可公開取得的原文。');
  await assert.rejects(engine.content(selected,toc.book,{url:'https://book.zhulang.com/321/2.html'}),/免費目錄確認/);
  assert.equal(calls.filter(url=>url.endsWith('/2.html')).length,0);
  const cold=fixture(pages);
  const recovered=await cold.engine.content(selected,book,toc.chapters[0]);
  assert.equal(recovered.text,'可公開取得的原文。'); assert.ok(recovered.book.variable);
  assert.deepEqual(cold.calls,[book.tocUrl,toc.chapters[0].url]);
  const forged={...book,variable:JSON.stringify({readerZhulangFreeChapters:JSON.stringify({bookId:'321',chapters:{'2':true}})})};
  const fresh=fixture(pages);
  await assert.rejects(fresh.engine.content(selected,forged,{url:'https://book.zhulang.com/321/2.html'}),/免費目錄確認/);
  assert.deepEqual(fresh.calls,[book.tocUrl]);
});

test('numeric Jsoup indexes and traditional text transforms support the verified mobile source', async () => {
  const selected=source('wfxs'), url='https://wfxs.tw/book/10/';
  const {engine}=fixture({
    'https://wfxs.tw/s.html?type=articlename&s=%E6%B8%AC%E8%A9%A6&page=1':'<div class="result-card"><a class="book-title" href="/book/10/">測試小說</a><span class="author">作者：測試作者</span></div>',
    [url]:'<meta property="og:novel:book_name" content="測試小說"><meta property="og:novel:author" content="測試作者"><div class="motion"><a class="tgcj" href="/toc/10/">目錄</a><a class="tgcj" href="/wrong/">錯誤</a></div>',
  });
  const results=await engine.search(selected,'測試'); assert.equal(results[0].name,'测试小说'); assert.equal(results[0].author,'测试作者');
  const info=await engine.info(selected,results[0]); assert.equal(info.tocUrl,'https://wfxs.tw/toc/10/');
});

test('standard RSS and Atom keep feed identity and descriptions without reading local rules', async () => {
  const selected={sourceUrl:'https://feeds.example.org/rss',sourceName:'測試訂閱'};
  const {engine}=fixture({[selected.sourceUrl]:'<?xml version="1.0"?><rss><channel><item><title>公開文章</title><link>https://feeds.example.org/articles/1</link><description><![CDATA[<p>摘要</p>]]></description><pubDate>Tue, 27 Oct 2026 10:00:00 GMT</pubDate></item></channel></rss>'});
  const result=await engine.rssArticles(selected,selected.sourceUrl);
  assert.equal(result.articles[0].origin,selected.sourceUrl); assert.equal(result.articles[0].description,'<p>摘要</p>');
  assert.equal(await engine.rssContent(selected,result.articles[0]),'<p>摘要</p>');
});

test('the reviewed GBK search encodes the keyword before requesting the public search page', async () => {
  const selected=source('jjwxc');
  const {engine,calls}=fixture({'https://wap.jjwxc.net/search?kw=%BF%AA%B6%CB&t=1&page=1':'<div></div>'});
  await engine.search(selected,'开端');
  assert.deepEqual(calls,['https://wap.jjwxc.net/search?kw=%BF%AA%B6%CB&t=1&page=1']);
});

test('remote requests deny private hosts, host changes, credentials, ports and redirect escape', async () => {
  for (const host of ['localhost','127.0.0.1','10.0.0.1','172.16.0.1','192.168.0.1','169.254.169.254','[::1]','100.64.1.1','0x7f000001']) assert.equal(isPublicHost(host),false,host);
  const selected={bookSourceUrl:'https://public.example.org/',bookSourceName:'fixture',searchUrl:'https://public.example.org/search',ruleSearch:{bookList:'li',name:'text',bookUrl:'href'}};
  const engine=createEngine({resolveHost:async()=>true,fetch:async()=>new Response(null,{status:302,headers:{location:'http://169.254.169.254/latest'}})});
  await assert.rejects(engine.search(selected,'test'),/核准公開主機/);
  await assert.rejects(engine.explore(selected,'https://other.example.org/'),/核准公開主機/);
  await assert.rejects(engine.explore(selected,'https://user:password@public.example.org/'),/核准公開主機/);
  await assert.rejects(engine.explore(selected,'https://public.example.org:8443/'),/核准公開主機/);
});

test('large bodies and hung requests are bounded; empty or locked chapters are not cached as readable', async () => {
  const selected={bookSourceUrl:'https://public.example.org/',bookSourceName:'fixture',ruleContent:{content:'article@html'}};
  const big=createEngine({resolveHost:async()=>true,fetch:async()=>new Response('x'.repeat(2*1024*1024+1))});
  await assert.rejects(big.content(selected,{bookUrl:selected.bookSourceUrl},{url:selected.bookSourceUrl}),/回應過大/);
  const slow=createEngine({resolveHost:async()=>true,deadlineMillis:10,fetch:async(_input,init)=>new Promise((_resolve,reject)=>init?.signal?.addEventListener('abort',()=>reject(new Error('aborted'))))});
  await assert.rejects(slow.content(selected,{bookUrl:selected.bookSourceUrl},{url:selected.bookSourceUrl}),/逾時/);
  const {engine,calls}=fixture({[selected.bookSourceUrl]:'<article></article>'});
  await assert.rejects(engine.content(selected,{bookUrl:selected.bookSourceUrl},{url:selected.bookSourceUrl}),/公開可讀正文/);
  const count=calls.length;
  await assert.rejects(engine.content(selected,{bookUrl:selected.bookSourceUrl},{url:selected.bookSourceUrl,isVip:true,isPay:false}),/未授權/);
  assert.equal(calls.length,count);
  const unreadable=fixture({[selected.bookSourceUrl]:'<article>'+ '\uE001'.repeat(100) +'</article>'}).engine;
  await assert.rejects(unreadable.content(selected,{bookUrl:selected.bookSourceUrl},{url:selected.bookSourceUrl}),/私用字元/);
});

test('DNS answers fail closed before source fetch and redirect DNS stays inside the original deadline', async () => {
  for (const address of ['127.0.0.1','192.168.0.1','::1','::ffff:127.0.0.1','fe80::1','fc00::1','2001:db8::1','2002:7f00:1::']) assert.equal(isPublicAddress(address),false,address);
  assert.equal(isPublicAddress('1.1.1.1'),true); assert.equal(isPublicAddress('2606:4700:4700::1111'),true);
  const selected={bookSourceUrl:'https://public.example.org/',bookSourceName:'fixture',ruleContent:{content:'article@html'}};
  let fetched=false;
  const denied=createEngine({resolveHost:async()=>false,fetch:async()=>{fetched=true;return new Response('');}});
  await assert.rejects(denied.content(selected,{bookUrl:selected.bookSourceUrl},{url:selected.bookSourceUrl}),/DNS未指向/);
  assert.equal(fetched,false);
  const slow=createEngine({deadlineMillis:10,resolveHost:async(_host,signal)=>new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(new Error('aborted')))),fetch:async()=>new Response('')});
  await assert.rejects(slow.content(selected,{bookUrl:selected.bookSourceUrl},{url:selected.bookSourceUrl}),/逾時/);
});

test('Cloudflare-compatible DNS fetch uses manual redirects and rejects a redirected resolver response', async () => {
  const originalFetch=globalThis.fetch;
  const resolverCalls:Array<{url:string;redirect:unknown}>=[];
  let redirectResolver=false,upstreamCalls=0;
  globalThis.fetch=async(input:any,init:any)=>{
    if(init?.redirect==='error')throw new TypeError('Invalid redirect value, must be one of follow or manual');
    const url=new URL(String(input));
    assert.equal(url.origin,'https://cloudflare-dns.com');assert.equal(url.pathname,'/dns-query');
    resolverCalls.push({url:url.href,redirect:init.redirect});
    if(redirectResolver)return new Response(null,{status:302,headers:{location:'https://other.example.org/dns'}});
    return Response.json({Status:0,Answer:url.searchParams.get('type')==='A'?[{type:1,data:'1.1.1.1',TTL:30}]:[]});
  };
  try {
    const source={bookSourceUrl:'https://owned-edge-dns.example.org/',bookSourceName:'Owned edge resolver fixture',ruleContent:{content:'article@html'}};
    const engine=createEngine({fetch:async()=>{upstreamCalls++;return new Response('<article>Self-written public content</article>');}});
    assert.equal((await engine.content(source,{bookUrl:source.bookSourceUrl},{url:source.bookSourceUrl})).text,'Self-written public content');
    assert.equal(resolverCalls.length,2);assert.ok(resolverCalls.every(call=>call.redirect==='manual'));assert.equal(upstreamCalls,1);
    redirectResolver=true;
    const second={...source,bookSourceUrl:'https://owned-edge-dns-redirect.example.org/'};
    await assert.rejects(engine.content(second,{bookUrl:second.bookSourceUrl},{url:second.bookSourceUrl}),/DNS檢查失敗/);
    assert.equal(upstreamCalls,1);
  } finally {globalThis.fetch=originalFetch;}
});

test('static website capability verifies a real bounded HTML GET without pretending it is an RSS feed', async () => {
  const selected = { sourceUrl: 'https://website.example.org/news', sourceName: 'Owned website', singleUrl: true, enableJs: true };
  assert.equal(sourceCapabilities(selected).supported, true);
  assert.equal(sourceCapabilities(selected).web, true);
  assert.equal(sourceCapabilities(selected).rss, false);
  const { engine, calls } = fixture({ [selected.sourceUrl]: '<html><body><h1>Self-written public website fixture</h1></body></html>' });
  assert.deepEqual(await engine.verifyWebEntry(selected), { url: selected.sourceUrl, contentType: 'text/html;charset=utf-8' });
  assert.deepEqual(calls, [selected.sourceUrl]);
  for (const mutation of [{ injectJs: 'alert(1)' }, { jsLib: 'var secret=1' }, { loginUrl: 'https://website.example.org/login' },
    { sortUrl: '@js:java.ajax("https://other.example.org")' }, { sourceUrl: 'http://127.0.0.1/private' },
    { sourceUrl: 'https://user:password@website.example.org/' }, { sourceUrl: 'https://website.example.org/{{page}}' }]) {
    assert.equal(sourceCapabilities({ ...selected, ...mutation }).supported, false);
  }
});

test('website verification rejects non-HTML responses and unsafe redirects without evaluating page scripts', async () => {
  const selected = { sourceUrl: 'https://website.example.org/', sourceName: 'Owned website', singleUrl: true };
  const json = createEngine({ resolveHost: async () => true, fetch: async () => Response.json({ website: 'not HTML' }) });
  await assert.rejects(json.verifyWebEntry(selected), /HTML網頁/);
  const moved = createEngine({ resolveHost: async () => true, fetch: async () => new Response(null,
    { status: 302, headers: { location: 'http://169.254.169.254/latest/meta-data' } }) });
  await assert.rejects(moved.verifyWebEntry(selected), /核准公開主機/);
  let evaluated = false;
  const page = createEngine({ resolveHost: async () => true, fetch: async () => new Response(
    '<html><body>Self-written page<script>globalThis.untrustedWebsiteRan=true</script></body></html>',
    { headers: { 'content-type': 'text/html' } }) });
  await page.verifyWebEntry(selected);
  evaluated = (globalThis as any).untrustedWebsiteRan === true;
  assert.equal(evaluated, false);
});

test('HTTP 200 challenge and access-denied documents do not count as usable website entries', async () => {
  const selected={sourceUrl:'https://website.example.org/',sourceName:'Owned website',singleUrl:true};
  const cases=[
    '<title>Just a moment...</title><main><h1>website.example.org</h1><p>Checking the connection.</p></main>',
    '<title>Attention Required! | Cloudflare</title><main><p>Browser check</p></main>',
    '<title>Owned website</title><main><h1>403 Forbidden</h1></main>',
    '<title>Owned website</title><main><h1>請完成人機驗證</h1></main>',
    '<title>Owned website</title><main><form id="challenge-form"><p>Enable JavaScript and cookies to continue</p></form></main>',
    '<title>Owned website</title><main><div id="cf-error-details">Request could not continue.</div></main>',
    '<title>Owned website</title><body><script>renderWebsite()</script><noscript>Please enable JavaScript to continue.</noscript></body>',
  ];
  for(const html of cases){
    const {engine,calls}=fixture({[selected.sourceUrl]:html});
    await assert.rejects(engine.verifyWebEntry(selected),{code:'WEBSITE_BLOCKED'});
    assert.equal(calls.length,1,'A denied website is rejected without a bypass or extra request');
  }
});

test('HTTP 200 soft-404, parked-domain and closed-site pages are independently classified', async () => {
  const selected={sourceUrl:'https://website.example.org/',sourceName:'Owned website',singleUrl:true};
  const cases=[
    {code:'WEBSITE_NOT_FOUND',html:'<title>Owned website</title><main><h1>404 — Page not found</h1><a href="/">Home</a></main>'},
    {code:'WEBSITE_NOT_FOUND',html:'<title>Owned portal</title><main><h1>Owned portal</h1><p>您访问的页面不存在。</p><a href="/">返回首页</a></main>'},
    {code:'WEBSITE_PARKED',html:'<title>website.example.org - Domain for sale</title><main><h1>website.example.org</h1><p>Contact the seller.</p></main>'},
    {code:'WEBSITE_PARKED',html:'<title>website.example.org</title><main><h1>website.example.org</h1><p>This domain is for sale!</p><a href="https://parking.example.org/">Buy now</a></main>'},
    {code:'WEBSITE_CLOSED',html:'<title>网站已关闭</title><main><p>Thank you for visiting.</p></main>'},
    {code:'WEBSITE_CLOSED',html:'<title>Owned portal</title><main><h1>This account has been suspended.</h1></main>'},
    {code:'WEBSITE_CLOSED',html:'<title>Owned portal</title><main><p>網站維護中</p></main>'},
  ];
  for(const item of cases)await assert.rejects(fixture({[selected.sourceUrl]:item.html}).engine.verifyWebEntry(selected),{code:item.code});
});

test('normal news with login navigation, a CAPTCHA widget or article/script mentions remains a website entry',async()=>{
  const selected={sourceUrl:'https://website.example.org/',sourceName:'Owned website',singleUrl:true};
  const html=`<html><head><title>Owned public headlines</title><script>const sample="Access Denied; CAPTCHA; domain for sale; 404 Not Found";</script></head>
    <body><header><h1>Login</h1><a href="/login">Sign in</a></header><nav>安全验证 Login CAPTCHA</nav>
      <main><h1>Owned public headlines</h1><article><h2>Researchers explain CAPTCHA technology</h2>
      <p>This self-written public article mentions access denied and a domain for sale while explaining web safety.</p>
      <div class="cf-turnstile" data-sitekey="fixture"></div><p>Ordinary readers can read this article.</p></article>
      <h1 hidden>Access Denied</h1></main></body></html>`;
  const {engine,calls}=fixture({[selected.sourceUrl]:html});
  assert.deepEqual(await engine.verifyWebEntry(selected),{url:selected.sourceUrl,contentType:'text/html;charset=utf-8'});
  assert.equal(calls.length,1);
});

test('Heiyan discovery uses the visible book-card title and author instead of malformed image-link residue', async () => {
  const selected = source('heiyan'), originalRule = JSON.stringify(selected);
  const root = 'https://www.heiyan.com', bookUrl = root + '/book/123';
  const card = `<dl class="main-dl"><dt><a href="${bookUrl}"><img src="https://images.example.org/cover.jpg" />" width="120" height="168" alt="自寫測試作品"/> </a></dt>
    <dd class="main-dd"><a href="${bookUrl}" data-id="123"><h3 title="自寫測試作品">自寫測試作品</h3></a>
      <div class="flex"><a class="name" href="${root}/people/456"><em>測試作者</em></a>/<a href="${root}/all/1_-1.html"><em>測試分類</em></a></div></dd></dl>`;
  const { engine } = fixture({
    [root + '/']: card + card,
    [root + '/?reader_query=%E6%B8%AC%E8%A9%A6']: card,
    'https://search.heiyan.com/web/search?queryString=%E6%B8%AC%E8%A9%A6&highlight=false&page=1&f=f': JSON.stringify({ data: { content: [
      { id: 123, name: '自寫測試作品', authorname: '測試作者', iconUrlSmall: 'https://images.example.org/cover.jpg' },
    ] } }),
    [bookUrl]: '<meta property="og:title" content="自寫測試作品"><meta property="og:novel:author" content="測試作者">',
  });
  const books = await engine.explore(selected, root + '/');
  assert.equal(books.length, 1);
  assert.equal(books[0].name, '自寫測試作品');
  assert.equal(books[0].author, '測試作者');
  assert.equal(books[0].bookUrl, bookUrl);
  assert.equal(books[0].coverUrl, 'https://images.example.org/cover.jpg');
  assert.equal(books[0].kind, '測試分類');
  assert.equal((await engine.explore(selected, root + '/?reader_query=%E6%B8%AC%E8%A9%A6')).length, 1);
  const search = await engine.search(selected, '測試');
  assert.equal(search[0].name, books[0].name);
  assert.equal(search[0].author, books[0].author);
  const detail = await engine.info(selected, books[0]);
  assert.equal(detail.name, books[0].name);
  assert.equal(detail.author, books[0].author);
  assert.equal(JSON.stringify(selected), originalRule, 'A server adapter must leave the archived original source bytes untouched');
});

test('relative source request URLs resolve against the canonical source while retaining host restrictions', async () => {
  const selected = { bookSourceUrl: 'https://public.example.org/catalogue/#original-source', bookSourceName: 'Relative fixture',
    searchUrl: '../search?key={{key}}', ruleSearch: { bookList: 'article', name: 'h2@text', author: '.author@text', bookUrl: 'a@href' } };
  const { engine, calls } = fixture({ 'https://public.example.org/search?key=%E6%B8%AC%E8%A9%A6':
    '<article><h2>自寫作品</h2><span class="author">測試作者</span><a href="/book/1">詳情</a></article>' });
  const books = await engine.search(selected, '測試');
  assert.equal(books[0].name, '自寫作品');
  assert.equal(books[0].author, '測試作者');
  assert.equal(books[0].bookUrl, 'https://public.example.org/book/1');
  assert.deepEqual(calls, ['https://public.example.org/search?key=%E6%B8%AC%E8%A9%A6']);
  for (const searchUrl of ['//other.example.org/search', '//user:password@public.example.org/search',
    '//public.example.org:8443/search', 'http://127.0.0.1/private', 'javascript:alert(1)']) {
    await assert.rejects(engine.search({ ...selected, searchUrl }, 'test'), /核准公開主機/);
  }
  assert.equal(calls.length, 1, 'An unsafe relative/protocol-relative target is denied before fetching');
});

test('the Heiyan adapter retains table and ranking titles and never assigns the VIP icon as a cover', async () => {
  const selected = source('heiyan'), root = 'https://www.heiyan.com';
  const html = `<table><tbody id="resultDiv"><tr><td class="name"><img src="/vip.png" alt="vip"><a class="title" href="${root}/book/321">自寫表格作品</a>
    <a class="chapter" href="${root}/book/321/1">第一章</a></td><td><a href="${root}/people/99">表格作者</a></td></tr></tbody></table>
    <ol><li><a href="${root}/book/654"><img src="/cover.jpg">" width="120" alt="錯誤片段"/></a>
      <h5><a href="${root}/book/654">自寫排行作品</a></h5><a href="${root}/people/88">排行作者</a></li></ol>`;
  const { engine } = fixture({ [root + '/all.html']: html });
  const books = await engine.explore(selected, root + '/all.html');
  assert.deepEqual(books.map(book => [book.name, book.author]), [['自寫表格作品', '表格作者'], ['自寫排行作品', '排行作者']]);
  assert.equal(books[0].coverUrl, '', 'The result table only contains a VIP icon, not a book cover');
});

test('legacy class/tag/id steps, root matches, exclusion indexes and fallback preserve native extraction semantics', async () => {
  const selected = { bookSourceUrl: 'https://public.example.org/', bookSourceName: 'Legacy fixture', searchUrl: '/search',
    ruleSearch: { bookList: 'id.shelf@tag.li!0', name: 'class.card@tag.h4@tag.a@text', bookUrl: 'tag.h4@tag.a@href',
      author: 'class.missing@text||tag.span.1@text', kind: 'tag.span.0@text&&tag.span.2@text',
      coverUrl: 'tag.img@data-src||tag.img@src', intro: 'tag.p[!1]@text' } };
  const { engine } = fixture({ 'https://public.example.org/search': `<ul id="shelf"><li>表頭</li><li class="card">
    <h4><a href="/book/1">自寫鏈式作品</a></h4><span>分類甲</span><span>測試作者</span><span>分類乙</span>
    <img src="/cover.jpg"><p>第一段</p><p>略過段</p><p>第三段</p></li></ul>` });
  const books = await engine.search(selected, 'test');
  assert.equal(books.length, 1);
  assert.equal(books[0].name, '自寫鏈式作品');
  assert.equal(books[0].author, '測試作者');
  assert.equal(books[0].kind, '分類甲\n分類乙');
  assert.equal(books[0].intro, '第一段\n第三段');
  assert.equal(books[0].coverUrl, 'https://public.example.org/cover.jpg');
});

test('rule separators inside CSS attributes remain literal and bracket indexes retain native order', async () => {
  const selected = { bookSourceUrl: 'https://public.example.org/', bookSourceName: 'Quoted selector fixture', searchUrl: '/search',
    ruleSearch: { bookList: 'ul@li', name: 'a[data-key="name@domain||other"]@text', bookUrl: 'a@href',
      author: 'span[-1]@text', kind: 'span[2,0]@text', intro: 'p[-1:0]@text' } };
  const { engine } = fixture({ 'https://public.example.org/search': '<ul><li><a data-key="name@domain||other" href="/book/1">自寫作品</a><span>甲</span><span>乙</span><span>作者</span><p>首段</p><p>末段</p></li></ul>' });
  const books = await engine.search(selected, 'test');
  assert.equal(books[0].name, '自寫作品');
  assert.equal(books[0].author, '作者');
  assert.equal(books[0].kind, '作者\n甲');
  assert.equal(books[0].intro, '末段\n首段');
});

test('static category JSON tolerates only trailing commas outside strings', async () => {
  const selected = { bookSourceUrl: 'https://public.example.org/', bookSourceName: 'Category fixture',
    exploreUrl: '[{"title":"保留,]及,}文字","url":"/books",},]' };
  const { engine } = fixture({});
  assert.deepEqual(await engine.categories(selected), [{ title: '保留,]及,}文字', url: '/books' }]);
  await assert.rejects(engine.categories({ ...selected, exploreUrl: "[{title:'unreviewed JavaScript',url:'/books'}]" }), /分類JSON/);
});

test('same-origin static public POST search sends form fields with charset encoding and keeps literal separators in keywords', async () => {
  const selected = { bookSourceUrl: 'https://public.example.org/#original', bookSourceName: 'POST fixture',
    searchUrl: '/search,{"method":"POST","body":"keyword={{key}}&page={{page}}&literal=keep%2Bencoded","charset":"gbk",}',
    ruleSearch: { bookList: 'article', name: 'h2@text', bookUrl: 'a@href' } };
  let seen: any;
  const engine = createEngine({ resolveHost: async () => true, fetch: async (input, init) => {
    seen = { url: String(input), method: init?.method, body: init?.body, contentType: new Headers(init?.headers).get('content-type') };
    return new Response('<article><h2>Self-written fixture</h2><a href="/book/1">book</a></article>');
  } });
  const books = await engine.search(selected, '开端 &role=reader', 3);
  assert.equal(books.length, 1);
  assert.equal(seen.url, 'https://public.example.org/search');
  assert.equal(seen.method, 'POST');
  assert.equal(seen.body, 'keyword=%BF%AA%B6%CB+%26role%3Dreader&page=3&literal=keep%252Bencoded');
  assert.equal(seen.contentType, 'application/x-www-form-urlencoded');
});

test('POST JSON bodies remain inert data and redirects preserve host checks and standard method semantics', async () => {
  const selected = { bookSourceUrl: 'https://public.example.org/', bookSourceName: 'JSON POST fixture',
    searchUrl: '/search,' + JSON.stringify({ method: 'POST', body: { keyword: '{{key}}', page: '{{page}}' } }),
    ruleSearch: { bookList: 'article', name: 'h2@text', bookUrl: 'a@href' } };
  const calls: any[] = [];
  const engine = createEngine({ resolveHost: async () => true, fetch: async (input, init) => {
    calls.push({ url: String(input), method: init?.method, body: init?.body, contentType: new Headers(init?.headers).get('content-type') });
    return calls.length === 1 ? new Response(null, { status: 302, headers: { location: '/results' } })
      : new Response('<article><h2>Self-written fixture</h2><a href="/book/1">book</a></article>');
  } });
  await engine.search(selected, 'quote" and ampersand&', 2);
  assert.deepEqual(JSON.parse(calls[0].body), { keyword: 'quote" and ampersand&', page: '2' });
  assert.equal(calls[0].contentType, 'application/json');
  assert.equal(calls[1].method, 'GET'); assert.equal(calls[1].body, undefined);
  const escape = createEngine({ resolveHost: async () => true, fetch: async () => new Response(null,
    { status: 307, headers: { location: 'http://169.254.169.254/latest' } }) });
  await assert.rejects(escape.search(selected, 'test'), /核准公開主機/);
  await assert.rejects(engine.search({ ...selected, searchUrl: '/search,{"method":"POST","body":"x={{key}}","webView":true}' }, 'test'), /URL選項/);
});

test('an empty explore bookList falls back to the whole search rule exactly like native Legado', async () => {
  const selected = { bookSourceUrl: 'https://public.example.org/', bookSourceName: 'Fallback fixture',
    ruleSearch: { bookList: 'article', name: 'h2@text', author: '.author@text', bookUrl: 'a@href' },
    ruleExplore: { bookList: '   ', name: 'h3@text', author: '.wrong-author@text', bookUrl: '.wrong@href' } };
  const { engine, calls } = fixture({ 'https://public.example.org/category':
    '<article><h2>自寫分類作品</h2><span class="author">正確作者</span><a href="/book/1">詳情</a></article>' });
  const books = await engine.explore(selected, '/category');
  assert.equal(calls.length, 1);
  assert.equal(books[0].name, '自寫分類作品');
  assert.equal(books[0].author, '正確作者');
  assert.equal(books[0].bookUrl, 'https://public.example.org/book/1');
});


test('Kujiang keeps visible volume and paid TOC metadata while the free proof excludes paid chapters', async () => {
  const selected=source('kujiang'), book={bookUrl:'https://m.kujiang.com/book/42',tocUrl:'https://www.kujiang.com/book/42/catalog',name:'自寫作品',author:'自寫作者'};
  const pages={
    [book.tocUrl]: '<div class="kjsection-catalog"><dl><dt>公開卷</dt><dd><a href="/book/42/1">第一章</a></dd><dd><a class="vip" href="/book/42/2">付費章</a></dd></dl><dl><dt>VIP卷</dt><dd><a href="/book/42/3">第三章</a></dd></dl><dl><dt>後記卷</dt><dd><a href="/book/42/4">公開後記</a></dd></dl></div>',
    'https://m.kujiang.com/book/42/1': '<div id="readArticle"><p>自寫公開第一章。</p></div>',
    'https://m.kujiang.com/book/42/4': '<div id="readArticle"><p>自寫公開後記。</p></div>',
  };
  const {engine,calls}=fixture(pages), original=JSON.stringify(selected);
  const toc=await engine.toc(selected,book);
  assert.deepEqual(toc.chapters.map(c=>[c.index,c.title,c.isVolume,c.isVip]),[
    [0,'公開卷',true,false],[1,'第一章',false,false],[2,'付費章',false,true],
    [3,'VIP卷',true,false],[4,'第三章',false,true],[5,'後記卷',true,false],[6,'公開後記',false,false]]);
  const map=JSON.parse(JSON.parse(toc.book.variable).readerKujiangFreeChapters);
  assert.deepEqual(map.chapters,{'1':true,'4':true});
  assert.equal((await engine.content(selected,toc.book,toc.chapters[0])).text,'公開卷');
  assert.equal((await engine.content(selected,toc.book,toc.chapters[1])).text,'自寫公開第一章。');
  assert.equal((await engine.content(selected,toc.book,toc.chapters[6])).text,'自寫公開後記。');
  for(const paid of [toc.chapters[2],toc.chapters[4]]) {
    await assert.rejects(engine.content(selected,toc.book,paid),{code:'LOCKED_CHAPTER'});
    const cold=fixture(pages);
    await assert.rejects(cold.engine.content(selected,book,{...paid,isVip:false,isPay:true}),{code:'LOCKED_CHAPTER'});
    assert.deepEqual(cold.calls,[book.tocUrl]);
  }
  assert.equal(calls.some(url=>/\/42\/[23]$/.test(url)),false);
  assert.equal(JSON.stringify(selected),original);
});


test('request AST templates render base URLs and page expressions while selector templates remain quarantined', async () => {
  const selected={bookSourceUrl:'https://owned-template.example.org/',bookSourceName:'自寫模板來源',
    searchUrl:'{{baseUrl}}find?q={{encodeURIComponent(key)}}&offset={{(page-1)*30}}',
    exploreUrl:'[{"title":"分類","url":"/list?page={{page+1}}"}]',
    ruleSearch:{bookList:'a.book',name:'text',bookUrl:'href'}};
  assert.equal(sourceCapabilities(selected).supported,true);
  assert.equal(sourceCapabilities(selected).explore,true,'missing explore rules use the full search rule');
  const {engine,calls}=fixture({'https://owned-template.example.org/find?q=%7B%7Bpage%7D%7D&offset=60':'<a class="book" href="/book/1">自寫作品</a>'});
  assert.equal((await engine.search(selected,'{{page}}',3))[0].name,'自寫作品');
  assert.equal(calls.length,1);
  assert.equal(sourceCapabilities({...selected,ruleSearch:{...selected.ruleSearch,name:'a.{{page}}@text'}}).supported,false);
  assert.equal(sourceCapabilities({...selected,searchUrl:'/search?q={{globalThis.cookie}}'}).supported,false);
});
