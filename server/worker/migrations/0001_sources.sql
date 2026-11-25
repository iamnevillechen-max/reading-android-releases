CREATE TABLE sources (
  kind TEXT NOT NULL CHECK(kind IN ('book', 'rss')),
  id TEXT NOT NULL,
  json TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  name TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  enabled_explore INTEGER NOT NULL DEFAULT 0,
  supported INTEGER NOT NULL DEFAULT 0,
  capabilities TEXT NOT NULL DEFAULT '[]',
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending', 'healthy', 'unsupported')),
  failures INTEGER NOT NULL DEFAULT 0,
  retry_at INTEGER NOT NULL DEFAULT 0,
  verified_at INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(kind, id)
);
CREATE INDEX sources_health ON sources(kind, status, enabled, id);
CREATE TABLE catalogue_routes (
  kind TEXT NOT NULL,
  source_id TEXT NOT NULL,
  source_sha256 TEXT NOT NULL,
  label TEXT NOT NULL,
  url TEXT NOT NULL,
  category_id TEXT NOT NULL,
  category_label TEXT NOT NULL,
  filter_ids TEXT NOT NULL DEFAULT '[]',
  PRIMARY KEY(kind, source_id, url),
  FOREIGN KEY(kind, source_id) REFERENCES sources(kind, id) ON DELETE CASCADE
);
CREATE INDEX routes_categories ON catalogue_routes(kind, category_id, source_id);
CREATE TABLE response_cache (
  key TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  source_id TEXT NOT NULL,
  source_sha256 TEXT NOT NULL,
  payload TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX cache_expiry ON response_cache(expires_at);
CREATE INDEX cache_sources ON response_cache(kind, source_id, created_at);
