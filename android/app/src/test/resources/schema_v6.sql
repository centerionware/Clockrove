CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL);
INSERT INTO state VALUES(1,0);
CREATE TABLE jobs(id TEXT PRIMARY KEY,name TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0);
CREATE TABLE sessions(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),start_ms INTEGER NOT NULL,end_ms INTEGER,timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL,origin TEXT NOT NULL,CHECK(end_ms IS NULL OR end_ms>start_ms));
CREATE UNIQUE INDEX one_open_session ON sessions((1)) WHERE end_ms IS NULL;
CREATE INDEX session_dates ON sessions(start_ms,end_ms);
CREATE TABLE commands(id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,version INTEGER NOT NULL,conflict TEXT);
CREATE TABLE location_points(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),measured_ms INTEGER NOT NULL,received_ms INTEGER NOT NULL,latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),accuracy REAL NOT NULL CHECK(accuracy>=0));
CREATE INDEX location_session_date ON location_points(session_id,measured_ms);
CREATE TABLE pending_departure(session_id TEXT PRIMARY KEY REFERENCES sessions(id),detected_ms INTEGER NOT NULL,deadline_ms INTEGER NOT NULL CHECK(deadline_ms>=detected_ms));
CREATE UNIQUE INDEX one_departure_candidate ON pending_departure((1));
CREATE TABLE automation_policies(job_id TEXT PRIMARY KEY REFERENCES jobs(id),enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN(0,1)),auto_start INTEGER NOT NULL DEFAULT 0 CHECK(auto_start IN(0,1)),automatic_departure INTEGER NOT NULL DEFAULT 1 CHECK(automatic_departure IN(0,1)),grace_minutes INTEGER NOT NULL DEFAULT 30 CHECK(grace_minutes BETWEEN 0 AND 1440),armed INTEGER NOT NULL DEFAULT 0 CHECK(armed IN(0,1)),suppressed INTEGER NOT NULL DEFAULT 0 CHECK(suppressed IN(0,1)),last_presence TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(last_presence IN('UNKNOWN','INSIDE','OUTSIDE')),configured_ms INTEGER NOT NULL,facts_since_ms INTEGER NOT NULL,manual_barrier_ms INTEGER);
CREATE TABLE work_sites(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),name TEXT NOT NULL,latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),radius REAL NOT NULL CHECK(radius BETWEEN 150 AND 10000),enabled INTEGER NOT NULL CHECK(enabled IN(0,1)),registration_key TEXT NOT NULL UNIQUE,presence TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(presence IN('UNKNOWN','INSIDE','OUTSIDE')),observed_ms INTEGER,CHECK((presence='UNKNOWN' AND observed_ms IS NULL) OR (presence!='UNKNOWN' AND observed_ms IS NOT NULL)));
CREATE INDEX sites_job ON work_sites(job_id);
CREATE TABLE geofence_event_results(id TEXT PRIMARY KEY REFERENCES commands(id),payload TEXT NOT NULL);
CREATE TABLE geofence_conflicts(session_id TEXT PRIMARY KEY REFERENCES sessions(id),job_id TEXT NOT NULL REFERENCES jobs(id),observed_ms INTEGER NOT NULL,reason TEXT NOT NULL,requires_history_review INTEGER NOT NULL DEFAULT 0 CHECK(requires_history_review IN(0,1)));
CREATE TABLE finalized_departures(session_id TEXT PRIMARY KEY REFERENCES sessions(id),detected_ms INTEGER NOT NULL,deadline_ms INTEGER NOT NULL);
CREATE TABLE customers(id TEXT PRIMARY KEY,name TEXT NOT NULL,email TEXT NOT NULL DEFAULT '',phone TEXT NOT NULL DEFAULT '',address TEXT NOT NULL DEFAULT '',notes TEXT NOT NULL DEFAULT '',archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1)));
CREATE TABLE logo_assets(id TEXT PRIMARY KEY,mime TEXT NOT NULL,width INTEGER NOT NULL CHECK(width>0),height INTEGER NOT NULL CHECK(height>0),content_hash TEXT NOT NULL,bytes BLOB NOT NULL);
CREATE TABLE app_settings(id INTEGER PRIMARY KEY CHECK(id=1),currency TEXT NOT NULL,default_hourly_minor INTEGER CHECK(default_hourly_minor IS NULL OR (typeof(default_hourly_minor)='integer' AND default_hourly_minor>=0)),company_name TEXT NOT NULL DEFAULT '',company_email TEXT NOT NULL DEFAULT '',company_phone TEXT NOT NULL DEFAULT '',company_address TEXT NOT NULL DEFAULT '',company_website TEXT NOT NULL DEFAULT '',invoice_header TEXT NOT NULL DEFAULT '',invoice_footer TEXT NOT NULL DEFAULT '',invoice_style TEXT NOT NULL DEFAULT 'MODERN' CHECK(invoice_style IN('MINIMAL','CLASSIC','MODERN')),email_subject TEXT NOT NULL DEFAULT 'Invoice {invoice_number} — {job_name}',email_body TEXT NOT NULL DEFAULT 'Hello {customer_name},

Please find invoice {invoice_number} attached.
Amount: {amount} {currency}

{company_name}',logo_id TEXT REFERENCES logo_assets(id),next_invoice_number INTEGER NOT NULL DEFAULT 1000 CHECK(next_invoice_number>0));
ALTER TABLE jobs ADD COLUMN archived_ms INTEGER CHECK(archived_ms IS NULL OR archived_ms>=0);
ALTER TABLE jobs ADD COLUMN color_argb INTEGER NOT NULL DEFAULT 4279724885;
ALTER TABLE jobs ADD COLUMN billing_mode TEXT NOT NULL DEFAULT 'HOURLY' CHECK(billing_mode IN('HOURLY','TASK'));
ALTER TABLE jobs ADD COLUMN currency TEXT NOT NULL DEFAULT 'USD';
ALTER TABLE jobs ADD COLUMN hourly_rate_minor INTEGER CHECK(hourly_rate_minor IS NULL OR (typeof(hourly_rate_minor)='integer' AND hourly_rate_minor>=0));
ALTER TABLE jobs ADD COLUMN customer_id TEXT REFERENCES customers(id);
ALTER TABLE jobs ADD COLUMN customer_email_override TEXT;
CREATE TABLE kanban_columns(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),name TEXT NOT NULL,position INTEGER NOT NULL,completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)));
CREATE INDEX columns_job_position ON kanban_columns(job_id,position,id);
CREATE UNIQUE INDEX columns_identity_job ON kanban_columns(id,job_id);
CREATE TABLE task_templates(id TEXT PRIMARY KEY,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',price_minor INTEGER CHECK(price_minor IS NULL OR (typeof(price_minor)='integer' AND price_minor>=0)),currency TEXT NOT NULL,source_job_id TEXT,source_card_id TEXT);
CREATE TABLE kanban_cards(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),column_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',due_date TEXT,position INTEGER NOT NULL,completed_ms INTEGER,billable INTEGER NOT NULL DEFAULT 1 CHECK(billable IN(0,1)),price_minor INTEGER CHECK(price_minor IS NULL OR (typeof(price_minor)='integer' AND price_minor>=0)),currency TEXT NOT NULL,template_id TEXT REFERENCES task_templates(id),FOREIGN KEY(column_id,job_id) REFERENCES kanban_columns(id,job_id));
CREATE INDEX cards_column_position ON kanban_cards(column_id,position,id);
CREATE TABLE invoices(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),customer_id TEXT REFERENCES customers(id),number TEXT NOT NULL UNIQUE,created_ms INTEGER NOT NULL,payload TEXT NOT NULL,pdf BLOB,logo_id TEXT REFERENCES logo_assets(id));
CREATE INDEX invoices_job_date ON invoices(job_id,created_ms,id);
CREATE TABLE job_deletions(id TEXT PRIMARY KEY,revision_id TEXT NOT NULL UNIQUE,deleted_ms INTEGER NOT NULL);
INSERT INTO app_settings(id,currency) VALUES(1,'USD');
CREATE TABLE entity_revisions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,job_id TEXT,payload TEXT,deleted INTEGER NOT NULL CHECK(deleted IN(0,1)),changed_ms INTEGER NOT NULL,origin TEXT NOT NULL);
CREATE INDEX revision_entity ON entity_revisions(kind,entity_id);
CREATE INDEX revision_job ON entity_revisions(job_id);
CREATE TABLE revision_parents(revision_id TEXT NOT NULL REFERENCES entity_revisions(id),parent_id TEXT NOT NULL REFERENCES entity_revisions(id),PRIMARY KEY(revision_id,parent_id),CHECK(revision_id!=parent_id));
CREATE TABLE entity_heads(kind TEXT NOT NULL,entity_id TEXT NOT NULL,revision_id TEXT NOT NULL REFERENCES entity_revisions(id),PRIMARY KEY(kind,entity_id));
CREATE TABLE widget_configurations(widget_id INTEGER PRIMARY KEY CHECK(widget_id>0),job_id TEXT,token TEXT NOT NULL UNIQUE);
