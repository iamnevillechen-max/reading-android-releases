import reviewed from '../../catalogue/source-adapters.json';
import { reviewedHosts, reviewedSources } from './compiled-sources';

const AUTH_OR_INJECTION_FIELDS = ['loginUrl','loginUi','loginCheckJs','injectJs','shouldOverrideUrlLoading'];
const EXECUTION_FIELDS = new Set(['bookUrlPattern','jsLib','header','loginUrl','loginUi','loginCheckJs',
  'coverDecodeJs','searchUrl','exploreUrl','exploreScreen']);
const PUBLIC_HEADERS = new Set(['user-agent','accept','accept-language','referer','content-type']);
const isExecutionField = (key: string) => key.startsWith('rule') || EXECUTION_FIELDS.has(key);
type PublicFamily = { canonicalId: string; sourceHost: string; hosts: string[] };
const canonicalHosts = new Map<string, PublicFamily>();
const aliasHosts = new Map<string, PublicFamily[]>();
for (const canonicalId of Object.keys(reviewedSources)) {
  const sourceHost = new URL(canonicalId).hostname;
  const family = { canonicalId, sourceHost, hosts: reviewedHosts[canonicalId] };
  canonicalHosts.set(sourceHost, family);
  const suffix = sourceHost.split('.').slice(-2).join('.');
  for (const host of family.hosts) {
    // A request allowlist can contain a credited external host (e.g. GitHub).
    // Only separately reviewed hosts inside this provider's own domain identify a family.
    if (host !== suffix && !host.endsWith('.' + suffix)) continue;
    aliasHosts.set(host, [...(aliasHosts.get(host) || []), family]);
  }
}

function publicFamily(source: Record<string, any>): PublicFamily | undefined {
  if (typeof source.bookSourceUrl !== 'string' || (source.bookSourceType != null && source.bookSourceType !== 0)) return;
  const id = source.bookSourceUrl;
  if (id !== id.trim() || /[\u0000-\u001f\u007f]|@js:|<js>|\{\{/i.test(id) || AUTH_OR_INJECTION_FIELDS.some(key => !!source[key])) return;
  let url: URL;
  try { url = new URL(id); } catch { return; }
  if (!['https:','http:'].includes(url.protocol) || url.username || url.password || (url.port && !['80','443'].includes(url.port))) return;
  let headers = source.header;
  try {
    if (typeof headers === 'string') headers = headers ? JSON.parse(headers) : {};
    if (headers && (typeof headers !== 'object' || Array.isArray(headers))) return;
    for (const [key, value] of Object.entries(headers || {})) {
      if (!PUBLIC_HEADERS.has(key.toLowerCase()) || typeof value !== 'string' || /[\u0000-\u001f\u007f]|@js:|<js>|\{\{/i.test(value)) return;
    }
  } catch { return; }
  const canonical = canonicalHosts.get(url.hostname);
  if (canonical) return canonical;
  const aliases = aliasHosts.get(url.hostname);
  return aliases?.length === 1 ? aliases[0] : undefined;
}

/** A known incomplete interface must not gain health from preview-only results. */
export function sourceVerificationHold(source: Record<string, any>): string | undefined {
  if (typeof source.bookSourceUrl !== 'string') return;
  try {
    const url = new URL(source.bookSourceUrl);
    if (!['https:','http:'].includes(url.protocol)) return;
    return reviewed.verificationHolds.find(hold => hold.host === url.hostname)?.reasonCode;
  } catch { return; }
}

/** Public verification defaults/provider deduplication; this grants no health status. */
export function canonicalSourceIdForExecution(source: Record<string, any>): string | undefined {
  return publicFamily(source)?.canonicalId;
}

/** Derive host access from checked-in policy, never an imported field or URL prefix. */
export function sourceExecutionHosts(source: Record<string, any>): string[] | undefined {
  return publicFamily(source)?.hosts.slice();
}

/** JSON object member order is not part of a rule's meaning (Gson reorders it). */
function sameRuleValue(left: unknown, right: unknown, depth = 0): boolean {
  if (Object.is(left, right)) return true;
  if (depth > 32 || left === null || right === null || typeof left !== 'object' || typeof right !== 'object' ||
      Array.isArray(left) !== Array.isArray(right)) return false;
  const first = left as Record<string, unknown>, second = right as Record<string, unknown>;
  const keys = Object.keys(first);
  return keys.length === Object.keys(second).length && keys.every(key => Object.hasOwn(second, key) &&
    sameRuleValue(first[key], second[key], depth + 1));
}

/** Reviewed execution-only repairs. Never mutate the stored original rule or its SHA. */
export function sourceForExecution<T extends Record<string, any>>(source: T): T {
  const family = publicFamily(source);
  if (!family) return source;
  let result: Record<string, any> = source;
  if (source.bookSourceUrl !== family.canonicalId) {
    result = { ...source };
    for (const key of Object.keys(result)) if (isExecutionField(key)) delete result[key];
    for (const [key, value] of Object.entries(reviewedSources[family.canonicalId])) {
      if (isExecutionField(key)) result[key] = structuredClone(value);
    }
  }
  // Existing reviewed IDs retain their own rules (including quarantine of an
  // altered unknown script). Only their explicit reviewed repair is overlaid.
  const adapter = reviewed.adapters.find(item => item.sourceIds.includes(family.canonicalId));
  const matchesReviewedInput = source.bookSourceUrl !== family.canonicalId || !adapter ||
    Object.keys(adapter.rules).every(key => sameRuleValue(source[key], reviewedSources[family.canonicalId][key]));
  return (adapter && matchesReviewedInput ? { ...result, ...structuredClone(adapter.rules) } : result) as T;
}

export const sourceAdapterRevision = reviewed.revision + ':public-family-v1';
