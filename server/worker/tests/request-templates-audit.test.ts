import test from 'node:test';
import assert from 'node:assert/strict';
import { requestAddress as address } from '../src/request-templates';
import { evaluateTemplateExpression as evaluate } from '../src/templates';

const post = (body: unknown) => '/find,' + JSON.stringify({ method: 'POST', body });

test('a complete form-body expression never reinterprets template-shaped keyword data', () => {
  const key = '{{page}}';
  assert.equal(address(post('{{"q="+key}}'), key, 3).body, 'q=%7B%7Bpage%7D%7D');
  assert.equal(address(post('{{key}}'), 'q={{page}}', 3).body, 'q=%7B%7Bpage%7D%7D');
});

test('an explicit URI expression cannot mark a neighbouring bare-key form expression as already encoded', () => {
  const body = 'q={{key}}-{{encodeURIComponent("suffix")}}';
  assert.equal(address(post(body), 'literal+percent%2F', 2).body, 'q=literal%2Bpercent%252F-suffix');
  assert.equal(address(post('q={{encodeURIComponent(key)}}'), 'literal+percent%2F', 2).body, 'q=literal%2Bpercent%252F');
});

test('template-shaped keyword values stay inert across ordinary JSON and form fields', () => {
  const key = '{{page}} & __proto__=%25 + "quoted" \\';
  const jsonObject = address(post({ query: '{{key}}', page: '{{page+1}}' }), key, 3);
  assert.deepEqual(JSON.parse(jsonObject.body!), { query: key, page: '4' });
  const jsonString = address(post(JSON.stringify({ query: '{{key}}' })), key, 3);
  assert.deepEqual(JSON.parse(jsonString.body!), { query: key });
  assert.equal(new URLSearchParams(address(post('q={{key}}'), key, 3).body).get('q'), key);
  assert.equal(({} as Record<string, unknown>).polluted, undefined);
});

test('large expression arrays and prototype access are rejected without returning host objects', () => {
  const oversizedArray = '[' + Array(6).fill('"x".repeat(64).repeat(64)').join(',') + '].join("")';
  for (const expression of ['({constructor:page})', 'key["__proto__"]', 'JSON.stringify({x:globalThis})',
    '"x".repeat(64).repeat(64).repeat(64)', oversizedArray]) {
    assert.throws(() => evaluate(expression, { key: 'fixture', page: 1 }));
  }
});
