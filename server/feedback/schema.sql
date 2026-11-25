CREATE TABLE IF NOT EXISTS reports (
  id TEXT PRIMARY KEY NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('android', 'dot')),
  version TEXT NOT NULL,
  payload TEXT NOT NULL,
  received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved'))
);
CREATE INDEX IF NOT EXISTS reports_inbox ON reports(status, received_at DESC);
