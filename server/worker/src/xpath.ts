/** XPath 1.0 over the existing Cheerio/domhandler tree. No parser, I/O or source JS. */
import * as xpath from 'xpath';
import render from 'dom-serializer';
import {isTag, type AnyNode, type Element} from 'domhandler';

export class XPathError extends Error {
  constructor(readonly code: 'XPATH_RULE' | 'XPATH_LIMIT' | 'XPATH_CONTEXT' | 'XPATH_RESULT', message: string) {
    super(message); this.name = 'XPathError';
  }
}

export const XPATH_LIMITS = Object.freeze({
  expressionChars: 2048, tokens: 384, nesting: 16, pathSteps: 64, functions: 32,
  nodes: 30_000, depth: 128, attributes: 80_000, documentChars: 2_097_152,
  visits: 1_000_000, results: 5000, outputChars: 1_048_576, milliseconds: 100,
});
type Limits = typeof XPATH_LIMITS;
/** Callers may lower a limit, but cannot lift the service bounds. */
export type XPathLimits = Partial<Record<keyof Limits, number>>;
const fail = (code: XPathError['code'], message: string): never => { throw new XPathError(code, message); };
function limits(request: XPathLimits = {}): Limits {
  return Object.fromEntries(Object.entries(XPATH_LIMITS).map(([key, bound]) => {
    const value = request[key as keyof Limits] ?? bound;
    if (!Number.isSafeInteger(value) || value < 1 || value > bound) fail('XPATH_LIMIT', 'Invalid XPath limit');
    return [key, value];
  })) as unknown as Limits;
}

const coreFunctions = new Set(('last position count id local-name namespace-uri name string concat starts-with contains '
  + 'substring-before substring-after substring string-length normalize-space translate boolean not true false lang number '
  + 'sum floor ceiling round text node comment processing-instruction').split(' '));
const expressions = new Map<string, xpath.XPathEvaluator>();

/** Validate before parsing. Quoted strings are data, never function names or rule code. */
function checkedExpression(rule: string, bound: Limits): xpath.XPathEvaluator {
  if (typeof rule !== 'string' || !rule.trim() || rule.length > bound.expressionChars) fail('XPATH_RULE', 'XPath expression is empty or too long');
  const tokens: string[] = [];
  let cursor = 0, depth = 0, steps = 0, calls = 0;
  while (cursor < rule.length) {
    if (/\s/.test(rule[cursor])) { cursor++; continue; }
    const rest = rule.slice(cursor);
    const token = /^(?:"[^"]*"|'[^']*'|[\p{L}_][\p{L}\p{N}_.-]*(?::[\p{L}_][\p{L}\p{N}_.-]*)?|\d+(?:\.\d*)?|\.\d+|::|\/\/|\.\.|!=|<=|>=|[\/()\[\]@.,|+*<>=:-])/u.exec(rest)?.[0]
      ?? fail('XPATH_RULE', 'Unsupported XPath token');
    tokens.push(token); cursor += token.length;
    if (tokens.length > bound.tokens) fail('XPATH_LIMIT', 'XPath token limit exceeded');
    if (token === '(' || token === '[') {
      if (++depth > bound.nesting) fail('XPATH_LIMIT', 'XPath nesting limit exceeded');
    } else if (token === ')' || token === ']') { if (--depth < 0) fail('XPATH_RULE', 'Unbalanced XPath expression'); }
    if (token === '/' || token === '//' || token === '::') {
      if (++steps > bound.pathSteps) fail('XPATH_LIMIT', 'XPath path limit exceeded');
    }
  }
  if (depth !== 0) fail('XPATH_RULE', 'Unbalanced XPath expression');
  for (let i = 0; i < tokens.length; i++) {
    if (tokens[i + 1] !== '(' || !/^[\p{L}_]/u.test(tokens[i])) continue;
    if (!coreFunctions.has(tokens[i])) fail('XPATH_RULE', 'Unsupported XPath function');
    if (++calls > bound.functions) fail('XPATH_LIMIT', 'XPath function limit exceeded');
  }
  // namespace axis returns synthetic library nodes, not original Cheerio nodes.
  if (tokens.some((token, i) => token === 'namespace' && tokens[i + 1] === '::')) fail('XPATH_RULE', 'XPath namespace axis is unsupported');
  let parsed = expressions.get(rule);
  if (!parsed) {
    try { parsed = xpath.parse(rule); } catch { fail('XPATH_RULE', 'Invalid XPath expression'); }
    if (expressions.size >= 128) expressions.delete(expressions.keys().next().value!);
    expressions.set(rule, parsed!);
  }
  return parsed!;
}
export function validateXPath(rule: string): void { checkedExpression(rule, XPATH_LIMITS); }

class Budget {
  visits = 0;
  readonly started = Date.now();
  constructor(readonly bound: Limits) {}
  checkTime() {
    if (Date.now() - this.started > this.bound.milliseconds) fail('XPATH_LIMIT', 'XPath evaluation time limit exceeded');
  }
  tick() {
    if (++this.visits > this.bound.visits) fail('XPATH_LIMIT', 'XPath traversal limit exceeded');
    // A deterministic visit limit remains effective on Workers, whose clock can stay fixed during CPU work.
    if ((this.visits & 255) === 0) this.checkTime();
  }
}
type View = Record<string, any>;
interface Entry { raw: AnyNode; order: number; end: number; view?: View; attrs?: View; children?: View }
const documents = new WeakMap<AnyNode, Adapter>();

/** Invalidate after mutating the original tree, e.g. Cheerio.remove(). */
export function invalidateXPath(root: AnyNode): void { documents.delete(root); }

class Adapter {
  readonly entries = new Map<AnyNode, Entry>();
  readonly originals = new WeakMap<object, AnyNode>();
  readonly positions = new WeakMap<object, {raw: AnyNode; attribute: number}>();
  readonly ids = new Map<string, AnyNode>();
  readonly metrics = {nodes: 0, depth: 0, attributes: 0, documentChars: 0};
  budget?: Budget;
  constructor(readonly root: AnyNode) {
    if (!root || root.type !== 'root' || root.parent) fail('XPATH_CONTEXT', 'XPath requires the original document root');
    const pending: Array<{raw: AnyNode; depth: number; exit?: boolean}> = [{raw: root, depth: 0}];
    let order = 0;
    while (pending.length) {
      const {raw, depth, exit} = pending.pop()!;
      if (exit) { this.entries.get(raw)!.end = order; continue; }
      if (this.entries.has(raw)) fail('XPATH_CONTEXT', 'Cyclic or shared XPath document nodes');
      const entry = {raw, order: order++, end: 0}; this.entries.set(raw, entry);
      this.metrics.nodes++; this.metrics.depth = Math.max(this.metrics.depth, depth);
      if ('data' in raw) this.metrics.documentChars += raw.data.length;
      if (isTag(raw)) {
        this.metrics.documentChars += raw.name.length;
        for (const [name, value] of Object.entries(raw.attribs)) {
          this.metrics.attributes++; this.metrics.documentChars += name.length + value.length;
        }
        if (raw.attribs.id && !this.ids.has(raw.attribs.id)) this.ids.set(raw.attribs.id, raw);
      }
      // Standard HTML PUBLIC/SYSTEM doctypes stay inert: this module has no parser or entity loader.
      if (raw.type === 'directive' && /(?:<!ENTITY|!ENTITY|\[)/i.test(raw.data)) fail('XPATH_RULE', 'Entity declarations and internal DTD subsets are unsupported');
      this.check(XPATH_LIMITS);
      pending.push({raw, depth, exit: true});
      if ('children' in raw && raw.children.length > XPATH_LIMITS.nodes) fail('XPATH_LIMIT', 'XPath child count limit exceeded');
      if ('children' in raw && pending.length + raw.children.length > XPATH_LIMITS.nodes + XPATH_LIMITS.depth) fail('XPATH_LIMIT', 'XPath pending node limit exceeded');
      if ('children' in raw) for (let i = raw.children.length - 1; i >= 0; i--) {
        const child: AnyNode = raw.children[i];
        if (child.parent !== raw) fail('XPATH_CONTEXT', 'Broken XPath parent relationship');
        pending.push({raw: child, depth: depth + 1});
      }
    }
  }
  check(bound: Limits) {
    for (const name of ['nodes', 'depth', 'attributes', 'documentChars'] as const) {
      if (this.metrics[name] > bound[name]) fail('XPATH_LIMIT', 'XPath document limit exceeded');
    }
  }
  tick() { this.budget?.tick(); }
  list(values: AnyNode[]): View {
    return new Proxy(Object.create(null), {get: (_target, key) => {
      this.tick();
      if (key === 'length') return values.length;
      const item = (index: number) => { this.tick(); return this.view(values[index] ?? null); };
      if (key === 'item') return item;
      if (typeof key === 'string' && /^(?:0|[1-9]\d*)$/.test(key)) return item(Number(key));
      return undefined;
    }, set: () => false});
  }
  attribute(owner: Element, name: string, value: string, index: number): View {
    const data: View = {
      nodeType: 2, nodeName: name, name, localName: name.split(':').pop(), prefix: name.includes(':') ? name.split(':')[0] : null,
      namespaceURI: name === 'xmlns' || name.startsWith('xmlns:') ? 'http://www.w3.org/2000/xmlns/' : name.startsWith('xml:') ? 'http://www.w3.org/XML/1998/namespace' : null,
      nodeValue: value, value, specified: true,
    };
    const proxy = new Proxy(data, {get: (target, key) => {
      this.tick();
      if (key === 'ownerElement') return this.view(owner);
      if (key === 'ownerDocument') return this.view(this.root);
      if (key === 'parentNode') return null;
      if (key === 'compareDocumentPosition') return (other: object) => this.compare(owner, other, index + 1);
      return target[key as string];
    }, set: () => false});
    this.positions.set(proxy, {raw: owner, attribute: index + 1});
    return proxy;
  }
  attributes(owner: Element): View {
    const values = Object.entries(owner.attribs), cached = new Map<number, View>();
    const indices = new Map(values.map(([name], index) => [name, index]));
    const item = (index: number) => {
      this.tick();
      if (!values[index]) return null;
      let attr = cached.get(index);
      if (!attr) { attr = this.attribute(owner, values[index][0], values[index][1], index); cached.set(index, attr); }
      return attr;
    };
    return new Proxy(Object.create(null), {get: (_target, key) => {
      this.tick();
      if (key === 'length') return values.length;
      if (key === 'item') return item;
      if (key === 'getNamedItem') return (name: string) => item(indices.get(name) ?? -1);
      return typeof key === 'string' && /^(?:0|[1-9]\d*)$/.test(key) ? item(Number(key)) : undefined;
    }, set: () => false});
  }
  compare(raw: AnyNode, other: object, attrIndex = 0): number {
    this.tick();
    const otherPosition = this.positions.get(other);
    if (!otherPosition) return 1;
    const a = this.entries.get(raw)!, b = this.entries.get(otherPosition!.raw)!;
    if (raw === otherPosition!.raw) return attrIndex === otherPosition!.attribute ? 0 : attrIndex < otherPosition!.attribute ? 4 : 2;
    if (a.order < b.order) return b.order < a.end ? 20 : 4;
    return a.order < b.end ? 10 : 2;
  }
  view(raw: AnyNode | null): View | null {
    if (!raw) return null;
    const entry = this.entries.get(raw);
    if (!entry) fail('XPATH_CONTEXT', 'XPath context belongs to another document or detached tree');
    if (entry!.view) return entry!.view;
    const proxy = new Proxy(Object.create(null), {get: (_target, key) => {
      this.tick();
      const element = isTag(raw);
      switch (key) {
        case 'nodeType': return raw.type === 'directive' ? raw.name === '!doctype' ? 10 : 7 : raw.nodeType;
        case 'nodeName': return element ? raw.name : raw.type === 'root' ? '#document' : raw.type === 'text' ? '#text' : raw.type === 'comment' ? '#comment' : raw.type === 'cdata' ? '#cdata-section' : 'name' in raw ? raw.name : '';
        case 'localName': return element ? raw.name.split(':').pop() : null;
        case 'tagName': return element ? raw.name : undefined;
        case 'prefix': return element && raw.name.includes(':') ? raw.name.split(':')[0] : null;
        case 'namespaceURI': return null; // HTML names use the no-prefix XPath convention.
        case 'ownerDocument': return raw === this.root ? null : this.view(this.root);
        case 'parentNode': return this.view(raw.parent);
        case 'previousSibling': return this.view(raw.prev);
        case 'nextSibling': return this.view(raw.next);
        case 'firstChild': return this.view('children' in raw ? raw.children[0] ?? null : null);
        case 'lastChild': return this.view('children' in raw ? raw.children.at(-1) ?? null : null);
        case 'childNodes': return entry!.children ??= this.list('children' in raw ? raw.children : []);
        case 'nodeValue': case 'data': return 'data' in raw ? raw.data : raw.type === 'cdata' ? render(raw.children, {xmlMode: true}) : null;
        case 'attributes': return element ? entry!.attrs ??= this.attributes(raw) : null;
        case 'documentElement': return raw === this.root && 'children' in raw ? this.view(raw.children.find(isTag) ?? null) : null;
        case 'getElementById': return (id: string) => this.view(this.ids.get(id) ?? null);
        case 'getAttribute': return (name: string) => element ? raw.attribs[name] ?? null : null;
        case 'compareDocumentPosition': return (other: object) => this.compare(raw, other);
        default: return undefined;
      }
    }, set: () => false});
    entry!.view = proxy; this.originals.set(proxy, raw); this.positions.set(proxy, {raw, attribute: 0}); return proxy;
  }
}

function evaluate(rule: string, root: AnyNode, contexts: readonly AnyNode[] | undefined, options: XPathLimits, nodesOnly: boolean): AnyNode[] | string[] {
  const bound = limits(options), parsed = checkedExpression(rule, bound);
  let adapter = documents.get(root);
  if (!adapter) { adapter = new Adapter(root); documents.set(root, adapter); }
  adapter.check(bound);
  const rows = contexts ?? [root];
  if (rows.length > bound.results) fail('XPATH_LIMIT', 'XPath context count limit exceeded');
  const output: Array<AnyNode | string> = [], seen = new Set<AnyNode>();
  let outputChars = 0, resultCount = 0;
  adapter.budget = new Budget(bound);
  try {
    for (const row of rows) {
      const result = parsed.evaluate({node: adapter.view(row) as unknown as Node, isHtml: true,
        // Bound amplification before allocating a giant concat string, including nested concat calls.
        functions: {concat: (_context, ...args) => {
          adapter!.tick();
          if (args.length < 2) fail('XPATH_RULE', 'XPath concat requires at least two arguments');
          let text = '';
          for (const arg of args) {
            adapter!.tick(); const value = arg.stringValue();
            if (text.length + value.length > bound.outputChars) fail('XPATH_LIMIT', 'XPath string size limit exceeded');
            text += value;
          }
          return text;
        }},
      });
      const nodeSet = result as xpath.XPathNodeSet;
      adapter.budget.checkTime();
      const values: Array<Node | string> = typeof nodeSet.toArray === 'function'
        ? (nodeSet.size > bound.results ? fail('XPATH_LIMIT', 'XPath result count limit exceeded') : nodeSet.toArray())
        : nodesOnly ? fail('XPATH_RESULT', 'XPath element selection requires a node set') : [result.stringValue()];
      resultCount += values.length;
      if (resultCount > bound.results) fail('XPATH_LIMIT', 'XPath result count limit exceeded');
      for (const value of values) {
        adapter.tick();
        const original = typeof value === 'object' ? adapter.originals.get(value) : undefined;
        if (nodesOnly) {
          if (!original) fail('XPATH_RESULT', 'XPath attributes and scalar values require getStrings');
          if (!seen.has(original!)) { output.push(original!); seen.add(original!); }
          continue;
        }
        // Native JsoupXpath JXNode.asString serializes selected elements; text()/@attr are plain values.
        const text = typeof value === 'string' ? value : original
          ? isTag(original) || original.type === 'root' ? render(original) : 'data' in original ? original.data : render(original)
          : value.nodeType === 2 ? value.nodeValue ?? '' : fail('XPATH_RESULT', 'Unsupported XPath result node');
        outputChars += text.length;
        if (outputChars > bound.outputChars) fail('XPATH_LIMIT', 'XPath output size limit exceeded');
        output.push(text);
      }
    }
    adapter.budget.checkTime();
  } finally { adapter.budget = undefined; }
  return output as AnyNode[] | string[];
}

/** Return original nodes so subsequent relative/parent/absolute XPath shares the same document. */
export function getNodes(rule: string, root: AnyNode, contexts?: readonly AnyNode[], options: XPathLimits = {}): AnyNode[] {
  return evaluate(rule, root, contexts, options, true) as AnyNode[];
}
export function getStrings(rule: string, root: AnyNode, contexts?: readonly AnyNode[], options: XPathLimits = {}): string[] {
  return evaluate(rule, root, contexts, options, false) as string[];
}
