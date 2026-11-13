package io.github.centerionware.clockrove.tracking

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Global recording defaults and nullable per-job inherit/override values. */
internal object LocationRecordingStore {
    fun install(db: SQLiteDatabase, at: Long) {
        db.execSQL("CREATE TABLE location_recording_settings(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL CHECK(enabled IN(0,1)))")
        db.execSQL("INSERT INTO location_recording_settings VALUES(1,1)")
        db.execSQL("CREATE TABLE job_location_recording(job_id TEXT PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,enabled INTEGER NOT NULL CHECK(enabled IN(0,1)))")
        snapshotDefault(db, at, "MIGRATION")
    }

    fun defaultEnabled(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT enabled FROM location_recording_settings WHERE id=1", null,
    ).use { it.moveToFirst() && it.getInt(0) != 0 }

    fun override(db: SQLiteDatabase, jobId: String): Boolean? = db.rawQuery(
        "SELECT enabled FROM job_location_recording WHERE job_id=?", arrayOf(jobId),
    ).use { if (it.moveToFirst()) it.getInt(0) != 0 else null }

    fun enabled(db: SQLiteDatabase, jobId: String?): Boolean {
        if (jobId == null) return false
        return override(db, jobId) ?: defaultEnabled(db)
    }

    fun saveDefault(db: SQLiteDatabase, enabled: Boolean, at: Long) {
        db.execSQL("UPDATE location_recording_settings SET enabled=? WHERE id=1", arrayOf(if (enabled) 1 else 0))
        snapshotDefault(db, at)
    }

    fun saveJob(db: SQLiteDatabase, jobId: String, enabled: Boolean?, at: Long) {
        if (enabled == null) db.execSQL("DELETE FROM job_location_recording WHERE job_id=?", arrayOf(jobId))
        else {
            val setting = ContentValues(1).apply { put("enabled", if (enabled) 1 else 0) }
            if (override(db, jobId) == null) {
                setting.put("job_id", jobId)
                db.insertOrThrow("job_location_recording", null, setting)
            } else db.update("job_location_recording", setting, "job_id=?", arrayOf(jobId))
        }
        RevisionStore.record(db, "job_location_recording", jobId, jobId,
            mapOf("job_id" to jobId, "enabled" to enabled?.let { if (it) 1 else 0 }), at)
    }

    private fun snapshotDefault(db: SQLiteDatabase, at: Long, origin: String = "MUTATION") {
        RevisionStore.record(db, "location_recording_settings", "1", null,
            mapOf("id" to 1, "enabled" to if (defaultEnabled(db)) 1 else 0), at, origin = origin)
    }
}
