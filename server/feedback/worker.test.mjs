import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import worker from "./worker.mjs";

const base = "https://reader.test";
const route = base + "/api/v1/reports";
function fixture() {
  const sql = new DatabaseSync(":memory:");
  sql.exec(readFileSync(new URL("schema.sql", import.meta.url), "utf8"));
  const env = { DB: { prepare(query) {
    const stmt = sql.prepare(query);
    return { bind(...values) { return {
      run: () => ({ meta: stmt.run(...values) }), first: () => stmt.get(...values),
    }; } };
  } }, INTAKE_LIMIT: { limit: () => ({ success: true }) } };
  const send = (body) => worker.fetch(new Request(route, {method: "POST", headers: {"Content-Type":"application/json"}, body: JSON.stringify(body)}), env);
  return { sql, env, send };
}
const dot = () => ({ id: crypto.randomUUID(), schema: 1, source: "dot", version: "unknown", problem: "锁屏听书后亮屏会退出。" });
const android = () => ({ id: crypto.randomUUID(), schema: 1, source: "android", version: "1.2.9", api: 35,
  events: [[123,"reader_resumed"],[125,"screen_off"]], exits: [[136,4]], crash: { at: 135, version: "1.2.9",
    types: ["java.lang.IllegalStateException"], frames: ["io.legado.app.Reader.draw:123"] } });

test("both incoming formats are saved once, replies only include a receipt", async () => {
  const { send, sql } = fixture();
  for (const sample of [android(), { ...dot(), receipt: "ac001234" }]) {
    const reply = await send(sample);
    assert.equal(reply.status, 201);
    assert.deepEqual(await reply.json(), { id: sample.id, receipt: sample.id.slice(0,8) });
    assert.equal((await send(sample)).status, 200);
    assert.equal((await send({ ...sample, version: "1.2.8" })).status, 409);
  }
  assert.equal(sql.prepare("SELECT count(*) AS n FROM reports WHERE status='open'").get().n,2);
});
test("mobile only accepts code symbols and bounded event names", async () => {
  const { send, sql } = fixture();
  const a = android();
  for (const patch of [{ url: "https://private.test/" }, { events: [[1,"My book"]] }, {events: Array(49).fill([1,"screen_off"])},
      {crash:{...a.crash,frames:["my secret"]}}, {crash:{...a.crash,message:"private"}}, {exits:[[-1,4]]}]) {
    assert.equal((await send({...a,...patch})).status,400);
  }
  assert.equal(sql.prepare("SELECT count(*) AS n FROM reports").get().n,0);
});
test("Dot form works without scripts and with a duplicate or invalid submission", async () => {
  const { env } = fixture();
  const text = await (await worker.fetch(new Request(base), env)).text();
  const id = text.match(/name="id" value="([a-f0-9-]+)"/)[1];
  const form = (problem) => worker.fetch(new Request(route, {method:"POST", body: new URLSearchParams({id,problem})}), env);
  const receipt = await form("听书中断"); assert.equal(receipt.status,201); assert.match(await receipt.text(),new RegExp(id.slice(0,8)));
  assert.equal((await form("听书中断")).status,200);
  assert.equal((await form("")).status,400);
});
test("records cannot be listed, oversized or rate-limited requests are not saved", async () => {
  const { env, send, sql } = fixture();
  assert.equal((await worker.fetch(new Request(route),env)).status,405);
  assert.equal((await worker.fetch(new Request(route+"/example"),env)).status,404);
  assert.equal((await send({...dot(),problem:"x".repeat(25000)})).status,413);
  env.INTAKE_LIMIT.limit = () => ({success:false});
  const response = await send(dot()); assert.equal(response.status,429); assert.equal(response.headers.get("Retry-After"),"60");
  assert.equal(sql.prepare("SELECT count(*) AS n FROM reports").get().n,0);
});
