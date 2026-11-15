package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.util.TimeZone
import java.util.UUID

/** Stores GPS-only range coverage and the per-point tombstones created by it. */
internal object GpsDeletionStore {
    data class Range(
        val jobId: String?,
        val startUtcMillis: Long,
        val endUtcMillis: Long,
        val timezoneId: String,
    )

    data class Point(val id: String, val sessionId: String, val jobId: String)

    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE location_deletion_ranges(id TEXT PRIMARY KEY,job_id TEXT,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,timezone_id TEXT NOT NULL,deleted_ms INTEGER NOT NULL,revision_id TEXT NOT NULL UNIQUE REFERENCES entity_revisions(id),CHECK(end_ms>start_ms))")
        db.execSQL("CREATE INDEX location_deletion_scope_bounds ON location_deletion_ranges(job_id,start_ms,end_ms)")
    }

    /** Upgrade points already on disk into coordinate-free revision identities. */
    fun migrate(db: SQLiteDatabase, migratedUtcMillis: Long) {
        createTables(db)
        db.rawQuery(
            "SELECT p.id,p.session_id,s.job_id FROM location_points p JOIN sessions s ON s.id=p.session_id ORDER BY p.id",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val sessionId = rows.getString(1)
                RevisionStore.record(
                    db,
                    "location",
                    id,
                    rows.getString(2),
                    mapOf("id" to id, "session_id" to sessionId),
                    migratedUtcMillis,
                    origin = "MIGRATION",
                )
            }
        }
    }

    fun validationProblem(db: SQLiteDatabase, range: Range): String? = when {
        range.startUtcMillis >= range.endUtcMillis -> "INVALID_GPS_RANGE"
        range.timezoneId !in TimeZone.getAvailableIDs().toSet() -> "INVALID_TIMEZONE"
        range.jobId != null && !jobExists(db, range.jobId) -> "JOB_UNAVAILABLE"
        else -> null
    }

    fun count(db: SQLiteDatabase, range: Range): Long {
        val jobFilter = if (range.jobId == null) "" else " AND s.job_id=?"
        val args = buildList {
            add(range.startUtcMillis.toString())
            add(range.endUtcMillis.toString())
            range.jobId?.let(::add)
        }.toTypedArray()
        return db.rawQuery(
            "SELECT COUNT(*) FROM location_points p JOIN sessions s ON s.id=p.session_id WHERE p.measured_ms>=? AND p.measured_ms<?$jobFilter",
            args,
        ).use { rows -> check(rows.moveToFirst()); rows.getLong(0) }
    }

    /**
     * The revision parents prove that a point was created after every deletion
     * range that covers its measured instant. Bounds and job scope are portable;
     * no point coordinates enter revision history.
     */
    fun parentsForPoint(db: SQLiteDatabase, jobId: String, measuredUtcMillis: Long): Set<String> =
        db.rawQuery(
            "SELECT revision_id FROM location_deletion_ranges WHERE start_ms<=? AND end_ms>? AND (job_id IS NULL OR job_id=?) ORDER BY revision_id",
            arrayOf(measuredUtcMillis.toString(), measuredUtcMillis.toString(), jobId),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }

    /** Called inside TimerRepository's serialized, versioned transaction. */
    fun delete(db: SQLiteDatabase, range: Range, deletedUtcMillis: Long): String? {
        validationProblem(db, range)?.let { return it }

        val rangeId = UUID.randomUUID().toString()
        val revisionId = RevisionStore.record(
            db,
            "location_deletion_range",
            rangeId,
            range.jobId,
            mapOf(
                "id" to rangeId,
                "job_id" to range.jobId,
                "start_ms" to range.startUtcMillis,
                "end_ms" to range.endUtcMillis,
                "timezone_id" to range.timezoneId,
            ),
            deletedUtcMillis,
            origin = "GPS_RANGE_DELETE",
        )
        db.execSQL(
            "INSERT INTO location_deletion_ranges(id,job_id,start_ms,end_ms,timezone_id,deleted_ms,revision_id) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(rangeId, range.jobId, range.startUtcMillis, range.endUtcMillis,
                range.timezoneId, deletedUtcMillis, revisionId),
        )

        var afterId: String? = null
        while (true) {
            val batch = pointBatch(db, range, afterId)
            if (batch.isEmpty()) break
            batch.forEach { point ->
                RevisionStore.record(
                    db,
                    "location",
                    point.id,
                    point.jobId,
                    mapOf("id" to point.id, "session_id" to point.sessionId),
                    deletedUtcMillis,
                    deleted = true,
                    origin = "GPS_RANGE_DELETE",
                    additionalParents = setOf(revisionId),
                )
                db.delete("location_points", "id=?", arrayOf(point.id))
                afterId = point.id
            }
        }
        return null
    }

    private fun pointBatch(db: SQLiteDatabase, range: Range, afterId: String?): List<Point> {
        val idFilter = if (afterId == null) "" else " AND p.id>?"
        val jobFilter = if (range.jobId == null) "" else " AND s.job_id=?"
        val args = buildList {
            add(range.startUtcMillis.toString())
            add(range.endUtcMillis.toString())
            afterId?.let(::add)
            range.jobId?.let(::add)
        }.toTypedArray()
        return db.rawQuery(
            "SELECT p.id,p.session_id,s.job_id FROM location_points p JOIN sessions s ON s.id=p.session_id WHERE p.measured_ms>=? AND p.measured_ms<?$idFilter$jobFilter ORDER BY p.id LIMIT $POINT_BATCH_SIZE",
            args,
        ).use { rows -> buildList {
            while (rows.moveToNext()) add(Point(rows.getString(0), rows.getString(1), rows.getString(2)))
        } }
    }

    private fun jobExists(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT id FROM jobs WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    private const val POINT_BATCH_SIZE = 256
}
