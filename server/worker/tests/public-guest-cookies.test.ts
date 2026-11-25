import test from 'node:test';
import assert from 'node:assert/strict';
import { PublicGuestCookies, PublicGuestSessionError } from '../src/public-guest-cookies';
const origin = 'https://www.owned.example.org';

test('anonymous guest jars are fresh, same-origin, path scoped and expire independently', () => {
  const jar = new PublicGuestCookies(origin), fresh = new PublicGuestCookies(origin);
  const headers = new Headers();
  headers.append('Set-Cookie', 'owned_guest=fixture-only; Domain=.owned.example.org; Path=/; Secure; HttpOnly');
  headers.append('Set-Cookie', 'owned_chapter=fixture-chapter; Path=/chapter; Secure');
  jar.receive(headers, origin + '/chapter/1');
  assert.equal(jar.header(origin + '/chapter/session'), 'owned_chapter=fixture-chapter; owned_guest=fixture-only');
  assert.equal(jar.header(origin + '/search'), 'owned_guest=fixture-only');
  assert.equal(fresh.header(origin + '/chapter/session'), '');
  jar.receive(new Headers({ 'Set-Cookie': 'owned_chapter=; Path=/chapter; Max-Age=0' }), origin + '/chapter/session');
  assert.equal(jar.header(origin + '/chapter/session'), 'owned_guest=fixture-only');
  assert.throws(() => jar.header('https://other.example.org/chapter/session'), PublicGuestSessionError);
});

test('combined cookie headers retain Expires commas and reject foreign domains, control bytes and oversized jars', () => {
  const jar = new PublicGuestCookies(origin);
  const headers = { get: () => 'a=fixture-a; Expires=Wed, 21 Oct 2099 07:28:00 GMT; Path=/, b=fixture-b; Path=/' } as unknown as Headers;
  jar.receive(headers, origin + '/chapter/1');
  assert.equal(jar.header(origin + '/chapter/session'), 'a=fixture-a; b=fixture-b');
  for (const value of ['a=fixture; Domain=other.example.org', 'a=fixture; Domain=org', 'a=' + 'x'.repeat(4096)]) {
    assert.throws(() => new PublicGuestCookies(origin).receive(new Headers({ 'Set-Cookie': value }), origin + '/chapter/1'), PublicGuestSessionError);
  }
  const tooMany = new Headers();
  for (let index = 0; index < 25; index++) tooMany.append('Set-Cookie', `a${index}=fixture; Path=/`);
  assert.throws(() => new PublicGuestCookies(origin).receive(tooMany, origin + '/chapter/1'), PublicGuestSessionError);
  assert.throws(() => jar.receive(new Headers(), 'http://www.owned.example.org/chapter/1'), PublicGuestSessionError);
});
