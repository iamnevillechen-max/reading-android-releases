import { parse } from 'acorn';
import type { QuickJSHandle, QuickJSRuntime, QuickJSContext, QuickJSWASMModule } from 'quickjs-emscripten-core';

/** This module is deliberately not connected to the source engine or a host IO bridge. */
export class IsolatedJavascriptError extends Error {
  readonly status = 422;
  constructor(public code: string, message = '隔離規則程式未符合資料或執行界限') { super(message); }
}
export type JavascriptData = null | boolean | number | string | JavascriptData[] | { [key: string]: JavascriptData };
export type JavascriptLimits = {
  maxCodeBytes: number; maxCompileBytes: number; maxInputBytes: number; maxOutputBytes: number;
  maxCalls: number; maxNodes: number; maxDepth: number; maxInterrupts: number; maxWallMillis: number;
};
const DEFAULT_LIMITS: JavascriptLimits = Object.freeze({
  maxCodeBytes: 32 * 1024, maxCompileBytes: 64 * 1024,
  maxInputBytes: 256 * 1024, maxOutputBytes: 128 * 1024,
  maxCalls: 128, maxNodes: 8192, maxDepth: 24, maxInterrupts: 2, maxWallMillis: 50,
});
const encoder = new TextEncoder();
const bytes = (value: string) => encoder.encode(value).byteLength;
const malformedUtf16 = (value: string) => /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(value);
const error = (code: string): never => { throw new IsolatedJavascriptError(code); };

function limitsWith(lower: Partial<JavascriptLimits>): JavascriptLimits {
  const result = { ...DEFAULT_LIMITS };
  for (const name of Object.keys(lower) as Array<keyof JavascriptLimits>) {
    if (!(name in result) || !Number.isSafeInteger(lower[name]) || Number(lower[name]) < 1 || Number(lower[name]) > result[name]) error('ISOLATED_JS_LIMITS');
    result[name] = lower[name]!;
  }
  return result;
}

/** Use the VM's own completion semantics; host eval/Function are never used. */
function functionSource(code: string): { source: string; evalMode: boolean } {
  let ast: any;
  try { ast = parse(code, { ecmaVersion: 'latest', allowReturnOutsideFunction: true }); }
  catch { return error('ISOLATED_JS_COMPILE'); }
  function topLevelReturn(node: any): boolean {
    if (!node || typeof node !== 'object') return false;
    if (['FunctionDeclaration','FunctionExpression','ArrowFunctionExpression'].includes(node.type)) return false;
    if (node.type === 'ReturnStatement') return true;
    return Object.values(node).some(value => Array.isArray(value) ? value.some(topLevelReturn) : topLevelReturn(value));
  }
  if (topLevelReturn(ast)) return { source: '(function(result,book,baseUrl){"use strict";\n' + code + '\n})', evalMode: false };
  // A non-strict outer factory may bind `eval`. Its strict inner function then
  // performs direct eval using that captured VM intrinsic, even if an earlier
  // rule replaced globalThis.eval. The factory receives no kernel/table handle.
  return { source: '(function(eval){return function(result,book,baseUrl){"use strict";return eval(' + JSON.stringify(code) + ');};})', evalMode: true };
}

/** Trusted code. Intrinsics/serialization stay in an inaccessible VM closure. */
function kernelSource(limits: JavascriptLimits): string {
  return `(function(){
    "use strict";
    const parse=JSON.parse, stringify=JSON.stringify, keys=Object.keys,
      descriptors=Object.getOwnPropertyDescriptors, symbols=Object.getOwnPropertySymbols, prototype=Object.getPrototypeOf,
      create=Object.create, setPrototype=Object.setPrototypeOf, isArray=Array.isArray,
      objectPrototype=Object.prototype, arrayPrototype=Array.prototype,
      has=Object.prototype.hasOwnProperty, apply=Reflect.apply, finite=Number.isFinite,
      integer=Number.isSafeInteger, Failure=Error;
    const own=(object,key)=>apply(has,object,[key]);
    const list=()=>setPrototype([],null);
    const fail=()=>{throw new Failure("Non-JSON or oversized isolated output");};
    function clone(value,ancestors,depth,quota){
      if(++quota.nodes>${limits.maxNodes}||depth>${limits.maxDepth})fail();
      if(value===null||typeof value==="boolean")return value;
      if(typeof value==="number"){if(!finite(value))fail();return value;}
      if(typeof value==="string"){if(value.length>quota.characters)fail();return value;}
      if(typeof value!=="object")fail();
      const array=isArray(value), proto=prototype(value);
      if(symbols(value).length)fail();
      if(proto!==null&&proto!==(array?arrayPrototype:objectPrototype))fail();
      for(let index=0;index<ancestors.length;index++)if(ancestors[index]===value)fail();
      ancestors[ancestors.length]=value;
      const fields=descriptors(value), names=keys(fields), result=array?list():create(null);
      if(names.length>${limits.maxNodes})fail();
      if(array){
        const length=fields.length;
        if(!length||!own(length,"value")||!integer(length.value)||length.value<0||length.value>${limits.maxNodes})fail();
        for(let index=0;index<length.value;index++){
          const field=fields[index];if(!field||!own(field,"value"))fail();
          result[index]=clone(field.value,ancestors,depth+1,quota);
        }
        if(names.length!==length.value+1)fail();
      }else for(let index=0;index<names.length;index++){
        const name=names[index], field=fields[name];if(!own(field,"value")||!field.enumerable)fail();
        result[name]=clone(field.value,ancestors,depth+1,quota);
      }
      ancestors.length=ancestors.length-1;return result;
    }
    return function(batchText,functions){
      const batch=parse(batchText), output=list(), inputQuota={nodes:0,characters:${limits.maxInputBytes}}, outputQuota={nodes:0,characters:${limits.maxOutputBytes}};
      for(let index=0;index<batch.length;index++){
        const binding=batch[index].bindings;
        const result=clone(binding.result,list(),0,inputQuota);
        const book=own(binding,"book")?clone(binding.book,list(),0,inputQuota):null;
        const baseUrl=own(binding,"baseUrl")?binding.baseUrl:"";
        const value=apply(functions[index],undefined,[result,book,baseUrl]);
        output[index]=clone(value,list(),0,outputQuota);
      }
      return stringify(output);
    };
  })()`;
}

type BatchEntry = { code: string; bindings: { result: JavascriptData; book?: { [key: string]: JavascriptData } | null; baseUrl?: string } };
export type JavascriptSessionStats = { realms: number; calls: number; compiledRules: number; compileBytes: number;
  inputBytes: number; outputBytes: number; interrupts: number; evalCompilations: number; failed: boolean; closed: boolean };

/**
 * One operation owns one realm and a cumulative budget. The JSON-text boundary
 * refuses host objects before observing getters, proxies, methods or toJSON.
 */
export function createIsolatedJavascriptSession(module: QuickJSWASMModule, lower: Partial<JavascriptLimits> = {}) {
  const limits = limitsWith(lower), runtime: QuickJSRuntime = module.newRuntime();
  runtime.setMemoryLimit(4 * 1024 * 1024); runtime.setMaxStackSize(256 * 1024);
  let context: QuickJSContext;
  try { context = runtime.newContext(); } catch { runtime.dispose(); return error('ISOLATED_JS_INITIALIZATION'); }
  const compiled = new Map<string, QuickJSHandle>(), evalRules = new Map<string, number>();
  const stats: JavascriptSessionStats = { realms: 1, calls: 0, compiledRules: 0, compileBytes: 0, inputBytes: 0, outputBytes: 0, interrupts: 0, evalCompilations: 0, failed: false, closed: false };
  let kernel: QuickJSHandle; let intrinsicEval: QuickJSHandle | undefined;
  try {
    intrinsicEval = context.getProp(context.global, 'eval');
    const result = context.evalCode(kernelSource(limits), 'trusted-isolation-kernel.js');
    if (result.error) { result.error.dispose(); throw new Error('kernel unavailable'); }
    kernel = result.value;
  } catch { intrinsicEval?.dispose(); context.dispose(); runtime.dispose(); return error('ISOLATED_JS_INITIALIZATION'); }
  const started = Date.now();
  runtime.setInterruptHandler(() => ++stats.interrupts > limits.maxInterrupts || Date.now() - started > limits.maxWallMillis);

  function close(): void {
    if (stats.closed) return;
    stats.closed = true;
    for (const handle of compiled.values()) handle.dispose();
    compiled.clear(); evalRules.clear(); kernel.dispose(); intrinsicEval!.dispose(); context.dispose(); runtime.dispose();
  }
  function fail(code: string): never { stats.failed = true; return error(code); }
  function requireOpen(): void { if (stats.closed) error('ISOLATED_JS_CLOSED'); }
  function requireTime(): void { if (Date.now() - started > limits.maxWallMillis) fail('ISOLATED_JS_TIME_LIMIT'); }
  function data(value: unknown, depth: number, count: { value: number }): void {
    if (++count.value > limits.maxNodes || depth > limits.maxDepth) error('ISOLATED_JS_INPUT');
    if (value === null || typeof value === 'string' || typeof value === 'boolean') return;
    if (typeof value === 'number' && Number.isFinite(value)) return;
    if (Array.isArray(value)) { value.forEach(item => data(item, depth + 1, count)); return; }
    if (value && typeof value === 'object') { for (const item of Object.values(value)) data(item, depth + 1, count); return; }
    error('ISOLATED_JS_INPUT');
  }
  function evaluateBatch(batchJson: string): JavascriptData[] {
    requireOpen();
    try {
      requireTime();
      if (typeof batchJson !== 'string' || malformedUtf16(batchJson)) return fail('ISOLATED_JS_INPUT');
      const inputBytes = bytes(batchJson);
      if (stats.inputBytes + inputBytes > limits.maxInputBytes) return fail('ISOLATED_JS_INPUT_LIMIT');
      stats.inputBytes += inputBytes;
      let entries: BatchEntry[];
      try { entries = JSON.parse(batchJson); } catch { return fail('ISOLATED_JS_INPUT'); }
      if (!Array.isArray(entries) || !entries.length) return fail('ISOLATED_JS_INPUT');
      if (stats.calls + entries.length > limits.maxCalls) return fail('ISOLATED_JS_CALL_LIMIT');
      const inputNodes = { value: 0 };
      for (const entry of entries) {
        if (!entry || Array.isArray(entry) || typeof entry !== 'object' || Object.keys(entry).some(key => !['code','bindings'].includes(key)) || typeof entry.code !== 'string' || !entry.code.trim()) return fail('ISOLATED_JS_INPUT');
        const binding = entry.bindings;
        if (!binding || Array.isArray(binding) || typeof binding !== 'object' || !Object.hasOwn(binding,'result') ||
          Object.keys(binding).some(key => !['result','book','baseUrl'].includes(key)) ||
          (Object.hasOwn(binding,'baseUrl') && typeof binding.baseUrl !== 'string') ||
          (binding.book != null && (typeof binding.book !== 'object' || Array.isArray(binding.book)))) return fail('ISOLATED_JS_INPUT');
        data(binding, 0, inputNodes);
        if (malformedUtf16(entry.code)) return fail('ISOLATED_JS_INPUT');
        if (bytes(entry.code) > limits.maxCodeBytes) return fail('ISOLATED_JS_CODE_LIMIT');
      }
      requireTime();
      stats.calls += entries.length;
      for (const { code } of entries) if (!compiled.has(code)) {
        const wrapped = functionSource(code), size = bytes(wrapped.source); requireTime();
        if (stats.compileBytes + size > limits.maxCompileBytes) return fail('ISOLATED_JS_COMPILE_LIMIT');
        stats.compileBytes += size;
        const result = context.evalCode(wrapped.source, 'isolated-source-rule.js');
        if (result.error) { result.error.dispose(); return fail(stats.interrupts > limits.maxInterrupts ? 'ISOLATED_JS_INTERRUPTED' : 'ISOLATED_JS_COMPILE'); }
        if (wrapped.evalMode) {
          try {
            const callable = context.callFunction(result.value, context.undefined, intrinsicEval!);
            if (callable.error) { callable.error.dispose(); return fail(stats.interrupts > limits.maxInterrupts ? 'ISOLATED_JS_INTERRUPTED' : 'ISOLATED_JS_COMPILE'); }
            compiled.set(code, callable.value); evalRules.set(code, bytes(code));
          } finally { result.value.dispose(); }
        } else compiled.set(code, result.value);
        stats.compiledRules++; requireTime();
      }
      // Native eval parses each invocation even when its lexical wrapper is
      // cached. Reserve all submitted source bytes before running any row.
      const evalBytes = entries.reduce((sum, entry) => sum + (evalRules.get(entry.code) || 0), 0);
      if (stats.compileBytes + evalBytes > limits.maxCompileBytes) return fail('ISOLATED_JS_COMPILE_LIMIT');
      stats.compileBytes += evalBytes;
      stats.evalCompilations += entries.filter(entry => evalRules.has(entry.code)).length;
      const batch = context.newString(batchJson); let functions: QuickJSHandle | undefined;
      try {
        functions = context.newArray();
        entries.forEach((entry, index) => context.setProp(functions!, index, compiled.get(entry.code)!));
        const result = context.callFunction(kernel, context.undefined, batch, functions);
        if (result.error) { result.error.dispose(); return fail(stats.interrupts > limits.maxInterrupts ? 'ISOLATED_JS_INTERRUPTED' : 'ISOLATED_JS_EXECUTION'); }
        try {
          requireTime();
          // Only our captured serializer's primitive string crosses the boundary.
          if (context.typeof(result.value) !== 'string') return fail('ISOLATED_JS_OUTPUT');
          const length = context.getProp(result.value, 'length');
          let characters: number;
          try { characters = context.getNumber(length); } finally { length.dispose(); }
          if (!Number.isSafeInteger(characters) || characters < 0 || stats.outputBytes + characters > limits.maxOutputBytes) return fail('ISOLATED_JS_OUTPUT_LIMIT');
          const serialized = context.getString(result.value), outputBytes = bytes(serialized);
          if (stats.outputBytes + outputBytes > limits.maxOutputBytes) return fail('ISOLATED_JS_OUTPUT_LIMIT');
          stats.outputBytes += outputBytes;
          const output = JSON.parse(serialized) as JavascriptData[]; requireTime();
          return output;
        } finally { result.value.dispose(); }
      } finally { functions?.dispose(); batch.dispose(); }
    } catch (caught) {
      if (caught instanceof IsolatedJavascriptError) { stats.failed = true; close(); throw caught; }
      stats.failed = true; close(); return error('ISOLATED_JS_EXECUTION');
    }
  }
  function evaluate(code: string, bindingsJson: string): JavascriptData {
    requireOpen();
    if (typeof code !== 'string' || typeof bindingsJson !== 'string') { stats.failed = true; close(); return error('ISOLATED_JS_INPUT'); }
    return evaluateBatch('[{"code":' + JSON.stringify(code) + ',"bindings":' + bindingsJson + '}]')[0];
  }
  return { evaluate, evaluateBatch, close, get stats() { return { ...stats }; } };
}

/** Perform only a fixed synthetic prewarm during Worker startup, never source code. */
export function warmIsolatedJavascriptModule(module: QuickJSWASMModule): void {
  const runtime = module.newRuntime(); runtime.setMemoryLimit(4 * 1024 * 1024); runtime.setMaxStackSize(256 * 1024);
  const context = runtime.newContext();
  try {
    const result = context.evalCode('JSON.stringify(Array.from({length:100},(_,i)=>({name:"Fixture "+i,url:"/book/"+i})))', 'synthetic-startup-only.js');
    if (result.error) { result.error.dispose(); error('ISOLATED_JS_INITIALIZATION'); }
    else result.value.dispose();
  } finally { context.dispose(); runtime.dispose(); }
  // Prewarm the host parser, trusted kernel and serialization path too. A raw
  // QuickJS prewarm alone leaves the first operation paying the Acorn cold cost.
  const session = createIsolatedJavascriptSession(module);
  try {
    session.evaluateBatch(JSON.stringify(Array.from({ length: 10 }, (_unused, index) => ({
      code: 'result.trim().replace("Fixture", "Book")',
      bindings: { result: ' Fixture ' + index + ' ', book: { name: 'Synthetic only' }, baseUrl: 'https://owned.example.org/' },
    }))));
  } finally { session.close(); }
}
