CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL);
INSERT INTO state VALUES(1,0);
CREATE TABLE jobs(id TEXT PRIMARY KEY,name TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0);
CREATE TABLE sessions(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),start_ms INTEGER NOT NULL,end_ms INTEGER,timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL,origin TEXT NOT NULL,CHECK(end_ms IS NULL OR end_ms>start_ms));
CREATE UNIQUE INDEX one_open_session ON sessions((1)) WHERE end_ms IS NULL;
CREATE INDEX session_dates ON sessions(start_ms,end_ms);
CREATE TABLE commands(id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,version INTEGER NOT NULL,conflict TEXT);
