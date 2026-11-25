const eventNames = new Set([
  "app_started", "reader_resumed", "reader_paused", "narration_started", "narration_stopped",
  "screen_off", "screen_on", "discovery_opened", "subscriptions_opened", "source_archived", "source_restored",
]);
const uuid = /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/;
const version = /^\d{1,3}\.\d{1,3}\.\d{1,3}$/;
const symbol = /^(?:io\.legado\.app|android|androidx|java|kotlin|kotlinx|okhttp3|org\.chromium)\.[a-zA-Z0-9_.$]+$/;
const frame = /^(?:io\.legado\.app|android|androidx|java|kotlin|kotlinx|okhttp3|org\.chromium)\.[a-zA-Z0-9_.$]+\.[a-zA-Z0-9_$<>-]+:(?:-1|\d{1,6})$/;
const receiptPattern = /^(?:[\da-f]{8}|[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12})$/;
const limit = 24 * 1024;
const headers = {
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
};
const json = (body, status, extra) => Response.json(body, { status, headers: { ...headers, ...extra } });

function keys(value, allowed) {
  return value && typeof value === "object" && !Array.isArray(value) &&
    Object.keys(value).every((key) => allowed.includes(key));
}
function bounded(value, max, test) {
  return Array.isArray(value) && value.length <= max && value.every(test);
}
function timestamp(value) {
  return Number.isSafeInteger(value) && value >= 0 && value <= 9999999999999;
}
function pair(row, test) {
  return Array.isArray(row) && row.length === 2 && timestamp(row[0]) && test(row[1]);
}
function code(value, regex, max) {
  return typeof value === "string" && value.length <= max && regex.test(value);
}
export function normalize(body) {
  if (!body || body.schema !== 1 || !code(body.id, uuid, 36)) return null;
  if (body.source === "dot") {
    if (!keys(body, ["schema", "id", "source", "version", "problem", "receipt"]) ||
        (body.version !== "unknown" && !code(body.version, version, 11)) ||
        typeof body.problem !== "string" || !body.problem.trim() || body.problem.length > 3000 ||
        (body.receipt != null && !code(body.receipt, receiptPattern, 36))) return null;
    return { version: body.version, problem: body.problem.trim(), receipt: body.receipt || null };
  }
  if (body.source !== "android" || !keys(body, ["schema", "id", "source", "version", "api", "events", "exits", "crash"]) ||
      !code(body.version, version, 11) || !Number.isInteger(body.api) || body.api < 21 || body.api > 200 ||
      !bounded(body.events, 48, (row) => pair(row, (s) => eventNames.has(s))) ||
      !bounded(body.exits, 4, (row) => pair(row, (n) => Number.isInteger(n) && n >= 0 && n <= 255))) return null;
  let crash = null;
  if (body.crash != null) {
    const c = body.crash;
    if (!keys(c, ["at", "version", "types", "frames"]) || !timestamp(c.at) ||
        !code(c.version, version, 11) || !bounded(c.types, 4, (s) => code(s, symbol, 180)) ||
        !bounded(c.frames, 20, (s) => code(s, frame, 300))) return null;
    crash = { at: c.at, version: c.version, types: c.types, frames: c.frames };
  }
  return { version: body.version, api: body.api, events: body.events, exits: body.exits, crash };
}

function page(title, inner, status = 200) {
  return new Response(`<!doctype html><html lang="zh-Hans"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title} · 阅读</title>
<style>body{font:1.125rem/1.65 system-ui,sans-serif;color:#182628;background:#fafcfb;margin:0}main{max-width:34rem;margin:5vh auto;padding:1.25rem}h1{font-size:1.8rem}label{display:block;margin:1.1rem 0 .3rem}input,textarea,button{box-sizing:border-box;font:inherit;border:1px solid #596865;border-radius:.35rem;padding:.55rem;width:100%}textarea{min-height:8rem}button{margin:1.4rem 0;background:#175e50;color:white;cursor:pointer}a{color:#125a4c}:focus-visible{outline:3px solid #2453ac;outline-offset:3px}small{font-size:.95rem}</style>
<main><h1>${title}</h1>${inner}</main></html>`, { status, headers: { ...headers, "Content-Type": "text/html; charset=utf-8" } });
}
function form() {
  return page("阅读问题反馈", `<p>把遇到的问题留给维护者。可以请 ChatGPT Dot 代填。</p>
<form method="post" action="/api/v1/reports"><input type="hidden" name="id" value="${crypto.randomUUID()}">
<label for="problem">遇到了什么问题？</label><textarea id="problem" name="problem" required maxlength="3000"></textarea>
<label for="receipt">日志回执（选填）</label><input id="receipt" name="receipt" maxlength="36" autocapitalize="none" spellcheck="false">
<button type="submit">提交给阅读项目</button></form><a href="/dot.json">Dot 提交说明</a>`);
}
const instructions = {
  project: "阅读 · Android（多书源拼接版）", method: "POST", path: "/api/v1/reports", contentType: "application/json",
  fields: {schema: 1, source: "dot", id: "随机 UUID v4。同一次提交重试沿用同一 ID 和正文。", version: "如 1.2.9，未知用 unknown",
    problem: "用用户自己的描述记录现象、操作经过和预期结果，最多 3000 字。", receipt: "可选。手机上传日志后给出的 8 位回执，或完整 UUID。"},
  result: "收到 201 或 200，且 id 与提交相同才算入库。仅能浏览网页时可用首页表单。请向用户读回回执。",
};

async function readLimited(request) {
  if (Number(request.headers.get("Content-Length") || 0) > limit) return null;
  if (!request.body) return "";
  const reader = request.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0, value = "";
  for (;;) {
    const chunk = await reader.read();
    if (chunk.done) return value + decoder.decode();
    size += chunk.value.length;
    if (size > limit) { await reader.cancel(); return null; }
    value += decoder.decode(chunk.value, { stream: true });
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/" && request.method === "GET") return form();
    if (url.pathname === "/dot.json" && request.method === "GET") return json(instructions, 200);
    if (url.pathname !== "/api/v1/reports") return json({ error: "not_found" }, 404);
    if (request.method !== "POST") return json({ error: "write_only" }, 405, { Allow: "POST" });
    const type = (request.headers.get("Content-Type") || "").split(";")[0].trim().toLowerCase();
    const browser = type === "application/x-www-form-urlencoded";
    if (!browser && type !== "application/json") return json({ error: "content_type" }, 415);
    const origin = request.headers.get("Origin");
    if (browser && origin && origin !== url.origin) return json({ error: "origin" }, 403);
    const reject = (status, message) => browser
      ? page("反馈尚未提交", `<p>${message}</p><p>返回上一页可继续编辑。</p>`, status)
      : json({ error: message }, status, status === 429 ? { "Retry-After": "60" } : {});
    let body;
    try {
      const raw = await readLimited(request);
      if (raw == null) return reject(413, "内容过长");
      if (browser) {
        const fields = new URLSearchParams(raw);
        body = { schema: 1, source: "dot", version: "unknown", id: fields.get("id"),
          problem: fields.get("problem"), receipt: fields.get("receipt")?.trim().toLowerCase() || null };
      } else { body = JSON.parse(raw); }
    } catch { return reject(400, "格式有误"); }
    const value = normalize(body);
    if (!value) return reject(400, "请检查反馈内容和日志回执");
    try {
      const { success } = await env.INTAKE_LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") || "local" });
      if (!success) return reject(429, "请稍后重试");
      const payload = JSON.stringify(value);
      const entry = await env.DB.prepare("INSERT INTO reports (id, source, version, payload) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO NOTHING")
        .bind(body.id, body.source, value.version, payload).run();
      const created = entry.meta.changes === 1;
      if (!created) {
        const old = await env.DB.prepare("SELECT source, payload FROM reports WHERE id = ?").bind(body.id).first();
        if (old?.source !== body.source || old?.payload !== payload) return reject(409, "回执编号已被使用");
      }
      const status = created ? 201 : 200;
      return browser ? page("反馈已收到", `<p>阅读项目的回执编号为 <strong>${body.id.slice(0, 8)}</strong>。</p>`, status)
        : json({ id: body.id, receipt: body.id.slice(0, 8) }, status);
    } catch { return reject(503, "暂时无法保存，请稍后重试"); }
  }
};
