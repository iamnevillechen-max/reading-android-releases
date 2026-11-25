import { authorizeRequest } from './authorization';
import type { Env } from './index';

export const SOURCE_RUNTIME_NAME = 'reader-source-runtime-v1';
const SCHEDULE_PATH = '/_reader-runtime/scheduled';
const SCHEDULE_ORIGIN = 'https://reader-runtime.internal';
export type RuntimeEnv = Env & { SOURCE_RUNTIME: DurableObjectNamespace };
type RuntimeApp = {
  fetch(request: Request, env: Env): Promise<Response>;
  scheduled(controller: ScheduledController, env: Env, context: ExecutionContext): Promise<void>;
};
function unavailable(): Response {
  return new Response(JSON.stringify({ error: { code: 'UNAVAILABLE', message: 'Service is temporarily unavailable' } }), {
    status: 503, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}
function notFound(): Response {
  return new Response(JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Endpoint not found' } }), {
    status: 404, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}
function stub(env: RuntimeEnv): DurableObjectStub {
  return env.SOURCE_RUNTIME.get(env.SOURCE_RUNTIME.idFromName(SOURCE_RUNTIME_NAME));
}
/** The public Worker never reads API request/response bodies or runs a source. */
export function createGateway() {
  return {
    async fetch(request: Request, env: RuntimeEnv): Promise<Response> {
      const url = new URL(request.url), path = url.pathname.replace(/\/$/, '') || '/';
      if (path === '/health' && request.method === 'GET') return new Response('ok', { headers: { 'Content-Type': 'text/plain' } });
      const role = authorizeRequest(request, env); if (role instanceof Response) return role;
      if (path === SCHEDULE_PATH) return notFound();
      try { return await stub(env).fetch(request); } catch { return unavailable(); }
    },
    async scheduled(controller: ScheduledController, env: RuntimeEnv): Promise<void> {
      if (env.MAINTENANCE_ENABLED !== 'true') return;
      if (!env.ADMIN_TOKEN || env.ADMIN_TOKEN.length < 32) throw new Error('Scheduled runtime authentication unavailable');
      const response = await stub(env).fetch(new Request(SCHEDULE_ORIGIN + SCHEDULE_PATH, {
        method: 'POST', headers: { Authorization: 'Bearer ' + env.ADMIN_TOKEN,
          'X-Reader-Cron': controller.cron, 'X-Reader-Scheduled-Time': String(controller.scheduledTime) },
      }));
      if (!response.ok) { await response.body?.cancel(); throw new Error('Scheduled source runtime unavailable'); }
      await response.body?.cancel();
    },
  };
}
/** Shared by the real DO and Node fixtures; contains no Cloudflare module import. */
export function createRuntimeReceiver(app: RuntimeApp, env: Env, context: ExecutionContext) {
  return {
    async fetch(request: Request): Promise<Response> {
      const url = new URL(request.url), path = url.pathname.replace(/\/$/, '') || '/';
      if (path !== SCHEDULE_PATH) return app.fetch(request, env);
      const role = authorizeRequest(request, env); if (role instanceof Response) return role;
      if (url.origin !== SCHEDULE_ORIGIN || request.method !== 'POST' || role !== 'admin') return notFound();
      const scheduledTime = Number(request.headers.get('X-Reader-Scheduled-Time'));
      if (!Number.isSafeInteger(scheduledTime) || scheduledTime < 0) return notFound();
      const controller = { scheduledTime, cron: request.headers.get('X-Reader-Cron') || '', noRetry() {} } as ScheduledController;
      await app.scheduled(controller, env, context);
      return new Response(null, { status: 204 });
    },
  };
}
