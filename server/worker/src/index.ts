import { dailyReadAvailability } from './storage-availability';
import * as engineImplementation from './engine';
import { authorizeRequest } from './authorization';
export { authorizeRequest } from './authorization';
import { classify, taxonomy } from './taxonomy';
import { SourceStore, type SourceKind, type SourceRow, type ImportSource, type CatalogueRoute, type VerificationProfile,
  type AssessmentPlan, type AssessmentReceipt } from './storage';
import { canReadContent, canMigrateContent, CONTENT_QUALITY_VERSION } from './content-quality';
import { MaintenanceBudgetError, maintenanceLimit, maintenanceStatus, runMaintenance, type WriteGuard } from './maintenance';
import publicVerificationCases from '../../catalogue/verification-cases.json';
import { BOOK_METADATA_VERSION, BookMetadataError, requireBookMetadata, usableBookResult } from './metadata-quality';
import { sourceAdapterRevision, canonicalSourceIdForExecution, sourceVerificationHold } from './source-adapters';

export interface Env { DB: D1Database; ADMIN_TOKEN: string; READ_TOKEN: string; MIGRATION_TOKEN: string;
  MAINTENANCE_ENABLED?: string; MAINTENANCE_DAILY_WRITES?: string }
type JsonObject = Record<string, any>;
export const TOC_INSTALL_CONTRACT = 'reader-toc-identity-1';
export interface EngineApi {
  assessSource(kind: SourceKind, source: JsonObject): any;
  categories(source: JsonObject): Promise<Array<{ title: string; url: string }>>;
  search(source: JsonObject, key: string, page?: number): Promise<JsonObject[]>;
  explore(source: JsonObject, url: string, page?: number): Promise<JsonObject[]>;
  info(source: JsonObject, book: JsonObject): Promise<JsonObject>;
  toc(source: JsonObject, book: JsonObject): Promise<{ book: JsonObject; chapters: JsonObject[] }>;
  content(source: JsonObject, book: JsonObject, chapter: JsonObject, nextChapterUrl?: string): Promise<{ text: string; book?: JsonObject }>;
  rssArticles(source: JsonObject, url: string, page?: number): Promise<{ articles: JsonObject[]; nextPageUrl?: string }>;
  rssContent(source: JsonObject, article: JsonObject): Promise<string>;
  verifyWebEntry(source: JsonObject): Promise<{ url: string; contentType: string }>;
}
class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public diagnosticCode?: string) { super(message); }
}
const encoder = new TextEncoder();
const MAX_BODY = 1_048_576;
const MAX_SOURCE = 768 * 1024;
const MAX_ASSESSMENT_BYTES = 2 * 1024 * 1024;
const MAX_ASSESSMENT_NODES = 40_000;
const MAX_ASSESSMENT_DEPTH = 64;
function assessmentWork(value: unknown, remainingNodes: number): number {
  const pending: Array<{value:unknown;depth:number}> = [{value,depth:0}];
  let nodes = 0;
  while (pending.length) {
    const item = pending.pop()!;
    if (++nodes > remainingNodes || item.depth > MAX_ASSESSMENT_DEPTH) throw new ApiError(413,'ASSESSMENT_LIMIT','Source assessment work limit exceeded');
    if (item.value && typeof item.value === 'object') {
      const record=item.value as Record<string,unknown>;
      for (const key in record) {
        if(!Object.hasOwn(record,key))continue;
        if (++nodes > remainingNodes || pending.length >= remainingNodes) throw new ApiError(413,'ASSESSMENT_LIMIT','Source assessment work limit exceeded');
        pending.push({value:record[key],depth:item.depth+1});
      }
    }
  }
  return nodes;
}
function assessmentCapabilities(assessed: any): string[] {
  const values = Array.isArray(assessed.capabilities) ? assessed.capabilities
    : Object.entries(assessed.capabilities??assessed).filter(([key,value])=>key!=='supported'&&value===true).map(([key])=>key);
  return [...new Set(values.filter((value:unknown):value is string=>typeof value==='string'&&value.length<=64))].sort() as string[];
}
const response = (data: unknown, status = 200) => new Response(JSON.stringify({ data }), {
  status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' },
});
const errorResponse = (error: ApiError) => new Response(JSON.stringify({ error: { code: error.code, message: error.message, diagnosticCode: error.diagnosticCode } }), {
  status: error.status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
});
function object(value: unknown, name: string): JsonObject {
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new ApiError(400, 'INVALID_INPUT', `${name} must be an object`);
  return value as JsonObject;
}
function string(value: unknown, name: string, max = 4096, required = true): string {
  if (value === undefined && !required) return '';
  if (typeof value !== 'string' || value.length > max || (required && !value.trim())) throw new ApiError(400, 'INVALID_INPUT', `Invalid ${name}`);
  return value;
}
function archiveId(value: unknown): string {
  if (typeof value !== 'string' || value.length > 16384) throw new ApiError(400,'INVALID_INPUT','A string source ID is required');
  return value;
}
function kind(value: unknown): SourceKind {
  if (value === 'book' || value === 'books' || value === undefined || value === null || value === '') return 'book';
  if (value === 'rss') return 'rss';
  throw new ApiError(400, 'INVALID_KIND', 'Unknown source kind');
}
function page(value: unknown): number {
  if (value === undefined) return 1;
  if (!Number.isSafeInteger(value) || (value as number) < 1 || (value as number) > 10000) throw new ApiError(400, 'INVALID_PAGE', 'Invalid page');
  return value as number;
}
export async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(text));
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}
async function readBody(request: Request): Promise<JsonObject> {
  const length = Number(request.headers.get('Content-Length'));
  if (Number.isFinite(length) && length > MAX_BODY) throw new ApiError(413, 'TOO_LARGE', 'Request is too large');
  if (!request.body) throw new ApiError(400, 'INVALID_JSON', 'JSON body required');
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BODY) { await reader.cancel(); throw new ApiError(413, 'TOO_LARGE', 'Request is too large'); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return object(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)), 'body'); }
  catch (error) { if (error instanceof ApiError) throw error; throw new ApiError(400, 'INVALID_JSON', 'Invalid JSON body'); }
}
function summary(source: SourceRow) {
  const capabilities = JSON.parse(source.capabilities);
  return { id: source.id, name: source.name, sha256: source.sha256, supported: !!source.supported,
    enabled: !!source.enabled, enabledExplore: !!source.enabled_explore, status: source.status,
    capabilities, webUrl: capabilities.includes('web') && source.status === 'healthy' ? source.web_url ?? null : null,
    retryAt: source.retry_at, verifiedAt: source.verified_at };
}
function receipt(source: SourceRow) {
  const capabilities = JSON.parse(source.capabilities);
  return { id: source.id, sha256: source.sha256, stored: true, supported: !!source.supported, status: source.status,
    capabilities, webUrl: capabilities.includes('web') && source.status === 'healthy' ? source.web_url ?? null : null };
}
function toCursor(id: string): string {
  return btoa(String.fromCharCode(...encoder.encode(id))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function fromCursor(cursor: string | null): string {
  if (!cursor) return '';
  if (!/^[A-Za-z0-9_-]{1,24000}$/.test(cursor)) throw new ApiError(400, 'INVALID_CURSOR', 'Invalid cursor');
  try {
    const bytes = Uint8Array.from(atob(cursor.replace(/-/g, '+').replace(/_/g, '/')), char => char.charCodeAt(0));
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch { throw new ApiError(400, 'INVALID_CURSOR', 'Invalid cursor'); }
}
function usefulText(text: unknown, minimum: number): text is string {
  if (typeof text !== 'string') return false;
  const clean = text.replace(/<[^>]*>|\s/g, '');
  return Array.from(clean).length >= minimum && !/^(获取正文失败|獲取正文失敗|请登录|請登入|access denied|forbidden|error\b)/i.test(clean);
}
function diagnosticCode(error: unknown): string {
  const candidate = error as { code?: unknown; name?: unknown } | null;
  if (typeof candidate?.code === 'string' && /^[A-Z][A-Z0-9_]{1,63}$/.test(candidate.code)) return candidate.code;
  const names: Record<string, string> = { Error: 'RUNTIME_ERROR', TypeError: 'RUNTIME_TYPE_ERROR', RangeError: 'RUNTIME_RANGE_ERROR',
    SyntaxError: 'RUNTIME_SYNTAX_ERROR', AbortError: 'RUNTIME_ABORT', TimeoutError: 'RUNTIME_TIMEOUT' };
  return names[String(candidate?.name)] ?? 'RUNTIME_ERROR';
}
export function createApp(engine: EngineApi = engineImplementation as unknown as EngineApi) {
  let providerSelection: { revision: number; ids: Set<string> } | undefined;
  async function preferredBookSources(store: SourceStore, revision: number): Promise<Set<string>> {
    if(providerSelection?.revision===revision)return providerSelection.ids;
    const ids=await store.activeBookIds(), healthy=new Set(ids), chosen=new Map<string,string>();
    for(const id of ids){
      const provider=canonicalSourceIdForExecution({bookSourceUrl:id})??id;
      if(!chosen.has(provider))chosen.set(provider,healthy.has(provider)?provider:id);
    }
    const selected=new Set(chosen.values());
    providerSelection={revision,ids:selected};
    return selected;
  }
  async function repairMetadata(source: SourceRow, rule: JsonObject, book: JsonObject): Promise<JsonObject> {
    if (book.origin && book.origin !== source.id) throw new BookMetadataError();
    if (usableBookResult('info', { book })) return book;
    const fresh = await engine.info(rule, book);
    requireBookMetadata(fresh);
    if (fresh.bookUrl !== book.bookUrl || (fresh.origin && fresh.origin !== source.id)) throw new BookMetadataError();
    // Repair display metadata only. Never adopt another book identity or caller progress.
    return { ...book, name: fresh.name, author: fresh.author ?? '' };
  }
  async function getSource(store: SourceStore, sourceKind: SourceKind, id: string, force = false): Promise<SourceRow> {
    const source = await store.get(sourceKind, id);
    if (!source) throw new ApiError(404, 'SOURCE_NOT_FOUND', 'Source not found');
    if (!source.supported || source.status === 'unsupported') throw new ApiError(422, 'SOURCE_UNSUPPORTED', 'Source is not supported by the cloud engine');
    if (!source.enabled) throw new ApiError(409, 'SOURCE_DISABLED', 'Source is disabled');
    if (!force && source.retry_at > Date.now()) throw new ApiError(503, 'SOURCE_BACKOFF', 'Source is temporarily unavailable');
    return source;
  }
  async function sourceCall(store: SourceStore, source: SourceRow, operation: string, args: unknown, action: () => Promise<unknown>): Promise<unknown> {
    const key = await sha256(JSON.stringify([source.kind, source.id, source.sha256, operation, args,
      operation === 'content' ? CONTENT_QUALITY_VERSION : 0,
      source.kind === 'book' ? [BOOK_METADATA_VERSION, sourceAdapterRevision, engineImplementation.ENGINE_RULE_CONTRACT_VERSION,engineImplementation.ENGINE_IMPLEMENTATION_VERSION]
        : [engineImplementation.ENGINE_RULE_CONTRACT_VERSION,engineImplementation.ENGINE_IMPLEMENTATION_VERSION]]));
    const cached = await store.cached(key, source).catch(() => undefined);
    if (cached !== undefined && (source.kind !== 'book' || usableBookResult(operation, cached)) &&
        (operation !== 'content' || canReadContent((cached as JsonObject)?.content))) return cached;
    try {
      const result = await action();
      if (source.kind === 'book' && !usableBookResult(operation, result)) throw new BookMetadataError();
      await store.success(source).catch(() => {});
      // Cache storage is an optimization; capacity/unavailability must not discard a valid upstream result.
      await store.cache(key, source, result, operation === 'content' ? 15 * 60_000 : 5 * 60_000).catch(() => {});
      return result;
    } catch (error) {
      if (error instanceof ApiError && error.status < 500) throw error;
      await store.failure(source).catch(() => {});
      throw new ApiError(502, 'UPSTREAM_FAILED', 'The source did not return usable content', diagnosticCode(error));
    }
  }
  async function verify(store: SourceStore, body: JsonObject, managementDiagnostics = false, writeGuard?:WriteGuard) {
    const sourceKind = kind(body.kind);
    const source = await getSource(store, sourceKind, string(body.sourceId, 'sourceId'), true);
    const sourceJson = JSON.parse(source.json);
    const readiness = sourceKind === 'book' ? sourceVerificationHold(sourceJson) : undefined;
    if (readiness) return { ...receipt(source), stages: {}, verified: false, verificationKind: 'book', reasonCode: readiness };
    const stages: Record<string, boolean> = {};
    let routes: CatalogueRoute[] = [];
    let successfulProfile:VerificationProfile|undefined;
    const verificationKind = sourceKind === 'book' ? 'book' : JSON.parse(source.capabilities).includes('web') ? 'web' : 'feed';
    try {
      if (sourceKind === 'book') {
        const rawRoutes = source.enabled_explore ? await engine.categories(sourceJson) : [];
        routes = rawRoutes.map(route => {
          const category = classify(route.title, sourceJson.bookSourceGroup ?? '', source.id);
          return { label: route.title, url: route.url, categoryId: category.id, categoryLabel: category.label, filterIds: category.filterIds };
        });
        stages.catalogue = true;
        const previous=await store.verificationProfile(source);
        const canonicalId=canonicalSourceIdForExecution(sourceJson);
        const canonicalSource=canonicalId&&canonicalId!==source.id?await store.get('book',canonicalId):null;
        const familyProfile=canonicalSource?await store.verificationProfile(canonicalSource):null;
        const profile=previous??familyProfile;
        const defaults=(publicVerificationCases.sources as Record<string,{key:string;bookUrl?:string;chapterUrl?:string}>)[canonicalId??source.id];
        const keyword=string(body.key??profile?.keyword??defaults?.key??'三国演义','key',200);
        const books = await engine.search(sourceJson, keyword, 1);
        stages.search = books.length > 0;
        if (!stages.search) throw new Error('search empty');
        books.forEach(requireBookMetadata);
        const requested = body.bookUrl ? books.find(book => book.bookUrl === body.bookUrl)
          : books.find(book=>book.bookUrl===(profile?.book_url||defaults?.bookUrl))??books[0];
        if (!requested) throw new Error('verification book must come from search');
        const book = await engine.info(sourceJson, requested);
        requireBookMetadata(book);
        stages.info = typeof book.bookUrl === 'string' && !!book.name;
        if (!stages.info) throw new Error('book info missing');
        const toc = await engine.toc(sourceJson, book);
        requireBookMetadata(toc.book);
        stages.toc = toc.chapters.length > 0;
        if (!stages.toc) throw new Error('toc empty');
        const preferredChapter=profile&&book.bookUrl===profile.book_url?profile.chapter_url:defaults?.chapterUrl;
        const readable=toc.chapters.filter(item=>!item.isVolume&&!item.isVip&&(item.url||item.chapterUrl));
        const chapter = readable.find(item=>(item.url||item.chapterUrl)===preferredChapter)??readable[0];
        if (!chapter) throw new Error('no readable chapter');
        const chapterIndex=toc.chapters.indexOf(chapter);
        const next=toc.chapters.slice(chapterIndex+1).find(item=>!item.isVolume&&(item.url||item.chapterUrl));
        const content = await engine.content(sourceJson, toc.book, chapter, next?.url||next?.chapterUrl);
        stages.content = canMigrateContent(content.text);
        if (!stages.content) throw new Error('content unusable');
        if (rawRoutes.length > 0) {
          let usableExplore = false;
          let exploreError: unknown;
          stages.explore = false;
          for (const route of rawRoutes.slice(0, 3)) {
            try {
              const listed = await engine.explore(sourceJson, route.url, 1);
              listed.forEach(requireBookMetadata);
              if (listed.length > 0) { usableExplore = true; break; }
            } catch (error) {
              if (error instanceof BookMetadataError) throw error;
              exploreError = error;
            }
          }
          stages.explore = usableExplore;
          if (!usableExplore) throw exploreError ?? new Error('explore empty');
        }
        successfulProfile={keyword,book_url:toc.book.bookUrl||book.bookUrl,chapter_url:chapter.url||chapter.chapterUrl};
      } else if (verificationKind === 'web') {
        const checked = await engine.verifyWebEntry(sourceJson);
        stages.website = true;
        const category = classify(source.name, sourceJson.sourceGroup ?? '', source.id);
        routes = [{ label: source.name, url: checked.url, categoryId: category.id, categoryLabel: category.label, filterIds: category.filterIds }];
      } else {
        const url = sourceJson.sourceUrl ?? sourceJson.url ?? source.id;
        const result = await engine.rssArticles(sourceJson, url, 1);
        stages.articles = result.articles.length > 0;
        if (!stages.articles) throw new Error('articles empty');
        const content = await engine.rssContent(sourceJson, result.articles[0]);
        stages.content = usefulText(content, 40);
        if (!stages.content) throw new Error('content unusable');
        const category = classify(source.name, sourceJson.sourceGroup ?? '', source.id);
        routes = [{ label: source.name, url, categoryId: category.id, categoryLabel: category.label, filterIds: category.filterIds }];
      }
      const committed = await store.markHealthy(source, routes,writeGuard);
      if (!committed) throw new ApiError(409, 'SOURCE_CHANGED', 'Source changed during verification; retry');
      if(successfulProfile)await store.saveVerificationProfile(source,successfulProfile);
      return { ...receipt((await store.get(sourceKind, source.id))!), stages, verified: true, verificationKind };
    } catch (error) {
      if (error instanceof ApiError || error instanceof MaintenanceBudgetError) throw error;
      const reasonCode = diagnosticCode(error);
      if (verificationKind === 'web' && ['WEBSITE_BLOCKED','WEBSITE_NOT_FOUND','WEBSITE_CLOSED','WEBSITE_PARKED'].includes(reasonCode)) {
        await store.quarantine(source, Date.now() + 5 * 60_000);
      } else await store.failure(source);
      return { ...receipt((await store.get(sourceKind, source.id))!), stages, verified: false,
        reason: verificationKind === 'web' ? 'Website entry did not pass its link check' : 'Source did not pass every reading stage',
        verificationKind, reasonCode,
        ...(managementDiagnostics ? { managementDiagnostics: {
          message: String((error as Error)?.message ?? '').slice(0, 200),
          location: String((error as Error)?.stack ?? '').split('\n').slice(1, 4)
            .map(line => line.trim().replace(/https?:\/\/[^\s)]+/g, '[remote]').slice(0, 200)),
        } } : {}) };
    }
  }
  return {
    async fetch(request: Request, env: Env): Promise<Response> {
      try {
        const url = new URL(request.url);
        const path = (url.pathname.replace(/\/$/, '') || '/').replace(/^\/v1\/books\//, '/v1/');
        if (path === '/health' && request.method === 'GET') return new Response('ok', { headers: { 'Content-Type': 'text/plain' } });
        const role = authorizeRequest(request, env); if (role instanceof Response) return role;
        if (!env.DB) throw new ApiError(503, 'UNAVAILABLE', 'Service storage is unavailable');
        const store = new SourceStore(env.DB);
        if (request.method === 'GET') {
          const sourceKind = kind(url.searchParams.get('kind'));
          if (path === '/v1/status') return response({ schemaVersion: 1, engineVersion: 'reader-cloud-v1', storage: true,
            features: { batchedSourceReceipts: true, contentContractVersion:engineImplementation.ENGINE_RULE_CONTRACT_VERSION,
              tocInstallContract:TOC_INSTALL_CONTRACT },
            books: await store.counts('book'), rss: await store.counts('rss'), categories: taxonomy,
            maintenance:await maintenanceStatus(env.DB,env.MAINTENANCE_ENABLED==='true',maintenanceLimit(env.MAINTENANCE_DAILY_WRITES)) });
          if (path === '/v1/source-receipt') {
            const source = await store.get(sourceKind, archiveId(url.searchParams.get('id')));
            if (!source) throw new ApiError(404, 'SOURCE_NOT_FOUND', 'Source not found');
            // Recompute the exact saved JSON bytes on each acknowledgement.
            const hash = await sha256(source.json);
            if (hash !== source.sha256) throw new ApiError(503, 'STORAGE_MISMATCH', 'Stored source could not be verified');
            return response(receipt(source));
          }
          if (path === '/v1/source-export') {
            if (role !== 'admin') throw new ApiError(403, 'FORBIDDEN', 'Management permission required');
            const source = await store.get(sourceKind, archiveId(url.searchParams.get('id')));
            if (!source) throw new ApiError(404, 'SOURCE_NOT_FOUND', 'Source not found');
            if (await sha256(source.json) !== source.sha256) throw new ApiError(503, 'STORAGE_MISMATCH', 'Stored source could not be verified');
            return response({ ...receipt(source), kind: source.kind, json: source.json });
          }
          if (path === '/v1/sources') {
            const rows = await store.list(sourceKind, url.searchParams.has('cursor') ? fromCursor(url.searchParams.get('cursor')) : null,
              101, url.searchParams.get('active') === 'true');
            const preferred=sourceKind==='book'&&url.searchParams.get('active')==='true'
              ? await preferredBookSources(store,(await store.counts('book')).revision):null;
            return response({ sources: rows.slice(0, 100).filter(row=>!preferred||preferred.has(row.id)).map(summary),
              nextCursor: rows.length > 100 ? toCursor(rows[99].id) : null });
          }
          if (path === '/v1/catalogue') {
            const counts = await store.counts(sourceKind);
            const after = fromCursor(url.searchParams.get('cursor'));
            const sourceIds = await store.catalogueSourcePage(sourceKind, after);
            let routes = await store.routes(sourceKind, after);
            if(sourceKind==='book'){
              const preferred=await preferredBookSources(store,counts.revision);
              routes=routes.filter(route=>preferred.has(route.sourceId));
            }
            const webEntries = sourceKind === 'rss' ? await store.webEntries(after) : [];
            const ids = new Set([...routes, ...webEntries].map(route => route.categoryId));
            const nextCursor = sourceIds.length > 100 ? toCursor(sourceIds[99]) : null;
            return response({ revision: `${counts.revision}:${counts.catalogueRevision}`, contentContractVersion:engineImplementation.ENGINE_RULE_CONTRACT_VERSION,
              categories: taxonomy.filter(category => ids.has(category.id)), routes, webEntries,
              checkedSources: counts.checked, totalSources: counts.total, complete: counts.checked === counts.total && nextCursor === null, nextCursor });
          }
          throw new ApiError(404, 'NOT_FOUND', 'Endpoint not found');
        }
        if (request.method !== 'POST') throw new ApiError(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
        if(path==='/v1/reassess'&&role!=='admin')throw new ApiError(403,'FORBIDDEN','Management permission required');
        const body = await readBody(request);
        if(path==='/v1/reassess'){
          if(body.kind!=='book'&&body.kind!=='rss')throw new ApiError(400,'INVALID_KIND','Source kind must be book or rss');
          if(Object.keys(body).some(key=>!['kind','sources'].includes(key))||!Array.isArray(body.sources)||body.sources.length<1||body.sources.length>50)
            throw new ApiError(400,'INVALID_INPUT','Assessment requires 1 to 50 expected source identities');
          const expected=body.sources.map((value:unknown)=>{
            const item=object(value,'source');
            if(Object.keys(item).some(key=>!['id','sha256'].includes(key)))throw new ApiError(400,'INVALID_INPUT','Assessment accepts expected identities only');
            const id=archiveId(item.id),hash=string(item.sha256,'sha256',64);
            if(!/^[a-f0-9]{64}$/.test(hash))throw new ApiError(400,'INVALID_INPUT','Invalid expected source SHA');
            return {id,sha256:hash};
          }) as Array<{id:string;sha256:string}>;
          if(new Set(expected.map(item=>item.id)).size!==expected.length)throw new ApiError(400,'DUPLICATE_ID','Duplicate assessment identity');
          const metadata=await store.assessmentMetadata(body.kind,expected.map(item=>item.id));
          const candidates=expected.filter(item=>metadata.get(item.id)?.sha256===item.sha256);
          if(candidates.some(item=>metadata.get(item.id)!.json_bytes>MAX_SOURCE)||
            candidates.reduce((bytes,item)=>bytes+metadata.get(item.id)!.json_bytes,0)>MAX_ASSESSMENT_BYTES)
            throw new ApiError(413,'ASSESSMENT_LIMIT','Source assessment byte limit exceeded');
          const plans:AssessmentPlan[]=[],stale=new Map<string,AssessmentReceipt>();
          const rawSources=await store.boundedAssessmentSources(body.kind,candidates,MAX_SOURCE,MAX_ASSESSMENT_BYTES);
          let bytes=0,nodes=0;
          // Every raw SHA and aggregate work bound is checked before any write.
          for(const item of expected){
            const source=rawSources.get(item.id);
            if(!source){stale.set(item.id,{id:item.id,expectedSha256:item.sha256,
              currentSha256:metadata.get(item.id)?.sha256!==item.sha256?metadata.get(item.id)?.sha256??null:null,
              outcome:'stale',changed:false,changeCount:0});continue;}
            bytes+=encoder.encode(source.json).length;
            if(bytes>MAX_ASSESSMENT_BYTES)throw new ApiError(413,'ASSESSMENT_LIMIT','Source assessment byte limit exceeded');
            if(await sha256(source.json)!==item.sha256)throw new ApiError(503,'STORAGE_MISMATCH','Stored source could not be verified');
            let rule:JsonObject;
            try{rule=object(JSON.parse(source.json),'source JSON');}catch{throw new ApiError(503,'STORAGE_MISMATCH','Stored source could not be verified');}
            if(rule[body.kind==='book'?'bookSourceUrl':'sourceUrl']!==item.id)throw new ApiError(503,'STORAGE_MISMATCH','Stored source identity could not be verified');
            nodes+=assessmentWork(rule,MAX_ASSESSMENT_NODES-nodes);
            const assessed=engine.assessSource(body.kind,rule),capabilities=assessmentCapabilities(assessed);
            let capabilitiesJson:string|undefined;
            try{if(JSON.stringify(assessmentCapabilities({capabilities:JSON.parse(source.capabilities)}))===JSON.stringify(capabilities))capabilitiesJson=source.capabilities;}catch{}
            plans.push({source,supported:assessed.supported===true,capabilities,capabilitiesJson});
          }
          const assessed=new Map((await store.reassessSources(body.kind,plans)).map(item=>[item.id,item]));
          return response({receipts:expected.map(item=>assessed.get(item.id)??stale.get(item.id)!),
            assessmentOnly:true,networkRequests:0,storageUsage:store.assessmentStorageUsage()});
        }
        if(path==='/v1/source-receipts'){
          if(body.kind!=='book'&&body.kind!=='rss')throw new ApiError(400,'INVALID_KIND','Source kind must be book or rss');
          if(!Array.isArray(body.ids)||body.ids.length<1||body.ids.length>100)throw new ApiError(400,'INVALID_INPUT','Receipt lookup requires 1 to 100 source IDs');
          const ids=body.ids.map(archiveId) as string[];
          if(new Set(ids).size!==ids.length)throw new ApiError(400,'DUPLICATE_ID','Duplicate source in receipt batch');
          const stored=await store.receiptSources(body.kind,ids);
          const receipts=[];
          for(const id of ids){
            const source=stored.get(id);
            if(!source){receipts.push({id,stored:false});continue;}
            if(await sha256(source.json)!==source.sha256)throw new ApiError(503,'STORAGE_MISMATCH','Stored source could not be verified');
            receipts.push(receipt(source));
          }
          return response({receipts});
        }
        if(path==='/v1/maintenance/run'){
          if(role!=='admin')throw new ApiError(403,'FORBIDDEN','Management permission required');
          return response(await runMaintenance(env.DB,(source,guard)=>verify(store,{kind:source.kind,sourceId:source.id},false,guard),
            {limit:maintenanceLimit(env.MAINTENANCE_DAILY_WRITES),trigger:'manual'}));
        }
        if (path === '/v1/import') {
          if (role === 'read') throw new ApiError(403, 'FORBIDDEN', 'Import permission required');
          const sourceKind = kind(body.kind);
          if (!Array.isArray(body.sources) || body.sources.length < 1 || body.sources.length > 50) throw new ApiError(400, 'INVALID_INPUT', 'Import requires 1 to 50 sources');
          const ids = new Set<string>();
          const imports: ImportSource[] = [];
          for (const itemValue of body.sources) {
            const item = object(itemValue, 'source');
            const id = archiveId(item.id);
            if (ids.has(id)) throw new ApiError(400, 'DUPLICATE_ID', 'Duplicate source in import batch');
            ids.add(id);
            const json = string(item.json, 'json', MAX_SOURCE);
            if (encoder.encode(json).length > MAX_SOURCE) throw new ApiError(413, 'TOO_LARGE', 'Source is too large');
            const claimedHash = string(item.sha256, 'sha256', 64);
            const hash = await sha256(json);
            if (claimedHash !== hash) throw new ApiError(400, 'HASH_MISMATCH', 'Source hash did not match its exact JSON bytes');
            let rule: JsonObject;
            try { rule = object(JSON.parse(json), 'source JSON'); } catch { throw new ApiError(400, 'INVALID_SOURCE', 'Invalid source JSON'); }
            const ruleId = sourceKind === 'book' ? rule.bookSourceUrl : rule.sourceUrl;
            if (ruleId !== id) throw new ApiError(400, 'ID_MISMATCH', 'Source ID did not match its JSON');
            const assessed = engine.assessSource(sourceKind, rule);
            const capabilities = Array.isArray(assessed.capabilities)
              ? assessed.capabilities.filter((capability: unknown) => typeof capability === 'string')
              : Object.entries(assessed.capabilities ?? {}).filter(([key, value]) => key !== 'supported' && value === true).map(([key]) => key);
            imports.push({ kind: sourceKind, id, json, sha256: hash,
              name: String(assessed.name ?? rule.bookSourceName ?? rule.sourceName ?? id).slice(0, 512),
              enabled: rule.enabled !== false, enabledExplore: sourceKind === 'rss' || (rule.enabledExplore !== false && (assessed.explore ?? assessed.enabledExplore ?? !!rule.exploreUrl)),
              supported: !!assessed.supported, capabilities });
          }
          const stored = await store.importSources(imports);
          for (let i = 0; i < stored.length; i++) {
            if (stored[i]?.json !== imports[i].json || stored[i]?.sha256 !== imports[i].sha256) throw new ApiError(503, 'STORAGE_MISMATCH', 'Import read-back failed');
          }
          return response({ receipts: stored.map(receipt) });
        }
        if (path === '/v1/verify') {
          if (role === 'read') throw new ApiError(403, 'FORBIDDEN', 'Verification permission required');
          return response(await verify(store, body, role === 'admin'));
        }
        if (path === '/v1/category-override') {
          if (role === 'read') throw new ApiError(403,'FORBIDDEN','Classification permission required');
          const sourceKind = kind(body.kind);
          const from = string(body.fromCategoryId,'fromCategoryId',64);
          const targetId = string(body.categoryId,'categoryId',64);
          const target = taxonomy.find(category=>category.id===targetId);
          if (!target || !taxonomy.some(category=>category.id===from)) throw new ApiError(400,'INVALID_CATEGORY','Choose an existing category');
          const source = await getSource(store,sourceKind,string(body.sourceId,'sourceId',16384),true);
          if (source.status!=='healthy') throw new ApiError(409,'SOURCE_NOT_HEALTHY','Only verified source categories can be changed');
          const result = await store.overrideCategory(source,from,target);
          if (!result.found) throw new ApiError(409,'CATEGORY_ROUTE_NOT_FOUND','The source category changed; refresh and retry');
          const counts = await store.counts(sourceKind);
          return response({ sourceId:source.id,fromCategoryId:from,categoryId:target.id,changed:result.changed,
            revision:`${counts.revision}:${counts.catalogueRevision}` });
        }
        const rss = path.startsWith('/v1/rss/');
        const source = await getSource(store, rss ? 'rss' : 'book', string(body.sourceId, 'sourceId', 16384));
        if (rss && JSON.parse(source.capabilities).includes('web')) throw new ApiError(422, 'WEBSITE_ENTRY', 'This source is a website entry, not an article feed');
        const rule = JSON.parse(source.json);
        const currentPage = page(body.page);
        let args: unknown;
        let operation: string;
        let action: () => Promise<unknown>;
        if (path === '/v1/search') {
          const key = string(body.key, 'key', 200); args = [key, currentPage]; operation = 'search';
          action = async () => ({ books: await engine.search(rule, key, currentPage) });
        } else if (path === '/v1/explore') {
          const requestedTarget = string(body.url, 'url');
          const target = requestedTarget === 'reader-cloud://explore' ? 'reader-cloud://category/all' : requestedTarget;
          args = [target, currentPage]; operation = 'explore';
          let routes = await store.sourceRoutes(source);
          let targetUrl = target;
          let routePage = currentPage;
          if (target.startsWith('reader-cloud://category/')) {
            const categoryId = target.slice('reader-cloud://category/'.length);
            if (categoryId !== 'all' && !taxonomy.some(category => category.id === categoryId)) throw new ApiError(400, 'INVALID_ROUTE', 'Unknown category');
            routes = routes.filter(route => categoryId === 'all' || route.categoryId === categoryId);
            if (!routes.length) return response({ books: [] });
            const routeIndex = (currentPage - 1) % routes.length;
            targetUrl = routes[routeIndex].url;
            routePage = Math.floor((currentPage - 1) / routes.length) + 1;
          } else if (!routes.some(route => route.url === target)) throw new ApiError(400, 'INVALID_ROUTE', 'Use a verified catalogue route');
          action = async () => ({ books: await engine.explore(rule, targetUrl, routePage) });
        } else if (path === '/v1/info') {
          const book = object(body.book, 'book'); args = book; operation = 'info';
          action = async () => ({ book: await engine.info(rule, book) });
        } else if (path === '/v1/toc') {
          if (body.tocInstallContract !== TOC_INSTALL_CONTRACT) throw new ApiError(409,'CLIENT_UPDATE_REQUIRED',
            '請更新至1.2.3或更新版本，以安全更新完整目錄並保留閱讀位置');
          const book = object(body.book, 'book'); args = book; operation = 'toc';
          const bookUrl=string(book.bookUrl,'book.bookUrl',16384);
          action = async () => ({...await engine.toc(rule, await repairMetadata(source, rule, book)),complete:true,
            contractVersion:engineImplementation.ENGINE_RULE_CONTRACT_VERSION,sourceId:source.id,bookUrl});
        } else if (path === '/v1/content') {
          const book = object(body.book, 'book'); const chapter = object(body.chapter, 'chapter');
          const bookUrl=string(book.bookUrl,'book.bookUrl',16384),chapterUrl=string(chapter.url,'chapter.url',16384);
          const nextChapterUrl=body.nextChapterUrl==null?undefined:string(body.nextChapterUrl,'nextChapterUrl',16384,false);
          args = [book, chapter, nextChapterUrl??null]; operation = 'content';
          action = async () => { const result = await engine.content(rule, await repairMetadata(source, rule, book), chapter,nextChapterUrl);
            if (!canReadContent(result.text)) throw new Error('Unusable content');
            return { content: result.text, book: result.book, complete:true, contractVersion:engineImplementation.ENGINE_RULE_CONTRACT_VERSION,
              sourceId:source.id,bookUrl,chapterUrl,bodySha256:await sha256(result.text) }; };
        } else if (path === '/v1/rss/articles') {
          let target = string(body.url ?? rule.sourceUrl, 'url');
          if (target === 'reader-cloud://articles') {
            const routes = await store.sourceRoutes(source);
            if (!routes.length) throw new ApiError(422, 'INVALID_ROUTE', 'No verified subscription route is available');
            target = routes[0].url;
          } else if (target.startsWith('reader-cloud:')) throw new ApiError(400, 'INVALID_ROUTE', 'Unknown subscription route');
          args = [target, currentPage]; operation = 'articles';
          action = async () => { const result = await engine.rssArticles(rule, target, currentPage); return { articles: result.articles, nextUrl: result.nextPageUrl ?? null }; };
        } else if (path === '/v1/rss/content') {
          const article = object(body.article, 'article'); args = article; operation = 'rss-content';
          action = async () => ({ content: await engine.rssContent(rule, article) });
        } else throw new ApiError(404, 'NOT_FOUND', 'Endpoint not found');
        return response(await sourceCall(store, source, operation, args, action));
      } catch (error) {
        if (error instanceof ApiError) return errorResponse(error);
        const quota = dailyReadAvailability(error);
        if (quota) return new Response(JSON.stringify({error:{code:quota.code,message:quota.message,resetAt:quota.resetAt}}), {
          status:503,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','Retry-After':String(quota.retryAfterSeconds)},
        });
        return errorResponse(new ApiError(503, 'UNAVAILABLE', 'Service is temporarily unavailable'));
      }
    },
    async scheduled(_controller:ScheduledController,env:Env,_context:ExecutionContext):Promise<void>{
      if(env.MAINTENANCE_ENABLED!=='true')return;
      const store=new SourceStore(env.DB);
      await runMaintenance(env.DB,(source,guard)=>verify(store,{kind:source.kind,sourceId:source.id},false,guard),
        {limit:maintenanceLimit(env.MAINTENANCE_DAILY_WRITES),trigger:'scheduled'});
    },
  };
}
export default createApp();
