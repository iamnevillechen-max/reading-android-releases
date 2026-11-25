/** Ephemeral cookies received only during one reviewed, anonymous same-origin flow. */
export class PublicGuestSessionError extends Error {
  readonly code = 'PUBLIC_GUEST_SESSION';
  readonly status = 502;
}
type Cookie = { name: string; value: string; domain: string; path: string; expires: number; order: number };
const fail = (): never => { throw new PublicGuestSessionError('公開訪客連線的 Cookie 範圍或大小無效'); };
export class PublicGuestCookies {
  private readonly origin: string;
  private readonly cookies = new Map<string, Cookie>();
  private sequence = 0;
  constructor(origin: string) {
    const url = new URL(origin);
    if (url.protocol !== 'https:' || url.username || url.password || url.port || url.pathname !== '/' || url.search || url.hash) fail();
    this.origin = url.origin;
  }
  private url(value: string): URL {
    const url = new URL(value);
    if (url.origin !== this.origin || url.username || url.password) fail();
    return url;
  }
  receive(headers: Headers, responseUrl: string): void {
    const url = this.url(responseUrl), values = (headers as Headers & { getSetCookie?: () => string[]; getAll?: (name: string) => string[] });
    const setCookies = values.getSetCookie?.() || values.getAll?.('Set-Cookie') ||
      (headers.get('set-cookie') || '').split(/,(?=\s*[!#$%&'*+.^_`|~\w-]+=)/).filter(Boolean);
    if (setCookies.length > 24) fail();
    for (const line of setCookies) {
      if (line.length > 4096 || /[\u0000-\u001f\u007f]/.test(line)) fail();
      const [first, ...attributes] = line.split(';'), equal = first.indexOf('=');
      const name = first.slice(0, equal).trim();
      let value = first.slice(equal + 1).trim();
      if (value.startsWith('"') && value.endsWith('"')) value = value.slice(1, -1);
      if (equal < 1 || !/^[!#$%&'*+.^_`|~\w-]+$/.test(name) || !/^[\x21-\x7e]*$/.test(value) || /[,;"\\]/.test(value)) fail();
      let domain = url.hostname, path = url.pathname.slice(0, url.pathname.lastIndexOf('/') + 1) || '/', expires = Infinity;
      let maxAge: number | undefined;
      for (const attribute of attributes) {
        const split = attribute.indexOf('='), key = (split < 0 ? attribute : attribute.slice(0, split)).trim().toLowerCase();
        const field = split < 0 ? '' : attribute.slice(split + 1).trim();
        if (key === 'domain') {
          domain = field.toLowerCase().replace(/^\./, '');
          if (domain.split('.').length < 2 || !(domain === url.hostname || url.hostname.endsWith('.' + domain))) fail();
        } else if (key === 'path') { if (!field.startsWith('/') || field.length > 2048) fail(); path = field; }
        else if (key === 'max-age') { if (!/^-?\d+$/.test(field)) fail(); maxAge = Number(field); }
        else if (key === 'expires') { const date = Date.parse(field); if (Number.isFinite(date)) expires = date; }
      }
      if (maxAge !== undefined) expires = maxAge <= 0 ? 0 : Date.now() + Math.min(maxAge, 86400) * 1000;
      const key = JSON.stringify([name, domain, path]);
      if (expires <= Date.now()) this.cookies.delete(key);
      else this.cookies.set(key, { name, value, domain, path, expires, order: this.cookies.get(key)?.order ?? ++this.sequence });
      this.prune();
      if (this.cookies.size > 24 || [...this.cookies.values()].reduce((sum, cookie) => sum + cookie.name.length + cookie.value.length + 2, 0) > 8192) fail();
    }
  }
  private prune(): void {
    for (const [key, cookie] of this.cookies) if (cookie.expires <= Date.now()) this.cookies.delete(key);
  }
  header(requestUrl: string): string {
    const url = this.url(requestUrl); this.prune();
    return [...this.cookies.values()].filter(cookie => url.pathname === cookie.path ||
      url.pathname.startsWith(cookie.path.endsWith('/') ? cookie.path : cookie.path + '/'))
      .sort((left, right) => right.path.length - left.path.length || left.order - right.order)
      .map(cookie => cookie.name + '=' + cookie.value).join('; ');
  }
}
