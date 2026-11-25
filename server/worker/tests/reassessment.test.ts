import test from 'node:test';
import assert from 'node:assert/strict';
import {createApp,sha256,type EngineApi,type Env} from '../src/index';
import {assessSource} from '../src/engine';
import {SourceStore,type SourceRow} from '../src/storage';
import {TestDatabase} from './d1';
function fixture(assess:EngineApi['assessSource']=()=>({supported:true,capabilities:['search','content','detail','chapters']})){
 const db=new TestDatabase(),store=new SourceStore(db as unknown as D1Database);let calls=0,io=0;
 const forbidden=async():Promise<any>=>{io++;throw Error('No source IO in assessment');};
 const engine:EngineApi={assessSource:(k,s)=>{calls++;return assess(k,s);},categories:forbidden,search:forbidden,info:forbidden,toc:forbidden,content:forbidden,explore:forbidden,rssArticles:forbidden,rssContent:forbidden,verifyWebEntry:forbidden};
 const env:Env={DB:db as unknown as D1Database,ADMIN_TOKEN:'a'.repeat(40),READ_TOKEN:'r'.repeat(40),MIGRATION_TOKEN:'m'.repeat(40)},app=createApp(engine);
 const request=(body:any,role='admin')=>app.fetch(new Request('https://reader.example/v1/reassess',{method:'POST',headers:{Authorization:'Bearer '+(role==='admin'?env.ADMIN_TOKEN:role==='read'?env.READ_TOKEN:role==='migration'?env.MIGRATION_TOKEN:role),'Content-Type':'application/json'},body:JSON.stringify(body)}),env);
 async function seed(n=0,options:{supported?:boolean;healthy?:boolean;caps?:string[];extra?:any;kind?:'book'|'rss'}={}){
  const kind=options.kind??'book',id=`https://owned-assessment.example.org/${n}`,rule={...(kind==='book'?{bookSourceUrl:id,bookSourceType:0}:{sourceUrl:id}),enabled:true,fixture:n,...options.extra};
  const json=' \n'+JSON.stringify(rule)+'\n',hash=await sha256(json);
  await store.importSources([{kind,id,json,sha256:hash,name:'Owned fixture',enabled:true,enabledExplore:true,supported:options.supported??false,capabilities:options.caps??[]}]);
  if(options.healthy)db.raw.prepare("UPDATE sources SET status='healthy',verified_at=456,failures=2,retry_at=123 WHERE kind=? AND id=?").run(kind,id);
  return {kind,id,json,hash,expected:{id,sha256:hash}};
 }
 const row=(id:string,kind='book')=>db.raw.prepare('SELECT * FROM sources WHERE kind=? AND id=?').get(kind,id)as unknown as SourceRow;
 const changes=()=>(db.raw.prepare('SELECT total_changes() AS n').get()as any).n;
 const snapshot=()=>JSON.stringify(['sources','catalogue_routes','verification_profiles','maintenance_attempts','source_stats','catalogue_revision'].map(t=>db.raw.prepare(`SELECT * FROM ${t} ORDER BY 1,2`).all()));
 function proof(id:string,hash:string){db.raw.prepare("INSERT INTO catalogue_routes(kind,source_id,source_sha256,label,url,category_id,category_label) VALUES('book',?,?,'Owned','https://owned-assessment.example.org/list','other','Other')").run(id,hash);db.raw.prepare("INSERT INTO verification_profiles VALUES('book',?,'Owned keyword','Owned book','Owned chapter')").run(id);db.raw.prepare("INSERT INTO maintenance_attempts VALUES('book',?,123)").run(id);}
 return {db,store,request,seed,row,changes,snapshot,proof,get calls(){return calls;},get io(){return io;}};
}
async function response(r:Response){assert.equal(r.status,200,await r.clone().text());return(await r.json()as any).data;}

test('admin-only identities are bounded, SHA-validated and deduplicated before any D1 query',async()=>{const h=fixture();try{
 for(const role of ['read','migration'])assert.equal((await h.request({kind:'book',sources:[]},role)).status,403);
 assert.equal((await h.request({},'wrong')).status,401);
 const invalid=[{}, {kind:'books',sources:[{id:'x',sha256:'a'.repeat(64)}]}, {kind:'book',sources:[]},
 {kind:'book',sources:Array.from({length:51},(_,i)=>({id:String(i),sha256:'a'.repeat(64)}))},
 {kind:'book',sources:[{id:'x',sha256:'a'.repeat(64)},{id:'x',sha256:'a'.repeat(64)}]},
 {kind:'book',sources:[{id:7,sha256:'a'.repeat(64)}]}, {kind:'book',sources:[{id:'x',sha256:'z'.repeat(64)}]},
 {kind:'book',sources:[{id:'x',sha256:'a'.repeat(64),json:'replacement'}]}, {kind:'book',sources:[{id:'x',sha256:'a'.repeat(64)}],supported:true}];
 for(const b of invalid)assert.equal((await h.request(b)).status,400);
 assert.equal((await h.request({kind:'book',sources:[],padding:'x'.repeat(1048576)})).status,413);assert.equal(h.db.preparedCount,0);assert.equal(h.calls,0);assert.equal(h.io,0);
}finally{h.db.raw.close();}});

test('unsupported becomes pending only, preserving exact raw/preferences and resetting only matching evidence',async()=>{const h=fixture();try{
 const a=await h.seed(),b=await h.seed(1);h.proof(a.id,a.hash);h.proof(b.id,b.hash);
 h.db.raw.prepare('UPDATE sources SET failures=4,retry_at=900,verified_at=800,enabled_explore=0 WHERE id=?').run(a.id);
 const before=h.row(a.id),other=h.row(b.id),generation=(await h.store.counts('book')).revision;
 const r=await response(await h.request({kind:'book',sources:[a.expected]}));assert.equal(r.assessmentOnly,true);assert.equal(r.networkRequests,0);
 assert.deepEqual(r.receipts.map((x:any)=>[x.outcome,x.changed,x.changeCount,x.status]),[['changed',true,1,'pending']]);
 const after=h.row(a.id);for(const k of ['kind','id','json','sha256','name','enabled','enabled_explore']as const)assert.equal(after[k],before[k]);
 assert.equal(after.supported,1);assert.equal(after.status,'pending');assert.equal(after.failures,0);assert.equal(after.retry_at,0);assert.equal(after.verified_at,0);
 for(const t of ['catalogue_routes','verification_profiles','maintenance_attempts']){assert.equal((h.db.raw.prepare(`SELECT count(*) AS n FROM ${t} WHERE source_id=?`).get(a.id)as any).n,0);assert.equal((h.db.raw.prepare(`SELECT count(*) AS n FROM ${t} WHERE source_id=?`).get(b.id)as any).n,1);}
 assert.deepEqual(h.row(b.id),other);assert.equal((await h.store.counts('book')).revision,generation+1);assert.equal(h.io,0);
}finally{h.db.raw.close();}});

test('unchanged healthy means zero writes, exact health/profile/capability formatting retained',async()=>{const h=fixture(()=>({supported:true,capabilities:['content','search','search']}));try{
 const a=await h.seed(0,{supported:true,healthy:true});h.proof(a.id,a.hash);h.db.raw.prepare('UPDATE sources SET capabilities=? WHERE id=?').run(' [ "search", "content" ] ',a.id);
 const before=h.snapshot(),changes=h.changes(),r=await response(await h.request({kind:'book',sources:[a.expected]}));
 assert.deepEqual(r.receipts.map((x:any)=>[x.outcome,x.changed,x.changeCount]),[['unchanged',false,0]]);assert.equal(h.snapshot(),before);assert.equal(h.changes(),changes);
}finally{h.db.raw.close();}});

test('capability-only change preserves healthy proof; support demotion withdraws matched evidence without editing raw',async()=>{let supported=true;const h=fixture(()=>({supported,capabilities:supported?['search','content']:[]}));try{
 const a=await h.seed(0,{supported:true,healthy:true,caps:['search']});h.proof(a.id,a.hash);
 const r=await response(await h.request({kind:'book',sources:[a.expected]}));assert.equal(r.receipts[0].outcome,'changed');assert.equal(h.row(a.id).status,'healthy');assert.equal(h.row(a.id).verified_at,456);assert.equal(h.row(a.id).failures,2);
 assert.equal((h.db.raw.prepare('SELECT count(*) AS n FROM verification_profiles').get()as any).n,1);supported=false;
 const d=await response(await h.request({kind:'book',sources:[a.expected]}));assert.equal(d.receipts[0].status,'unsupported');assert.equal(h.row(a.id).verified_at,0);assert.equal(h.row(a.id).json,a.json);assert.equal(h.row(a.id).sha256,a.hash);
 assert.equal((h.db.raw.prepare('SELECT count(*) AS n FROM catalogue_routes').get()as any).n,0);
}finally{h.db.raw.close();}});

test('stale SHA and missing rows receive no success alongside an independently changed source',async()=>{const h=fixture();try{
 const a=await h.seed(),b=await h.seed(1),before=h.row(b.id);
 const r=await response(await h.request({kind:'book',sources:[a.expected,{id:b.id,sha256:'0'.repeat(64)},{id:'missing',sha256:'1'.repeat(64)}]}));
 assert.deepEqual(r.receipts.map((x:any)=>[x.outcome,x.changed,x.changeCount]),[['changed',true,1],['stale',false,0],['stale',false,0]]);assert.deepEqual(h.row(b.id),before);assert.equal(h.calls,1);
}finally{h.db.raw.close();}});

test('read-to-write SHA race protects replacement raw and replacement route/profile, while another source can change',async()=>{let h:ReturnType<typeof fixture>,raw='',hash='';h=fixture((_k,s)=>{if(s.fixture===0){h.db.raw.prepare('UPDATE sources SET json=?,sha256=?,updated_at=updated_at+1 WHERE id=?').run(raw,hash,s.bookSourceUrl);h.db.raw.prepare("UPDATE verification_profiles SET keyword='New proof' WHERE source_id=?").run(s.bookSourceUrl);h.db.raw.prepare('UPDATE catalogue_routes SET source_sha256=? WHERE source_id=?').run(hash,s.bookSourceUrl);}return{supported:true,capabilities:['search']};});try{
 const a=await h.seed(),b=await h.seed(1);h.proof(a.id,a.hash);raw=JSON.stringify({bookSourceUrl:a.id,fixture:'new'});hash=await sha256(raw);
 const r=await response(await h.request({kind:'book',sources:[a.expected,b.expected]}));assert.deepEqual(r.receipts.map((x:any)=>[x.outcome,x.changed]),[['stale',false],['changed',true]]);
 assert.equal(h.row(a.id).json,raw);assert.equal(h.row(a.id).sha256,hash);assert.equal(h.row(a.id).supported,0);assert.equal((h.db.raw.prepare('SELECT keyword FROM verification_profiles WHERE source_id=?').get(a.id)as any).keyword,'New proof');assert.equal((h.db.raw.prepare('SELECT source_sha256 FROM catalogue_routes WHERE source_id=?').get(a.id)as any).source_sha256,hash);
 assert.equal((h.db.raw.prepare('SELECT count(*) AS n FROM maintenance_attempts WHERE source_id=?').get(a.id)as any).n,1);
}finally{h.db.raw.close();}});

test('same-SHA concurrent verify cannot be reset or lose its new profile',async()=>{let h:ReturnType<typeof fixture>;h=fixture((_k,s)=>{h.db.raw.prepare("UPDATE sources SET supported=1,status='healthy',verified_at=999,updated_at=updated_at+1 WHERE id=?").run(s.bookSourceUrl);h.db.raw.prepare("UPDATE verification_profiles SET keyword='New healthy proof' WHERE source_id=?").run(s.bookSourceUrl);return{supported:true,capabilities:['search']};});try{
 const a=await h.seed();h.proof(a.id,a.hash);const r=await response(await h.request({kind:'book',sources:[a.expected]}));assert.equal(r.receipts[0].outcome,'stale');assert.equal(r.receipts[0].changed,false);assert.equal(h.row(a.id).status,'healthy');assert.equal(h.row(a.id).verified_at,999);assert.equal((h.db.raw.prepare('SELECT keyword FROM verification_profiles WHERE source_id=?').get(a.id)as any).keyword,'New healthy proof');assert.equal((h.db.raw.prepare('SELECT count(*) AS n FROM catalogue_routes').get()as any).n,1);
}finally{h.db.raw.close();}});

test('UPDATE failure rolls back earlier conditional cleanup and index counters atomically',async()=>{const h=fixture();try{
 const a=await h.seed();h.proof(a.id,a.hash);const before=h.snapshot();h.db.raw.exec("CREATE TRIGGER reject_owned BEFORE UPDATE ON sources BEGIN SELECT RAISE(ABORT,'Owned rollback fixture'); END");assert.equal((await h.request({kind:'book',sources:[a.expected]})).status,503);assert.equal(h.snapshot(),before);
}finally{h.db.raw.close();}});

test('matching stored SHA is insufficient when the actual raw JSON bytes are corrupt',async()=>{const h=fixture();try{
 const a=await h.seed();h.db.raw.prepare('UPDATE sources SET json=? WHERE id=?').run('{"corrupted":true}',a.id);const before=h.snapshot(),changes=h.changes();assert.equal((await h.request({kind:'book',sources:[a.expected]})).status,503);assert.equal(h.calls,0);assert.equal(h.snapshot(),before);assert.equal(h.changes(),changes);
}finally{h.db.raw.close();}});

test('aggregate UTF8 bytes reject the batch before reading raw or updating any earlier row',async()=>{const h=fixture();try{
 const entries=[];for(let i=0;i<5;i++)entries.push(await h.seed(i,{extra:{padding:'字'.repeat(150000)}}));const before=h.snapshot(),queries=h.db.preparedCount;assert.equal((await h.request({kind:'book',sources:entries.map(x=>x.expected)})).status,413);assert.equal(h.db.preparedCount-queries,1);assert.equal(h.calls,0);assert.equal(h.snapshot(),before);
}finally{h.db.raw.close();}});

test('node or depth exhaustion in the fiftieth rule leaves all previous rules untouched',async()=>{for(const shape of ['nodes','depth']){const h=fixture();try{
 const entries=[];for(let i=0;i<49;i++)entries.push(await h.seed(i));let extra:any=shape==='nodes'?Object.fromEntries(Array.from({length:21000},(_,i)=>['k'+i,0])):{};if(shape==='depth')for(let i=0;i<70;i++)extra={child:extra};entries.push(await h.seed(49,{extra:{extra}}));const before=h.snapshot(),changes=h.changes();assert.equal((await h.request({kind:'book',sources:entries.map(x=>x.expected)})).status,413,shape);assert.equal(h.snapshot(),before,shape);assert.equal(h.changes(),changes,shape);assert.equal(h.io,0);
}finally{h.db.raw.close();}}});

test('fifty raw reads use an indexed bounded batch and stay under ten prepared D1 statements',async()=>{const h=fixture();try{
 const entries=[];for(let i=0;i<50;i++)entries.push(await h.seed(i));const before=h.db.preparedCount;const r=await response(await h.request({kind:'book',sources:entries.map(x=>x.expected)}));assert.equal(r.receipts.length,50);assert.equal(r.receipts.filter((x:any)=>x.changed).length,50);assert.ok(h.db.preparedCount-before<=10);assert.equal(h.calls,50);assert.equal(h.io,0);
 const sql=h.db.preparedSql.find(s=>s.includes('WITH expected AS'))!,plan=h.db.raw.prepare('EXPLAIN QUERY PLAN '+sql).all(JSON.stringify(entries.map(x=>x.expected)),'book',768*1024,'book',2*1024*1024);assert.match(JSON.stringify(plan),/SEARCH s USING INDEX sqlite_autoindex_sources_1/);assert.doesNotMatch(JSON.stringify(plan),/SCAN s(?:\b|2\b)/);
}finally{h.db.raw.close();}});

test('real static engine classifies XPath and inert URL expressions without source IO',async()=>{const h=fixture(assessSource);try{
 const a=await h.seed(0,{extra:{searchUrl:'https://owned-assessment.example.org/search?q={{encodeURIComponent(key)}}&p={{page+1}}',ruleSearch:{bookList:'//article',name:'.name@text',bookUrl:'a@href'},ruleBookInfo:{name:'h1@text'},ruleToc:{chapterList:'//li',chapterName:'a@text',chapterUrl:'a@href'},ruleContent:{content:'#content@text'}}});const r=await response(await h.request({kind:'book',sources:[a.expected]}));assert.equal(r.receipts[0].supported,true);assert.equal(r.receipts[0].status,'pending');assert.equal(h.row(a.id).json,a.json);assert.equal(h.io,0);
}finally{h.db.raw.close();}});
