import test from 'node:test';
import assert from 'node:assert/strict';
import { TestDatabase } from './d1';
import { SourceStore, type SourceRow } from '../src/storage';
import { maintenanceLimit, maintenanceStatus, runMaintenance } from '../src/maintenance';

function setup(){
  const adapter=new TestDatabase(),db=adapter as unknown as D1Database,store=new SourceStore(db);
  async function add(id:string,status='pending',supported=true){
    await store.importSources([{kind:'book',id,json:JSON.stringify({bookSourceUrl:id}),sha256:'a'.repeat(64),name:'Owned maintenance',
      enabled:true,enabledExplore:true,supported,capabilities:['search','content']}]);
    adapter.raw.prepare('UPDATE sources SET status=?,verified_at=? WHERE id=?').run(status,status==='healthy'?Date.now()-8*60*60_000:0,id);
  }
  async function pass(source:SourceRow,guard:(n:number)=>Promise<void>){
    const full=(await store.get(source.kind,source.id))!;
    await store.markHealthy(full,[{label:'Owned genre',url:source.id+'/genre',categoryId:'urban',categoryLabel:'都市／現實',filterIds:[]}],guard);
    return {verified:true};
  }
  return {adapter,db,store,add,pass};
}

test('maintenance reserves one stale healthy and one pending slot and persists progress before each probe',async()=>{
  const h=setup(),now=Date.now(),called:string[]=[];
  await h.add('https://fixture.example/healthy','healthy');
  await h.add('https://fixture.example/pending-a');await h.add('https://fixture.example/pending-b');
  await h.add('https://fixture.example/unsupported','unsupported',false);
  const verify=async(source:SourceRow,guard:(n:number)=>Promise<void>)=>{
    called.push(source.id);
    assert.ok(h.adapter.raw.prepare('SELECT last_attempt FROM maintenance_attempts WHERE kind=? AND source_id=?').get(source.kind,source.id));
    return h.pass(source,guard);
  };
  const first=await runMaintenance(h.db,verify,{now});
  assert.equal(first.processed,2);assert.equal(first.healthy,2);
  assert.deepEqual(called,['https://fixture.example/healthy','https://fixture.example/pending-a']);
  await runMaintenance(h.db,verify,{now:now+30*60_000});
  assert.equal(called.at(-1),'https://fixture.example/pending-b');
  const state=await maintenanceStatus(h.db,true,5000,now);
  assert.equal(state.leaseActive,false);assert.equal(state.lastTrigger,'scheduled');
  assert.ok(state.dailyReservedWrites<=5000);
  assert.ok(!JSON.stringify(state).includes('fixture.example'));
  h.adapter.raw.close();
});

test('failed health is immediately withdrawn and oldest-attempt rotation does not get stuck on the bad first source',async()=>{
  const h=setup(),now=Date.now(),called:string[]=[];
  for(const suffix of ['a','b','c'])await h.add('https://fixture.example/'+suffix);
  const fail=async(source:SourceRow)=>{called.push(source.id);return {verified:false,reasonCode:'SOURCE_HTTP'};};
  await runMaintenance(h.db,fail,{now});
  await runMaintenance(h.db,fail,{now:now+30*60_000});
  assert.deepEqual(called.slice(0,3),['https://fixture.example/a','https://fixture.example/b','https://fixture.example/c']);
  assert.ok(called.length<=4);
  const row=await h.store.get('book','https://fixture.example/a');
  assert.equal(row!.status,'pending');assert.ok(row!.retry_at>now);assert.equal(row!.json,JSON.stringify({bookSourceUrl:row!.id}));
  h.adapter.raw.close();
});

test('the persisted lease blocks overlapping triggers and a daily write ceiling defers a huge catalogue without quarantining its source',async()=>{
  const h=setup(),now=Date.now();await h.add('https://fixture.example/a');
  let nested:any;
  await runMaintenance(h.db,async(source,guard)=>{
    nested=await runMaintenance(h.db,async()=>{throw new Error('must not run');},{now:now+1});
    return h.pass(source,guard);
  },{now});
  assert.equal(nested.skipped,'lease');
  h.adapter.raw.prepare("UPDATE sources SET status='pending',verified_at=0,retry_at=0").run();
  const limited=await runMaintenance(h.db,async(source,guard)=>{
    const routes=Array.from({length:1000},(_,index)=>({label:'Owned '+index,url:source.id+'/genre/'+index,
      categoryId:'urban',categoryLabel:'都市／現實',filterIds:[]}));
    await h.store.markHealthy((await h.store.get(source.kind,source.id))!,routes,guard);
    return {verified:true};
  },{now:now+24*60*60_000,limit:250});
  assert.equal(limited.deferred,1);assert.equal(limited.failed,0);
  const row=await h.store.get('book','https://fixture.example/a');assert.equal(row!.status,'pending');assert.equal(row!.failures,0);
  const state=await maintenanceStatus(h.db,true,250,now+24*60*60_000);assert.ok(state.dailyReservedWrites<=250);
  assert.equal(maintenanceLimit('999999'),5000);assert.equal(maintenanceLimit('0'),0);
  h.adapter.raw.close();
});

test('source revalidation write estimates include route indexes and an unchanged large catalogue remains inexpensive',async()=>{
  const h=setup();await h.add('https://fixture.example/many');
  const source=(await h.store.get('book','https://fixture.example/many'))!;
  const routes=Array.from({length:1000},(_,index)=>({label:'Owned '+index,url:source.id+'/genre/'+index,
    categoryId:'urban',categoryLabel:'都市／現實',filterIds:[]}));
  let first=0,second=0;
  await h.store.markHealthy(source,routes,async estimate=>{first=estimate;});
  await h.store.markHealthy(source,routes,async estimate=>{second=estimate;});
  assert.equal(first,3010);assert.equal(second,10);
  const plan=h.adapter.raw.prepare(`EXPLAIN QUERY PLAN SELECT s.kind,s.id,s.sha256,s.status,1 AS supported,1 AS enabled,s.retry_at,s.verified_at
    FROM sources s LEFT JOIN maintenance_attempts a ON a.kind=s.kind AND a.source_id=s.id WHERE s.supported=1 AND s.enabled=1
    AND s.status='pending' AND s.retry_at<=? ORDER BY COALESCE(a.last_attempt,0),s.verified_at,s.kind,s.id LIMIT 3`).all(Date.now());
  assert.ok(JSON.stringify(plan).includes('maintenance_eligible'));
  h.adapter.raw.close();
});
