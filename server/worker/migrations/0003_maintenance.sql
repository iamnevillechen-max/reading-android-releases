CREATE TABLE verification_profiles (
  kind TEXT NOT NULL,
  source_id TEXT NOT NULL,
  keyword TEXT NOT NULL DEFAULT '',
  book_url TEXT NOT NULL DEFAULT '',
  chapter_url TEXT NOT NULL DEFAULT '',
  PRIMARY KEY(kind,source_id),
  FOREIGN KEY(kind,source_id) REFERENCES sources(kind,id) ON DELETE CASCADE
);
CREATE INDEX maintenance_eligible ON sources(status,verified_at,retry_at,kind,id,sha256)
  WHERE supported=1 AND enabled=1;
-- Two durable cache rows replace a full source-table aggregate on every app
-- refresh. A generation guards against a concurrent import/recheck racing a
-- cache fill; only real changes to the aggregate invalidate it.
CREATE TABLE source_stats (
  kind TEXT PRIMARY KEY CHECK(kind IN ('book','rss')),
  generation INTEGER NOT NULL DEFAULT 0,
  counts_json TEXT
);
INSERT INTO source_stats(kind) VALUES('book'),('rss');
CREATE TABLE maintenance_state (
  singleton INTEGER PRIMARY KEY CHECK(singleton=1),
  lease_token TEXT NOT NULL DEFAULT '',
  lease_until INTEGER NOT NULL DEFAULT 0,
  last_run INTEGER NOT NULL DEFAULT 0,
  last_finished INTEGER NOT NULL DEFAULT 0,
  last_trigger TEXT NOT NULL DEFAULT '',
  health_cursor TEXT NOT NULL DEFAULT '',
  pending_cursor TEXT NOT NULL DEFAULT '',
  last_processed INTEGER NOT NULL DEFAULT 0,
  last_healthy INTEGER NOT NULL DEFAULT 0,
  last_failed INTEGER NOT NULL DEFAULT 0,
  last_deferred INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT ''
);
INSERT INTO maintenance_state(singleton) VALUES(1);
CREATE TABLE maintenance_attempts (
  kind TEXT NOT NULL,
  source_id TEXT NOT NULL,
  last_attempt INTEGER NOT NULL,
  PRIMARY KEY(kind,source_id),
  FOREIGN KEY(kind,source_id) REFERENCES sources(kind,id) ON DELETE CASCADE
);
CREATE INDEX maintenance_oldest ON maintenance_attempts(last_attempt,kind,source_id);
CREATE TABLE maintenance_daily (
  day TEXT PRIMARY KEY,
  reserved_writes INTEGER NOT NULL DEFAULT 0,
  cleanup_done INTEGER NOT NULL DEFAULT 0
);
