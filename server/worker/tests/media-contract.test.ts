import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MEDIA_CONTRACT_VERSION, parseMediaManifest, validatePublicMediaUrl, recordAssetDownload,
  allAssetsDownloaded, isAudioPosition, MediaContractError,
  type MediaMode, type MediaRequest, type MediaUrlPolicy,
} from '../src/media-contract';

const origin = 'https://owned.example.org';
const identity = { sourceId: origin + '/source', originalSourceSha256: 'a'.repeat(64), bookUrl: origin + '/book', chapterUrl: origin + '/chapter/1' };
const policy: MediaUrlPolicy = {
  approvedHosts: ['owned.example.org'], resolveHost: async () => true,
  approvedPublicHeaders: { referer: [origin + '/book'], 'user-agent': ['Owned static media fixture'] },
};
function request(mode: MediaMode): MediaRequest {
  return mode === 'download' ? { mode, sourceId: identity.sourceId, originalSourceSha256: identity.originalSourceSha256, bookUrl: identity.bookUrl }
    : { mode, ...identity };
}
function asset(id = 'part-1', mimeType = 'audio/mpeg') {
  return { id, url: origin + '/' + id, method: 'GET', mimeType };
}
function manifest(mode: MediaMode = 'audio', assets: unknown[] = [asset()]) {
  const { mode: _, ...boundIdentity } = request(mode);
  return { contractVersion: MEDIA_CONTRACT_VERSION, mode, identity: boundIdentity, manifestComplete: true, assets,
    ...(mode === 'audio' ? { positionUnit: 'milliseconds' } : mode === 'image' ? { positionUnit: 'image-index' } : { importPolicy: 'isolated-file' }) };
}
const code = (value: string) => (error: unknown) => error instanceof MediaContractError && error.code === value;
const received = new Uint8Array([0x49, 0x44, 0x33, 0x04, 1, 2, 3, 4]); // Authored transfer bytes, not a playable-audio claim.
function observation(extra: Partial<Parameters<typeof recordAssetDownload>[2]> = {}) {
  return { finalUrl: origin + '/part-1', method: 'GET' as const, status: 200,
    responseHeaders: new Headers({ 'content-type': 'audio/mpeg', 'content-length': String(received.length) }),
    received, transportComplete: true, ...extra };
}

test('a complete audio descriptor manifest is not a completed download and offsets remain milliseconds', async () => {
  const parsed = await parseMediaManifest(manifest(), request('audio'), policy);
  assert.equal(parsed.manifestComplete, true);
  assert.equal(allAssetsDownloaded(parsed, []), false);
  assert.equal(isAudioPosition({ unit: 'milliseconds', value: 1234 }), true);
  for (const value of [{ unit: 'characters', value: 1234 }, { unit: 'seconds', value: 1 }, { unit: 'milliseconds', value: -1 }]) {
    assert.equal(isAudioPosition(value), false);
  }
  const receipt = await recordAssetDownload(parsed, 'part-1', observation(), policy);
  assert.equal(receipt.state, 'complete');
  assert.equal(receipt.digestScope, 'whole-asset');
  assert.equal(receipt.receivedBytes, received.length);
  assert.equal(allAssetsDownloaded(parsed, [receipt]), true);
  assert.equal(allAssetsDownloaded(parsed, [JSON.parse(JSON.stringify(receipt))]), false,
    'Deserialized flags require actual native-file revalidation');
});

test('image pages retain first-occurrence order, deduplicate matching descriptors, and need every asset receipt', async () => {
  const pages = [asset('page-b', 'image/png'), asset('page-a', 'image/png'), asset('page-b', 'image/png')];
  const parsed = await parseMediaManifest(manifest('image', pages), request('image'), policy);
  assert.deepEqual(parsed.assets.map(value => [value.id, value.order]), [['page-b', 0], ['page-a', 1]]);
  const receipts = [];
  for (const page of parsed.assets) {
    receipts.push(await recordAssetDownload(parsed, page.id, observation({ finalUrl: page.url,
      responseHeaders: new Headers({ 'content-type': 'image/png', 'content-length': String(received.length) }) }), policy));
  }
  assert.equal(allAssetsDownloaded(parsed, [receipts[0]]), false);
  assert.equal(allAssetsDownloaded(parsed, receipts), true);
  assert.equal(allAssetsDownloaded(parsed, [receipts[0], receipts[0]]), false);
  await assert.rejects(parseMediaManifest(manifest('image', [...pages, { ...pages[0], expectedBytes: 99 }]), request('image'), policy), code('MEDIA_DUPLICATE'));
  await assert.rejects(parseMediaManifest(manifest('image', Array.from({ length: 513 }, (_, index) => asset('page-' + index, 'image/png'))), request('image'), policy), code('MEDIA_ASSETS'));
});

test('unknown modes, empty/partial manifests, wrong identities and unsupported asset types fail closed', async () => {
  await assert.rejects(parseMediaManifest({ ...manifest(), mode: 'text' }, request('audio'), policy), code('MEDIA_MODE'));
  await assert.rejects(parseMediaManifest(manifest('audio', []), request('audio'), policy), code('MEDIA_ASSETS'));
  await assert.rejects(parseMediaManifest({ ...manifest(), manifestComplete: false }, request('audio'), policy), code('MEDIA_INCOMPLETE'));
  await assert.rejects(parseMediaManifest({ ...manifest(), identity: { ...identity, chapterUrl: origin + '/other' } }, request('audio'), policy), code('MEDIA_IDENTITY'));
  await assert.rejects(parseMediaManifest(manifest('audio', [asset('part-1', 'application/vnd.apple.mpegurl')]), request('audio'), policy), code('MEDIA_TYPE'));
  await assert.rejects(parseMediaManifest({ ...manifest(), positionUnit: 'characters' }, request('audio'), policy), code('MEDIA_MODE'));
});

test('URL options, unapproved CDN redirects, private hosts and nonpublic DNS never grant transport', async () => {
  for (const url of [origin + '/part-1,{"method":"POST"}', origin + '/part-1@js:throw 1', origin + '/{{value}}']) {
    await assert.rejects(validatePublicMediaUrl(url, policy), code('MEDIA_URL'));
  }
  for (const url of ['file:///etc/hosts', 'http://127.0.0.1/a', 'https://user:password@owned.example.org/a', 'https://owned.example.org:8080/a', 'https://cdn.example.org/a']) {
    await assert.rejects(validatePublicMediaUrl(url, policy), code('MEDIA_HOST'));
  }
  await assert.rejects(validatePublicMediaUrl(origin + '/a', { ...policy, resolveHost: async () => false }), code('MEDIA_DNS'));
  await assert.rejects(validatePublicMediaUrl(origin + '/a', { approvedHosts: policy.approvedHosts } as MediaUrlPolicy), code('MEDIA_DNS'));
  assert.equal(await validatePublicMediaUrl('https://cdn.example.org/a', { ...policy, approvedHosts: [...policy.approvedHosts, 'cdn.example.org'] }), 'https://cdn.example.org/a');
  const parsed = await parseMediaManifest(manifest(), request('audio'), policy);
  await assert.rejects(recordAssetDownload(parsed, 'part-1', observation({ finalUrl: origin + '/other-asset' }), policy), code('MEDIA_IDENTITY'));
});

test('only explicitly approved public GET headers pass, never cookies, authorization, programs or POST', async () => {
  const publicAsset = { ...asset(), headers: { Referer: origin + '/book', 'User-Agent': 'Owned static media fixture' } };
  const parsed = await parseMediaManifest(manifest('audio', [publicAsset]), request('audio'), policy);
  assert.equal(parsed.assets[0].headers.referer, origin + '/book');
  for (const headers of [{ Cookie: 'not-a-real-cookie' }, { Authorization: 'Bearer fixture' }, { Referer: 'https://other.example.org/' }, { 'X-Source-Program': '@js:fixture' }]) {
    await assert.rejects(parseMediaManifest(manifest('audio', [{ ...asset(), headers }]), request('audio'), policy), code('MEDIA_HEADERS'));
  }
  await assert.rejects(parseMediaManifest(manifest('audio', [{ ...asset(), headers: { 'User-Agent': '@js:fixture' } }]), request('audio'),
    { ...policy, approvedPublicHeaders: { 'user-agent': ['@js:fixture'] } }), code('MEDIA_HEADERS'));
  await assert.rejects(parseMediaManifest(manifest('audio', [{ ...asset(), method: 'POST' }]), request('audio'), policy), code('MEDIA_ASSET'));
});

test('Range fragments, interrupted bodies and inconsistent lengths cannot become whole-asset receipts', async () => {
  const parsed = await parseMediaManifest(manifest('audio', [{ ...asset(), expectedBytes: received.length }]), request('audio'), policy);
  const range = await recordAssetDownload(parsed, 'part-1', observation({ status: 206, requestHadRange: true,
    responseHeaders: new Headers({ 'content-type': 'audio/mpeg', 'content-length': String(received.length), 'content-range': 'bytes 0-7/800' }) }), policy);
  assert.equal(range.state, 'partial'); assert.equal(range.digestScope, 'received-bytes');
  assert.equal(allAssetsDownloaded(parsed, [range]), false);
  assert.equal(allAssetsDownloaded(parsed, [{ ...range, state: 'complete', digestScope: 'whole-asset' }]), false);
  const interrupted = await recordAssetDownload(parsed, 'part-1', observation({ transportComplete: false }), policy);
  assert.equal(interrupted.state, 'partial');
  const short = await recordAssetDownload(parsed, 'part-1', observation({ responseHeaders: new Headers({ 'content-type': 'audio/mpeg', 'content-length': '800' }) }), policy);
  assert.equal(short.state, 'partial');
  const expectedDigest = await parseMediaManifest(manifest('audio', [{ ...asset(), expectedSha256: '0'.repeat(64) }]), request('audio'), policy);
  await assert.rejects(recordAssetDownload(expectedDigest, 'part-1', observation(), policy), code('MEDIA_DIGEST'));
});

test('download files require a matching format/MIME and an isolated filename rather than a fake chapter', async () => {
  const file = { ...asset('download', 'text/plain'), format: 'txt', fileName: 'owned-fixture.txt' };
  const parsed = await parseMediaManifest(manifest('download', [file]), request('download'), policy);
  assert.equal(parsed.mode, 'download'); assert.equal(parsed.identity.chapterUrl, undefined);
  for (const invalid of [{ ...file, fileName: '../outside.txt' }, { ...file, format: 'pdf' }, { ...file, mimeType: 'text/html' }]) {
    await assert.rejects(parseMediaManifest(manifest('download', [invalid]), request('download'), policy), code('MEDIA_TYPE'));
  }
  await assert.rejects(parseMediaManifest({ ...manifest('download', [file]), identity }, request('download'), policy), code('MEDIA_IDENTITY'));
  await assert.rejects(parseMediaManifest({ ...manifest('download', [file]), importPolicy: 'replace-book' }, request('download'), policy), code('MEDIA_MODE'));
});
