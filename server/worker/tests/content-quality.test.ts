import test from 'node:test';
import assert from 'node:assert/strict';
import policy from '../../shared/content-quality-v1.json';
import {canReadContent,canMigrateContent} from '../src/content-quality';
import {createEngine} from '../src/engine';

test('shared reading and stricter migration cases reject wrappers without banning subscription dialogue',()=>{
  for(const vector of policy.cases) {
    assert.equal(canReadContent(vector.text),vector.readable,vector.name+' readable');
    assert.equal(canMigrateContent(vector.text),vector.migratable,vector.name+' migratable');
  }
});

test('actual content parsing denies a nonempty gate but preserves a genuinely short chapter',async()=>{
  const source={bookSourceUrl:'https://owned-quality.example.org/',bookSourceName:'Owned quality fixture',ruleContent:{content:'article@html'}};
  let text=policy.cases.find(vector=>vector.name==='long-nonempty-wrapper-is-still-paywall')!.text;
  const engine=createEngine({resolveHost:async()=>true,fetch:async()=>new Response('<article>'+text.replaceAll('\n','<br>')+'</article>')});
  await assert.rejects(engine.content(source,{bookUrl:source.bookSourceUrl},{url:source.bookSourceUrl}),/未取得正文/);
  text=policy.cases.find(vector=>vector.name==='ordinary-short-chapter')!.text;
  assert.equal((await engine.content(source,{bookUrl:source.bookSourceUrl},{url:source.bookSourceUrl})).text,text);
  assert.equal(canMigrateContent(text),false);
});

test('continued-login prompts are gates without mistaking quoted story dialogue for a gate',()=>{
  for(const text of ['請登入以繼續閱讀','请先登录后继续阅读','第12章\n請登入以繼續閱讀']){
    assert.equal(canReadContent(text),false,text);assert.equal(canMigrateContent(text),false,text);
  }
  assert.equal(canReadContent('「請登入以繼續閱讀。」他讀完螢幕上的提示，便把電腦關掉，走到窗邊。'),true);
});
