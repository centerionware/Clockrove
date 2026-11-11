package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase

internal object JobLifecycleStore {
    const val retentionMillis = 30L * 24 * 60 * 60 * 1000
    data class Archived(val id: String, val name: String, val archivedMillis: Long?, val canDelete: Boolean)
    fun eligible(archived: Boolean, since: Long?, now: Long): Boolean = archived && since != null && since >= 0 && now >= since && now - since >= retentionMillis
    fun archived(db: SQLiteDatabase, now: Long): List<Archived> = db.rawQuery("SELECT id,name,archived_ms FROM jobs WHERE archived=1 ORDER BY archived_ms DESC,id", null).use { rows -> buildList {
        while (rows.moveToNext()) { val since = if (rows.isNull(2)) null else rows.getLong(2); add(Archived(rows.getString(0), rows.getString(1), since, eligible(true, since, now))) }
    } }
    fun archive(db: SQLiteDatabase, id: String, now: Long): String? {
        val current = db.rawQuery("SELECT archived FROM jobs WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getInt(0) != 0 else return "JOB_UNAVAILABLE" }
        if (current) return "JOB_ALREADY_ARCHIVED"
        if (now < 0) return "INVALID_CLOCK"
        if (db.rawQuery("SELECT id FROM sessions WHERE job_id=? AND end_ms IS NULL", arrayOf(id)).use { it.moveToFirst() }) return "STOP_JOB_BEFORE_ARCHIVE"
        // Rotate before hiding from active queries. Preferences stay intact.
        GeofenceStore.invalidate(db, id, now)
        db.execSQL("UPDATE jobs SET archived=1,archived_ms=? WHERE id=?", arrayOf<Any?>(now, id))
        BusinessSchema.snapshotAutomation(db, id, now)
        RevisionStore.snapshotRows(db, "jobs", "job", BusinessSchema.jobColumns, now, where = "id=?", args = arrayOf(id))
        return null
    }
    fun restore(db: SQLiteDatabase, id: String, now: Long): String? {
        val current = db.rawQuery("SELECT archived FROM jobs WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getInt(0) != 0 else return "JOB_UNAVAILABLE" }
        if (!current) return "JOB_NOT_ARCHIVED"
        if (now < 0) return "INVALID_CLOCK"
        val restoring = db.rawQuery("SELECT COUNT(*) FROM work_sites s JOIN automation_policies p ON p.job_id=s.job_id WHERE s.job_id=? AND s.enabled=1 AND p.enabled=1", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
        if (GeofenceStore.sites(db, true).size + restoring > 100) return "SITE_CAPACITY_EXCEEDED"
        db.execSQL("UPDATE jobs SET archived=0,archived_ms=NULL WHERE id=?", arrayOf(id))
        GeofenceStore.invalidate(db, id, now)
        BusinessSchema.snapshotAutomation(db, id, now)
        RevisionStore.snapshotRows(db, "jobs", "job", BusinessSchema.jobColumns, now, where = "id=?", args = arrayOf(id))
        return null
    }
    fun remove(db: SQLiteDatabase, id: String, now: Long): String? {
        val age = db.rawQuery("SELECT archived,archived_ms FROM jobs WHERE id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) return "JOB_UNAVAILABLE"
            (it.getInt(0) != 0) to if (it.isNull(1)) null else it.getLong(1)
        }
        if (!eligible(age.first, age.second, now)) return "ARCHIVE_RETENTION_REQUIRED"
        if (db.rawQuery("SELECT id FROM sessions WHERE job_id=? AND end_ms IS NULL", arrayOf(id)).use { it.moveToFirst() }) return "STOP_JOB_BEFORE_DELETE"
        // Every card already creates an independent reusable record. A removed
        // template is an explicit user choice and must not be recreated here.
        val templates = db.rawQuery("SELECT id FROM task_templates WHERE source_job_id=?", arrayOf(id)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        db.execSQL("UPDATE task_templates SET source_job_id=NULL,source_card_id=NULL WHERE source_job_id=?", arrayOf(id))
        // Removed templates have no live catalog row, but their older revisions
        // must lose deleted-job provenance as well (without requiring JSON SQL).
        val historicalTemplates = db.rawQuery("SELECT id,payload FROM entity_revisions WHERE kind='template' AND payload IS NOT NULL", null).use { rows -> buildList {
            while (rows.moveToNext()) if (org.json.JSONObject(rows.getString(1)).optString("source_job_id") == id) add(rows.getString(0))
        } }
        historicalTemplates.forEach { revision -> db.execSQL("UPDATE entity_revisions SET payload=NULL WHERE id=?", arrayOf(revision)) }
        // Old template revisions must not retain deleted-job provenance either.
        templates.forEach { template ->
            db.execSQL("UPDATE entity_revisions SET payload=NULL WHERE kind='template' AND entity_id=?", arrayOf(template))
            RevisionStore.snapshotRows(db, "task_templates", "template", BoardStore.templateColumns, now, where = "id=?", args = arrayOf(template))
        }
        val entities = db.rawQuery("SELECT kind,entity_id FROM entity_heads WHERE revision_id IN (SELECT id FROM entity_revisions WHERE job_id=?)", arrayOf(id)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) } }
        db.execSQL("UPDATE entity_revisions SET payload=NULL WHERE job_id=?", arrayOf(id))
        entities.forEach { (kind, entityId) -> RevisionStore.record(db, kind, entityId, id, mapOf("id" to entityId, "job_id" to id), now, deleted = true) }
        listOf("location_points", "pending_departure", "finalized_departures", "geofence_conflicts").forEach { table ->
            db.execSQL("DELETE FROM $table WHERE session_id IN (SELECT id FROM sessions WHERE job_id=?)", arrayOf(id))
        }
        listOf("sessions", "invoices", "kanban_cards", "kanban_columns", "work_sites", "automation_policies").forEach { table -> db.execSQL("DELETE FROM $table WHERE job_id=?", arrayOf(id)) }
        db.execSQL("DELETE FROM jobs WHERE id=?", arrayOf(id))
        val revision = RevisionStore.record(db, "job", id, id, mapOf("id" to id, "job_id" to id), now, deleted = true)
        db.execSQL("INSERT INTO job_deletions VALUES(?,?,?)", arrayOf<Any?>(id, revision, now))
        LogoStore.collectUnused(db, now)
        OpaqueReceipt.purgePayloads(db)
        return null
    }
}
