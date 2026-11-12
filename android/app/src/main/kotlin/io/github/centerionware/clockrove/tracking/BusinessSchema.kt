package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.util.Currency
import java.util.Locale
import java.util.UUID

/** Version-5 foundation for the complete owner-requested business iteration. */
internal object BusinessSchema {
    val settingsColumns = listOf("id", "currency", "default_hourly_minor", "company_name", "company_email",
        "company_phone", "company_address", "company_website", "invoice_header", "invoice_footer", "invoice_style",
        "email_subject", "email_body", "logo_id", "next_invoice_number")
    val jobColumns = listOf("id", "name", "archived", "archived_ms", "color_argb", "billing_mode",
        "currency", "hourly_rate_minor", "customer_id", "customer_email_override")
    val sessionColumns = listOf("id", "job_id", "start_ms", "end_ms", "timezone", "offset_seconds", "origin")
    val siteColumns = listOf("id", "job_id", "name", "latitude", "longitude", "radius", "enabled")
    val columnColumns = listOf("id", "job_id", "name", "position", "completed")

    fun install(db: SQLiteDatabase, now: Long) {
        db.execSQL("CREATE TABLE customers(id TEXT PRIMARY KEY,name TEXT NOT NULL,email TEXT NOT NULL DEFAULT '',phone TEXT NOT NULL DEFAULT '',address TEXT NOT NULL DEFAULT '',notes TEXT NOT NULL DEFAULT '',archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1)))")
        db.execSQL("CREATE TABLE logo_assets(id TEXT PRIMARY KEY,mime TEXT NOT NULL,width INTEGER NOT NULL CHECK(width>0),height INTEGER NOT NULL CHECK(height>0),content_hash TEXT NOT NULL,bytes BLOB NOT NULL)")
        db.execSQL("CREATE TABLE app_settings(id INTEGER PRIMARY KEY CHECK(id=1),currency TEXT NOT NULL,default_hourly_minor INTEGER CHECK(default_hourly_minor IS NULL OR (typeof(default_hourly_minor)='integer' AND default_hourly_minor>=0)),company_name TEXT NOT NULL DEFAULT '',company_email TEXT NOT NULL DEFAULT '',company_phone TEXT NOT NULL DEFAULT '',company_address TEXT NOT NULL DEFAULT '',company_website TEXT NOT NULL DEFAULT '',invoice_header TEXT NOT NULL DEFAULT '',invoice_footer TEXT NOT NULL DEFAULT '',invoice_style TEXT NOT NULL DEFAULT 'MODERN' CHECK(invoice_style IN('MINIMAL','CLASSIC','MODERN')),email_subject TEXT NOT NULL DEFAULT 'Invoice {invoice_number} — {job_name}',email_body TEXT NOT NULL DEFAULT 'Hello {customer_name},\n\nPlease find invoice {invoice_number} attached.\nAmount: {amount} {currency}\n\n{company_name}',logo_id TEXT REFERENCES logo_assets(id),next_invoice_number INTEGER NOT NULL DEFAULT 1000 CHECK(next_invoice_number>0))")
        val currency = try { Currency.getInstance(Locale.getDefault())?.currencyCode ?: "USD" } catch (_: IllegalArgumentException) { "USD" }
        db.execSQL("INSERT INTO app_settings(id,currency) VALUES(1,?)", arrayOf(currency))
        db.execSQL("ALTER TABLE jobs ADD COLUMN archived_ms INTEGER CHECK(archived_ms IS NULL OR archived_ms>=0)")
        db.execSQL("ALTER TABLE jobs ADD COLUMN color_argb INTEGER NOT NULL DEFAULT 4279724885")
        db.execSQL("ALTER TABLE jobs ADD COLUMN billing_mode TEXT NOT NULL DEFAULT 'HOURLY' CHECK(billing_mode IN('HOURLY','TASK'))")
        db.execSQL("ALTER TABLE jobs ADD COLUMN currency TEXT NOT NULL DEFAULT 'USD'")
        db.execSQL("ALTER TABLE jobs ADD COLUMN hourly_rate_minor INTEGER CHECK(hourly_rate_minor IS NULL OR (typeof(hourly_rate_minor)='integer' AND hourly_rate_minor>=0))")
        db.execSQL("ALTER TABLE jobs ADD COLUMN customer_id TEXT REFERENCES customers(id)")
        db.execSQL("ALTER TABLE jobs ADD COLUMN customer_email_override TEXT")
        db.execSQL("UPDATE jobs SET currency=?", arrayOf(currency))
        // Legacy archive dates were never captured: do not manufacture 30-day eligibility.
        db.execSQL("UPDATE jobs SET archived_ms=? WHERE archived=1", arrayOf<Any?>(now.coerceAtLeast(0)))
        db.execSQL("CREATE TABLE kanban_columns(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),name TEXT NOT NULL,position INTEGER NOT NULL,completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)))")
        db.execSQL("CREATE INDEX columns_job_position ON kanban_columns(job_id,position,id)")
        db.execSQL("CREATE UNIQUE INDEX columns_identity_job ON kanban_columns(id,job_id)")
        db.execSQL("CREATE TABLE task_templates(id TEXT PRIMARY KEY,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',price_minor INTEGER CHECK(price_minor IS NULL OR (typeof(price_minor)='integer' AND price_minor>=0)),currency TEXT NOT NULL,source_job_id TEXT,source_card_id TEXT)")
        db.execSQL("CREATE TABLE kanban_cards(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),column_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',due_date TEXT,position INTEGER NOT NULL,completed_ms INTEGER,billable INTEGER NOT NULL DEFAULT 1 CHECK(billable IN(0,1)),price_minor INTEGER CHECK(price_minor IS NULL OR (typeof(price_minor)='integer' AND price_minor>=0)),currency TEXT NOT NULL,template_id TEXT REFERENCES task_templates(id),FOREIGN KEY(column_id,job_id) REFERENCES kanban_columns(id,job_id))")
        db.execSQL("CREATE INDEX cards_column_position ON kanban_cards(column_id,position,id)")
        db.execSQL("CREATE TABLE invoices(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),customer_id TEXT REFERENCES customers(id),number TEXT NOT NULL UNIQUE,created_ms INTEGER NOT NULL,payload TEXT NOT NULL,pdf BLOB,logo_id TEXT REFERENCES logo_assets(id))")
        db.execSQL("CREATE INDEX invoices_job_date ON invoices(job_id,created_ms,id)")
        db.execSQL("CREATE TABLE job_deletions(id TEXT PRIMARY KEY,revision_id TEXT NOT NULL UNIQUE,deleted_ms INTEGER NOT NULL)")
        RevisionStore.createTables(db)
        RevisionStore.snapshotRows(db, "app_settings", "settings", settingsColumns, now, origin = "MIGRATION")
        val jobs = db.rawQuery("SELECT id FROM jobs ORDER BY id", null).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        jobs.forEach { seedBoard(db, it, now, "MIGRATION"); snapshotAutomation(db, it, now, "MIGRATION") }
        RevisionStore.snapshotRows(db, "jobs", "job", jobColumns, now, origin = "MIGRATION")
        RevisionStore.snapshotRows(db, "sessions", "session", sessionColumns, now, "job_id", origin = "MIGRATION")
        RevisionStore.snapshotRows(db, "work_sites", "site", siteColumns, now, "job_id", origin = "MIGRATION")
    }

    fun snapshotAutomation(db: SQLiteDatabase, jobId: String, now: Long, origin: String = "MUTATION") {
        db.rawQuery("SELECT enabled,auto_start,automatic_departure,grace_minutes FROM automation_policies WHERE job_id=?", arrayOf(jobId)).use {
            if (it.moveToFirst()) RevisionStore.record(db, "automation", jobId, jobId, mapOf("job_id" to jobId,
                "enabled" to it.getInt(0), "auto_start" to it.getInt(1), "automatic_departure" to it.getInt(2),
                "grace_minutes" to it.getInt(3)), now, origin = origin)
        }
    }

    fun seedBoard(db: SQLiteDatabase, jobId: String, now: Long, origin: String = "MUTATION") {
        listOf("To do", "Doing", "Done").forEachIndexed { index, name ->
            val id = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO kanban_columns(id,job_id,name,position,completed) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(id, jobId, name, index, if (index == 2) 1 else 0))
        }
        RevisionStore.snapshotRows(db, "kanban_columns", "column", columnColumns, now, "job_id", "job_id=?", arrayOf(jobId), origin)
    }
}
