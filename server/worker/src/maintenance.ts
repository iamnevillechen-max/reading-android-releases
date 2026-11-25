import { SourceStore, type SourceRow } from './storage';

export class MaintenanceBudgetError extends Error {
  readonly code='MAINTENANCE_BUDGET';
  constructor(){super('Maintenance daily write budget exhausted');}
}
export type WriteGuard=(estimatedWrites:number)=>Promise<void>;
type Verification=(source:SourceRow,guard:WriteGuard)=>Promise<{verified?:boolean;reasonCode?:string}>;
type State={last_run:number;last_finished:number;last_trigger:string;lease_until:number;
  last_processed:number;last_healthy:number;last_failed:number;last_deferred:number;last_error:string};
const HARD_DAILY_WRITES=5000;
const SOURCE_BASE_RESERVATION=20;
export function maintenanceLimit(value?:string):number {
  const requested=Number(value??HARD_DAILY_WRITES);
  return Number.isSafeInteger(requested)?Math.max(0,Math.min(HARD_DAILY_WRITES,requested)):HARD_DAILY_WRITES;
}
async function reserve(db:D1Database,day:string,amount:number,limit:number):Promise<boolean>{
  const result=await db.prepare(`INSERT INTO maintenance_daily(day,reserved_writes) SELECT ?,? WHERE ?<=?
    ON CONFLICT(day) DO UPDATE SET reserved_writes=reserved_writes+excluded.reserved_writes
    WHERE maintenance_daily.reserved_writes+excluded.reserved_writes<=?`)
    .bind(day,amount,amount,limit,limit).run();
  return result.meta.changes>0;
}
export async function maintenanceStatus(db:D1Database,enabled:boolean,limit:number,now=Date.now()){
  const day=new Date(now).toISOString().slice(0,10);
  const state=await db.prepare('SELECT * FROM maintenance_state WHERE singleton=1').first<State>();
  const daily=await db.prepare('SELECT reserved_writes FROM maintenance_daily WHERE day=?').bind(day).first<{reserved_writes:number}>();
  return {enabled,intervalMinutes:30,maxSourcesPerRun:2,healthyEligibilityHours:6,
    dailyWriteLimit:limit,dailyReservedWrites:daily?.reserved_writes??0,
    lastRunAt:state?.last_run??0,lastFinishedAt:state?.last_finished??0,lastTrigger:state?.last_trigger??'',
    leaseActive:(state?.lease_until??0)>now,lastProcessed:state?.last_processed??0,lastHealthy:state?.last_healthy??0,
    lastFailed:state?.last_failed??0,lastDeferred:state?.last_deferred??0,lastErrorCode:state?.last_error??''};
}
/** No source body is edited or deleted. An uncertain probe can only withdraw a catalogue entry. */
export async function runMaintenance(db:D1Database,verify:Verification,options:{now?:number;limit?:number;trigger?:string}={}){
  const now=options.now??Date.now(),limit=maintenanceLimit(String(options.limit??HARD_DAILY_WRITES));
  const clock=options.now===undefined?()=>Date.now():()=>now;
  const day=new Date(now).toISOString().slice(0,10),token=crypto.randomUUID();
  // Reserve the lease, cursor, outcome and budget bookkeeping before starting.
  // No-op exhaustion costs no repeated source/route/index writes.
  if(!await reserve(db,day,20,limit))return {skipped:'daily-budget',processed:0};
  const leased=await db.prepare(`UPDATE maintenance_state SET lease_token=?,lease_until=?,last_run=?,last_trigger=?,
    last_processed=0,last_healthy=0,last_failed=0,last_deferred=0,last_error='' WHERE singleton=1 AND lease_until<=?`)
    .bind(token,now+10*60_000,now,options.trigger??'scheduled',now).run();
  if(!leased.meta.changes)return {skipped:'lease',processed:0};
  let processed=0,healthy=0,failed=0,deferred=0,lastError='';
  const selected=new Set<string>();
  const store=new SourceStore(db);
  async function select(lane:'health'|'pending'):Promise<SourceRow|null>{
    const eligibility=lane==='health'
      ? `((s.status='healthy' AND s.verified_at<=?) OR (s.status='pending' AND s.verified_at>0 AND s.retry_at<=?))`
      : `(s.status='pending' AND s.retry_at<=?)`;
    const statement=db.prepare(`SELECT s.kind,s.id,s.sha256,s.status,1 AS supported,1 AS enabled,s.retry_at,s.verified_at
      FROM sources s LEFT JOIN maintenance_attempts a ON a.kind=s.kind AND a.source_id=s.id
      WHERE s.supported=1 AND s.enabled=1 AND ${eligibility}
      ORDER BY COALESCE(a.last_attempt,0),s.verified_at,s.kind,s.id LIMIT 3`);
    const rows=await(lane==='health'?statement.bind(now-6*60*60_000,now):statement.bind(now)).all<SourceRow>();
    return rows.results.find(source=>!selected.has(source.kind+'\u0000'+source.id))??null;
  }
  try{
    const daily=await db.prepare('SELECT cleanup_done FROM maintenance_daily WHERE day=?').bind(day).first<{cleanup_done:number}>();
    if(!daily?.cleanup_done&&await reserve(db,day,160,limit)){
      await db.batch([
        db.prepare('DELETE FROM response_cache WHERE key IN (SELECT key FROM response_cache WHERE expires_at<=? LIMIT 16)').bind(now),
        db.prepare(`DELETE FROM catalogue_routes WHERE rowid IN (SELECT r.rowid FROM catalogue_routes r
          LEFT JOIN sources s ON s.kind=r.kind AND s.id=r.source_id WHERE s.id IS NULL OR s.sha256<>r.source_sha256 OR s.status='unsupported' LIMIT 16)`),
        db.prepare('UPDATE maintenance_daily SET cleanup_done=1 WHERE day=?').bind(day),
        db.prepare('DELETE FROM maintenance_daily WHERE day IN (SELECT day FROM maintenance_daily WHERE day<? ORDER BY day LIMIT 8)')
          .bind(new Date(now-35*24*60*60_000).toISOString().slice(0,10)),
      ]);
    }
    for(const lane of ['health','pending'] as const){
      const source=await select(lane)??await select(lane==='health'?'pending':'health');
      if(!source)continue;
      if(!await reserve(db,day,SOURCE_BASE_RESERVATION,limit)){deferred++;break;}
      selected.add(source.kind+'\u0000'+source.id);
      const advance=await db.batch([
        db.prepare(`INSERT INTO maintenance_attempts(kind,source_id,last_attempt) VALUES(?,?,?)
          ON CONFLICT(kind,source_id) DO UPDATE SET last_attempt=excluded.last_attempt`).bind(source.kind,source.id,now),
        db.prepare(`UPDATE maintenance_state SET ${lane==='health'?'health_cursor':'pending_cursor'}=? WHERE singleton=1 AND lease_token=?`)
          .bind(source.kind+'\u0000'+source.id,token),
      ]);
      if(!advance[1].meta.changes){deferred++;break;}
      processed++;
      const guard:WriteGuard=async estimated=>{
        const owned=await db.prepare('SELECT 1 AS owned FROM maintenance_state WHERE singleton=1 AND lease_token=? AND lease_until>?')
          .bind(token,clock()).first<{owned:number}>();
        if(!owned)throw new MaintenanceBudgetError();
        const additional=Math.max(0,Math.ceil(estimated)-SOURCE_BASE_RESERVATION);
        if(additional&&!await reserve(db,day,additional,limit))throw new MaintenanceBudgetError();
      };
      try{
        const result=await verify(source,guard);
        if(result.verified){healthy++;continue;}
        failed++;lastError=result.reasonCode&&/^[A-Z][A-Z0-9_]{1,63}$/.test(result.reasonCode)?result.reasonCode:'MAINTENANCE_PROBE_FAILED';
      }catch(error){
        const code=String((error as {code?:unknown})?.code??'');
        if(error instanceof MaintenanceBudgetError||code==='SOURCE_CHANGED'){deferred++;continue;}
        failed++;lastError=/^[A-Z][A-Z0-9_]{1,63}$/.test(code)?code:'MAINTENANCE_PROBE_FAILED';
      }
      // A failed scheduled recheck is withdrawn immediately, including its first
      // failure. The exact source and successful probe profile remain archived.
      await store.quarantine(source,now+5*60_000);
    }
  }catch{lastError='MAINTENANCE_STORAGE_FAILED';}
  finally{
    await db.prepare(`UPDATE maintenance_state SET lease_token='',lease_until=0,last_finished=?,last_processed=?,
      last_healthy=?,last_failed=?,last_deferred=?,last_error=? WHERE singleton=1 AND lease_token=?`)
      .bind(Date.now(),processed,healthy,failed,deferred,lastError,token).run();
  }
  return {processed,healthy,failed,deferred,errorCode:lastError||null};
}
