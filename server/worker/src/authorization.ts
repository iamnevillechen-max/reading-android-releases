export type AuthRole = 'admin' | 'migration' | 'read';
export type AuthTokens = { ADMIN_TOKEN: string; MIGRATION_TOKEN: string; READ_TOKEN: string };
function matches(actual: string, expected: string | undefined): boolean {
  if (!expected || expected.length < 32 || actual.length !== expected.length) return false;
  let difference = 0;
  for (let index = 0; index < actual.length; index++) difference |= actual.charCodeAt(index) ^ expected.charCodeAt(index);
  return difference === 0;
}
function denied(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({ error: { code, message } }), {
    status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}
/** Shared by the thin gateway and the application inside the private runtime. */
export function authorizeRequest(request: Request, tokens: AuthTokens): AuthRole | Response {
  if (request.headers.has('Origin')) return denied(403, 'ORIGIN_DENIED', 'Browser cross-origin access is disabled');
  const header = request.headers.get('Authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (matches(token, tokens.ADMIN_TOKEN)) return 'admin';
  if (matches(token, tokens.MIGRATION_TOKEN)) return 'migration';
  if (matches(token, tokens.READ_TOKEN)) return 'read';
  return denied(401, 'UNAUTHORIZED', 'Authentication required');
}
