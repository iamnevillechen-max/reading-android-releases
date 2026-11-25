ALTER TABLE catalogue_routes ADD COLUMN base_category_id TEXT NOT NULL DEFAULT 'other';
UPDATE catalogue_routes SET base_category_id=category_id;
CREATE TABLE category_overrides (
  kind TEXT NOT NULL CHECK(kind IN ('book','rss')),
  source_id TEXT NOT NULL,
  base_category_id TEXT NOT NULL,
  category_id TEXT NOT NULL,
  category_label TEXT NOT NULL,
  applied_from TEXT NOT NULL,
  PRIMARY KEY(kind,source_id,base_category_id),
  FOREIGN KEY(kind,source_id) REFERENCES sources(kind,id) ON DELETE CASCADE
);
CREATE TABLE catalogue_revision (
  kind TEXT PRIMARY KEY CHECK(kind IN ('book','rss')),
  revision INTEGER NOT NULL DEFAULT 0
);
INSERT INTO catalogue_revision(kind,revision) VALUES('book',0),('rss',0);
