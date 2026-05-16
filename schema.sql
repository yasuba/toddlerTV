CREATE TABLE IF NOT EXISTS videos (
  id TEXT PRIMARY KEY,
  source_url TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  runtime_seconds INTEGER,
  status TEXT NOT NULL,
  tags TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS external_metadata (
  video_id TEXT NOT NULL REFERENCES videos(id),
  source TEXT NOT NULL,
  fetched_at TEXT NOT NULL,
  raw_json TEXT NOT NULL,
  PRIMARY KEY (video_id, source)
);

CREATE TABLE IF NOT EXISTS tag_overrides (
  video_id TEXT NOT NULL REFERENCES videos(id),
  field_name TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (video_id, field_name)
);

CREATE TABLE IF NOT EXISTS play_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  video_id TEXT NOT NULL REFERENCES videos(id),
  played_at TEXT NOT NULL,
  session_id TEXT REFERENCES sessions(id),
  completed INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  built_at TEXT NOT NULL,
  target_duration_seconds INTEGER NOT NULL,
  mood_filter TEXT,
  video_ids TEXT NOT NULL
);
