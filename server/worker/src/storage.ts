export type SourceKind = 'book' | 'rss';
export type SourceStatus = 'pending' | 'healthy' | 'unsupported';
export interface SourceRow {
  kind: SourceKind; id: string; json: string; sha256: string; name: string;
  enabled: number; enabled_explore: number; supported: number; capabilities: string;
  status: SourceStatus; failures: number; retry_at: number; verified_at: number; updated_at: number;
  web_url?: string | null;
}
export interface RouteRow {
  sourceId: string; sourceName: string; label: string; url: string;
  categoryId: string; categoryLabel: string; fingerprint: string; filterIds?: string[];
}
export interface WebEntryRow {
  sourceId: string; name: string; url: string; categoryId: string; categoryLabel: string; fingerprint: string;
}
export interface ImportSource {
  kind: SourceKind; id: string; json: string; sha256: string; name: string;
  enabled: boolean; enabledExplore: boolean; supported: boolean; capabilities: string[];
}
export interface CatalogueRoute {
  label: string; url: string; categoryId: string; categoryLabel: string; filterIds: string[];
}
export interface VerificationProfile { keyword: string; book_url: string; chapter_url: string }
export type AssessmentSourceMetadata = Omit<SourceRow, 'json'> & { json_bytes: number };
export type AssessmentPlan = { source: SourceRow; supported: boolean; capabilities: string[]; capabilitiesJson?: string };
export type AssessmentReceipt = { id: string; expectedSha256: string; currentSha256: string | null;
  outcome: 'changed' | 'unchanged' | 'stale'; changed: boolean; changeCount: number;
  supported?: boolean; status?: SourceStatus; capabilities?: string[] };
const ASSESSMENT_STATE_FIELDS = ['supported','capabilities','status','enabled','enabled_explore',
  'failures','retry_at','verified_at','updated_at'] as const;
export type AssessmentStorageUsage = Readonly<{ queryCount: number; rowsRead: number | null;
  rowsWritten: number | null; metaAvailable: boolean }>;
export class SourceStore {
  private assessmentQueries = 0;
  private assessmentRowsRead: number | null = 0;
  private assessmentRowsWritten: number | null = 0;
  /** Assessment statements only; no SQL, source identities or response payloads are retained. */
  private recordAssessmentUsage(results: readonly unknown[], expected = results.length): void {
    const add = (total: number | null, value: unknown): number | null => total !== null &&
      typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 &&
      Number.isSafeInteger(total + value) ? total + value : null;
    for (let index = 0; index < expected; index++) {
      this.assessmentQueries++;
      const item = results[index] as {meta?: {rows_read?: unknown; rows_written?: unknown}} | undefined;
      this.assessmentRowsRead = add(this.assessmentRowsRead, item?.meta?.rows_read);
      this.assessmentRowsWritten = add(this.assessmentRowsWritten, item?.meta?.rows_written);
    }
    if (results.length !== expected) this.assessmentRowsRead = this.assessmentRowsWritten = null;
  }
  /** Zero statements is known zero; missing/invalid metadata for any statement stays unknown. */
  assessmentStorageUsage(): AssessmentStorageUsage {
    return {queryCount:this.assessmentQueries, rowsRead:this.assessmentRowsRead,
      rowsWritten:this.assessmentRowsWritten,
      metaAvailable:this.assessmentRowsRead !== null && this.assessmentRowsWritten !== null};
  }

  constructor(private db: D1Database) {}
  async get(kind: SourceKind, id: string): Promise<SourceRow | null> {
    return this.db.prepare(`SELECT sources.*,(SELECT MIN(r.url) FROM catalogue_routes r
      WHERE r.kind=sources.kind AND r.source_id=sources.id AND r.source_sha256=sources.sha256) AS web_url
      FROM sources WHERE kind = ? AND id = ?`).bind(kind, id).first<SourceRow>();
  }
  async receiptSources(kind: SourceKind, ids: string[]): Promise<Map<string, SourceRow>> {
    // The JSON iterator is the left side: each requested opaque ID performs a
    // primary-key lookup instead of scanning every archived source of this kind.
    const result = await this.db.prepare(`SELECT i.value AS requested_id,s.*,
      (SELECT MIN(r.url) FROM catalogue_routes r WHERE r.kind=s.kind AND r.source_id=s.id
        AND r.source_sha256=s.sha256) AS web_url
      FROM json_each(?) i LEFT JOIN sources s ON s.kind=? AND s.id=i.value ORDER BY CAST(i.key AS INTEGER)`)
      .bind(JSON.stringify(ids),kind).all<SourceRow & {requested_id:string}>();
    return new Map(result.results.filter(row=>row.kind!==null).map(row=>[row.requested_id,row]));
  }
  /** Read sizes before fetching any raw rule; indexed IDs, never an archive scan. */
  async assessmentMetadata(kind: SourceKind, ids: string[]): Promise<Map<string, AssessmentSourceMetadata>> {
    const rows = await this.db.prepare(`SELECT s.kind,s.id,s.sha256,s.name,s.enabled,s.enabled_explore,
      s.supported,s.capabilities,s.status,s.failures,s.retry_at,s.verified_at,s.updated_at,
      length(CAST(s.json AS BLOB)) AS json_bytes FROM json_each(?) i CROSS JOIN sources s WHERE s.kind=? AND s.id=i.value`)
      .bind(JSON.stringify(ids),kind).all<AssessmentSourceMetadata>();
    this.recordAssessmentUsage([rows]);
    return new Map(rows.results.map(row=>[row.id,row]));
  }
  async boundedAssessmentSources(kind: SourceKind, expected: Array<{id:string;sha256:string}>, sourceLimit: number, totalLimit: number): Promise<Map<string,SourceRow>> {
    if(!expected.length)return new Map();
    // The second size check is in this same SELECT snapshot, so a rule that
    // changes after the metadata read cannot force an unbounded raw read.
    const rows=await this.db.prepare(`WITH expected AS(SELECT value FROM json_each(?))
      SELECT s.* FROM expected i CROSS JOIN sources s WHERE s.kind=? AND s.id=json_extract(i.value,'$.id')
        AND s.sha256=json_extract(i.value,'$.sha256') AND length(CAST(s.json AS BLOB))<=?
      AND (SELECT COALESCE(SUM(length(CAST(s2.json AS BLOB))),0) FROM expected i2 CROSS JOIN sources s2 WHERE s2.kind=?
        AND s2.id=json_extract(i2.value,'$.id') AND s2.sha256=json_extract(i2.value,'$.sha256'))<=?`)
      .bind(JSON.stringify(expected),kind,sourceLimit,kind,totalLimit).all<SourceRow>();
    this.recordAssessmentUsage([rows]);
    return new Map(rows.results.map(row=>[row.id,row]));
  }
  /** Each cleanup and UPDATE has the same snapshot/SHA guard inside one atomic D1 batch. */
  async reassessSources(kind: SourceKind, plans: AssessmentPlan[]): Promise<AssessmentReceipt[]> {
    if (!plans.length) return [];
    const payload = JSON.stringify(plans.map(plan=>({ id:plan.source.id,sha256:plan.source.sha256,
      before:Object.fromEntries(ASSESSMENT_STATE_FIELDS.map(field=>[field,plan.source[field]])),
      supported:plan.supported?1:0,capabilities:plan.capabilitiesJson??JSON.stringify(plan.capabilities) })));
    const guard = `s.kind=? AND s.id=json_extract(i.value,'$.id') AND s.sha256=json_extract(i.value,'$.sha256') AND `+
      ASSESSMENT_STATE_FIELDS.map(field=>`s.${field}=json_extract(i.value,'$.before.${field}')`).join(' AND ');
    const flip = `s.supported<>json_extract(i.value,'$.supported')`;
    const results = await this.db.batch([
      this.db.prepare(`DELETE FROM catalogue_routes WHERE kind=? AND (source_id,source_sha256) IN
        (SELECT s.id,s.sha256 FROM json_each(?) i CROSS JOIN sources s WHERE ${guard} AND ${flip})`).bind(kind,payload,kind),
      this.db.prepare('UPDATE catalogue_revision SET revision=revision+1 WHERE kind=? AND changes()>0').bind(kind),
      this.db.prepare(`DELETE FROM verification_profiles WHERE kind=? AND source_id IN
        (SELECT s.id FROM json_each(?) i CROSS JOIN sources s WHERE ${guard} AND ${flip})`).bind(kind,payload,kind),
      this.db.prepare(`DELETE FROM maintenance_attempts WHERE kind=? AND source_id IN
        (SELECT s.id FROM json_each(?) i CROSS JOIN sources s WHERE ${guard} AND ${flip})`).bind(kind,payload,kind),
      this.db.prepare(`WITH candidates AS MATERIALIZED (
        SELECT s.rowid AS target_rowid,
          json_extract(i.value,'$.supported') AS next_supported,
          json_extract(i.value,'$.capabilities') AS next_capabilities,
          CASE WHEN ${flip} THEN CASE WHEN json_extract(i.value,'$.supported')=1
            THEN 'pending' ELSE 'unsupported' END ELSE s.status END AS next_status,
          CASE WHEN ${flip} THEN 0 ELSE s.failures END AS next_failures,
          CASE WHEN ${flip} THEN 0 ELSE s.retry_at END AS next_retry_at,
          CASE WHEN ${flip} THEN 0 ELSE s.verified_at END AS next_verified_at
        FROM json_each(?) i CROSS JOIN sources s WHERE ${guard}
          AND (${flip} OR s.capabilities<>json_extract(i.value,'$.capabilities'))
      )
      UPDATE sources SET (supported,capabilities,status,failures,retry_at,verified_at,updated_at)=
        (SELECT c.next_supported,c.next_capabilities,c.next_status,c.next_failures,
          c.next_retry_at,c.next_verified_at,? FROM candidates c WHERE c.target_rowid=sources.rowid)
      WHERE rowid IN(SELECT target_rowid FROM candidates)
      RETURNING id,sha256,supported,capabilities,status`).bind(payload,kind,Date.now()),
      this.db.prepare('UPDATE source_stats SET generation=generation+1,counts_json=NULL WHERE kind=? AND changes()>0').bind(kind),
      this.db.prepare(`SELECT s.kind,s.id,s.sha256,s.supported,s.capabilities,s.status,s.enabled,s.enabled_explore,
        s.failures,s.retry_at,s.verified_at,s.updated_at FROM json_each(?) i CROSS JOIN sources s WHERE s.kind=? AND s.id=json_extract(i.value,'$.id')`)
        .bind(payload,kind),
    ]);
    this.recordAssessmentUsage(results, 7);
    const changed = new Set((results[4].results as Array<{id:string}>).map(row=>row.id));
    const current = new Map((results[6].results as SourceRow[]).map(row=>[row.id,row]));
    return plans.map(plan=>{
      const row=current.get(plan.source.id), applied=changed.has(plan.source.id);
      const matched=!!row&&row.sha256===plan.source.sha256&&(applied||ASSESSMENT_STATE_FIELDS.every(field=>row[field]===plan.source[field]));
      return {id:plan.source.id,expectedSha256:plan.source.sha256,currentSha256:row?.sha256??null,
        outcome:matched?(applied?'changed':'unchanged'):'stale',changed:matched&&applied,changeCount:matched&&applied?1:0,
        ...(matched?{supported:!!row.supported,status:row.status,capabilities:JSON.parse(row.capabilities)}:{})};
    });
  }
  async importSources(sources: ImportSource[]): Promise<SourceRow[]> {
    const now = Date.now();
    const payload = JSON.stringify(sources);
    const imported = await this.db.prepare(`
      INSERT INTO sources(kind,id,json,sha256,name,enabled,enabled_explore,supported,capabilities,status,updated_at)
      SELECT json_extract(value,'$.kind'),json_extract(value,'$.id'),json_extract(value,'$.json'),
      json_extract(value,'$.sha256'),json_extract(value,'$.name'),json_extract(value,'$.enabled'),
      json_extract(value,'$.enabledExplore'),json_extract(value,'$.supported'),json_extract(value,'$.capabilities'),
      CASE WHEN json_extract(value,'$.supported') THEN 'pending' ELSE 'unsupported' END,?
      FROM json_each(?) WHERE 1
      ON CONFLICT(kind,id) DO UPDATE SET
      json=excluded.json, name=excluded.name, enabled=excluded.enabled,
      enabled_explore=excluded.enabled_explore, supported=excluded.supported, capabilities=excluded.capabilities,
      status=CASE WHEN excluded.supported=0 THEN 'unsupported'
        WHEN sources.sha256=excluded.sha256 AND sources.supported=excluded.supported THEN sources.status ELSE 'pending' END,
      failures=CASE WHEN sources.sha256=excluded.sha256 AND sources.supported=excluded.supported THEN sources.failures ELSE 0 END,
      retry_at=CASE WHEN sources.sha256=excluded.sha256 AND sources.supported=excluded.supported THEN sources.retry_at ELSE 0 END,
      verified_at=CASE WHEN sources.sha256=excluded.sha256 AND sources.supported=excluded.supported THEN sources.verified_at ELSE 0 END,
      sha256=excluded.sha256,updated_at=excluded.updated_at
      WHERE sources.sha256<>excluded.sha256 OR sources.supported<>excluded.supported
        OR sources.capabilities<>excluded.capabilities OR sources.enabled_explore<>excluded.enabled_explore
    `).bind(now, payload).run();
    if (imported.meta.changes) await this.db.prepare(`UPDATE source_stats
      SET generation=generation+1,counts_json=NULL WHERE kind=?`).bind(sources[0].kind).run();
    // A constant number of SQL queries per chunk regardless of source count. JSON1 decodes
    // the transport envelope; the source JSON string itself is stored unchanged.
    const readback = await this.db.prepare(`SELECT s.*,(SELECT MIN(r.url) FROM catalogue_routes r
      WHERE r.kind=s.kind AND r.source_id=s.id AND r.source_sha256=s.sha256) AS web_url FROM sources s JOIN json_each(?) i
      ON s.kind=json_extract(i.value,'$.kind') AND s.id=json_extract(i.value,'$.id')`)
      .bind(payload).all<SourceRow>();
    const byId = new Map(readback.results.map(row => [row.id, row]));
    return sources.map(source => byId.get(source.id)!);
  }
  async list(kind: SourceKind, after: string | null, limit: number, active = false): Promise<SourceRow[]> {
    const statement = this.db.prepare(`SELECT kind,id,name,sha256,enabled,enabled_explore,supported,
      capabilities,status,failures,retry_at,verified_at,updated_at,
      (SELECT MIN(r.url) FROM catalogue_routes r WHERE r.kind=sources.kind AND r.source_id=sources.id
        AND r.source_sha256=sources.sha256) AS web_url FROM sources
      WHERE kind=? ${after === null ? '' : 'AND id>?'} ${active ? "AND status='healthy' AND enabled=1" : ''}
      ORDER BY id LIMIT ?`);
    const result = await (after === null ? statement.bind(kind,limit) : statement.bind(kind,after,limit)).all<SourceRow>();
    return result.results;
  }
  async activeBookIds(): Promise<string[]> {
    const result=await this.db.prepare("SELECT id FROM sources WHERE kind='book' AND status='healthy' AND enabled=1 ORDER BY id")
      .all<{id:string}>();
    return result.results.map(row=>row.id);
  }
  async counts(kind: SourceKind): Promise<{ total: number; checked: number; healthy: number; pending: number;
    unsupported: number; probed: number; revision: number; catalogueRevision: number }> {
    const cached = await this.db.prepare(`SELECT generation,counts_json,
      (SELECT revision FROM catalogue_revision WHERE kind=?) AS catalogueRevision FROM source_stats WHERE kind=?`)
      .bind(kind,kind).first<{generation:number;counts_json:string|null;catalogueRevision:number}>();
    if(cached?.counts_json) {
      const prior=JSON.parse(cached.counts_json);
      if(Number.isSafeInteger(prior.probed))return {...prior,revision:cached.generation,catalogueRevision:cached.catalogueRevision};
    }
    const row = await this.db.prepare(`SELECT COUNT(*) AS total,
      COALESCE(SUM(CASE WHEN status='healthy' OR status='unsupported' OR failures>0 THEN 1 ELSE 0 END),0) AS checked,
      COALESCE(SUM(CASE WHEN status='healthy' AND enabled=1 THEN 1 ELSE 0 END),0) AS healthy,
      COALESCE(SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END),0) AS pending,
      COALESCE(SUM(CASE WHEN status='unsupported' THEN 1 ELSE 0 END),0) AS unsupported,
      COALESCE(SUM(CASE WHEN verified_at>0 OR failures>0 THEN 1 ELSE 0 END),0) AS probed FROM sources WHERE kind=?`).bind(kind)
      .first<{ total: number; checked: number; healthy: number; pending: number; unsupported: number; probed: number }>();
    if(!cached||!row)throw new Error('SOURCE_STATS_MISSING');
    // If a real mutation occurs between the aggregate and this write, the
    // generation mismatch prevents an indefinitely stale cache from being saved.
    await this.db.prepare(`UPDATE source_stats SET counts_json=? WHERE kind=? AND generation=?`)
      .bind(JSON.stringify(row),kind,cached.generation).run();
    return {...row,revision:cached.generation,catalogueRevision:cached.catalogueRevision};
  }
  async routes(kind: SourceKind, after = '', limit = 100): Promise<RouteRow[]> {
    const result = await this.db.prepare(`SELECT r.source_id AS sourceId,s.name AS sourceName,
      r.label,r.url,r.category_id AS categoryId,r.category_label AS categoryLabel,
      r.source_sha256 AS fingerprint,r.filter_ids AS filterIdsJson
      FROM catalogue_routes r JOIN sources s ON s.kind=r.kind AND s.id=r.source_id
      WHERE r.kind=? AND s.status='healthy' AND s.enabled=1 AND s.enabled_explore=1
      AND NOT EXISTS(SELECT 1 FROM json_each(s.capabilities) WHERE value='web')
      AND r.source_sha256=s.sha256 AND s.id IN
      (SELECT id FROM sources WHERE kind=? AND status='healthy' AND enabled=1 AND enabled_explore=1 AND id>? ORDER BY id LIMIT ?)
      AND r.url=(SELECT MIN(r2.url) FROM catalogue_routes r2 WHERE r2.kind=r.kind AND r2.source_id=r.source_id
        AND r2.source_sha256=r.source_sha256 AND r2.category_id=r.category_id)
      ORDER BY r.category_id,s.id,r.url`).bind(kind, kind, after, limit)
      .all<RouteRow & { filterIdsJson: string }>();
    return result.results.map(({ filterIdsJson, ...route }) => ({ ...route, filterIds: JSON.parse(filterIdsJson) }));
  }
  async webEntries(after = '', limit = 100): Promise<WebEntryRow[]> {
    const result = await this.db.prepare(`SELECT r.source_id AS sourceId,s.name,r.url,
      r.category_id AS categoryId,r.category_label AS categoryLabel,r.source_sha256 AS fingerprint
      FROM catalogue_routes r JOIN sources s ON s.kind=r.kind AND s.id=r.source_id
      WHERE r.kind='rss' AND s.status='healthy' AND s.enabled=1 AND r.source_sha256=s.sha256
      AND EXISTS(SELECT 1 FROM json_each(s.capabilities) WHERE value='web')
      AND s.id IN (SELECT id FROM sources WHERE kind='rss' AND status='healthy' AND enabled=1
        AND enabled_explore=1 AND id>? ORDER BY id LIMIT ?)
      ORDER BY s.id,r.url`).bind(after, limit).all<WebEntryRow>();
    return result.results;
  }
  async sourceRoutes(source: SourceRow): Promise<Array<{ label: string; url: string; categoryId: string }>> {
    const result = await this.db.prepare(`SELECT label,url,category_id AS categoryId FROM catalogue_routes
      WHERE kind=? AND source_id=? AND source_sha256=? ORDER BY category_id,url`)
      .bind(source.kind, source.id, source.sha256).all<{ label: string; url: string; categoryId: string }>();
    return result.results;
  }
  async catalogueSourcePage(kind: SourceKind, after: string): Promise<string[]> {
    const rows = await this.db.prepare(`SELECT id FROM sources WHERE kind=? AND status='healthy' AND enabled=1
      AND enabled_explore=1 AND id>? ORDER BY id LIMIT 101`).bind(kind, after).all<{ id: string }>();
    return rows.results.map(row => row.id);
  }
  async markHealthy(source: SourceRow, routes: CatalogueRoute[], beforeWrite?: (estimatedWrites:number)=>Promise<void>): Promise<boolean> {
    const now = Date.now();
    const routeJson=JSON.stringify(routes);
    if(beforeWrite){
      const estimate=await this.db.prepare(`WITH incoming AS (
        SELECT json_extract(i.value,'$.url') AS url,json_extract(i.value,'$.label') AS label,
          json_extract(i.value,'$.categoryId') AS base_category_id,
          COALESCE(o.category_id,json_extract(i.value,'$.categoryId')) AS category_id,
          COALESCE(o.category_label,json_extract(i.value,'$.categoryLabel')) AS category_label,
          json_extract(i.value,'$.filterIds') AS filter_ids
        FROM json_each(?) i LEFT JOIN category_overrides o ON o.kind=? AND o.source_id=?
          AND o.base_category_id=json_extract(i.value,'$.categoryId'))
        SELECT 10+3*((SELECT COUNT(*) FROM catalogue_routes r WHERE r.kind=? AND r.source_id=?
          AND (r.source_sha256<>? OR r.url NOT IN(SELECT url FROM incoming)))+
          (SELECT COUNT(*) FROM incoming i LEFT JOIN catalogue_routes r ON r.kind=? AND r.source_id=? AND r.url=i.url
            WHERE r.url IS NULL OR r.source_sha256<>? OR r.label<>i.label OR r.category_id<>i.category_id
              OR r.category_label<>i.category_label OR r.base_category_id<>i.base_category_id OR r.filter_ids<>i.filter_ids)) AS writes`)
        .bind(routeJson,source.kind,source.id,source.kind,source.id,source.sha256,source.kind,source.id,source.sha256)
        .first<{writes:number}>();
      // Ten includes source/health indexes, an optional profile, and the tiny
      // statistics/revision records. Route changes include both existing indexes.
      await beforeWrite(estimate?.writes??Number.MAX_SAFE_INTEGER);
    }
    const statements = [
      this.db.prepare(`UPDATE source_stats SET generation=generation+1,counts_json=NULL WHERE kind=?
        AND EXISTS(SELECT 1 FROM sources WHERE kind=? AND id=? AND sha256=? AND status<>'healthy')`)
        .bind(source.kind,source.kind,source.id,source.sha256),
      this.db.prepare(`UPDATE sources SET status='healthy',failures=0,retry_at=0,verified_at=?,updated_at=?
        WHERE kind=? AND id=? AND sha256=?`).bind(now, now, source.kind, source.id, source.sha256),
      this.db.prepare(`DELETE FROM catalogue_routes WHERE kind=? AND source_id=?
        AND (source_sha256<>? OR url NOT IN (SELECT json_extract(value,'$.url') FROM json_each(?)))
        AND EXISTS(SELECT 1 FROM sources WHERE kind=? AND id=? AND sha256=?)`)
        .bind(source.kind, source.id, source.sha256, routeJson, source.kind, source.id, source.sha256),
      this.db.prepare(`INSERT INTO catalogue_routes
        (kind,source_id,source_sha256,label,url,category_id,category_label,filter_ids,base_category_id)
        SELECT ?,?,?,json_extract(i.value,'$.label'),json_extract(i.value,'$.url'),
        COALESCE(o.category_id,json_extract(i.value,'$.categoryId')),
        COALESCE(o.category_label,json_extract(i.value,'$.categoryLabel')),
        json_extract(i.value,'$.filterIds'),json_extract(i.value,'$.categoryId') FROM json_each(?) i
        LEFT JOIN category_overrides o ON o.kind=? AND o.source_id=? AND o.base_category_id=json_extract(i.value,'$.categoryId')
        WHERE EXISTS(SELECT 1 FROM sources WHERE kind=? AND id=? AND sha256=?)
        ON CONFLICT(kind,source_id,url) DO UPDATE SET source_sha256=excluded.source_sha256,
          label=excluded.label,category_id=excluded.category_id,category_label=excluded.category_label,
          filter_ids=excluded.filter_ids,base_category_id=excluded.base_category_id
        WHERE catalogue_routes.source_sha256<>excluded.source_sha256 OR catalogue_routes.label<>excluded.label
          OR catalogue_routes.category_id<>excluded.category_id OR catalogue_routes.category_label<>excluded.category_label
          OR catalogue_routes.filter_ids<>excluded.filter_ids OR catalogue_routes.base_category_id<>excluded.base_category_id`)
        .bind(source.kind, source.id, source.sha256, routeJson, source.kind, source.id,
          source.kind, source.id, source.sha256),
    ];
    const result = await this.db.batch(statements);
    if(result[2].meta.changes||result[3].meta.changes)await this.db.prepare(`UPDATE catalogue_revision
      SET revision=revision+1 WHERE kind=?`).bind(source.kind).run();
    return result[1].meta.changes > 0;
  }
  async verificationProfile(source:SourceRow):Promise<VerificationProfile|null>{
    return this.db.prepare('SELECT keyword,book_url,chapter_url FROM verification_profiles WHERE kind=? AND source_id=?')
      .bind(source.kind,source.id).first<VerificationProfile>();
  }
  async saveVerificationProfile(source:SourceRow,profile:VerificationProfile):Promise<void>{
    await this.db.prepare(`INSERT INTO verification_profiles(kind,source_id,keyword,book_url,chapter_url)
      SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM sources WHERE kind=? AND id=? AND sha256=? AND status='healthy')
      ON CONFLICT(kind,source_id) DO UPDATE SET keyword=excluded.keyword,book_url=excluded.book_url,chapter_url=excluded.chapter_url
      WHERE verification_profiles.keyword<>excluded.keyword OR verification_profiles.book_url<>excluded.book_url
        OR verification_profiles.chapter_url<>excluded.chapter_url`)
      .bind(source.kind,source.id,profile.keyword,profile.book_url,profile.chapter_url,source.kind,source.id,source.sha256).run();
  }
  async overrideCategory(source: SourceRow, from: string, target: { id: string; label: string }): Promise<{ found: boolean; changed: boolean }> {
    const matching = await this.db.prepare(`SELECT COUNT(*) AS n FROM catalogue_routes
      WHERE kind=? AND source_id=? AND source_sha256=? AND category_id=?`)
      .bind(source.kind,source.id,source.sha256,from).first<{ n:number }>();
    if (!matching?.n) {
      // A repeated request can arrive after the first response was lost. The
      // last applied-from marker acknowledges it without rewriting or chaining.
      const prior = await this.db.prepare(`SELECT COUNT(*) AS n FROM category_overrides o
        WHERE o.kind=? AND o.source_id=? AND o.applied_from=? AND o.category_id=?
        AND EXISTS(SELECT 1 FROM catalogue_routes r WHERE r.kind=o.kind AND r.source_id=o.source_id
          AND r.base_category_id=o.base_category_id AND r.category_id=o.category_id AND r.source_sha256=?)`)
        .bind(source.kind,source.id,from,target.id,source.sha256).first<{ n:number }>();
      return { found: !!prior?.n, changed: false };
    }
    if (from === target.id) return { found:true,changed:false };
    const result = await this.db.batch([
      this.db.prepare(`INSERT INTO category_overrides(kind,source_id,base_category_id,category_id,category_label,applied_from)
        SELECT DISTINCT ?,?,r.base_category_id,?,?,? FROM catalogue_routes r
        WHERE r.kind=? AND r.source_id=? AND r.source_sha256=? AND r.category_id=?
        AND EXISTS(SELECT 1 FROM sources s WHERE s.kind=r.kind AND s.id=r.source_id AND s.sha256=? AND s.status='healthy')
        ON CONFLICT(kind,source_id,base_category_id) DO UPDATE SET category_id=excluded.category_id,
          category_label=excluded.category_label,applied_from=excluded.applied_from
        WHERE category_overrides.category_id<>excluded.category_id OR category_overrides.applied_from<>excluded.applied_from`)
        .bind(source.kind,source.id,target.id,target.label,from,source.kind,source.id,source.sha256,from,source.sha256),
      this.db.prepare(`UPDATE catalogue_routes SET category_id=?,category_label=?
        WHERE kind=? AND source_id=? AND source_sha256=? AND category_id=?
        AND EXISTS(SELECT 1 FROM sources WHERE kind=? AND id=? AND sha256=? AND status='healthy')`)
        .bind(target.id,target.label,source.kind,source.id,source.sha256,from,source.kind,source.id,source.sha256),
      this.db.prepare(`UPDATE catalogue_revision SET revision=revision+1 WHERE kind=? AND changes()>0`).bind(source.kind),
    ]);
    return { found: result[1].meta.changes > 0, changed: result[1].meta.changes > 0 };
  }
  async failure(source: SourceRow): Promise<void> {
    await this.db.batch([
      this.db.prepare(`UPDATE source_stats SET generation=generation+1,counts_json=NULL WHERE kind=?
        AND EXISTS(SELECT 1 FROM sources WHERE kind=? AND id=? AND sha256=?
          AND ((status='pending' AND failures=0) OR (status='healthy' AND failures>=1)))`)
        .bind(source.kind,source.kind,source.id,source.sha256),
      this.db.prepare(`UPDATE sources SET failures=failures+1,
      retry_at=CASE WHEN failures>=1 THEN ? ELSE 0 END,
      status=CASE WHEN failures>=1 THEN 'pending' ELSE status END,updated_at=?
      WHERE kind=? AND id=? AND sha256=?`).bind(Date.now() + 5 * 60_000, Date.now(), source.kind, source.id, source.sha256),
    ]);
  }
  async quarantine(source:SourceRow,retryAt:number):Promise<void>{
    await this.db.batch([
      this.db.prepare(`UPDATE source_stats SET generation=generation+1,counts_json=NULL WHERE kind=?
        AND EXISTS(SELECT 1 FROM sources WHERE kind=? AND id=? AND sha256=?
          AND (status='healthy' OR (status='pending' AND failures=0)))`)
        .bind(source.kind,source.kind,source.id,source.sha256),
      this.db.prepare(`UPDATE sources SET status='pending',failures=MAX(failures,1),retry_at=MAX(retry_at,?),updated_at=?
        WHERE kind=? AND id=? AND sha256=?`).bind(retryAt,Date.now(),source.kind,source.id,source.sha256),
    ]);
  }
  async success(source: SourceRow): Promise<void> {
    if (!source.failures && !source.retry_at) return;
    // A successful individual read does not certify a quarantined source's full
    // chain or erase its attempted/checked state; only verification restores it.
    await this.db.prepare(`UPDATE sources SET failures=CASE WHEN status='healthy' THEN 0 ELSE failures END,retry_at=0
      WHERE kind=? AND id=? AND sha256=?`)
      .bind(source.kind, source.id, source.sha256).run();
  }
  async cached(key: string, source: SourceRow): Promise<unknown | undefined> {
    const row = await this.db.prepare(`SELECT payload FROM response_cache
      WHERE key=? AND kind=? AND source_id=? AND source_sha256=? AND expires_at>?`)
      .bind(key, source.kind, source.id, source.sha256, Date.now()).first<{ payload: string }>();
    if (!row) return undefined;
    try { return JSON.parse(row.payload); } catch { return undefined; }
  }
  async cache(key: string, source: SourceRow, payload: unknown, ttlMs: number): Promise<void> {
    const text = JSON.stringify(payload);
    if (new TextEncoder().encode(text).length > 256_000) return;
    const now = Date.now();
    await this.db.batch([
      this.db.prepare(`INSERT OR REPLACE INTO response_cache
        (key,kind,source_id,source_sha256,payload,expires_at,created_at) VALUES(?,?,?,?,?,?,?)`)
        .bind(key, source.kind, source.id, source.sha256, text, now + ttlMs, now),
      this.db.prepare('DELETE FROM response_cache WHERE expires_at<=?').bind(now),
      this.db.prepare(`DELETE FROM response_cache WHERE key IN
        (SELECT key FROM response_cache WHERE kind=? AND source_id=? ORDER BY created_at DESC,key LIMIT -1 OFFSET 16)`)
        .bind(source.kind, source.id),
      this.db.prepare(`DELETE FROM response_cache WHERE key IN
        (SELECT key FROM response_cache ORDER BY created_at DESC,key LIMIT -1 OFFSET 512)`),
    ]);
  }
}
