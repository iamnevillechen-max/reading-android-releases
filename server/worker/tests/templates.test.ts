import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluateTemplateExpression as evaluate, isSupportedTemplateExpression as supported, templateExpressions } from '../src/templates';

const env = { key: '中文 & / "keyword"', page: 3, baseUrl: 'https://fixture.example/', now: 1700000000000 };

test('bounded URL expressions match native page arithmetic and conditional syntax without eval', () => {
  for (const [expression, expected] of [
    ['(page-1)*20', 40], ['page+1', 4], ['page > 1 ? "_"+page : ""', '_3'],
    ['Math.ceil(page / 2)', 2], ['Math.max(1,page-5)', 1], ['(page-1)*10;', 20],
    ['page === 3 && "third" || "other"', 'third'], ['null ?? page', 3], ['Date.now()', env.now],
  ] as const) assert.equal(evaluate(expression, env), expected, expression);
});

test('raw keywords remain raw for explicit URI encoding and data-only JSON bodies', () => {
  assert.equal(evaluate('key', env), env.key);
  assert.equal(evaluate('encodeURIComponent(key)', env), encodeURIComponent(env.key));
  assert.equal(evaluate('decodeURIComponent(encodeURIComponent(key))', env), env.key);
  assert.equal(evaluate('JSON.stringify({search:key,page:page})', env), JSON.stringify({ search: env.key, page: 3 }));
  assert.equal(evaluate('key.slice(0,2)', env), '中文');
  assert.equal(evaluate('String(page).padStart(3,"0")', env), '003');
  assert.equal(evaluate('key.replaceAll(" ","+")', env), env.key.replaceAll(' ', '+'));
  assert.equal(evaluate('["search",page].join("/")', env), 'search/3');
});

test('template boundaries respect literal closing braces and nested JSON objects', () => {
  const input = '/find?page={{(page-1)*20}}&data={{JSON.stringify({text:"}}", page:page})}}&suffix={{page;}}';
  const expressions = templateExpressions(input);
  assert.equal(expressions.length, 3);
  assert.deepEqual(expressions.map(x => evaluate(x.expression, env)), [40, '{"text":"}}","page":3}', 3]);
  for (const x of expressions) assert.match(input.slice(x.start, x.end), /^\{\{[\s\S]*\}\}$/);
});

test('functions, mutations, prototype access and host globals never become executable templates', () => {
  for (const expression of ['process.env', 'globalThis.fetch("https://elsewhere.example")',
    'key.constructor', 'key["constructor"]', 'String.constructor("return process")()',
    '({__proto__:page})', '(()=>page)()', 'eval("page")', 'new Date()', 'page++',
    'java.ajax("https://fixture.example")', 'Array(1000000)', 'key.replace(/(a+)+$/, "x")']) {
    assert.equal(supported(expression), false, expression);
    assert.throws(() => evaluate(expression, env), /unsupported expression|limits/, expression);
  }
});

test('builtins are available only at approved call sites and never leak as data', () => {
  for (const expression of ['Math', 'String', 'parseInt', 'typeof String', 'JSON.stringify(Math)',
    'JSON.stringify({value:Date})', '[Number].join("")', 'page ? Math : JSON',
    'Math.abs', 'String.length', 'Number.now()', 'Math.stringify(page)', 'Math?.PI']) {
    assert.equal(supported(expression), false, expression);
    assert.throws(() => evaluate(expression, env), /unsupported expression|limits/, expression);
  }
  assert.equal(evaluate('Math.PI', env), Math.PI);
  assert.equal(evaluate('Math["E"]', env), Math.E);
  assert.equal(evaluate('String.fromCharCode(65)', env), 'A');
  assert.equal(evaluate('parseInt("12",10)', env), 12);
});

test('finite memory bounds refuse output multiplication before allocating an oversized replacement', () => {
  for (const expression of ['"x".repeat(100000000)', '"x".padStart(100000000)',
    '"x".repeat(64).replaceAll("", "y".padStart(16384))',
    '"a".repeat(64).replaceAll("a", "$`".repeat(64))',
    '"a".repeat(64).repeat(64).repeat(2).replaceAll("a".repeat(64).repeat(64).repeat(2), "$&".repeat(64).repeat(64))',
    '1/0', '10**1000000']) {
    assert.throws(() => evaluate(expression, env), /unsupported expression|limits/, expression);
  }
  assert.throws(() => evaluate('('.repeat(1025) + 'page', env));
  assert.throws(() => templateExpressions('{{' + '"' + 'x'.repeat(2000) + '"}}'));
});

test('literal replacements preserve JavaScript dollar substitution without quadratic native expansion',()=>{
  for(const [input,search,replacement]of [
    ['abc abc','abc',"$&-$`-$'$$"], ['xy','','$&'], ['abc','b','$$'], ['abc','z','$&'],
  ]){
    const bindings={...env,key:input};
    assert.equal(evaluate(`key.replaceAll(${JSON.stringify(search)},${JSON.stringify(replacement)})`,bindings),input.replaceAll(search,replacement));
    assert.equal(evaluate(`key.replace(${JSON.stringify(search)},${JSON.stringify(replacement)})`,bindings),input.replace(search,replacement));
  }
  assert.equal(evaluate('"a".repeat(64).repeat(64).replaceAll("", "$&".repeat(64).repeat(64))',env),'a'.repeat(4096));
});

test('unsupported or oversized expressions do not silently return fabricated empty values', () => {
  assert.throws(() => templateExpressions('/search?key={{unknownName}}'));
  assert.throws(() => templateExpressions('{{page}}'.repeat(65)));
  assert.throws(() => templateExpressions('x'.repeat(65537)));
  assert.throws(() => evaluate('page', { ...env, page: 0 }));
  assert.throws(() => evaluate('page', { ...env, key: 'x'.repeat(1025) }));
  assert.equal(evaluate('page', env), 3);
});
