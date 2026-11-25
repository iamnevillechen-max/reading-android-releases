import { isPublicHost } from './engine';

/** Isolated contract prototype. No engine route, source promotion, fetch, or Android integration. */
export const MEDIA_CONTRACT_VERSION = 'reader-media-manifest-1';
export type MediaMode = 'audio' | 'image' | 'download';
export type MediaIdentity = Readonly<{
  sourceId: string; originalSourceSha256: string; bookUrl: string; chapterUrl?: string;
}>;
export type MediaRequest = MediaIdentity & { mode: MediaMode };
export type MediaUrlPolicy = Readonly<{
  /** Supplied by reviewed provider configuration, never by the imported manifest. */
  approvedHosts: readonly string[];
  /** Same public-DoH contract as EngineOptions.resolveHost; omission never skips DNS. */
  resolveHost: (hostname: string, signal: AbortSignal) => Promise<boolean>;
  approvedPublicHeaders?: Readonly<Record<string, readonly string[]>>;
}>;
export class MediaContractError extends Error {
  constructor(public code: string, message: string) { super(message); }
}
export type MediaAsset = Readonly<{
  id: string; order: number; url: string; method: 'GET'; headers: Readonly<Record<string, string>>;
  mimeType: string; expectedBytes?: number; expectedSha256?: string;
  format?: string; fileName?: string; descriptorSha256: string;
}>;
type BaseManifest = Readonly<{
  contractVersion: typeof MEDIA_CONTRACT_VERSION; identity: MediaIdentity; manifestComplete: true;
  assets: readonly MediaAsset[];
}>;
export type MediaManifest = BaseManifest & (
  { mode: 'audio'; positionUnit: 'milliseconds' } |
  { mode: 'image'; positionUnit: 'image-index' } |
  { mode: 'download'; importPolicy: 'isolated-file' }
);
export type AssetDownloadReceipt = Readonly<{
  contractVersion: typeof MEDIA_CONTRACT_VERSION; identity: MediaIdentity; mode: MediaMode;
  assetId: string; descriptorSha256: string; finalUrl: string;
  receivedBytes: number; receivedSha256: string;
  state: 'complete' | 'partial'; digestScope: 'whole-asset' | 'received-bytes';
}>;

const issuedManifests = new WeakSet<object>(), issuedReceipts = new WeakSet<object>();
const MAX_ASSETS = { audio: 8, image: 512, download: 32 } as const;
const AUDIO_MIME = new Set(['audio/mpeg', 'audio/mp4', 'audio/aac', 'audio/ogg', 'audio/flac', 'audio/wav', 'audio/x-wav']);
const IMAGE_MIME = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/avif']);
// HLS playlists/segments and encrypted media need their own reviewed subcontracts.
const FILE_MIME: Record<string, readonly string[]> = {
  txt: ['text/plain'], epub: ['application/epub+zip'], pdf: ['application/pdf'],
  zip: ['application/zip'], mobi: ['application/x-mobipocket-ebook'], umd: ['application/octet-stream'],
};
const PUBLIC_HEADERS = new Set(['user-agent', 'accept', 'accept-language', 'referer', 'origin']);
function fail(code: string, message: string): never { throw new MediaContractError(code, message); }
function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      ![Object.prototype, null].includes(Object.getPrototypeOf(value))) fail('MEDIA_SCHEMA', 'Expected an inert JSON object');
  const row = value as Record<string, unknown>;
  if (Object.keys(row).some(key => !keys.includes(key))) fail('MEDIA_SCHEMA', 'Unexpected contract field');
  return row;
}
function string(value: unknown, max = 16_384): string {
  if (typeof value !== 'string' || !value || value.length > max || value.trim() !== value || /[\u0000-\u001f\u007f]/.test(value)) {
    fail('MEDIA_SCHEMA', 'Expected a bounded nonempty string');
  }
  return value;
}
function sha(value: unknown): string {
  const result = string(value, 64);
  if (!/^[a-f0-9]{64}$/.test(result)) fail('MEDIA_DIGEST', 'Expected an exact SHA-256');
  return result;
}
function bytes(value: unknown): number {
  if (!Number.isSafeInteger(value) || Number(value) < 1) fail('MEDIA_LENGTH', 'Expected a positive exact byte count');
  return Number(value);
}
async function digest(value: string | Uint8Array<ArrayBuffer>): Promise<string> {
  const input = typeof value === 'string' ? new TextEncoder().encode(value) : value;
  const result = await crypto.subtle.digest('SHA-256', input);
  return Array.from(new Uint8Array(result), byte => byte.toString(16).padStart(2, '0')).join('');
}

/** Apply again to every redirect target; no imported URL may add an approved CDN. */
export async function validatePublicMediaUrl(raw: unknown, policy: MediaUrlPolicy,
                                             signal: AbortSignal = AbortSignal.timeout(5_000)): Promise<string> {
  const value = string(raw);
  if (/\s|,\s*\{|@js:|<js>|\{\{/i.test(value)) fail('MEDIA_URL', 'URL options and programs are not media URLs');
  let url: URL;
  try { url = new URL(value); } catch { return fail('MEDIA_URL', 'An absolute public URL is required'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
      (url.port && !['80', '443'].includes(url.port)) || !isPublicHost(url.hostname) ||
      !policy.approvedHosts.includes(url.hostname.toLowerCase())) fail('MEDIA_HOST', 'Media host is not approved');
  if (typeof policy.resolveHost !== 'function' || signal.aborted || !await policy.resolveHost(url.hostname, signal) || signal.aborted) {
    fail('MEDIA_DNS', 'Public DNS validation is required');
  }
  url.hash = '';
  return url.href;
}
async function headers(raw: unknown, policy: MediaUrlPolicy, signal: AbortSignal): Promise<Readonly<Record<string, string>>> {
  if (raw === undefined) return Object.freeze({});
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) fail('MEDIA_HEADERS', 'Headers must be inert public values');
  const result: Record<string, string> = {};
  if (Object.keys(raw).length > 5) fail('MEDIA_HEADERS', 'Too many media headers');
  for (const [original, unknownValue] of Object.entries(raw)) {
    const key = original.toLowerCase(), value = string(unknownValue, 2048);
    if (/@js:|<js>|\{\{/i.test(value) || !PUBLIC_HEADERS.has(key) || Object.hasOwn(result, key) ||
        !policy.approvedPublicHeaders?.[key]?.includes(value)) fail('MEDIA_HEADERS', 'Header was not approved as public');
    if (key === 'referer' || key === 'origin') {
      const checked = await validatePublicMediaUrl(value, policy, signal);
      if (key === 'origin' && new URL(checked).origin !== value) fail('MEDIA_HEADERS', 'Origin must contain only a public origin');
    }
    result[key] = value;
  }
  return Object.freeze(Object.fromEntries(Object.entries(result).sort(([left], [right]) => left.localeCompare(right))));
}
function sameIdentity(left: MediaIdentity, right: MediaIdentity): boolean {
  return left.sourceId === right.sourceId && left.originalSourceSha256 === right.originalSourceSha256 &&
    left.bookUrl === right.bookUrl && left.chapterUrl === right.chapterUrl;
}

export async function parseMediaManifest(raw: unknown, expected: MediaRequest, policy: MediaUrlPolicy,
                                        signal: AbortSignal = AbortSignal.timeout(5_000)): Promise<MediaManifest> {
  const row = object(raw, ['contractVersion', 'mode', 'identity', 'manifestComplete', 'assets', 'positionUnit', 'importPolicy']);
  if (!['audio', 'image', 'download'].includes(String(row.mode)) || row.mode !== expected.mode) fail('MEDIA_MODE', 'Unsupported or mismatched media mode');
  const mode = row.mode as MediaMode;
  if (row.contractVersion !== MEDIA_CONTRACT_VERSION || row.manifestComplete !== true) fail('MEDIA_INCOMPLETE', 'Only a fully enumerated manifest is accepted');
  const inputIdentity = object(row.identity, ['sourceId', 'originalSourceSha256', 'bookUrl', 'chapterUrl']);
  const identity: MediaIdentity = Object.freeze({ sourceId: string(inputIdentity.sourceId),
    originalSourceSha256: sha(inputIdentity.originalSourceSha256), bookUrl: string(inputIdentity.bookUrl),
    ...(inputIdentity.chapterUrl === undefined ? {} : { chapterUrl: string(inputIdentity.chapterUrl) }) });
  if (!sameIdentity(identity, expected) || (mode === 'download' ? identity.chapterUrl !== undefined : !identity.chapterUrl)) {
    fail('MEDIA_IDENTITY', 'Source, archive SHA, book and chapter must match the request');
  }
  for (const url of [identity.sourceId, identity.bookUrl, identity.chapterUrl].filter((value): value is string => !!value)) {
    await validatePublicMediaUrl(url, policy, signal);
  }
  if (mode === 'audio' && (row.positionUnit !== 'milliseconds' || row.importPolicy !== undefined) ||
      mode === 'image' && (row.positionUnit !== 'image-index' || row.importPolicy !== undefined) ||
      mode === 'download' && (row.importPolicy !== 'isolated-file' || row.positionUnit !== undefined)) {
    fail('MEDIA_MODE', 'Playback position and import semantics belong to their mode');
  }
  if (!Array.isArray(row.assets) || row.assets.length < 1 || row.assets.length > MAX_ASSETS[mode]) fail('MEDIA_ASSETS', 'Asset list is empty or exceeds the mode limit');
  const assets: MediaAsset[] = [], ids = new Map<string, string>(), urls = new Map<string, string>();
  for (const item of row.assets) {
    const allowed = ['id', 'url', 'method', 'headers', 'mimeType', 'expectedBytes', 'expectedSha256',
      ...(mode === 'download' ? ['format', 'fileName'] : [])];
    const asset = object(item, allowed), id = string(asset.id, 120);
    if (!/^[A-Za-z0-9._:-]+$/.test(id) || asset.method !== 'GET') fail('MEDIA_ASSET', 'Only identified public GET assets are accepted');
    const url = await validatePublicMediaUrl(asset.url, policy, signal), publicHeaders = await headers(asset.headers, policy, signal);
    const mimeType = string(asset.mimeType, 100).toLowerCase();
    if (mode === 'audio' && !AUDIO_MIME.has(mimeType) || mode === 'image' && !IMAGE_MIME.has(mimeType)) fail('MEDIA_TYPE', 'Media MIME does not match its mode');
    let file: { format: string; fileName: string } | undefined;
    if (mode === 'download') {
      const format = string(asset.format, 8), fileName = string(asset.fileName, 240);
      if (!Object.hasOwn(FILE_MIME, format) || !FILE_MIME[format].includes(mimeType) || /[\\/:]/.test(fileName) ||
          fileName === '.' || fileName === '..' || !fileName.toLowerCase().endsWith('.' + format)) fail('MEDIA_TYPE', 'File format, MIME and isolated filename must agree');
      file = { format, fileName };
    }
    const descriptor = { url, method: 'GET' as const, headers: publicHeaders, mimeType,
      ...(asset.expectedBytes === undefined ? {} : { expectedBytes: bytes(asset.expectedBytes) }),
      ...(asset.expectedSha256 === undefined ? {} : { expectedSha256: sha(asset.expectedSha256) }), ...file };
    const representation = JSON.stringify(descriptor), previous = urls.get(url);
    if (ids.has(id) && ids.get(id) !== url || previous !== undefined && previous !== representation) fail('MEDIA_DUPLICATE', 'Conflicting asset identity or representation');
    ids.set(id, url);
    if (previous !== undefined) continue; // Preserve the first occurrence and original order.
    urls.set(url, representation);
    assets.push(Object.freeze({ id, order: assets.length, ...descriptor,
      descriptorSha256: await digest(JSON.stringify({ id, ...descriptor })) }));
  }
  const base: BaseManifest = { contractVersion: MEDIA_CONTRACT_VERSION, identity, manifestComplete: true as const, assets: Object.freeze(assets) };
  const manifest: MediaManifest = Object.freeze(mode === 'audio' ? { ...base, mode, positionUnit: 'milliseconds' as const }
    : mode === 'image' ? { ...base, mode, positionUnit: 'image-index' as const }
      : { ...base, mode, importPolicy: 'isolated-file' as const });
  issuedManifests.add(manifest);
  return manifest;
}

export function isAudioPosition(value: unknown): value is { unit: 'milliseconds'; value: number } {
  const row = value as { unit?: unknown; value?: unknown } | null;
  return !!row && row.unit === 'milliseconds' && Number.isSafeInteger(row.value) && Number(row.value) >= 0;
}

/**
 * Bounded in-memory verifier for the isolated prototype and small fixtures.
 * Large native audio/file caches must stream/hash their actual file, rather than
 * loading a whole asset into a Worker or trusting a serialized complete flag.
 * A full HTTP entity receipt is still not a codec/decompression/import guarantee.
 * Observations come only from a trusted GET/native-cache verifier, never remote
 * completion claims submitted as JSON to an API.
 */
export async function recordAssetDownload(manifest: MediaManifest, assetId: string, observation: {
  finalUrl: string; method: 'GET'; status: number; responseHeaders: Headers;
  received: Uint8Array<ArrayBuffer>; transportComplete: boolean; requestHadRange?: boolean;
}, policy: MediaUrlPolicy, signal: AbortSignal = AbortSignal.timeout(5_000)): Promise<AssetDownloadReceipt> {
  if (!issuedManifests.has(manifest)) fail('MEDIA_MANIFEST', 'A validated manifest is required');
  const asset = manifest.assets.find(value => value.id === assetId);
  if (!asset) fail('MEDIA_ASSET', 'Asset does not belong to this manifest');
  if (observation.method !== 'GET' || ![200, 206].includes(observation.status) ||
      !(observation.received instanceof Uint8Array) || !observation.received.length) fail('MEDIA_TRANSFER', 'No successful media bytes were observed');
  if (observation.received.length > 8 * 1024 * 1024) fail('MEDIA_PROOF_LIMIT', 'Use a native streaming verifier for large media');
  const finalUrl = await validatePublicMediaUrl(observation.finalUrl, policy, signal);
  // Redirect approval grants transport only. A changed resource URL must first
  // be resolved into a new source-bound descriptor, never silently re-labelled.
  if (finalUrl !== asset.url) fail('MEDIA_IDENTITY', 'Downloaded bytes belong to another resolved asset');
  const mime = observation.responseHeaders.get('content-type')?.split(';')[0].trim().toLowerCase();
  if (mime !== asset.mimeType) fail('MEDIA_TYPE', 'Response MIME does not match the descriptor');
  const receivedSha256 = await digest(observation.received);
  const rawLength = observation.responseHeaders.get('content-length');
  const length = rawLength === null ? undefined : /^\d+$/.test(rawLength) ? Number(rawLength) : NaN;
  if (length !== undefined && (!Number.isSafeInteger(length) || length < 0)) fail('MEDIA_LENGTH', 'Invalid response byte count');
  const ranged = observation.requestHadRange === true || observation.status === 206 || observation.responseHeaders.has('content-range');
  const expected = asset.expectedBytes ?? length;
  const complete = !ranged && observation.status === 200 && observation.transportComplete === true && expected !== undefined &&
    expected === observation.received.length && (length === undefined || length === observation.received.length);
  if (complete && asset.expectedSha256 !== undefined && asset.expectedSha256 !== receivedSha256) fail('MEDIA_DIGEST', 'Whole-file digest differs from the expected asset');
  const receipt: AssetDownloadReceipt = Object.freeze({ contractVersion: MEDIA_CONTRACT_VERSION, identity: manifest.identity,
    mode: manifest.mode, assetId, descriptorSha256: asset.descriptorSha256, finalUrl,
    receivedBytes: observation.received.length, receivedSha256,
    state: complete ? 'complete' : 'partial', digestScope: complete ? 'whole-asset' : 'received-bytes' });
  issuedReceipts.add(receipt);
  return receipt;
}

/** Serialized receipts require actual native-cache revalidation before reuse. */
export function allAssetsDownloaded(manifest: MediaManifest, receipts: readonly unknown[]): boolean {
  if (!issuedManifests.has(manifest) || receipts.length !== manifest.assets.length) return false;
  const seen = new Set<string>();
  return receipts.every(input => {
    if (!input || typeof input !== 'object' || !issuedReceipts.has(input)) return false;
    const receipt = input as AssetDownloadReceipt, asset = manifest.assets.find(value => value.id === receipt.assetId);
    if (!asset || seen.has(asset.id) || receipt.state !== 'complete' || receipt.digestScope !== 'whole-asset' ||
        receipt.mode !== manifest.mode || !sameIdentity(receipt.identity, manifest.identity) ||
        receipt.descriptorSha256 !== asset.descriptorSha256 || receipt.finalUrl !== asset.url) return false;
    seen.add(asset.id); return true;
  });
}
