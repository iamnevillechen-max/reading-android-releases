import test from 'node:test';
import assert from 'node:assert/strict';
import { newQuickJSWASMModuleFromVariant } from 'quickjs-emscripten-core';
import RELEASE_SYNC from '@jitl/quickjs-wasmfile-release-sync';
import { createIsolatedJavascriptSession, warmIsolatedJavascriptModule } from '../src/isolated-javascript';

const module = await newQuickJSWASMModuleFromVariant(RELEASE_SYNC);
warmIsolatedJavascriptModule(module);
const input = (result: unknown, extra: Record<string,unknown> = {}) => JSON.stringify({ result, book: { name: '自寫作品' }, baseUrl: 'https://owned.example.org/book/1', ...extra });
const request = (code: string, result: unknown) => ({ code, bindings: JSON.parse(input(result)) });

test('one operation realm evaluates a data-only batch with lexical bindings and native completion values', () => {
  const session = createIsolatedJavascriptSession(module);
  try {
    assert.deepEqual(session.evaluateBatch(JSON.stringify([
      request('result.trim().replace("Fixture", "Book")', ' Fixture A '),
      request('var output=[]; for(var i=0;i<result.length;i++) output.push({title:result[i].title,url:baseUrl}); output;', [{title:'自寫章節'}]),
      request('if (result) { book.name + ":" + result } else { "empty" }', 'row'),
    ])), ['Book A', [{title:'自寫章節',url:'https://owned.example.org/book/1'}], '自寫作品:row']);
    assert.equal(session.stats.calls, 3);
    assert.equal(session.stats.realms, 1);
  } finally { session.close(); }
});

test('a repeated metadata transform caches one lexical wrapper and never creates one VM per row', () => {
  const session = createIsolatedJavascriptSession(module);
  try {
    const code='result.trim()', rows=Array.from({length:30},(_,index)=>request(code,' fixture '+index+' '));
    assert.deepEqual(session.evaluateBatch(JSON.stringify(rows)), rows.map((_,index)=>'fixture '+index));
    assert.equal(session.stats.calls,30); assert.equal(session.stats.compiledRules,1); assert.equal(session.stats.realms,1);
    assert.equal(session.stats.evalCompilations,30); assert.ok(session.stats.compileBytes > 30*code.length);
  } finally { session.close(); }
});

test('source code cannot reach host IO, callbacks or another operation realm', () => {
  const session=createIsolatedJavascriptSession(module), other=createIsolatedJavascriptSession(module);
  try {
    assert.equal(session.evaluate('globalThis.fixtureMarker=7; [typeof process,typeof require,typeof fetch,typeof java,typeof Packages,typeof WebAssembly].join(",")', input('fixture')),
      'undefined,undefined,undefined,undefined,undefined,undefined');
    assert.equal(other.evaluate('typeof globalThis.fixtureMarker',input(null)),'undefined');
  } finally {session.close();other.close();}
});

test('host input objects, accessors and toJSON hooks are refused without invoking them', () => {
  const session=createIsolatedJavascriptSession(module);let accesses=0;
  const hostile={get result(){accesses++;return 'bad';},toJSON(){accesses++;return 'bad';}};
  try {
    assert.throws(()=>session.evaluate('result',hostile as any),{code:'ISOLATED_JS_INPUT'});
    assert.equal(accesses,0);
  }finally{session.close();}
});

test('captured intrinsics serialize output after source globals are modified without invoking host getters', () => {
  const session=createIsolatedJavascriptSession(module);
  try {
    const code='JSON.stringify=function(){return "forged"}; Object.prototype.toJSON=function(){return "forged"}; ({title:result})';
    assert.deepEqual(session.evaluate(code,input('real')),{title:'real'});
  }finally{session.close();}
  const getter=createIsolatedJavascriptSession(module);
  try{assert.throws(()=>getter.evaluate('({ get title(){while(true){}} })',input(null)),{code:'ISOLATED_JS_EXECUTION'});}
  finally{getter.close();}
});

test('cycles, functions and non-JSON output are rejected rather than silently truncated', () => {
  for(const code of ['var value={};value.self=value;value;', '({value:function(){return "bad"}})',
    'undefined', 'NaN', 'Promise.resolve("bad")', '({get title(){return "hidden"}})']){
    const session=createIsolatedJavascriptSession(module);
    try{assert.throws(()=>session.evaluate(code,input(null)),{code:'ISOLATED_JS_EXECUTION'});}
    finally{session.close();}
  }
});

test('infinite loops, adversarial regex and allocation failures poison the whole operation without partial values', () => {
  for(const code of ['while(true){}','/(a+)+$/.test("a".repeat(24)+"!")','"x".repeat(64*1024*1024)']){
    const session=createIsolatedJavascriptSession(module);
    try{
      assert.throws(()=>session.evaluateBatch(JSON.stringify([request('"first"',null),request(code,null)])));
      assert.equal(session.stats.failed,true);
      assert.throws(()=>session.evaluate('"after"',input(null)),{code:'ISOLATED_JS_CLOSED'});
    }finally{session.close();}
  }
});

test('code, input, output and cumulative call/compile budgets reject incomplete batches', () => {
  const cases:Array<[Record<string,number>,string]>=[
    [{maxCalls:1},JSON.stringify([request('result',1),request('result',2)])],
    [{maxCompileBytes:100},JSON.stringify([request('"one"',1),request('"two long"',2)])],
    [{maxInputBytes:200},JSON.stringify([request('result','x'.repeat(200))])],
    [{maxOutputBytes:30},JSON.stringify([request('"x".repeat(64)',null)])],
    [{maxCodeBytes:8},JSON.stringify([request('"x".repeat(64)',null)])],
  ];
  for(const[limits,batch]of cases){const session=createIsolatedJavascriptSession(module,limits);try{assert.throws(()=>session.evaluateBatch(batch));assert.equal(session.stats.failed,true);}finally{session.close();}}
  const cumulative=createIsolatedJavascriptSession(module,{maxCalls:1});
  try{assert.equal(cumulative.evaluate('result',input(1)),1);assert.throws(()=>cumulative.evaluate('result',input(2)),{code:'ISOLATED_JS_CALL_LIMIT'});}finally{cumulative.close();}
});


test('rule functions receive lexical data without access to the batch function table or stale book state', () => {
  const session=createIsolatedJavascriptSession(module);
  try {
    assert.deepEqual(session.evaluateBatch(JSON.stringify([
      request('book.name="changed"; typeof this',null),
      request('typeof globalThis.result+":"+book.name',null),
    ])), ['undefined','undefined:自寫作品']);
  }finally{session.close();}
});

test('symbol properties and malformed literal UTF16 input are refused without losing data', () => {
  const symbols=createIsolatedJavascriptSession(module);
  try{assert.throws(()=>symbols.evaluate('({[Symbol("hidden")]:"value"})',input(null)));}finally{symbols.close();}
  const malformed=createIsolatedJavascriptSession(module);
  try{assert.throws(()=>malformed.evaluate('result', '{"result":"'+String.fromCharCode(0xd800)+'"}'),{code:'ISOLATED_JS_INPUT'});}finally{malformed.close();}
});


test('the JSON-text boundary does not observe a host Proxy and VM toJSON methods never reach serialization', () => {
  let observed=0;
  const proxy=new Proxy({}, {get(){observed++;throw new Error('host getter must not run');},ownKeys(){observed++;return[];}});
  const session=createIsolatedJavascriptSession(module);
  try{assert.throws(()=>session.evaluateBatch(proxy as any),{code:'ISOLATED_JS_INPUT'});assert.equal(observed,0);}finally{session.close();}
  const vm=createIsolatedJavascriptSession(module);
  try{assert.throws(()=>vm.evaluate('({toJSON(){while(true){}}})',input(null)),{code:'ISOLATED_JS_EXECUTION'});}finally{vm.close();}
});

test('UTF8 output bytes and unique compiled rules consume cumulative limits', () => {
  const unicode=createIsolatedJavascriptSession(module,{maxOutputBytes:30});
  try{assert.throws(()=>unicode.evaluate('"中".repeat(15)',input(null)),{code:'ISOLATED_JS_OUTPUT_LIMIT'});}finally{unicode.close();}
  const compile=createIsolatedJavascriptSession(module,{maxCompileBytes:100});
  try{
    assert.throws(()=>compile.evaluateBatch(JSON.stringify([request('"one"',null),request('"two long"',null)])),{code:'ISOLATED_JS_COMPILE_LIMIT'});
    assert.equal(compile.stats.compiledRules,1);
  }finally{compile.close();}
});


test('an expired operation cannot complete a short batch just because no VM interrupt was needed', async () => {
  const session=createIsolatedJavascriptSession(module,{maxWallMillis:1});
  try {
    await new Promise(resolve=>setTimeout(resolve,5));
    assert.throws(()=>session.evaluate('"late"',input(null)),{code:'ISOLATED_JS_TIME_LIMIT'});
    assert.equal(session.stats.failed,true);
  }finally{session.close();}
});

test('native VM completion values survive loops, try/finally, switch and trailing declarations', () => {
  const cases = [
    ["for(let i=0;i<2;i++) result += 'x'", 'axx'],
    ["try{result+'body'}finally{result+'finally'}", 'abody'],
    ["switch(result){case 'a':'branch';break;}", 'branch'],
    ["'first';var x=1;", 'first'],
  ];
  for(const[code,expected]of cases){const session=createIsolatedJavascriptSession(module);try{assert.equal(session.evaluate(code,input('a')),expected);}finally{session.close();}}
});

test('captured VM eval stays lexical after globals are poisoned and explicit returns use unchanged function semantics', () => {
  const session=createIsolatedJavascriptSession(module);
  try {
    assert.deepEqual(session.evaluateBatch(JSON.stringify([
      request('globalThis.eval=function(){return "poisoned"}; "first"','a'),
      request('result+"second"','a'),
      request('function inner(){return result+"nested"};inner()','a'),
      request('try{return result+"body"}finally{result+"ignored"}','a'),
    ])), ['first','asecond','anested','abody']);
  }finally{session.close();}
});


test('repeated native eval source parsing consumes the compile budget even with one cached wrapper', () => {
  const session=createIsolatedJavascriptSession(module,{maxCompileBytes:200});
  try {
    assert.throws(()=>session.evaluateBatch(JSON.stringify(Array.from({length:20},()=>request('result.trim()','a')))),{code:'ISOLATED_JS_COMPILE_LIMIT'});
    assert.equal(session.stats.compiledRules,1);
    assert.equal(session.stats.failed,true);
  }finally{session.close();}
});
