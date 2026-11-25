import test from 'node:test';
import assert from 'node:assert/strict';
import {dailyReadAvailability} from '../src/storage-availability';
import {createApp,type Env} from '../src/index';
const message="D1_ERROR: Your account has exceeded D1's free tier daily row read limit. Upgrade to a paid plan or wait until tomorrow (midnight UTC) to continue.";
test('daily storage read availability resets at UTC midnight with bounded Retry-After',()=>{
 const now=Date.parse('2026-10-28T23:59:59.001Z'),error=new Error(message),quota=dailyReadAvailability(error,now)!;
 assert.equal(quota.resetAt,Date.parse('2026-10-29T00:00:00Z'));assert.equal(quota.retryAfterSeconds,1);assert.equal(quota.code,'CLOUD_DAILY_READ_LIMIT');assert.match(quota.message,/上午 8 時/);assert.ok(!quota.message.includes('paid'));
 const nested=new Error('storage failed',{cause:error});assert.deepEqual(dailyReadAvailability(nested,now),quota);
 assert.equal(dailyReadAvailability(new Error('remote text '+message),now),undefined);assert.equal(dailyReadAvailability(new Error('D1_ERROR: unknown storage failure'),now),undefined);
});
test('quota failure returns actionable service availability without source IO or leaking the storage exception',async()=>{
 let storageCalls=0;const env={DB:{prepare(){storageCalls++;throw new Error(message)}},ADMIN_TOKEN:'a'.repeat(40),READ_TOKEN:'r'.repeat(40),MIGRATION_TOKEN:'m'.repeat(40)} as unknown as Env;
 const response=await createApp().fetch(new Request('https://reader.example/v1/status',{headers:{Authorization:'Bearer '+env.READ_TOKEN}}),env);
 assert.equal(response.status,503);assert.equal(storageCalls,1);const data=await response.json() as any;assert.equal(data.error.code,'CLOUD_DAILY_READ_LIMIT');assert.match(data.error.message,/離線閱讀/);assert.equal(new Date(data.error.resetAt).getUTCHours(),0);assert.ok(Number(response.headers.get('Retry-After'))>0);assert.ok(Number(response.headers.get('Retry-After'))<=86400);assert.equal(response.headers.get('Cache-Control'),'no-store');assert.ok(!JSON.stringify(data).includes('D1_ERROR'));assert.ok(!JSON.stringify(data).includes('Upgrade'));
});
