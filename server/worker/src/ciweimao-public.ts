import * as cheerio from 'cheerio';

/** Reviewed normal guest reading protocol. Never executes imported site/source scripts. */
export class CiweimaoPublicError extends Error {
  constructor(public code: string, message: string) { super(message); }
}

export type CiweimaoFreeContext = Readonly<{
  chapterId: string; bookId: string; chapterUrl: string;
  anonymous: true; isPaid: 0; imageMode: false;
  declaredWordCount?: number;
}>;

function deny(code: string, message: string): never { throw new CiweimaoPublicError(code, message); }
const MAX_CONTEXT_HTML = 2 * 1024 * 1024;
const MAX_CIPHER_CHARS = 2 * 1024 * 1024;

/** A small literal-only parser: no eval, functions, property access or assignments. */
function flatObject(input: string): Record<string, unknown> {
  let position = 0;
  const result: Record<string, unknown> = Object.create(null);
  const space = () => { while (/\s/.test(input[position] || '') && position < input.length) position++; };
  function quoted(): string {
    const quote = input[position++];
    let value = '';
    while (position < input.length) {
      const character = input[position++];
      if (character === quote) return value;
      if (character !== '\\') { value += character; continue; }
      const escaped = input[position++];
      const simple: Record<string, string> = { n: '\n', r: '\r', t: '\t', b: '\b', f: '\f', v: '\v', '0': '\0' };
      if (escaped === 'x' || escaped === 'u') {
        const length = escaped === 'x' ? 2 : 4;
        const digits = input.slice(position, position + length);
        if (!new RegExp(`^[0-9a-f]{${length}}$`, 'i').test(digits)) deny('PUBLIC_CHAPTER_CONTEXT', 'Invalid chapter context escape');
        value += String.fromCharCode(parseInt(digits, 16)); position += length;
      } else value += simple[escaped] ?? escaped;
    }
    return deny('PUBLIC_CHAPTER_CONTEXT', 'Unterminated chapter context string');
  }
  space();
  if (input[position++] !== '{') deny('PUBLIC_CHAPTER_CONTEXT', 'Chapter context must be a literal object');
  while (true) {
    space();
    if (input[position] === '}') { position++; break; }
    let key: string;
    if (input[position] === '"' || input[position] === "'") key = quoted();
    else {
      const match = /^[A-Za-z_$][\w$]*/.exec(input.slice(position));
      if (!match) deny('PUBLIC_CHAPTER_CONTEXT', 'Unsupported chapter context key');
      key = match![0]; position += key.length;
    }
    if (['__proto__', 'constructor', 'prototype'].includes(key) || Object.hasOwn(result, key)) {
      deny('PUBLIC_CHAPTER_CONTEXT', 'Ambiguous chapter context key');
    }
    space();
    if (input[position++] !== ':') deny('PUBLIC_CHAPTER_CONTEXT', 'Invalid chapter context property');
    space();
    let value: unknown;
    if (input[position] === '"' || input[position] === "'") value = quoted();
    else {
      const match = /^(?:-?\d+(?:\.\d+)?|true|false|null)(?![\w$.])/.exec(input.slice(position));
      if (!match) deny('PUBLIC_CHAPTER_CONTEXT', 'Chapter context contains an executable expression');
      value = JSON.parse(match![0]); position += match![0].length;
    }
    result[key] = value;
    space();
    if (input[position] === ',') { position++; continue; }
    if (input[position] === '}') { position++; break; }
    deny('PUBLIC_CHAPTER_CONTEXT', 'Unsupported chapter context syntax');
  }
  space();
  if (position !== input.length) deny('PUBLIC_CHAPTER_CONTEXT', 'Unexpected chapter context suffix');
  return result;
}

function assignment(scripts: string, name: 'book' | 'userinfo'): Record<string, unknown> {
  const pattern = new RegExp(`\\bHB\\.${name}\\s*=\\s*\\{`, 'g');
  const values: Record<string, unknown>[] = [];
  for (let match; (match = pattern.exec(scripts));) {
    const start = pattern.lastIndex - 1;
    let quote = '', escaped = false, depth = 0, end = start;
    for (; end < scripts.length && end - start <= 16_384; end++) {
      const character = scripts[end];
      if (quote) {
        if (escaped) escaped = false;
        else if (character === '\\') escaped = true;
        else if (character === quote) quote = '';
      } else if (character === '"' || character === "'") quote = character;
      else if (character === '{') depth++;
      else if (character === '}' && --depth === 0) { end++; break; }
    }
    if (depth !== 0 || end - start > 16_384) deny('PUBLIC_CHAPTER_CONTEXT', 'Chapter context exceeds its literal limit');
    values.push(flatObject(scripts.slice(start, end)));
    pattern.lastIndex = end;
  }
  if (values.length !== 1) deny('PUBLIC_CHAPTER_CONTEXT', 'A unique official chapter context is required');
  return values[0];
}

/** Bind permission to the fetched official page, never to client-supplied isVip flags. */
export function parseCiweimaoFreeChapter(html: string, chapterUrl: string): CiweimaoFreeContext {
  let url: URL;
  try { url = new URL(chapterUrl); } catch { return deny('PUBLIC_CHAPTER_URL', 'Invalid public chapter URL'); }
  const match = /^\/chapter\/([1-9]\d*)\/?$/.exec(url.pathname);
  if (url.protocol !== 'https:' || url.hostname !== 'www.ciweimao.com' || url.port || url.username || url.password || !match) {
    deny('PUBLIC_CHAPTER_URL', 'Only the reviewed official public chapter endpoint is supported');
  }
  if (typeof html !== 'string' || html.length > MAX_CONTEXT_HTML) deny('PUBLIC_CHAPTER_CONTEXT', 'Chapter page is unavailable or too large');
  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)]
    .filter(item => !/\bsrc\s*=/.test(item[1])).map(item => item[2]).join('\n');
  const book = assignment(scripts, 'book');
  const user = assignment(scripts, 'userinfo');
  if (book.is_paid !== 0) deny('PUBLIC_CHAPTER_LOCKED', 'This adapter reads explicitly free chapters only');
  if (user.reader_id !== 0) deny('PUBLIC_CHAPTER_AUTH', 'A fresh anonymous reading context is required');
  const modes = [...scripts.matchAll(/\bHB\.config\.bookread\.useImage\s*=\s*(\d+)\s*;/g)];
  if (modes.length !== 1 || modes[0][1] !== '0') deny('PUBLIC_CHAPTER_MODE', 'This page does not expose the normal text reading mode');
  if (!Number.isSafeInteger(book.chapter_id) || !Number.isSafeInteger(book.book_id)
      || Number(book.book_id) <= 0 || String(book.chapter_id) !== match![1]) {
    deny('PUBLIC_CHAPTER_IDENTITY', 'The requested chapter does not match the official page');
  }
  const document = cheerio.load(html);
  const declared = /(?:字数|字數)[^0-9]{0,8}([0-9][0-9,]{0,9})/.exec(document('.read-hd').text());
  const wordCount = declared ? Number(declared[1].replaceAll(',', '')) : undefined;
  return Object.freeze({ chapterId: String(book.chapter_id), bookId: String(book.book_id), chapterUrl: url.href,
    anonymous: true, isPaid: 0, imageMode: false,
    ...(Number.isSafeInteger(wordCount) && Number(wordCount) > 0 ? { declaredWordCount: wordCount } : {}) });
}

function fromBase64(value: unknown, maximum: number): Uint8Array<ArrayBuffer> {
  if (typeof value !== 'string' || !value || value.length > maximum || value.length % 4 !== 0
      || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) deny('PUBLIC_CHAPTER_CIPHER', 'Invalid public chapter frame');
  let binary: string;
  try { binary = atob(value); } catch { return deny('PUBLIC_CHAPTER_CIPHER', 'Invalid public chapter frame'); }
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

/** Reimplement the site's normal two-layer guest display format using WebCrypto. */
export async function decodeCiweimaoFreeChapter(context: CiweimaoFreeContext, response: unknown,
                                               accessKey: string): Promise<string> {
  if (context?.isPaid !== 0 || context?.anonymous !== true || context?.imageMode !== false) {
    deny('PUBLIC_CHAPTER_AUTH', 'Verified anonymous free-chapter context is required');
  }
  const body = response as Record<string, unknown> | null;
  if (!body || body.code !== 100000) deny('PUBLIC_CHAPTER_DENIED', 'The public reading endpoint did not grant this request');
  const keys = body.encryt_keys;
  if (!Array.isArray(keys) || keys.length < 1 || keys.length > 64 || typeof accessKey !== 'string'
      || !/^[\x21-\x7e]{1,256}$/.test(accessKey)) deny('PUBLIC_CHAPTER_CIPHER', 'Invalid public reading key metadata');
  const keyOrder = [accessKey.charCodeAt(accessKey.length - 1) % keys.length,
                    accessKey.charCodeAt(0) % keys.length];
  let encoded: unknown = body.chapter_content;
  for (const index of keyOrder) {
    const frame = fromBase64(encoded, MAX_CIPHER_CHARS);
    if (frame.length < 32) deny('PUBLIC_CHAPTER_CIPHER', 'Public chapter frame is incomplete');
    const iv = frame.slice(0, 16);
    let cipher = frame.slice(16);
    // Match the public OpenSSL-format decoder; a raw-key frame may include its header.
    if (cipher.length >= 16 && new TextDecoder().decode(cipher.slice(0, 8)) === 'Salted__') cipher = cipher.slice(16);
    if (!cipher.length || cipher.length % 16 !== 0) deny('PUBLIC_CHAPTER_CIPHER', 'Public chapter frame is incomplete');
    const rawKey = fromBase64(keys[index], 128);
    if (![16, 24, 32].includes(rawKey.length)) deny('PUBLIC_CHAPTER_CIPHER', 'Unsupported public display key length');
    try {
      const key = await crypto.subtle.importKey('raw', rawKey, { name: 'AES-CBC' }, false, ['decrypt']);
      const clear = await crypto.subtle.decrypt({ name: 'AES-CBC', iv }, key, cipher);
      encoded = new TextDecoder('utf-8', { fatal: true }).decode(clear);
    } catch { return deny('PUBLIC_CHAPTER_CIPHER', 'The public chapter frame could not be decoded'); }
  }
  if (typeof encoded !== 'string' || !encoded.trim()) deny('EMPTY_CONTENT', 'The free chapter contains no reading text');
  return encoded;
}

/**
 * The official reading stylesheet renders uniform per-paragraph marker spans
 * at low opacity. They are presentation marks, excluded from the publisher's
 * chapter word count. Remove only that reviewed format, never arbitrary spans
 * or copyright notices. Unexpected markup stays unaccepted rather than lost.
 */
export function extractCiweimaoFreeText(context: CiweimaoFreeContext, html: string): {
  text: string; paragraphs: number; normalisedCharacters: number; removedMarkerCharacters: number;
} {
  if (context?.isPaid !== 0 || context?.anonymous !== true || context?.imageMode !== false) {
    deny('PUBLIC_CHAPTER_AUTH', 'Verified anonymous free-chapter context is required');
  }
  if (typeof html !== 'string' || html.length > MAX_CIPHER_CHARS) deny('PUBLIC_CHAPTER_FORMAT', 'Invalid free chapter markup');
  const document = cheerio.load(html);
  document('script,style,iframe').remove();
  const paragraphs = document('p.chapter').toArray();
  if (!paragraphs.length) deny('PUBLIC_CHAPTER_FORMAT', 'The reviewed chapter paragraph structure is missing');
  const marks: Array<{ element: typeof paragraphs[number]; text: string }> = [];
  for (const paragraph of paragraphs) {
    const spans = document(paragraph).find('span').toArray();
    if (spans.length > 1) deny('PUBLIC_CHAPTER_FORMAT', 'Unreviewed inline chapter markup');
    for (const span of spans) {
      const text = document(span).text();
      if (span.parent !== paragraph || Object.keys(span.attribs).length || !/^[A-Za-z0-9]{6}$/.test(text)
          || document(span).nextAll().text().trim() || document(span).contents().toArray().some(node => node.type !== 'text')) {
        deny('PUBLIC_CHAPTER_FORMAT', 'Unreviewed inline chapter markup');
      }
      const following = paragraph.children.slice(paragraph.children.indexOf(span) + 1);
      if (following.some(node => node.type === 'text' && node.data.trim())) deny('PUBLIC_CHAPTER_FORMAT', 'Marker position differs from the reviewed format');
      marks.push({ element: span, text });
    }
  }
  if (marks.length && (marks.length !== paragraphs.length || new Set(marks.map(mark => mark.text)).size !== 1)) {
    deny('PUBLIC_CHAPTER_FORMAT', 'Paragraph markers differ from the reviewed format');
  }
  for (const mark of marks) document(mark.element).remove();
  const parts = paragraphs.map(paragraph => document(paragraph).text().trim()).filter(Boolean);
  const text = parts.join('\n');
  const normalisedCharacters = Array.from(text.replace(/\s/gu, '')).length;
  if (!text) deny('EMPTY_CONTENT', 'The free chapter contains no reading text');
  if (context.declaredWordCount !== undefined && normalisedCharacters !== context.declaredWordCount) {
    deny('PUBLIC_CHAPTER_INCOMPLETE', 'Reading text does not match the publisher chapter word count');
  }
  return { text, paragraphs: parts.length, normalisedCharacters,
    removedMarkerCharacters: marks.reduce((total, mark) => total + mark.text.length, 0) };
}
