import { parseExpressionAt } from 'acorn';

/** A small, inert expression language for Legado URL templates. No eval or source JS. */
export type TemplateBindings = { key: string; page: number; baseUrl?: string; now?: number };
type Ast = any;
const MAX_EXPRESSION = 1024;
const MAX_NODES = 192;
const MAX_DEPTH = 24;
const MAX_STRING = 16384;
const forbidden = new Set(['__proto__', 'prototype', 'constructor']);
const mathFunctions = new Set(['abs', 'ceil', 'floor', 'round', 'trunc', 'min', 'max', 'pow', 'sqrt', 'log', 'log2', 'log10']);
const stringFunctions = new Set(['charAt', 'charCodeAt', 'substring', 'substr', 'slice', 'trim', 'toLowerCase', 'toUpperCase', 'indexOf', 'lastIndexOf', 'replace', 'replaceAll', 'split', 'includes', 'startsWith', 'endsWith', 'padStart', 'padEnd', 'repeat', 'toString']);
const dataBindings = new Set(['key', 'page', 'baseUrl']);
const globalFunctions = new Set(['String', 'Number', 'parseInt', 'parseFloat', 'encodeURI', 'encodeURIComponent', 'decodeURI', 'decodeURIComponent']);
const builtinMethods: Record<string, Set<string>> = {
  Math: mathFunctions, JSON: new Set(['stringify']), Date: new Set(['now']), String: new Set(['fromCharCode']),
};
const tag = (name: string) => Object.freeze({ templateBuiltin: name });
const builtin = new Map(['Math', 'JSON', 'Date', 'String', 'Number'].map(name => [name, tag(name)]));

export class TemplateError extends Error {
  readonly code = 'UNSUPPORTED_TEMPLATE';
  constructor() { super('URL template needs an unsupported expression or exceeds its limits'); }
}
function reject(): never { throw new TemplateError(); }
function bounded(value: any): any {
  if (typeof value === 'number' && !Number.isFinite(value)) reject();
  if (typeof value === 'string' && value.length > MAX_STRING) reject();
  if (Array.isArray(value) && value.length > 64) reject();
  return value;
}
function property(node: Ast): string {
  const key = node.computed ? node.property.type === 'Literal' ? node.property.value : reject() : node.property.name;
  if ((typeof key !== 'string' && typeof key !== 'number') || forbidden.has(String(key))) reject();
  return String(key);
}

function replaceLiteral(input: string, search: string, replacement: string, all: boolean): string {
  type Token = { kind: 'literal'|'match'|'prefix'|'suffix'; text: string; count: number };
  const tokens: Token[] = [];
  const weights = { literal: 0, match: 0, prefix: 0, suffix: 0 };
  function push(kind: Token['kind'], text = '') {
    weights[kind] += kind === 'literal' ? text.length : 1;
    const previous = tokens.at(-1);
    if (previous?.kind === kind) {
      if (kind === 'literal') previous.text += text;
      else previous.count++;
    } else tokens.push({kind, text, count: 1});
  }
  for (let i = 0; i < replacement.length; i++) {
    if (replacement[i] === '$' && i + 1 < replacement.length) {
      const next = replacement[i + 1];
      if (next === '$') { push('literal', '$'); i++; continue; }
      if (next === '&') { push('match'); i++; continue; }
      if (next === '`') { push('prefix'); i++; continue; }
      if (next === "'") { push('suffix'); i++; continue; }
    }
    push('literal', replacement[i]);
  }
  const positions: number[] = [];
  if (!search) {
    for (let i = 0; i <= (all ? input.length : 0); i++) positions.push(i);
  } else {
    let position = 0;
    while ((position = input.indexOf(search, position)) >= 0) {
      positions.push(position); position += search.length;
      if (!all) break;
    }
  }
  const addedAt = (position: number) => weights.literal + weights.match * search.length +
    weights.prefix * position + weights.suffix * (input.length - position - search.length);
  const length = input.length - positions.length * search.length + positions.reduce((sum, position) => sum + addedAt(position), 0);
  if (length > MAX_STRING) reject();
  const chunks: string[] = [];
  let cursor = 0, work = 0;
  for (const position of positions) {
    if (++work > 32768) reject();
    if (position > cursor) chunks.push(input.slice(cursor, position));
    if (addedAt(position) > 0) for (const token of tokens) {
      if (++work > 32768) reject();
      const part = token.kind === 'literal' ? token.text : token.kind === 'match' ? search :
        token.kind === 'prefix' ? input.slice(0, position) : input.slice(position + search.length);
      if (part) chunks.push(token.kind === 'literal' ? part : part.repeat(token.count));
    }
    cursor = position + search.length;
  }
  if (cursor < input.length) chunks.push(input.slice(cursor));
  return chunks.join('');
}

function parse(expression: string): Ast {
  if (!expression || expression.length > MAX_EXPRESSION) reject();
  let ast: Ast;
  try { ast = parseExpressionAt(expression, 0, { ecmaVersion: 'latest' }); } catch { return reject(); }
  if (!['', ';'].includes(expression.slice(ast.end).trim())) reject();
  let count = 0;
  function check(node: Ast, depth = 0): void {
    if (++count > MAX_NODES || depth > MAX_DEPTH) reject();
    const visit = (child: Ast) => check(child, depth + 1);
    switch (node.type) {
      case 'Literal': if (node.regex || typeof node.value === 'bigint') reject(); bounded(node.value); break;
      case 'Identifier': if (!dataBindings.has(node.name)) reject(); break;
      case 'UnaryExpression': if (!['+', '-', '!', '~', 'typeof'].includes(node.operator)) reject(); visit(node.argument); break;
      case 'BinaryExpression': if (!['+', '-', '*', '/', '%', '**', '==', '!=', '===', '!==', '<', '<=', '>', '>=', '|', '&', '^', '<<', '>>', '>>>'].includes(node.operator)) reject(); visit(node.left); visit(node.right); break;
      case 'LogicalExpression': if (!['&&', '||', '??'].includes(node.operator)) reject(); visit(node.left); visit(node.right); break;
      case 'ConditionalExpression': visit(node.test); visit(node.consequent); visit(node.alternate); break;
      case 'MemberExpression': {
        if (node.optional) reject();
        const name = property(node);
        if (node.object.type === 'Identifier' && node.object.name === 'Math' && ['PI', 'E'].includes(name)) break;
        visit(node.object); break;
      }
      case 'CallExpression':
        if (node.optional || node.arguments.length > 32 || node.arguments.some((arg: Ast) => arg.type === 'SpreadElement')) reject();
        if (node.callee.type === 'Identifier') {
          if (!globalFunctions.has(node.callee.name)) reject();
        } else if (node.callee.type === 'MemberExpression') {
          if (node.callee.optional) reject();
          const name = property(node.callee);
          const owner = node.callee.object;
          if (owner.type === 'Identifier' && builtin.has(owner.name)) {
            if (!builtinMethods[owner.name]?.has(name)) reject();
          } else {
            if (!stringFunctions.has(name) && !['join', 'toFixed'].includes(name)) reject();
            visit(owner);
          }
        } else reject();
        node.arguments.forEach(visit); break;
      case 'ArrayExpression': if (node.elements.length > 64 || node.elements.some((item: Ast) => !item || item.type === 'SpreadElement')) reject(); node.elements.forEach(visit); break;
      case 'ObjectExpression':
        if (node.properties.length > 32) reject();
        for (const item of node.properties) {
          if (item.type !== 'Property' || item.kind !== 'init' || item.computed || item.method) reject();
          const key = item.key.name ?? item.key.value;
          if (forbidden.has(String(key))) reject();
          visit(item.value);
        }
        break;
      case 'TemplateLiteral': node.expressions.forEach(visit); break;
      default: reject();
    }
  }
  check(ast);
  return ast;
}

function run(ast: Ast, bindings: TemplateBindings): any {
  const evaluate = (node: Ast): any => bounded(run(node, bindings));
  switch (ast.type) {
    case 'Literal': return ast.value;
    case 'Identifier':
      if (ast.name === 'key') return bindings.key;
      if (ast.name === 'page') return bindings.page;
      if (ast.name === 'baseUrl') return bindings.baseUrl ?? '';
      return builtin.get(ast.name) ?? ast.name;
    case 'UnaryExpression': {
      const x = evaluate(ast.argument);
      switch (ast.operator) { case '+': return +x; case '-': return -x; case '!': return !x; case '~': return ~x; case 'typeof': return typeof x; }
      return reject();
    }
    case 'BinaryExpression': {
      const a = evaluate(ast.left), b = evaluate(ast.right);
      if ((a && typeof a === 'object') || (b && typeof b === 'object')) reject();
      switch (ast.operator) {
        case '+': return a + b; case '-': return a - b; case '*': return a * b; case '/': return a / b; case '%': return a % b; case '**': return a ** b;
        case '==': return a == b; case '!=': return a != b; case '===': return a === b; case '!==': return a !== b;
        case '<': return a < b; case '<=': return a <= b; case '>': return a > b; case '>=': return a >= b;
        case '|': return a | b; case '&': return a & b; case '^': return a ^ b; case '<<': return a << b; case '>>': return a >> b; case '>>>': return a >>> b;
      }
      return reject();
    }
    case 'LogicalExpression': {
      const a = evaluate(ast.left);
      return ast.operator === '&&' ? a && evaluate(ast.right) : ast.operator === '||' ? a || evaluate(ast.right) : a ?? evaluate(ast.right);
    }
    case 'ConditionalExpression': return evaluate(ast.test) ? evaluate(ast.consequent) : evaluate(ast.alternate);
    case 'ArrayExpression': return ast.elements.map(evaluate);
    case 'ObjectExpression': {
      const value = Object.create(null);
      for (const item of ast.properties) value[item.key.name ?? item.key.value] = evaluate(item.value);
      return value;
    }
    case 'TemplateLiteral': return ast.quasis.map((item: Ast, i: number) => (item.value.cooked ?? item.value.raw) + (i < ast.expressions.length ? String(evaluate(ast.expressions[i])) : '')).join('');
    case 'MemberExpression': {
      const owner = evaluate(ast.object), name = property(ast);
      if (owner === builtin.get('Math') && ['PI', 'E'].includes(name)) return (Math as any)[name];
      if (typeof owner === 'string' && name === 'length') return owner.length;
      if ((Array.isArray(owner) || typeof owner === 'string') && /^\d+$/.test(name)) return owner[Number(name)];
      if (owner && Object.getPrototypeOf(owner) === null && Object.hasOwn(owner, name)) return owner[name];
      return reject();
    }
    case 'CallExpression': {
      const args = ast.arguments.map(evaluate);
      if (ast.callee.type === 'Identifier') {
        const name = ast.callee.name;
        const fn: Record<string, (...values: any[]) => any> = { String, Number, parseInt, parseFloat, encodeURI, encodeURIComponent, decodeURI, decodeURIComponent };
        return bounded(fn[name](...args));
      }
      const owner = evaluate(ast.callee.object), name = property(ast.callee);
      if (owner === builtin.get('Math') && mathFunctions.has(name)) return bounded((Math as any)[name](...args));
      if (owner === builtin.get('JSON') && name === 'stringify') return bounded(JSON.stringify(args[0]));
      if (owner === builtin.get('Date') && name === 'now' && !args.length) return bindings.now ?? Date.now();
      if (owner === builtin.get('String') && name === 'fromCharCode') return String.fromCharCode(...args);
      if (typeof owner === 'number' && ['toString', 'toFixed'].includes(name)) {
        if (name === 'toFixed' && args[0] !== undefined && (!Number.isInteger(args[0]) || args[0] < 0 || args[0] > 20)) reject();
        return bounded((Number.prototype as any)[name].apply(owner, args));
      }
      if (Array.isArray(owner) && ['join', 'slice'].includes(name)) return bounded((Array.prototype as any)[name].apply(owner, args));
      if (typeof owner === 'string' && stringFunctions.has(name)) {
        if (name === 'repeat' && (!Number.isInteger(args[0]) || args[0] < 0 || args[0] > 64 || owner.length * args[0] > MAX_STRING)) reject();
        if (['padStart', 'padEnd'].includes(name) && (!Number.isInteger(args[0]) || args[0] < 0 || args[0] > MAX_STRING)) reject();
        if (['replace', 'replaceAll'].includes(name)) {
          if (typeof args[0] !== 'string' || typeof args[1] !== 'string') reject();
          return replaceLiteral(owner, args[0], args[1], name === 'replaceAll');
        }
        return bounded((String.prototype as any)[name].apply(owner, args));
      }
      return reject();
    }
  }
  return reject();
}

export function evaluateTemplateValue(expression: string, bindings: TemplateBindings): {value:unknown;uriEncoded:boolean;hasUriEncoding:boolean} {
  try {
    if (!Number.isSafeInteger(bindings.page) || bindings.page < 1 || bindings.page > 10000 || bindings.key.length > 1024) reject();
    const ast=parse(expression);
    const isUriCall=(node:Ast)=>node?.type==='CallExpression'&&node.callee.type==='Identifier'&&['encodeURI','encodeURIComponent'].includes(node.callee.name);
    const containsUriCall=(node:any):boolean=>!!node&&typeof node==='object'&&
      (isUriCall(node)||Object.values(node).some(value=>Array.isArray(value)?value.some(containsUriCall):containsUriCall(value)));
    return {value:bounded(run(ast, bindings)),uriEncoded:isUriCall(ast),hasUriEncoding:containsUriCall(ast)};
  } catch { return reject(); }
}
export function evaluateTemplateExpression(expression: string, bindings: TemplateBindings): unknown {
  return evaluateTemplateValue(expression,bindings).value;
}

/** Structural recognition does not claim that a source or endpoint is healthy. */
export function isSupportedTemplateExpression(expression: string): boolean {
  try { parse(expression); return true; } catch { return false; }
}

export function templateExpressions(value: string): Array<{ start: number; end: number; expression: string }> {
  if (value.length > 65536) reject();
  const expressions: Array<{ start: number; end: number; expression: string }> = [];
  let position = 0;
  while ((position = value.indexOf('{{', position)) >= 0) {
    if (expressions.length >= 64) reject();
    let expression: Ast;
    const start = position + 2;
    try { expression = parseExpressionAt(value.slice(start, start + MAX_EXPRESSION), 0, { ecmaVersion: 'latest' }); } catch { return reject(); }
    let end = start + expression.end;
    while (/\s/.test(value[end] ?? '') && end < value.length) end++;
    if (value[end] === ';') { end++; while (/\s/.test(value[end] ?? '') && end < value.length) end++; }
    if (value.slice(end, end + 2) !== '}}') reject();
    const source = value.slice(start, start + expression.end).trim();
    parse(source);
    expressions.push({ start: position, end: end + 2, expression: source });
    position = end + 2;
  }
  return expressions;
}
