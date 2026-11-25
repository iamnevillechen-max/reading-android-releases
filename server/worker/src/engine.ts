import * as cheerio from 'cheerio';
import { JSONPath } from 'jsonpath-plus';
import * as OpenCC from 'opencc-js';
import { requestAddress as address } from './request-templates';
import { templateExpressions } from './templates';
import { PublicGuestCookies } from './public-guest-cookies';
import { parseCiweimaoFreeChapter, decodeCiweimaoFreeChapter, extractCiweimaoFreeText, CiweimaoPublicError } from './ciweimao-public';
import { reviewedHosts, reviewedScripts } from './compiled-sources';
import { canReadContent } from './content-quality';
import { sourceForExecution, sourceExecutionHosts } from './source-adapters';
import { getNodes as xpathNodes, getStrings as xpathStrings, validateXPath, invalidateXPath } from './xpath';

export type NativeObject = Record<string, any>;
export type Source = NativeObject;
export const ENGINE_RULE_CONTRACT_VERSION = 'reader-rules-paging-xpath-1';
export const ENGINE_IMPLEMENTATION_VERSION = 'reader-engine-paging-xpath-guest-2';
export type CategoryRoute = { title: string; url: string };
export class EngineError extends Error {
  constructor(public code: string, message: string, public status = 422) { super(message); }
}
const MAX_BODY = 2 * 1024 * 1024;
const DEADLINE = 8_000;
const MAX_ITEMS = 10_000;
const MAX_TOC_PAGES = 12;
const MAX_CONTENT_PAGES = 8;
const MAX_PAGING_BYTES = 4 * 1024 * 1024;
const MAX_PAGING_FETCHES = 32;
type PagingBudget = { bytes: number; fetches: number };
type PageIdentity = { url: string; key: string };
type PageTarget = PageIdentity & { template: string; base: string; ordinal: number };
type PublicGuestFlow = { cookies: PublicGuestCookies; chapterUrl: string; ajax?: boolean };
const simplified = OpenCC.Converter({ from: 't', to: 'cn' });
const sourceId = (source: Source) => String(source.bookSourceUrl || source.sourceUrl || '');
const sourceName = (source: Source) => String(source.bookSourceName || source.sourceName || '');
const javascript = (value: string) => {
  const position = value.indexOf('@js:');
  return position < 0 ? undefined : value.slice(position + 4).trim();
};

function ruleStrings(source: Source, onlyDeclarative = false): string[] {
  const values: string[] = [];
  function visit(value: any) {
    if (typeof value === 'string') values.push(value);
    else if (value && typeof value === 'object') Object.values(value).forEach(visit);
  }
  for (const key of Object.keys(source)) if (key.startsWith('rule') || (!onlyDeclarative && ['exploreUrl','sortUrl','searchUrl','jsLib','loginCheckJs'].includes(key))) visit(source[key]);
  return values;
}

/** Unsupported imports are retained by storage as quarantined, never silently executed. */
export function sourceCapabilities(source: Source) {
  source = sourceForExecution(source);
  const rss = !source.bookSourceUrl && !!source.sourceUrl;
  const web = rss && source.singleUrl === true;
  let reason: string | undefined;
  if (!sourceId(source)) reason = '來源識別缺失';
  else if (sourceId(source) !== sourceId(source).trim() || /[\u0000-\u001F\u007F]|@js:|<js>|\{\{/i.test(sourceId(source))) reason = '來源識別不是可執行的靜態公開網址';
  else if (!(() => {
    try {
      const url = new URL(sourceId(source));
      return ['http:','https:'].includes(url.protocol) && !url.username && !url.password &&
        (!url.port || ['80','443'].includes(url.port)) && isPublicHost(url.hostname);
    } catch { return false; }
  })()) reason = '來源識別不是可執行的靜態公開網址';
  else if (source.bookSourceType && source.bookSourceType !== 0) reason = '目前只支援公開文字書籍';
  else if (source.loginUrl || source.loginUi || source.loginCheckJs || source.jsLib || source.coverDecodeJs || source.injectJs || source.shouldOverrideUrlLoading) reason = '登入、注入或自訂程式需獨立維護';
  else if (web) {
    try {
      const url = new URL(source.sourceUrl);
      if (/@js:|<js>|\{\{/i.test(source.sourceUrl) || !['https:', 'http:'].includes(url.protocol) || url.username || url.password ||
          (url.port && !['80', '443'].includes(url.port)) || !isPublicHost(url.hostname)) reason = '網站入口須是靜態公開網址';
      else if (ruleStrings(source).some(value => /@js:|<js>|javascript:|\{\{|@web|@put:|@get:/i.test(value))) reason = '網站入口包含需要獨立維護的動態規則';
    } catch { reason = '網站入口須是靜態公開網址'; }
  }
  else {
    for (const value of ruleStrings(source)) {
      const script = javascript(value);
      if ((script !== undefined && !Object.hasOwn(reviewedScripts, script)) || /<js>|@web|@put:|@get:/i.test(value)) {
        reason = '規則包含未核准程式或解析語法'; break;
      }

    }
  }
  if (!reason && !web) {
    try {
      // Request templates use the inert AST language. Selector/value templates
      // are a separate native feature and remain unsupported until rendered.
      if (ruleStrings(source, true).some(value => value.includes('{{'))) throw new Error('selector template');
      const check = (value: any): void => {
        if (typeof value === 'string') templateExpressions(value);
        else if (value && typeof value === 'object') Object.values(value).forEach(check);
      };
      for (const field of ['searchUrl', 'exploreUrl', 'sortUrl']) {
        const raw = String(source[field] || '').trim();
        if (!raw.includes('{{')) continue;
        if (raw.startsWith('[')) { check(staticJson(raw)); continue; }
        const options = /,\s*(\{[\s\S]*\})\s*$/.exec(raw);
        if (options) { check(raw.slice(0, options.index)); check(staticJson(options[1])); }
        else check(raw);
      }
    } catch { reason = '模板包含未支援運算或尚未支援的規則位置'; }
  }
  if (!reason && !web) {
    for (const value of ruleStrings(source, true)) {
      const prefix = value.split('@js:')[0].split('##')[0];
      try {
        for (const part of splitRule(prefix, ['&&', '||', '%%']).parts) {
          const expression = xpathExpression(part); if (expression !== undefined) validateXPath(expression);
        }
      } catch { reason = 'XPath規則語法或服務界限未支援'; break; }
    }
  }
  if (!reason) {
    try { requestHeaders(source); } catch (error) { reason = (error as Error).message; }
  }
  const supported = !reason;
  return { supported, reason, search: supported && !rss && !!source.searchUrl && !!source.ruleSearch,
    explore: supported && !rss && source.enabledExplore !== false && !!source.exploreUrl && !!String(source.ruleExplore?.bookList || source.ruleSearch?.bookList || '').trim(),
    rss: supported && rss && !web, web: supported && web, detail: supported && !rss && !!source.ruleBookInfo,
    chapters: supported && !rss && !!source.ruleToc, content: supported && !rss && !!source.ruleContent };
}
export function assessSource(kind: string, source: Source) {
  const capabilities = sourceCapabilities(source);
  return { ...capabilities, capabilities: Object.entries(capabilities).filter(([key,value])=>key !== 'supported' && value === true).map(([key])=>key),
    name: sourceName(source), enabledExplore: capabilities.explore,
    kind, sourceId: sourceId(source) };
}
function assertSupported(source: Source) {
  const capability = sourceCapabilities(source);
  if (!capability.supported) throw new EngineError('UNSUPPORTED_SOURCE', capability.reason || '未支援來源');
}

function requestHeaders(source: Source): Headers {
  let raw = source.header;
  if (raw && typeof raw === 'string') {
    try { raw = JSON.parse(raw); } catch { throw new EngineError('UNSUPPORTED_HEADERS', '請求頭須是靜態JSON'); }
  }
  const headers = new Headers({ 'User-Agent': 'Mozilla/5.0 (compatible; ReaderPublicSource/1.0)', Accept: 'text/html,application/json,application/xml,text/xml;q=0.9,*/*;q=0.8' });
  for (const [key, value] of Object.entries(raw || {})) {
    if (!['user-agent','accept','accept-language','referer','content-type'].includes(key.toLowerCase()) || typeof value !== 'string' || /[\r\n]/.test(value)) {
      throw new EngineError('UNSUPPORTED_HEADERS', '私人登入或不支援請求頭需獨立維護');
    }
    headers.set(key, value);
  }
  return headers;
}

export function isPublicHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
  if (!host || host === 'localhost' || /(?:^|\.)(localhost|local|internal|test|invalid|onion)$/.test(host) || host.includes(':')) return false;
  if (/^\d+(?:\.\d+){3}$/.test(host)) {
    const parts = host.split('.').map(Number);
    if (parts.some(part => part > 255)) return false;
    const [a,b] = parts;
    return !(a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && [0,168].includes(b)) || (a === 198 && [18,19].includes(b)));
  }
  // A public DNS name must be an ordinary fully qualified host, not a numeric IP alias.
  return /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*$/.test(host);
}
export function isPublicAddress(address: string): boolean {
  if (!address.includes(':')) return /^\d+(?:\.\d+){3}$/.test(address) && isPublicHost(address);
  const ip = address.toLowerCase();
  // Only globally routable unicast. Reject mapped/NAT64, link-local, loopback,
  // unique-local, multicast, documentation and transition address ranges.
  return /^[23][0-9a-f]{3}:/.test(ip) && !/^2001:(?:0{0,3}[0-9a-f]{1,2}|db8):/.test(ip) && !ip.startsWith('2002:');
}
const dnsCache = new Map<string,{ expires: number; safe: boolean }>();
async function resolvePublicHost(hostname: string, signal: AbortSignal): Promise<boolean> {
  if (/^\d+(?:\.\d+){3}$/.test(hostname)) return isPublicAddress(hostname);
  const cached = dnsCache.get(hostname);
  if (cached && cached.expires > Date.now()) return cached.safe;
  const replies = await Promise.all(['A','AAAA'].map(async type => {
    const response = await fetch(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(hostname)}&type=${type}`, {
      headers:{accept:'application/dns-json'},signal,redirect:'manual',
    });
    if (!response.ok || Number(response.headers.get('content-length')) > 64*1024) throw new EngineError('DNS_FAILED','來源DNS檢查失敗',502);
    const text = await response.text();
    if (text.length > 64*1024) throw new EngineError('DNS_FAILED','來源DNS回應過大',502);
    const data = JSON.parse(text) as { Status?: number; Answer?: Array<{type:number;data:string;TTL?:number}> };
    if (data.Status !== 0 && data.Status !== 3) throw new EngineError('DNS_FAILED','來源DNS暫不可用',502);
    return (data.Answer || []).filter(answer=>[1,28].includes(answer.type));
  }));
  const answers = replies.flat(), safe = answers.length > 0 && answers.every(answer=>isPublicAddress(answer.data));
  const ttl = Math.max(5,Math.min(300,...answers.map(answer=>Number(answer.TTL || 30))));
  if (dnsCache.size >= 128) dnsCache.delete(dnsCache.keys().next().value!);
  dnsCache.set(hostname,{safe,expires:Date.now()+ttl*1000});
  return safe;
}
function hostsFor(source: Source): Set<string> {
  // The server may supply its separately reviewed host list; source imports cannot
  // grant themselves extra hosts. Built-in cross-site aliases are compiled above.
  return new Set(sourceExecutionHosts(source) || reviewedHosts[sourceId(source)] || [new URL(sourceId(source)).hostname]);
}
function validateUrl(value: string, hosts: Set<string>, base?: string): URL {
  let url: URL;
  try { url = new URL(value, base); } catch { throw new EngineError('INVALID_URL', '來源URL無效'); }
  if (!['https:','http:'].includes(url.protocol) || url.username || url.password ||
    (url.port && !['80','443'].includes(url.port)) || !isPublicHost(url.hostname) || !hosts.has(url.hostname.toLowerCase())) {
    throw new EngineError('UNSAFE_URL', '來源URL不在已核准公開主機內');
  }
  url.hash = '';
  return url;
}

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
type EngineOptions = { fetch?: Fetcher; deadlineMillis?: number; approvedHosts?: (source: Source) => string[];
  resolveHost?: (hostname: string, signal: AbortSignal) => Promise<boolean> };
/** Accept the native trailing-comma convention without evaluating JSON5/JavaScript. */
function staticJson(raw: string): any {
  let output = '', quoted = false;
  for (let at = 0; at < raw.length; at++) {
    const char = raw[at];
    if (quoted && char === '\\') { output += char + (raw[++at] || ''); continue; }
    if (char === '"') quoted = !quoted;
    if (!quoted && char === ',') {
      let next = at + 1; while (/\s/.test(raw[next] || '') && next < raw.length) next++;
      if (raw[next] === '}' || raw[next] === ']') continue;
    }
    output += char;
  }
  return JSON.parse(output);
}


function checkedRegex(pattern: string, flags = ''): RegExp {
  if (pattern.length > 512 || /\([^)]*[+*][^)]*\)[+*{]/.test(pattern)) throw new EngineError('UNSUPPORTED_REGEX', '正則表達式超出安全範圍');
  try { return new RegExp(pattern, flags); } catch { throw new EngineError('INVALID_REGEX', '正則表達式無效'); }
}
function normalizeSelector(selector: string, pseudos: Record<string, any>): string {
  // Jsoup treats href~=... as a regex. Cheerio otherwise treats ~= as a CSS word match.
  let output = '';
  for (let i = 0; i < selector.length;) {
    if (selector[i] === '[') {
      let end = i + 1, depth = 1, quote = '';
      for (; end < selector.length && depth; end++) {
        const char = selector[end];
        if (char === '\\') { end++; continue; }
        if (quote) { if (char === quote) quote = ''; continue; }
        if (char === '"' || char === "'") { quote = char; continue; }
        if (char === '[') depth++;
        else if (char === ']') depth--;
      }
      const raw = selector.slice(i + 1, end - 1), match = /^([\w:-]+)~=(.+)$/.exec(raw);
      if (match) {
        const name = `readerattr${Object.keys(pseudos).length}`, regex = checkedRegex(match[2].replace(/^(["'])(.*)\1$/, '$2'));
        pseudos[name] = (element: any) => regex.test(element.attribs?.[match[1]] || '');
        output += `:${name}`;
      } else output += selector.slice(i, end);
      i = end;
    } else if (selector.slice(i).toLowerCase().startsWith(':matchesown(')) {
      const start = i + 12;
      let end = start, depth = 1;
      for (; end < selector.length && depth; end++) {
        if (selector[end] === '\\') { end++; continue; }
        if (selector[end] === '(') depth++;
        else if (selector[end] === ')') depth--;
      }
      const name = `readertext${Object.keys(pseudos).length}`, regex = checkedRegex(selector.slice(start, end - 1));
      pseudos[name] = (element: any) => regex.test((element.children || []).filter((node: any) => node.type === 'text').map((node: any) => node.data).join(''));
      output += `:${name}`; i = end;
    } else output += selector[i++];
  }
  return output.replace(/\[([\w:-]+)([\^$*|]?=)([^\]"'\s][^\]]*)\]/g, (_all, key, operator, value) => `[${key}${operator}"${value.replaceAll('"','\\"')}"]`);
}

/** RuleAnalyzer splits operators only outside CSS/JSONPath brackets and quoted values. */
function splitRule(input: string, operators: string[]): { parts: string[]; operator?: string } {
  let quote = '', depth = 0, start = 0, operator: string | undefined;
  const parts: string[] = [];
  for (let at = 0; at < input.length; at++) {
    const char = input[at];
    if (char === '\\') { at++; continue; }
    if (quote) { if (char === quote) quote = ''; continue; }
    if (char === '"' || char === "'") { quote = char; continue; }
    if (char === '[' || char === '(') { depth++; continue; }
    if (char === ']' || char === ')') { depth--; continue; }
    if (depth !== 0) continue;
    const found = operator ? (input.startsWith(operator, at) ? operator : undefined)
      : operators.find(candidate => input.startsWith(candidate, at));
    if (found) {
      operator = found; parts.push(input.slice(start, at)); at += found.length - 1; start = at + 1;
    }
  }
  parts.push(input.slice(start));
  return { parts, operator };
}

function indexedSelector(raw: string) {
  let selector = raw.trim(), specification: string | undefined, exclude = false, ranges = false;
  const bracket = /^(.*)\[(!?[\d\s,:-]+)\]$/.exec(selector);
  const suffix = /^(.*?)([.!])(-?\d+(?::-?\d+){0,2})$/.exec(selector);
  if (bracket) {
    selector = bracket[1]; specification = bracket[2]; exclude = specification.startsWith('!'); ranges = true;
    if (exclude) specification = specification.slice(1);
  } else if (suffix) {
    selector = suffix[1]; specification = suffix[3]; exclude = suffix[2] === '!';
  }
  return { selector, specification, exclude, ranges };
}

function applyIndexes(nodes: any[], specification: string, exclude: boolean, ranges: boolean): any[] {
  const indexes = new Set<number>(), length = nodes.length;
  const add = (index: number) => { const normalized = index < 0 ? index + length : index; if (normalized >= 0 && normalized < length) indexes.add(normalized); };
  for (const part of specification.split(ranges ? ',' : ':')) {
    if (ranges && part.includes(':')) {
      const values = part.split(':').map(value => value.trim() === '' ? undefined : Number(value));
      if (values.length > 3 || values.some(value => value !== undefined && !Number.isInteger(value))) throw new EngineError('INVALID_SELECTOR', '來源索引無效');
      if (!length) continue;
      let start = values[0] ?? 0, end = values[1] ?? length - 1;
      if (start < 0) start += length; if (end < 0) end += length;
      if ((start < 0 && end < 0) || (start >= length && end >= length)) continue;
      start = Math.max(0, Math.min(length - 1, start)); end = Math.max(0, Math.min(length - 1, end));
      const rawStep = values[2] ?? 1;
      const step = rawStep > 0 ? rawStep : -rawStep < length ? rawStep + length : 1;
      if (!step) throw new EngineError('INVALID_SELECTOR', '來源索引間隔無效');
      for (let index = start; start <= end ? index <= end : index >= end; index += start <= end ? step : -step) add(index);
    } else {
      const index = Number(part.trim());
      if (!part.trim() || !Number.isInteger(index)) throw new EngineError('INVALID_SELECTOR', '來源索引無效');
      add(index);
    }
  }
  return exclude ? nodes.filter((_node, index) => !indexes.has(index)) : [...indexes].map(index => nodes[index]);
}

class JsoupElements {
  constructor(readonly document: DocumentContext, readonly selection: any) {}
  size() { return this.selection.length; }
  get(index: number) { return new JsoupElements(this.document, this.selection.eq(index)); }
  first() { return this.get(0); }
  select(selector: string) { return this.document.select(selector, this.selection); }
  closest(selector: string) {
    return new JsoupElements(this.document, this.selection.closest(normalizeSelector(selector, this.document.pseudos)));
  }
  text() { return this.selection.toArray().map((node: any) => this.document.$(node).text().replace(/\s+/g,' ').trim()).join(' ').trim(); }
  ownText() { return this.selection.toArray().map((node: any) => this.document.$(node).contents().filter((_i: number, child: any) => child.type === 'text').text()).join(' ').trim(); }
  html() { return this.selection.toArray().map((node: any) => this.document.$(node).html() || '').join('\n'); }
  attr(name: string) { return this.selection.first().attr(name) || ''; }
  hasClass(name: string) { return this.selection.hasClass(name); }
  clone() { return new JsoupElements(this.document, this.selection.clone()); }
  remove() { this.selection.remove(); invalidateXPath(this.document.rootNode()); this.document.invalidateSelectionCaches(); return this; }
  toArray(): JsoupElements[] { return this.selection.toArray().map((node: any) => new JsoupElements(this.document, this.document.$(node))); }
}
class DocumentContext {
  readonly pseudos: Record<string, any> = {};
  readonly $: any;
  private readonly normalizedSelectors = new Map<string, string>();
  private readonly selfMatches = new Map<string, Set<any>>();
  private readonly literalOwnPseudos = new Map<string, string>();
  constructor(readonly body: string, xml = false) {
    this.$ = cheerio.load(body, { xmlMode: xml, pseudos: this.pseudos });
  }
  root() { return new JsoupElements(this, this.$.root()); }
  rootNode() { return this.$.root().toArray()[0]; }
  invalidateSelectionCaches() { this.selfMatches.clear(); }
  select(selector: string, scope?: any, legacy = false) {
    const indexed = indexedSelector(selector); selector = indexed.selector;
    let children = selector === '' || selector === 'children';
    if (legacy) {
      const simple = /^(class|tag|id|text)\.(.*)$/.exec(selector);
      if (simple) {
        const [, type, target] = simple;
        if (type === 'class') selector = target.trim().split(/\s+/).map(name => '.' + [...name].map((char, index) =>
          /[a-z_\u0080-\uffff-]/i.test(char) || (index > 0 && /\d/.test(char)) ? char : `\\${char.codePointAt(0)!.toString(16)} `).join('')).join('');
        else if (type === 'id') selector = `[id=${JSON.stringify(target)}]`;
        else if (type === 'tag') selector = target;
        else {
          const name = this.literalOwnPseudos.get(target) || `readerown${Object.keys(this.pseudos).length}`, needle = target.toLowerCase();
          this.literalOwnPseudos.set(target, name);
          this.pseudos[name] = (element: any) => this.$(element).contents().filter((_i: number, node: any) => node.type === 'text').text().toLowerCase().includes(needle);
          selector = `*:${name}`;
        }
      }
    }
    let normalized = this.normalizedSelectors.get(selector);
    if (normalized === undefined) {
      normalized = normalizeSelector(selector, this.pseudos);
      this.normalizedSelectors.set(selector, normalized);
    }
    const query = normalized;
    const matchesSelf = (_index: number, node: any) => {
      if (!['tag', 'script', 'style'].includes(node.type)) return false;
      let matches = this.selfMatches.get(query);
      if (!matches) { matches = new Set(this.$(query).toArray()); this.selfMatches.set(query, matches); }
      return matches.has(node);
    };
    const found = children ? (scope || this.$.root()).children()
      : scope ? scope.find(query).add(scope.filter(matchesSelf)) : this.$(query);
    return new JsoupElements(this, indexed.specification === undefined ? found
      : this.$(applyIndexes(found.toArray(), indexed.specification, indexed.exclude, indexed.ranges)));
  }
}

function jsonPath(input: any, path: string): any[] {
  if (typeof input === 'string') { try { input = JSON.parse(input); } catch { return []; } }
  const filter = /^(.*)\[\?\(@\.([\w]+)\s*==\s*(['"])([^'"]*)\3\)\]$/.exec(path);
  if (filter) return jsonPath(input, filter[1]).flatMap(value => Array.isArray(value) ? value : [value]).filter(value => String(value?.[filter[2]]) === filter[4]);
  if (!/^\$(?:\.[\w-]+|\['[\w-]+'\]|\[\d+\]|\[\*\])*$/u.test(path)) throw new EngineError('UNSUPPORTED_JSONPATH', 'JSONPath包含未支援運算');
  return JSONPath({ json: input, path, eval: false, wrap: true }) as any[];
}
function variables(book: NativeObject) {
  let values: NativeObject = {};
  try { values = typeof book.variable === 'string' ? JSON.parse(book.variable) : { ...(book.variable || {}) }; } catch { /* A corrupt optional cache cannot grant access. */ }
  return { getVariable: (key: string) => values[key] ?? '', putVariable: (key: string, value: any) => {
    values[key] = String(value); book.variable = JSON.stringify(values);
  } };
}
type Evaluation = { document: DocumentContext; scope: any; baseUrl: string; book: NativeObject; original: string };
function xpathExpression(raw: string): string | undefined {
  const rule = raw.trim();
  if (/^@xpath:/i.test(rule)) return rule.slice(7).trim();
  return /^(?:\/|\.\.?\/|\(\s*\.?\/)/.test(rule) ? rule : undefined;
}
function evaluate(rule: string | undefined, env: Evaluation, wantElements = false, listValues = false): any {
  if (!rule) return '';
  const script = javascript(rule);
  const prefix = script === undefined ? rule : rule.slice(0, rule.indexOf('@js:')).trim();
  let result: any = env.original;
  if (prefix) result = declarative(prefix, env, wantElements, listValues);
  if (script !== undefined) {
    const execute = reviewedScripts[script];
    if (!execute) throw new EngineError('UNSUPPORTED_SCRIPT', '未核准的規則程式');
    result = execute({ result, baseUrl: env.baseUrl, book: variables(env.book), java: {
      getElements: (selector: string) => env.scope instanceof JsoupElements ? env.scope.select(selector) : env.document.select(selector),
      getString: (value: string) => String(declarative(value, env) ?? ''),
      t2s: (value: any) => simplified(String(value ?? '')),
    } });
  }
  return result;
}
function declarative(raw: string, env: Evaluation, wantElements = false, listValues = false): any {
  const [rule, pattern, replacement = ''] = raw.trim().split('##');
  let result: any;
  const combined = splitRule(rule, ['&&', '||', '%%']);
  if (combined.operator) {
    const rows: any[][] = [];
    for (const part of combined.parts) {
      const output = declarative(part, env, wantElements, listValues);
      const values = output instanceof JsoupElements ? output.toArray() : Array.isArray(output) ? output : output === '' || output == null ? [] : [output];
      rows.push(values);
      if (combined.operator === '||' && values.length) break;
    }
    result = combined.operator === '||' ? rows.find(row => row.length) || []
      : combined.operator === '%%' ? (rows[0] || []).flatMap((_value, index) => rows.flatMap(row => index < row.length ? [row[index]] : [])) : rows.flat();
  } else if (xpathExpression(rule) !== undefined) {
    if (!(env.scope instanceof JsoupElements)) return listValues || wantElements ? [] : '';
    const expression = xpathExpression(rule)!, contexts = env.scope.selection.toArray(), root = env.document.rootNode();
    if (wantElements) result = new JsoupElements(env.document, env.document.$(xpathNodes(expression, root, contexts)));
    else {
      const values = xpathStrings(expression, root, contexts); result = listValues ? values : values.join('\n');
    }
  } else if (rule.startsWith('$')) {
    const values = jsonPath(env.scope instanceof JsoupElements ? env.original : env.scope, rule);
    result = values.length === 1 ? values[0] : values;
  } else {
    if (!(env.scope instanceof JsoupElements)) return '';
    const css = /^@css:/i.test(rule), cleaned = (css ? rule.slice(5) : rule).replace(/^@+/, '').trim();
    const steps = splitRule(cleaned, ['@']).parts.map(step => step.trim()).filter(Boolean);
    // Native value rules always treat their final step as a text operation or
    // attribute name, including arbitrary data-* attributes on the row itself.
    const selectionOnly = wantElements;
    const property = selectionOnly ? '' : steps.pop() || '';
    let elements: JsoupElements = env.scope;
    for (const step of css ? [steps.join('@')].filter(Boolean) : steps) {
      const selected = elements.toArray().flatMap(item => env.document.select(step, item.selection, !css).selection.toArray());
      elements = new JsoupElements(env.document, env.document.$(selected));
    }
    if (selectionOnly) result = elements;
    else if (property === 'text') {
      const values = elements.toArray().map(item => item.text()).filter(Boolean); result = listValues ? values : values.join('\n');
    } else if (property === 'ownText') {
      const values = elements.toArray().map(item => item.ownText()).filter(Boolean); result = listValues ? values : values.join('\n');
    }
    else if (property === 'html' || property === 'all') result = elements.html();
    else if (property === 'textNodes') result = elements.selection.toArray().flatMap((node: any) => env.document.$(node).contents().toArray().filter((child: any) => child.type === 'text').map((child: any) => child.data)).join('\n');
    else result = listValues ? [...new Set(elements.selection.toArray().map((node: any) => env.document.$(node).attr(property) || '').filter(Boolean))] : elements.attr(property);
  }
  if (pattern !== undefined) {
    const regex = checkedRegex(pattern, 'g');
    result = listValues && Array.isArray(result) ? result.map(item => String(item ?? '').replace(regex, replacement))
      : (Array.isArray(result) ? result.join('\n') : String(result ?? '')).replace(regex, replacement);
  }
  return result;
}
function items(value: any): any[] {
  const result = value instanceof JsoupElements ? value.toArray() : Array.isArray(value) ? value : value && typeof value === 'object' ? [value] : [];
  if (result.length > MAX_ITEMS) throw new EngineError('TOO_MANY_ITEMS','來源列表超出服務上限，未截斷保存');
  return result;
}
function value(rule: string | undefined, env: Evaluation): string {
  const result = evaluate(rule, env);
  return result === null || result === undefined ? '' : Array.isArray(result) ? result.join('\n') : String(result);
}
function pageLinks(rule: string | undefined, env: Evaluation): string[] {
  if (!rule?.trim()) return [];
  const result = evaluate(rule, env, false, true);
  if (result instanceof JsoupElements) throw new EngineError('INVALID_PAGINATION', '分頁規則未取得明確URL');
  const values = Array.isArray(result) ? result.flat() : [result];
  if (values.length > MAX_ITEMS) throw new EngineError('PAGINATION_LINK_LIMIT', '分頁URL數量超出服務上限');
  const links = values.flatMap(item => {
    if (item == null || item === '') return [];
    if (typeof item !== 'string') throw new EngineError('INVALID_PAGINATION', '分頁URL類型無效');
    return item.split('\n').map(url => url.trim()).filter(Boolean);
  });
  if (links.length > MAX_ITEMS) throw new EngineError('PAGINATION_LINK_LIMIT', '分頁URL數量超出服務上限');
  return links;
}
function flag(rule: string | undefined, env: Evaluation): boolean {
  const evaluated = value(rule,env).trim();
  return !!evaluated && !/^(?:0|false|null|undefined)$/i.test(evaluated);
}
function absolute(value: string, base: string): string {
  if (!value) return '';
  try { const url = new URL(value, base); return ['https:','http:'].includes(url.protocol) ? url.href : ''; } catch { return ''; }
}
function plaintext(html: string): string {
  const $ = cheerio.load(html);
  $('script,style,noscript,iframe').remove();
  $('br').replaceWith('\n');
  $('p,div,section,li,h1,h2,h3').each((_i, node) => { $(node).append('\n'); });
  return $.root().text().split('\n').map(line => line.trim()).filter(Boolean).join('\n');
}

export function createEngine(options: EngineOptions = {}) {
  const transport = options.fetch || ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init));
  const trustedToc = new Map<string,{ variable: string; expires: number }>();
  const deadline = () => Date.now() + Math.min(options.deadlineMillis || DEADLINE,DEADLINE);
  const contextKey = (source: Source, book: NativeObject) => [sourceId(source),book.bookUrl,book.tocUrl || '',JSON.stringify(source.ruleToc),source.ruleContent?.content].join('\n');
  const contextVariable = (source: Source) => String(source.ruleContent?.content || '').match(/book\.getVariable\(['"]([^'"]+)['"]\)/)?.[1];
  const boundaries = new Map<string, { expires: number; size:number; urls: Set<string>; vip: Set<string>; volumes: Map<string,string>; next: Map<string,string> }>();
  let boundaryEntries = 0;
  const identity = (url: string, method = 'GET', body?: string): PageIdentity => ({ url, key: JSON.stringify([url, method, body ?? null]) });
  const target = (source: Source, template: string, base: string, ordinal: number): PageTarget => {
    const spec = address(template, '', ordinal, base);
    const url = validateUrl(spec.url, new Set(options.approvedHosts?.(source) || [...hostsFor(source)]), base).href;
    return { ...identity(url, spec.method, spec.body), template, base, ordinal };
  };
  const budget = (): PagingBudget => ({ bytes: 0, fetches: 0 });
  async function request(source: Source, template: string, key = '', page = 1, deadlineAt = deadline(),
    paging?: PagingBudget, baseUrl = sourceId(source), redirectCheck?: (page: PageIdentity) => void, guest?: PublicGuestFlow) {
    assertSupported(source);
    const hosts = new Set(options.approvedHosts?.(source) || [...hostsFor(source)]);
    const addressValue = address(template, key, page, baseUrl);
    // Legado resolves source request URLs against the source's canonical URL.
    // Resolve first, then apply the same public-host/port/credential allowlist.
    let url = validateUrl(addressValue.url, hosts, baseUrl);
    let method = addressValue.method, requestBody = addressValue.body;
    // A reviewed guest flow starts with default public headers. Imported source
    // headers and client/browser cookie stores cannot supply an authenticated session.
    const headers = requestHeaders(guest ? {} : source);
    if (guest?.ajax) {
      headers.set('X-Requested-With', 'XMLHttpRequest');
      headers.set('Origin', 'https://www.ciweimao.com');
      headers.set('Referer', guest.chapterUrl);
      headers.set('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
    }
    if (addressValue.contentType && !headers.has('content-type')) headers.set('content-type', addressValue.contentType);
    const controller = new AbortController();
    const remaining = deadlineAt - Date.now();
    if (remaining <= 0) throw new EngineError('SOURCE_TIMEOUT', '來源回應逾時',504);
    const timer = setTimeout(() => controller.abort(), remaining);
    try {
      for (let redirect = 0; redirect <= 3; redirect++) {
        if (guest) {
          const cookie = guest.cookies.header(url.href);
          if (cookie) headers.set('Cookie', cookie); else headers.delete('Cookie');
        }
        if (!await (options.resolveHost || resolvePublicHost)(url.hostname,controller.signal)) {
          throw new EngineError('UNSAFE_DNS','來源DNS未指向核准公開位址');
        }
        if (paging && ++paging.fetches > MAX_PAGING_FETCHES) throw new EngineError('PAGINATION_REQUEST_LIMIT', '分頁請求超出服務上限，未回傳部分資料', 502);
        const response = await transport(url, { headers, method, body: requestBody, redirect: 'manual', signal: controller.signal });
        if (guest) guest.cookies.receive(response.headers, url.href);
        if (guest && response.status >= 300 && response.status < 400) {
          await response.body?.cancel();
          throw new EngineError('PUBLIC_GUEST_REDIRECT', '公開訪客接口發生未核准重新導向', 502);
        }
        if (response.status >= 300 && response.status < 400) {
          const location = response.headers.get('location');
          if (response.body) await response.body.cancel();
          if (!location || redirect === 3) throw new EngineError('REDIRECT_LIMIT', '來源重新導向過多');
          url = validateUrl(new URL(location, url).href, hosts);
          if (response.status === 303 || ((response.status === 301 || response.status === 302) && method === 'POST')) {
            method = 'GET'; requestBody = undefined; headers.delete('content-type');
          }
          redirectCheck?.(identity(url.href, method, requestBody));
          continue;
        }
        if (!response.ok) throw new EngineError('SOURCE_HTTP', `來源回應${response.status}`, 502);
        if (Number(response.headers.get('content-length')) > MAX_BODY) { await response.body?.cancel(); throw new EngineError('BODY_TOO_LARGE', '來源回應過大'); }
        if (paging && Number(response.headers.get('content-length')) > MAX_PAGING_BYTES - paging.bytes) {
          await response.body?.cancel(); throw new EngineError('PAGINATION_BYTES_LIMIT', '分頁總資料超出服務上限，未回傳部分資料', 502);
        }
        const reader = response.body?.getReader(), chunks: Uint8Array[] = [];
        let size = 0;
        if (reader) while (true) {
          const next = await reader.read();
          if (next.done) break;
          size += next.value.byteLength;
          if (size > MAX_BODY) { await reader.cancel(); throw new EngineError('BODY_TOO_LARGE', '來源回應過大'); }
          if (paging && (paging.bytes += next.value.byteLength) > MAX_PAGING_BYTES) {
            await reader.cancel(); throw new EngineError('PAGINATION_BYTES_LIMIT', '分頁總資料超出服務上限，未回傳部分資料', 502);
          }
          chunks.push(next.value);
        }
        const bytes = new Uint8Array(size);
        let offset = 0; for (const chunk of chunks) { bytes.set(chunk,offset); offset += chunk.length; }
        const charset = addressValue.charset || /charset\s*=\s*["']?([\w-]+)/i.exec(response.headers.get('content-type') || '')?.[1] || 'utf-8';
        let body: string;
        try { body = new TextDecoder(charset).decode(bytes); } catch { throw new EngineError('UNSUPPORTED_CHARSET', '來源字元編碼不受支援'); }
        return { body, url: url.href, contentType: response.headers.get('content-type') || '', pageIdentity: identity(url.href, method, requestBody) };
      }
      throw new EngineError('REDIRECT_LIMIT', '來源重新導向過多');
    } catch (error) {
      if (controller.signal.aborted) throw new EngineError('SOURCE_TIMEOUT', '來源回應逾時', 504);
      throw error;
    } finally { clearTimeout(timer); }
  }
  function environment(body: string, baseUrl: string, book: NativeObject = {}, xml = false): Evaluation {
    const document = new DocumentContext(body, xml);
    return { document, scope: document.root(), baseUrl, book, original: body };
  }
  async function collectPages(source: Source, first: string, base: string, maximum: number, deadlineAt: number, paging: PagingBudget,
    visit: (env: Evaluation, response: Awaited<ReturnType<typeof request>>, index: number) => string[], book: NativeObject,
    allowPage?: (page: PageIdentity, redirect: boolean) => boolean) {
    const queue = [target(source, first, base, 1)], scheduled = new Set([queue[0].key]), visited = new Set<string>();
    let enumerated = false, pages = 0;
    while (queue.length) {
      if (pages >= maximum) throw new EngineError('PAGINATION_PAGE_LIMIT', '分頁數超出服務上限，未回傳部分資料', 502);
      const current = queue.shift()!;
      const checkRedirect = (next: PageIdentity) => {
        if (allowPage && !allowPage(next, true)) throw new EngineError('CONTENT_BOUNDARY', '正文分頁重新導向另一章，未合併資料', 502);
        if (visited.has(next.key)) throw new EngineError('PAGINATION_CYCLE', '分頁重新導向已讀頁面，未回傳部分資料', 502);
      };
      const response = await request(source, current.template, '', current.ordinal, deadlineAt, paging, current.base, checkRedirect);
      if (visited.has(response.pageIdentity.key)) throw new EngineError('PAGINATION_CYCLE', '分頁回到已讀頁面，未回傳部分資料', 502);
      if (allowPage && !allowPage(response.pageIdentity, true)) throw new EngineError('CONTENT_BOUNDARY', '正文分頁不是當前章節，未合併資料', 502);
      visited.add(current.key); visited.add(response.pageIdentity.key);
      pages++;
      const rawNext = visit(environment(response.body, response.url, book), response, pages);
      if (rawNext.length > 1) enumerated = true;
      for (const raw of rawNext) {
        if (raw.startsWith('#')) continue;
        const next = target(source, raw, response.url, pages + 1);
        // Native pagination treats a link to the current document as terminal.
        if (next.key === current.key || next.key === response.pageIdentity.key) continue;
        if (allowPage && !allowPage(next, false)) continue;
        if (visited.has(next.key)) {
          if (enumerated) continue;
          throw new EngineError('PAGINATION_CYCLE', '線性分頁回到較早頁面，未回傳部分資料', 502);
        }
        if (scheduled.has(next.key)) continue;
        if (scheduled.size >= maximum) throw new EngineError('PAGINATION_PAGE_LIMIT', '完整分頁超出服務上限，未回傳部分資料', 502);
        scheduled.add(next.key); queue.push(next);
      }
      if (Date.now() > deadlineAt) throw new EngineError('SOURCE_TIMEOUT', '來源分頁回應逾時，未回傳部分資料', 504);
    }
  }
  function rememberBoundaries(source: Source, book: NativeObject, sequence: string[], chapters: NativeObject[], paidUrls: Set<string>) {
    const urls = [...new Set(sequence.map(url => { const parsed = new URL(url); parsed.hash = ''; return parsed.href; }))];
    const key = contextKey(source, book), previous = boundaries.get(key);
    const volumes=new Map<string,string>(chapters.filter(chapter=>chapter.isVolume).map(chapter=>[chapter.url,chapter.title]));
    const size=urls.length+volumes.size;
    if (previous) { boundaryEntries -= previous.size; boundaries.delete(key); }
    while (boundaries.size && (boundaries.size >= 16 || boundaryEntries + size > 20_000)) {
      const oldest = boundaries.keys().next().value!; boundaryEntries -= boundaries.get(oldest)!.size; boundaries.delete(oldest);
    }
    if (!size) return;
    boundaries.set(key, { expires: Date.now() + 5 * 60_000,size, urls: new Set(urls),
      vip: new Set([...paidUrls].map(url => { const parsed = new URL(url); parsed.hash = ''; return parsed.href; })),
      volumes,
      next: new Map(urls.map((url, index) => [url, urls[index + 1] || urls[0]])) });
    boundaryEntries += size;
  }
  function knownBoundaries(source: Source, book: NativeObject) {
    const key = contextKey(source, book), cached = boundaries.get(key);
    if (cached && cached.expires > Date.now()) return cached;
    if (cached) { boundaryEntries -= cached.size; boundaries.delete(key); }
    return undefined;
  }
  async function categories(source: Source): Promise<CategoryRoute[]> {
    source = sourceForExecution(source);
    assertSupported(source);
    const raw = String(source.exploreUrl || source.sortUrl || source.sourceUrl || '').trim();
    if (!raw) return [];
    let list: any[];
    if (raw.startsWith('[')) { try { list = staticJson(raw); } catch { throw new EngineError('INVALID_CATEGORIES','分類JSON無效'); } }
    else list = raw.split(/(?:&&|\n)+/).filter(Boolean).map(entry => { const at = entry.indexOf('::'); return at < 0 ? {title:sourceName(source),url:entry} : { title:entry.slice(0,at),url:entry.slice(at+2) }; });
    if (list.length > 1000) throw new EngineError('TOO_MANY_CATEGORIES','來源原始分類超出維護上限，未截斷保存');
    return list.map(item => ({ title:String(item.title || item.name || '').trim(), url:String(item.url || '') })).filter(item => item.title && item.url);
  }
  async function listBooks(source: Source, template: string, rules: NativeObject, key: string, page: number) {
    if (!template || !rules?.bookList) return [];
    const response = await request(source, template, key, page), env = environment(response.body,response.url);
    const rows = items(evaluate(rules.bookList,env,true));
    return rows.map(scope => {
      const row = { ...env, scope };
      return { bookUrl:absolute(value(rules.bookUrl,row),response.url), origin:sourceId(source), originName:sourceName(source),
        type:8, name:value(rules.name,row).trim(), author:value(rules.author,row).trim(),
        coverUrl:absolute(value(rules.coverUrl,row),response.url), intro:value(rules.intro,row),
        kind:value(rules.kind,row), wordCount:value(rules.wordCount,row), latestChapterTitle:value(rules.lastChapter,row),
        tocUrl:absolute(value(rules.tocUrl,row),response.url), time:Date.now(), originOrder:Number(source.customOrder || 0) };
    }).filter(book => book.name && book.bookUrl).filter((book,index,all) => all.findIndex(other => other.bookUrl === book.bookUrl) === index);
  }
  async function search(source: Source, key: string, page = 1) {
    source = sourceForExecution(source);
    return listBooks(source,source.searchUrl,source.ruleSearch,key,page);
  }
  async function explore(source: Source, url: string, page = 1) {
    const adapted = sourceForExecution(source);
    // Native BookList chooses the entire search rule when explore.bookList is
    // blank. An existing but empty explore object is not an executable rule.
    const rules = String(adapted.ruleExplore?.bookList || '').trim() ? adapted.ruleExplore : adapted.ruleSearch;
    return listBooks(adapted,url,rules,'',page);
  }
  async function info(source: Source, original: NativeObject, deadlineAt = deadline(), paging?: PagingBudget) {
    source = sourceForExecution(source);
    const book = { ...original }, response = await request(source,book.bookUrl,'',1,deadlineAt,paging), env = environment(response.body,response.url,book), rules = source.ruleBookInfo || {};
    for (const [field,rule] of Object.entries({ name:rules.name, author:rules.author, intro:rules.intro, kind:rules.kind, wordCount:rules.wordCount, latestChapterTitle:rules.lastChapter })) {
      const result = value(rule as string,env); if (result) book[field] = result.trim();
    }
    const cover = absolute(value(rules.coverUrl,env),response.url); if (cover) book.coverUrl = cover;
    book.tocUrl = absolute(value(rules.tocUrl,env),response.url) || book.tocUrl || book.bookUrl;
    book.origin = sourceId(source); book.originName = sourceName(source);
    book.type = Number(book.type || 0) | 8;
    return book;
  }
  async function toc(source: Source, original: NativeObject, deadlineAt = deadline(), paging = budget()) {
    source = sourceForExecution(source);
    // A client-supplied variable is never proof that a paid chapter is public.
    const book = { ...original };
    if (contextVariable(source)) book.variable = undefined;
    const rules = source.ruleToc || {}, seen = new Set<string>(), sequenceSeen = new Set<string>(), paidUrls = new Set<string>(), chapters: NativeObject[] = [], sequence: string[] = [];
    const reverse = String(rules.chapterList || '').startsWith('-'), listRule = String(rules.chapterList || '').replace(/^[+-]/, '');
    const guard = contextVariable(source), proofs: NativeObject = Object.create(null); let proofBookId: string | undefined, rowCount = 0;
    await collectPages(source, book.tocUrl || book.bookUrl, sourceId(source), MAX_TOC_PAGES, deadlineAt, paging, (env, response) => {
      const rows = items(evaluate(listRule, env, true));
      rowCount += rows.length;
      if (rowCount > MAX_ITEMS) throw new EngineError('TOO_MANY_ITEMS', '完整目錄超出服務上限，未截斷保存');
      for (const [rowIndex, scope] of rows.entries()) {
        const row = { ...env, scope };
        const title = value(rules.chapterName, row).trim(), isVolume = flag(rules.isVolume, row), rawUrl = value(rules.chapterUrl, row).trim();
        const url = rawUrl ? absolute(rawUrl, response.url) || (isVolume ? rawUrl : '') : isVolume ? title + rowIndex : response.url;
        const chapter = { url, title,
          bookUrl: book.bookUrl, baseUrl: response.url, index: 0, isVolume,
          isVip: flag(rules.isVip, row), isPay: false, tag: value(rules.updateTime, row), wordCount: value(rules.wordCount, row), variable: null };
        if (!chapter.title || !chapter.url) continue;
        if (!chapter.isVolume && !sequenceSeen.has(chapter.url)) { sequenceSeen.add(chapter.url); sequence.push(chapter.url); }
        if (!chapter.isVolume && chapter.isVip) paidUrls.add(chapter.url);
        if (!seen.has(chapter.url)) { seen.add(chapter.url); chapters.push(chapter); }
      }
      if (guard && book.variable) {
        let state: NativeObject, proof: NativeObject;
        try { state = JSON.parse(book.variable); proof = JSON.parse(state[guard]); } catch { throw new EngineError('INVALID_FREE_CONTEXT', '公開章節核對資料無效'); }
        if (!proof.bookId || !proof.chapters || (proofBookId && proofBookId !== proof.bookId)) throw new EngineError('INVALID_FREE_CONTEXT', '分頁公開章節不屬同一本書');
        proofBookId = proof.bookId;
        Object.assign(proofs, proof.chapters);
        if (Object.keys(proofs).length > MAX_ITEMS) throw new EngineError('TOO_MANY_ITEMS', '公開章節核對資料超出上限');
        state[guard] = JSON.stringify({ ...proof, chapters: proofs }); book.variable = JSON.stringify(state);
      }
      return pageLinks(rules.nextTocUrl, env);
    }, book);
    if (reverse) { chapters.reverse(); sequence.reverse(); }
    if (!chapters.length) throw new EngineError('EMPTY_TOC', '完整目錄未取得可讀章節', 502);
    chapters.forEach((chapter,index) => { chapter.index = index; });
    rememberBoundaries(source, book, sequence, chapters, paidUrls);
    if (contextVariable(source) && book.variable) {
      if (trustedToc.size >= 64) trustedToc.delete(trustedToc.keys().next().value!);
      trustedToc.set(contextKey(source,book),{variable:book.variable,expires:Date.now()+5*60*1000});
    }
    return { book,chapters };
  }
  async function ciweimaoContent(source: Source, book: NativeObject, chapter: NativeObject, deadlineAt: number, paging: PagingBudget) {
    const origin = 'https://www.ciweimao.com';
    const chapterUrl = absolute(chapter.url, chapter.baseUrl || book.tocUrl || book.bookUrl);
    if (!chapterUrl) throw new EngineError('PUBLIC_CHAPTER_URL', '公開章節網址無效');
    const url = new URL(chapterUrl), bookMatch = /^https:\/\/www\.ciweimao\.com\/book\/([1-9]\d*)\/?$/.exec(String(book.bookUrl || ''));
    if (url.origin !== origin || url.search || url.hash || !/^\/chapter\/[1-9]\d*\/?$/.test(url.pathname) || !bookMatch) {
      throw new EngineError('PUBLIC_CHAPTER_URL', '公開訪客閱讀只支援同站正式書籍及章節網址');
    }
    const guest: PublicGuestFlow = { cookies: new PublicGuestCookies(origin), chapterUrl };
    const read = (template: string, ajax = false) => request(source, template, '', 1, deadlineAt, paging, origin, undefined, { ...guest, ajax });
    const json = (body: string): any => {
      try { return JSON.parse(body); } catch { throw new EngineError('PUBLIC_CHAPTER_DENIED', '公開訪客接口未回傳有效JSON', 502); }
    };
    try {
      const page = await read(chapterUrl), context = parseCiweimaoFreeChapter(page.body, page.url);
      if (context.bookId !== bookMatch[1]) throw new EngineError('PUBLIC_CHAPTER_IDENTITY', '免費章節不屬於當前書籍', 502);
      if (!context.declaredWordCount) throw new EngineError('PUBLIC_CHAPTER_INCOMPLETE', '未取得官方章節字數，不能確認正文完整', 502);
      const session = json((await read(origin + '/chapter/ajax_get_session_code,' + JSON.stringify({ method: 'POST', body: 'chapter_id=' + context.chapterId }), true)).body);
      if (session?.code !== 100000 || typeof session.chapter_access_key !== 'string' || !/^[\x21-\x7e]{1,256}$/.test(session.chapter_access_key)) {
        throw new EngineError('PUBLIC_CHAPTER_DENIED', '公開訪客接口未授予免費章節閱讀', 502);
      }
      const detail = json((await read(origin + '/chapter/get_book_chapter_detail_info,' + JSON.stringify({ method: 'POST',
        body: 'chapter_id=' + context.chapterId + '&chapter_access_key=' + encodeURIComponent(session.chapter_access_key) }), true)).body);
      const html = await decodeCiweimaoFreeChapter(context, detail, session.chapter_access_key);
      const visible = extractCiweimaoFreeText(context, html);
      if (!canReadContent(visible.text)) throw new EngineError('CONTENT_GATE', '公開接口只回傳登入、付費或錯誤提示', 502);
      if (/[\uE000-\uF8FF]/.test(visible.text)) throw new EngineError('UNREADABLE_CONTENT', '公開接口正文含無法確認的私用字元', 502);
      if (Date.now() > deadlineAt) throw new EngineError('SOURCE_TIMEOUT', '公開訪客閱讀逾時', 504);
      return { text: visible.text, book };
    } catch (error) {
      if (error instanceof CiweimaoPublicError) throw new EngineError(error.code, error.message, 502);
      throw error;
    }
  }
  async function content(source: Source, original: NativeObject, chapter: NativeObject, nextChapterUrl?: string) {
    source = sourceForExecution(source);
    assertSupported(source);
    const deadlineAt = deadline(), paging = budget();
    let book = { ...original };
    if (chapter.isVolume === true) {
      const title = String(chapter.title || '').trim();
      if (!title) throw new EngineError('EMPTY_VOLUME_TITLE', '卷標題為空');
      let known=knownBoundaries(source,book);
      if(!known){
        if(!String(source.ruleToc?.chapterList||'').trim()) throw new EngineError('UNVERIFIED_VOLUME','卷標題尚未經完整目錄核對');
        if(!book.tocUrl)book=await info(source,book,deadlineAt,paging);
        book=(await toc(source,book,deadlineAt,paging)).book;
        known=knownBoundaries(source,book);
      }
      if(known?.volumes.get(chapter.url)!==title) throw new EngineError('UNVERIFIED_VOLUME','卷標題與完整目錄不符');
      return { text: title, book };
    }
    if (chapter.isVip) throw new EngineError('LOCKED_CHAPTER','付費或未授權章節不取正文');
    if (new URL(sourceId(source)).hostname === 'www.ciweimao.com' && (!source.bookSourceType || source.bookSourceType === 0)) {
      return ciweimaoContent(source, book, chapter, deadlineAt, paging);
    }
    const guard = contextVariable(source);
    if (guard) {
      if (!book.tocUrl) book = await info(source,book,deadlineAt,paging);
      const cached = trustedToc.get(contextKey(source,book));
      book.variable = cached && cached.expires > Date.now() ? cached.variable : (await toc(source,book,deadlineAt,paging)).book.variable;
      let verified: NativeObject = {};
      try { verified = JSON.parse(JSON.parse(book.variable || '{}')[guard] || '{}'); } catch { /* Missing verification denies before requesting a body. */ }
      const target = absolute(chapter.url,chapter.baseUrl || book.tocUrl || book.bookUrl);
      const match = guard === 'readerZhulangFreeChapters' ? /^https:\/\/book\.zhulang\.com\/(\d+)\/(\d+)\.html$/.exec(target)
        : guard === 'readerKujiangFreeChapters' ? /^https:\/\/m\.kujiang\.com\/book\/(\d+)\/(\d+)$/.exec(target)
        : guard === 'readerQimaoFreeChapters' ? /^https:\/\/www\.qimao\.com\/shuku\/(\d+)-(\d+)\/$/.exec(target) : null;
      if (!match || verified.bookId !== match[1] || verified.chapters?.[match[2]] !== true) {
        throw new EngineError('LOCKED_CHAPTER','此章未經公開免費目錄確認，不取正文');
      }
    }
    const first = absolute(chapter.url, chapter.baseUrl || book.tocUrl || book.bookUrl), rules = source.ruleContent || {};
    if (!first) throw new EngineError('INVALID_CONTENT_URL', '當前章節URL無效');
    const firstPage = target(source, first, sourceId(source), 1);
    let known = knownBoundaries(source, book);
    if (rules.nextContentUrl && !known?.next.has(firstPage.url) && String(source.ruleToc?.chapterList || '').trim()) {
      if (!book.tocUrl) book = await info(source, book, deadlineAt, paging);
      book = (await toc(source, book, deadlineAt, paging)).book; known = knownBoundaries(source, book);
    }
    if (known?.vip.has(firstPage.url)) throw new EngineError('LOCKED_CHAPTER', '公開目錄標示為付費章節，不取正文');
    const stops = new Set<string>();
    if (nextChapterUrl && !known?.volumes.has(nextChapterUrl)) stops.add(target(source, nextChapterUrl, chapter.baseUrl || book.tocUrl || book.bookUrl, 1).key);
    const knownNext = known?.next.get(firstPage.url); if (knownNext) stops.add(identity(knownNext).key);
    const allowPage = (page: PageIdentity, redirect: boolean) => {
      if (page.key === firstPage.key) return true;
      if (stops.has(page.key)) return false;
      if (known?.urls.has(page.url) && page.url !== firstPage.url) throw new EngineError('CONTENT_BOUNDARY', '正文分頁指向另一個目錄章節，未合併資料', 502);
      if (!redirect && !stops.size && !known?.next.has(firstPage.url)) throw new EngineError('MISSING_CHAPTER_BOUNDARY', '尚未核對下一章邊界，不合併未知正文分頁', 502);
      return true;
    };
    const parts: string[] = [];
    await collectPages(source, first, sourceId(source), MAX_CONTENT_PAGES, deadlineAt, paging, env => {
      const raw = value(rules.content, env), text = raw.includes('<') && /<\/?[a-z]/i.test(raw) ? plaintext(raw) : raw.trim();
      if (!text) throw new EngineError('EMPTY_CONTENT', '未取得公開可讀正文', 502);
      if ((text.match(/[\uE000-\uF8FF]/g)?.length || 0) > Math.max(8, text.length * 0.05)) throw new EngineError('UNREADABLE_CONTENT', '正文含無法確認的私用字元，需維護來源', 502);
      if (!canReadContent(text)) throw new EngineError('CONTENT_GATE', '來源只回傳登入、付費、驗證或錯誤提示，未取得正文', 502);
      parts.push(text); return pageLinks(rules.nextContentUrl, env);
    }, book, allowPage);
    return { text: parts.join('\n'), book };
  }
  async function rssArticles(source: Source, url: string, page = 1) {
    const response = await request(source,url || source.sourceUrl,'',page), env = environment(response.body,response.url,{},true);
    let articles: NativeObject[];
    if (source.ruleArticles) {
      articles = items(evaluate(source.ruleArticles,env,true)).map(scope => {
        const row = { ...env,scope };
        return { title:value(source.ruleTitle,row), link:absolute(value(source.ruleLink,row),response.url),
          description:value(source.ruleDescription,row), image:absolute(value(source.ruleImage,row),response.url), pubDate:value(source.rulePubDate,row) };
      });
    } else {
      articles = env.document.select('item,entry').toArray().map((scope: JsoupElements) => {
        const $ = env.document.$, node = scope.selection;
        return { title:node.find('title').first().text(), link:absolute(node.find('link').first().attr('href') || node.find('link').first().text() || node.find('guid').first().text(),response.url),
          description:node.find('content\\:encoded').first().text() || node.find('content').first().text() || node.find('description').first().text() || node.find('summary').first().text(),
          image:node.find('enclosure[type^="image/"]').first().attr('url') || '', pubDate:node.find('pubDate,published,updated').first().text() };
      });
    }
    const seen = new Set<string>();
    articles = articles.filter(article => article.title && article.link && !seen.has(article.link) && !!seen.add(article.link)).map((article,index) => ({ ...article, origin:sourceId(source), sort:'', group:source.sourceGroup || '默认分组', content:null, read:false, order:Date.now()-index, variable:null }));
    return { articles, nextPageUrl:absolute(value(source.ruleNextPage,env),response.url) || undefined };
  }
  async function rssContent(source: Source, article: NativeObject) {
    if (!source.ruleContent && article.description) return String(article.description);
    if (!source.ruleContent) throw new EngineError('NO_ARTICLE_CONTENT','此訂閱只提供摘要及原文連結');
    const response = await request(source,article.link), env = environment(response.body,response.url,article);
    const html = value(source.ruleContent,env);
    if (!html.trim()) throw new EngineError('EMPTY_CONTENT','未取得訂閱正文',502);
    return html;
  }
  async function verifyWebEntry(source: Source) {
    if (!sourceCapabilities(source).web) throw new EngineError('NOT_WEBSITE_ENTRY', '來源不是受支援的靜態網站入口');
    const response = await request(source, source.sourceUrl);
    if (!/^(?:text\/html|application\/xhtml\+xml)(?:\s*;|\s*$)/i.test(response.contentType) || response.body.trim().length < 10) {
      throw new EngineError('WEBSITE_NOT_HTML', '網站入口沒有回傳可開啟的HTML網頁',502);
    }
    // Inspect a bounded static document. Scripts, login navigation and embedded
    // CAPTCHA widgets are not evidence that the entire website is blocked.
    const $ = cheerio.load(response.body.slice(0, 96 * 1024));
    const normalize = (value: string) => value.normalize('NFKC').replace(/[\u200B-\u200D\uFEFF]/g, '')
      .replace(/\s+/g, ' ').trim().toLowerCase();
    const title = normalize($('title').first().text()).slice(0, 300);
    const noScript = normalize($('noscript').text()).slice(0, 600);
    $('script,style,template,noscript,svg,nav,header,footer,aside,[hidden],[aria-hidden="true"],[style*="display:none"],[style*="display: none"]').remove();
    const main = $('main,[role="main"]').first();
    const primary = main.length ? main : $('body');
    const visible = normalize(primary.text()).slice(0, 8000);
    const headings = primary.find('h1,h2,[role="heading"][aria-level="1"]').slice(0, 6)
      .toArray().map(node => normalize($(node).text()).slice(0, 300));
    const labels = [title, ...headings].flatMap(value => [value, ...value.split(/\s+[|—–-]\s+/).slice(0, 4)]);
    // These are complete interstitial/error headings, not loose keywords found
    // in a news article, a navigation link, or a script's string literals.
    const challenge = /^(?:just a moment[.!…\s]*|attention required!?|checking your browser(?: before accessing(?: .+)?)?[.!…\s]*|(?:please )?verify (?:that )?you are (?:a )?human[.!…\s]*|security (?:verification|check)[.!…\s]*|ddos[- ]guard|(?:请|請)(?:完成|进行|進行)?(?:安全|人机|人機|访问|訪問)[验驗][证證][!！。.…\s]*|(?:安全|人机|人機|访问|訪問)[验驗][证證][!！。.…\s]*|安全(?:检查|檢查|检测|檢測)[!！。.…\s]*)$/i;
    const denied = /^(?:(?:error[ :]*|http[ :]*|错误[ :]*|錯誤[ :]*|錯誤碼[ :]*|错误码[ :]*)?(?:401|403|429|451)(?:[ :.-]*(?:forbidden|unauthorized|access denied|too many requests|unavailable for legal reasons))?|access denied|access forbidden|request (?:blocked|rejected)|you (?:have been|are) blocked|(?:訪問|访问|存取|請求|请求)(?:被拒绝|被拒絕|遭拒|已被阻止|被禁止)|禁止(?:访问|訪問)|拒绝访问|拒絕存取)[!！。.…\s]*$/i;
    const missing = /^(?:(?:error[ :]*|http[ :]*|错误[ :]*|錯誤[ :]*)?404(?:[ :.-]*(?:page )?not found)?|(?:page|website|site) not found|not found|(?:the )?(?:page|website|site) (?:does not|doesn't) exist|(?:抱歉[,，!！ ]*)?(?:(?:您|你)(?:所)?(?:访问|訪問)的)?(?:页面|頁面|网页|網頁|网站|網站)(?:不(?:存在|見了)|已(?:删除|刪除|失效))|找不到(?:页面|頁面|网页|網頁))[!！。.…\s]*$/i;
    const closed = /^(?:(?:(?:this|the) )?(?:website|site|account|domain) (?:is |has been )?(?:permanently )?(?:closed|suspended|shut down|expired|under maintenance)|under maintenance|service (?:unavailable|suspended)|(?:本站|本网站|本網站|此网站|此網站|网站|網站|站点|站點)(?:已|已经|已經)?(?:关闭|關閉|停止(?:服务|服務|运营|運營))|(?:网站|網站)(?:维护|維護)中|(?:域名|網域)(?:已|已经|已經)?(?:过期|過期))[!！。.…\s]*$/i;
    const parked = /^(?:(?:(?:this|the) )?(?:domain|domain name|website|site)(?: is)? (?:for sale|parked|available for (?:sale|purchase))|buy this domain|domain parking|(?:此|本|该|該)?(?:域名|網域)(?:正在|可供|现正|現正)?(?:出售|待售|停放))[!！。.…\s]*$/i;
    const reason = labels.some(value => challenge.test(value) || denied.test(value)) ? 'WEBSITE_BLOCKED'
      : labels.some(value => missing.test(value)) ? 'WEBSITE_NOT_FOUND'
      : labels.some(value => closed.test(value)) ? 'WEBSITE_CLOSED'
      : labels.some(value => parked.test(value)) ? 'WEBSITE_PARKED' : null;
    if (reason) throw new EngineError(reason, '網站入口回傳驗證、錯誤或停用頁面', 502);
    const firstParagraphs = primary.find('p').slice(0, 4).toArray().map(node => normalize($(node).text()).slice(0, 500));
    const explicitChallenge = $('#cf-error-details,#cf-challenge-running,#challenge-running').length > 0 ||
      ($('form#challenge-form').length > 0 && /(?:verify.{0,20}human|checking your browser|enable javascript and cookies|安全[验驗][证證]|人[机機][验驗][证證])/.test(visible));
    if (explicitChallenge || (!visible && /(?:enable|需要|启用|啟用).{0,30}javascript/.test(noScript))) {
      throw new EngineError('WEBSITE_BLOCKED', '網站入口需要完成存取驗證', 502);
    }
    if (visible.length < 2000 && !primary.find('article').length) {
      const messages = [visible, ...firstParagraphs];
      const messageReason = messages.some(value => missing.test(value)) ? 'WEBSITE_NOT_FOUND'
        : messages.some(value => closed.test(value)) ? 'WEBSITE_CLOSED'
        : messages.some(value => parked.test(value)) ? 'WEBSITE_PARKED'
        : messages.some(value => challenge.test(value) || denied.test(value)) ? 'WEBSITE_BLOCKED' : null;
      if (messageReason) throw new EngineError(messageReason, '網站入口回傳驗證、錯誤或停用頁面', 502);
    }
    return { url: response.url, contentType: response.contentType };
  }
  return { categories,search,explore,info,toc,content,rssArticles,rssContent,verifyWebEntry };
}
const engine = createEngine();
export const { categories,search,explore,info,toc,content,rssArticles,rssContent,verifyWebEntry } = engine;
