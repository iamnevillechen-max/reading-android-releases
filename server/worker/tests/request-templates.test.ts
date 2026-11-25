import {test} from 'node:test';
import assert from 'node:assert/strict';
import {requestAddress as address} from '../src/request-templates';

test('URL expressions support page offsets, conditional suffixes and raw explicit encoding',()=>{
  const key='中文 & / %';
  assert.equal(address('/find?k={{key}}&offset={{(page-1)*30}}',key,3).url,`/find?k=${encodeURIComponent(key)}&offset=60`);
  assert.equal(address('/find?k={{encodeURIComponent(key)}}&p={{Math.ceil(page/2)}}',key,3).url,`/find?k=${encodeURIComponent(key)}&p=2`);
  assert.equal(address('/list{{page>1?"_"+page:""}}.html','',1).url,'/list.html');
  assert.equal(address('/list{{page>1?"_"+page:""}}.html','',2).url,'/list_2.html');
  assert.equal(address('{{baseUrl}}list?page={{page}}','',2,'https://fixture.example/').url,'https://fixture.example/list?page=2');
});

test('JSON string and object bodies preserve keyword data and do not interpolate injected templates',()=>{
  const key='"quoted" \\ & {{page}}';
  const options={method:'POST',body:JSON.stringify({key:'{{key}}',page:'{{page+1}}'})};
  assert.deepEqual(JSON.parse(address('/find,'+JSON.stringify(options),key,2).body!),{key,page:'3'});
  const object={method:'POST',body:{key:'{{key}}',offset:'{{(page-1)*30}}'}};
  assert.deepEqual(JSON.parse(address('/find,'+JSON.stringify(object),key,2).body!),{key,offset:'30'});
  const expression={method:'POST',body:'{{JSON.stringify({key:key,page:page})}}'};
  assert.deepEqual(JSON.parse(address('/find,'+JSON.stringify(expression),key,2).body!),{key,page:2});
});

test('UTF8 and legacy form encoders preserve field boundaries and explicit URI encoding',()=>{
  const form=(body:string,charset?:string)=>'/find,'+JSON.stringify({method:'POST',body,...charset?{charset}:{}});
  assert.equal(address(form('q={{key}}&page={{page+1}}'),'中 &+%',2).body,'q=%E4%B8%AD+%26%2B%25&page=3');
  assert.equal(address(form('q={{encodeURIComponent(key)}}'),'中 &+%',2).body,'q=%E4%B8%AD%20%26%2B%25');
  assert.equal(address(form('q={{key}}&literal=keep%2Bencoded','gbk'),'中&',2).body,'q=%D6%D0%26&literal=keep%252Bencoded');
  assert.equal(address('/find?q=中文,{"charset":"gbk"}').url,'/find?q=%D6%D0%CE%C4');
});

test('unsupported expressions and dynamic request options fail before a request is made',()=>{
  for(const url of ['/find?p={{page++}}','/find?k={{java.ajax("https://fixture.example")}}','/find?p={{Math}}',
    '/find,{"method":"POST","webView":true}','/find,{"method":"DELETE"}','/find,{"body":"data"}',
    '/find,{"charset":"escape"}','/find,{"method":"POST","body":"{{\"x\".repeat(10000000)}}"}']) assert.throws(()=>address(url,'中',2),/URL|POST|GET|字元|公開/,url);
});

test('mixed URI form expressions remain explicit unsupported cases rather than silently double encoding',()=>{
  const form=(body:string)=>'/find,'+JSON.stringify({method:'POST',body});
  for(const expression of ['"prefix"+encodeURIComponent(key)','page>1?encodeURIComponent(key):key']){
    assert.throws(()=>address(form('q={{'+expression+'}}'),'中+%',2),/混合URI/);
  }
  assert.equal(address(form('q={{"literal encodeURIComponent( text"}}'),'中',2).body,'q=literal+encodeURIComponent%28+text');
});
