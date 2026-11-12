package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.util.TimeZone

/** Transaction helpers only. TimerRepository remains the single serialized writer. */
internal object SessionHistoryStore {
    data class Draft(val id: String, val jobId: String, val start: Long, val end: Long, val timezone: String, val offsetSeconds: Int)
    data class Entry(val id: String, val jobId: String, val start: Long, val end: Long?, val timezone: String,
        val offsetSeconds: Int, val origin: String, val needsReview: Boolean)
    data class Cursor(val start: Long, val id: String)
    data class Page(val entries: List<Entry>, val next: Cursor?)
    data class Revision(val id: String, val changed: Long, val origin: String, val deleted: Boolean, val payload: String?, val parents: List<String>)

    fun entry(db: SQLiteDatabase, id: String): Entry? = db.rawQuery(
        "SELECT id,job_id,start_ms,end_ms,timezone,offset_seconds,origin,EXISTS(SELECT 1 FROM geofence_conflicts c WHERE c.session_id=sessions.id) FROM sessions WHERE id=?", arrayOf(id)
    ).use { if (!it.moveToFirst()) null else decode(it) }
    private fun decode(row: android.database.Cursor) = Entry(row.getString(0), row.getString(1), row.getLong(2),
        if (row.isNull(3)) null else row.getLong(3), row.getString(4), row.getInt(5), row.getString(6), row.getInt(7) != 0)
    private fun jobExists(db: SQLiteDatabase, id: String) = db.rawQuery("SELECT id FROM jobs WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    fun read(db: SQLiteDatabase, jobId: String, before: Cursor?, limit: Int): Page {
        require(limit in 1..500 && jobExists(db, jobId)) { "INVALID_HISTORY_QUERY" }
        val filter = if (before == null) "" else " AND (start_ms<? OR (start_ms=? AND id<?))"
        val args = if (before == null) arrayOf(jobId) else arrayOf(jobId, before.start.toString(), before.start.toString(), before.id)
        val rows = db.rawQuery("SELECT id,job_id,start_ms,end_ms,timezone,offset_seconds,origin,EXISTS(SELECT 1 FROM geofence_conflicts c WHERE c.session_id=sessions.id) FROM sessions WHERE job_id=?$filter ORDER BY start_ms DESC,id DESC LIMIT ${limit + 1}", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor)) }
        }
        val visible = rows.take(limit)
        return Page(visible, if (rows.size > limit) visible.last().let { Cursor(it.start, it.id) } else null)
    }
    fun revisions(db: SQLiteDatabase, jobId: String, sessionId: String): List<Revision> {
        require(jobExists(db, jobId)) { "JOB_UNAVAILABLE" }
        return db.rawQuery("SELECT id,changed_ms,origin,deleted,payload FROM entity_revisions WHERE kind='session' AND entity_id=? AND job_id=? ORDER BY rowid DESC", arrayOf(sessionId, jobId)).use { rows -> buildList {
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val parents = db.rawQuery("SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id", arrayOf(id)).use { p -> buildList { while (p.moveToNext()) add(p.getString(0)) } }
                add(Revision(id, rows.getLong(1), rows.getString(2), rows.getInt(3) != 0, if (rows.isNull(4)) null else rows.getString(4), parents))
            }
        } }
    }
    data class AuditEvent(val sequence: Long, val sessionId: String, val revision: Revision)
    data class AuditPage(val events: List<AuditEvent>, val next: Long?)
    fun audit(db: SQLiteDatabase, jobId: String, before: Long?, limit: Int): AuditPage {
        require(limit in 1..500 && (before == null || before > 0) && jobExists(db, jobId)) { "INVALID_HISTORY_QUERY" }
        val filter = if (before == null) "" else " AND rowid<?"
        val args = if (before == null) arrayOf(jobId) else arrayOf(jobId, before.toString())
        // Insertion sequence, not a mutable wall clock, orders the local audit trail.
        val events = db.rawQuery("SELECT rowid,entity_id,id,changed_ms,origin,deleted,payload FROM entity_revisions WHERE kind='session' AND job_id=?$filter ORDER BY rowid DESC LIMIT ${limit + 1}", args).use { rows -> buildList {
            while (rows.moveToNext()) {
                val revisionId = rows.getString(2)
                val parents = db.rawQuery("SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id", arrayOf(revisionId)).use { p -> buildList { while (p.moveToNext()) add(p.getString(0)) } }
                add(AuditEvent(rows.getLong(0), rows.getString(1), Revision(revisionId, rows.getLong(3), rows.getString(4), rows.getInt(5) != 0, if (rows.isNull(6)) null else rows.getString(6), parents)))
            }
        } }
        return AuditPage(events.take(limit), if (events.size > limit) events[limit - 1].sequence else null)
    }
    fun save(db: SQLiteDatabase, draft: Draft, creating: Boolean, now: Long): String? {
        if (draft.id.isBlank() || draft.jobId.isBlank()) return "INVALID_SESSION"
        if (draft.end <= draft.start || draft.end - draft.start <= 0) return "INVALID_BOUNDARY"
        if (draft.timezone !in TimeZone.getAvailableIDs().toSet() || draft.offsetSeconds !in -64800..64800 ||
            TimeZone.getTimeZone(draft.timezone).getOffset(draft.start) / 1000 != draft.offsetSeconds) return "INVALID_TIMEZONE"
        if (!jobExists(db, draft.jobId)) return "JOB_UNAVAILABLE"
        val previous = entry(db, draft.id)
        if (creating) {
            val used = db.rawQuery("SELECT entity_id FROM entity_heads WHERE kind='session' AND entity_id=?", arrayOf(draft.id)).use { it.moveToFirst() }
            if (previous != null || used) return "SESSION_ID_EXISTS"
        } else {
            if (previous == null) return "SESSION_UNAVAILABLE"
            if (previous.end == null) return "STOP_SESSION_BEFORE_EDIT"
            if (previous.jobId != draft.jobId) return "SESSION_JOB_IMMUTABLE"
            if (db.rawQuery("SELECT id FROM invoice_time_claims WHERE session_id=? AND released_ms IS NULL AND (start_ms<? OR end_ms>?) LIMIT 1",
                arrayOf(draft.id, draft.start.toString(), draft.end.toString())).use { it.moveToFirst() }) return "INVOICED_SESSION_BOUNDARY"
        }
        if (db.rawQuery("SELECT id FROM sessions WHERE id!=? AND start_ms<? AND (end_ms IS NULL OR end_ms>?) LIMIT 1",
            arrayOf(draft.id, draft.end.toString(), draft.start.toString())).use { it.moveToFirst() }) return "OVERLAP"
        if (creating) {
            db.execSQL("INSERT INTO sessions(id,job_id,start_ms,end_ms,timezone,offset_seconds,origin) VALUES(?,?,?,?,?,?,'MANUAL')",
                arrayOf<Any?>(draft.id, draft.jobId, draft.start, draft.end, draft.timezone, draft.offsetSeconds))
        } else {
            forgetAutomation(db, draft.id)
            prunePoints(db, draft.id, draft.jobId, draft.start, draft.end, now)
            db.execSQL("UPDATE sessions SET start_ms=?,end_ms=?,timezone=?,offset_seconds=?,origin='MANUAL' WHERE id=?",
                arrayOf<Any?>(draft.start, draft.end, draft.timezone, draft.offsetSeconds, draft.id))
        }
        RevisionStore.snapshotRows(db, "sessions", "session", BusinessSchema.sessionColumns, now, "job_id", "id=?", arrayOf(draft.id),
            if (creating) "MANUAL_ENTRY" else "MANUAL_CORRECTION")
        return null
    }
    fun remove(db: SQLiteDatabase, id: String, now: Long): String? {
        val previous = entry(db, id) ?: return "SESSION_UNAVAILABLE"
        if (previous.end == null) return "STOP_SESSION_BEFORE_EDIT"
        forgetAutomation(db, id)
        prunePoints(db, id, previous.jobId, null, null, now)
        RevisionStore.record(db, "session", id, previous.jobId, mapOf("id" to id, "job_id" to previous.jobId), now, deleted = true, origin = "MANUAL_DELETE")
        db.execSQL("DELETE FROM sessions WHERE id=?", arrayOf(id))
        return null
    }
    private fun forgetAutomation(db: SQLiteDatabase, id: String) {
        // Historical correction must not cancel another session's live departure,
        // even when that current session belongs to the same job.
        for (table in listOf("pending_departure", "finalized_departures", "geofence_conflicts")) db.execSQL("DELETE FROM $table WHERE session_id=?", arrayOf(id))
    }
    private fun prunePoints(db: SQLiteDatabase, id: String, jobId: String, start: Long?, end: Long?, now: Long) {
        val filter = if (start == null) "" else " AND (measured_ms<? OR measured_ms>?)"
        val args = if (start == null) arrayOf(id) else arrayOf(id, start.toString(), requireNotNull(end).toString())
        val points = db.rawQuery("SELECT id FROM location_points WHERE session_id=?$filter", args).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        points.forEach { point -> RevisionStore.record(db, "location", point, jobId, mapOf("id" to point, "session_id" to id), now, deleted = true, origin = "MANUAL_CORRECTION") }
        db.execSQL("DELETE FROM location_points WHERE session_id=?$filter", args)
    }
}
