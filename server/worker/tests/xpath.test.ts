import test from 'node:test';
import assert from 'node:assert/strict';
import * as cheerio from 'cheerio';
import {getNodes, getStrings, invalidateXPath, validateXPath, XPathError, XPATH_LIMITS} from '../src/xpath';

const html = `<!doctype html><html><head><title>Fixture library</title></head><body>
<h1>Public books</h1><ul id="books">
<li class="book"><a href="/book/a?x=1&amp;y=2" title="First &amp; calm"><b>First</b> story</a><i>Writer A</i></li>
<li class="book"><a href="/book/b">Second story</a><i>Writer B</i></li>
</ul><script>globalThis.__xpathExecuted = true; throw new Error('inert fixture');</script>
</body></html>`;
function fixture(body = html) { const $ = cheerio.load(body); return {$, root: $.root()[0]}; }
function rejects(action: () => unknown, code: XPathError['code']) {
  assert.throws(action, error => error instanceof XPathError && error.code === code);
}

test('XPath selects original HTML root and row nodes without reparsing', () => {
  const {$, root} = fixture();
  const rows = getNodes('/html/body/ul/li', root);
  assert.equal(getNodes('/html', root)[0], $('html')[0]);
  assert.deepEqual(rows, $('li').toArray());
  assert.deepEqual(getStrings('.//a/@href', root, rows), ['/book/a?x=1&y=2', '/book/b']);
  assert.deepEqual(getStrings('./i/text()', root, rows), ['Writer A', 'Writer B']);
  assert.deepEqual(getStrings('../@id', root, [rows[1]]), ['books']);
  assert.deepEqual(getStrings('/html/body/h1/text()', root, [rows[1]]), ['Public books']);
  assert.equal(getNodes('..', root, [rows[0]])[0], $('ul')[0]);
  assert.equal(getNodes('following-sibling::li', root, [rows[0]])[0], rows[1]);
  assert.equal(getNodes('preceding-sibling::li', root, [rows[1]])[0], rows[0]);
  assert.equal(getNodes('id("books")', root)[0], $('ul')[0]);
});

test('XPath attributes, text, scalars and element serialization retain native value semantics', () => {
  const {root} = fixture();
  assert.deepEqual(getStrings('//li[1]/a/@title', root), ['First & calm']);
  assert.deepEqual(getStrings('//li[1]/a/text()', root), [' story']);
  assert.deepEqual(getStrings('normalize-space(string(//li[1]/a))', root), ['First story']);
  assert.deepEqual(getStrings('count(//li)', root), ['2']);
  assert.deepEqual(getStrings('boolean(//li[3])', root), ['false']);
  assert.deepEqual(getStrings('concat("doc( is data: ", //li[2]/i)', root), ['doc( is data: Writer B']);
  assert.deepEqual(getStrings('//li[1]/i', root), ['<i>Writer A</i>']);
  assert.deepEqual(getStrings('local-name(/html)', root), ['html']);
  assert.deepEqual(getStrings('//li/@*', root), ['book', 'book']);
  assert.deepEqual(getStrings('//li[1]/a/@*', root), ['/book/a?x=1&y=2', 'First & calm']);
  assert.deepEqual(getStrings('//li[1]/a/@title | //li[1]/a | //li[1]/a/@href', root),
    ['<a href="/book/a?x=1&amp;y=2" title="First &amp; calm"><b>First</b> story</a>', '/book/a?x=1&y=2', 'First & calm']);
  rejects(() => getNodes('count(//li)', root), 'XPATH_RESULT');
  rejects(() => getNodes('//li/@class', root), 'XPATH_RESULT');
  assert.equal((globalThis as any).__xpathExecuted, undefined);
});

test('XPath context remains confined to the original tree and empty rows remain empty', () => {
  const {root, $} = fixture(), other = fixture();
  assert.deepEqual(getStrings('//h1/text()', root, []), []);
  rejects(() => getStrings('.', root, [other.root]), 'XPATH_CONTEXT');
  rejects(() => getNodes('.', root, [$('li').clone()[0]]), 'XPATH_CONTEXT');
  rejects(() => getNodes('.', $('li')[0]), 'XPATH_CONTEXT');
  $('li').first().remove(); invalidateXPath(root);
  assert.deepEqual(getStrings('//li/i/text()', root), ['Writer B']);
});

test('XPath rejects external, extension and executable expressions before evaluation', () => {
  for (const rule of ['doc("https://fixture.example/a")', 'document("a")', 'collection("a")',
    'unparsed-text("a")', 'java:eval("x")', 'system-property("x")', '$value',
    '//a;globalThis.foo=1', 'namespace::*', '//a[', 'string(`code`)']) {
    rejects(() => validateXPath(rule), 'XPATH_RULE');
  }
  validateXPath('//a[contains(@title,"doc( is plain text")]/@href');
  const {root} = fixture('<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01//EN" "https://fixture.example/external"><p>plain</p>');
  assert.deepEqual(getStrings('//p/text()', root), ['plain']);
  const xml = cheerio.load('<!DOCTYPE library [<!ENTITY external SYSTEM "https://fixture.example/external">]><library/>', {xmlMode: true});
  rejects(() => getStrings('/*', xml.root()[0]), 'XPATH_RULE');
});

test('XPath bounds expression complexity, document size and evaluation visits', () => {
  const {root} = fixture();
  rejects(() => validateXPath('/' + 'a'.repeat(XPATH_LIMITS.expressionChars)), 'XPATH_RULE');
  rejects(() => getStrings('//li', root, undefined, {nodes: 2}), 'XPATH_LIMIT');
  rejects(() => getStrings('//li', root, undefined, {documentChars: 8}), 'XPATH_LIMIT');
  rejects(() => getStrings('//li/@class', root, undefined, {attributes: 1}), 'XPATH_LIMIT');
  rejects(() => getStrings('//li/@class', root, undefined, {visits: 3}), 'XPATH_LIMIT');
  rejects(() => getStrings('//li', root, undefined, {results: 1}), 'XPATH_LIMIT');
  rejects(() => getStrings('//li[1]', root, undefined, {outputChars: 10}), 'XPATH_LIMIT');
  rejects(() => getStrings('concat("123456", "789012")', root, undefined, {outputChars: 10}), 'XPATH_LIMIT');
  rejects(() => getStrings('string-length(concat("123456", "789012"))', root, undefined, {outputChars: 10}), 'XPATH_LIMIT');
  rejects(() => getStrings('.', root, undefined, {visits: XPATH_LIMITS.visits + 1}), 'XPATH_LIMIT');
  rejects(() => validateXPath('('.repeat(17) + 'true()' + ')'.repeat(17)), 'XPATH_LIMIT');
  const deep = fixture('<div>'.repeat(130) + 'inside' + '</div>'.repeat(130));
  rejects(() => getStrings('//div/text()', deep.root), 'XPATH_LIMIT');
  // Failed evaluations release their per-call budget and never poison a later query.
  assert.deepEqual(getStrings('count(//li)', root), ['2']);
});

test('XPath node-set comparisons consume the deterministic visit budget', () => {
  const {root} = fixture('<main>' + '<a>left</a><b>right</b>'.repeat(80) + '</main>');
  rejects(() => getStrings('//a = //b', root, undefined, {visits: 5000}), 'XPATH_LIMIT');
  assert.deepEqual(getStrings('count(//a)', root), ['80']);
});

test('XPath elapsed-time failures release the budget, including small scalar evaluations', () => {
  const {root} = fixture(), now = Date.now;
  let time = 0;
  try {
    Date.now = () => time += 1000;
    rejects(() => getStrings('true()', root, undefined, {milliseconds: 1}), 'XPATH_LIMIT');
  } finally { Date.now = now; }
  assert.deepEqual(getStrings('true()', root), ['true']);
});
