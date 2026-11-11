package io.github.centerionware.clockrove.tracking

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Native prototype boundary. Callers share this instance; Flutter never writes SQL. */
class TimerRepository internal constructor(context: Context) {
    private val helper = Store(context.applicationContext)

    enum class Action { START, STOP, SWITCH }
    enum class Origin { MANUAL, WIDGET, NOTIFICATION, GEOFENCE }
    data class Command(
        val id: String,
        val expectedVersion: Long,
        val action: Action,
        val jobId: String?,
        val atUtcMillis: Long,
        val timezone: String,
        val offsetSeconds: Int,
        val origin: Origin,
    )
    data class Session(val id: String, val jobId: String, val startUtcMillis: Long, val jobName: String)
    data class Job(val id: String, val name: String)
    data class Snapshot(val version: Long, val running: Session?, val jobs: List<Job>)
    data class Outcome(val version: Long, val conflict: String?)

    @Synchronized
    fun snapshot(): Snapshot = readSnapshot(helper.readableDatabase)

    /** Prototype job creation; the full job/board command contract follows in milestone 3. */
    @Synchronized
    fun createJob(name: String): String {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO jobs(id,name) VALUES (?,?)", arrayOf(id, name))
            db.execSQL("UPDATE state SET version=version+1 WHERE id=1")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }

    @Synchronized
    fun execute(command: Command): Outcome {
        require(command.id.isNotBlank())
        require(command.timezone.isNotBlank())
        require(command.offsetSeconds in -64800..64800)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // Persist the original outcome, including conflicts, across process recovery.
            val fingerprint = org.json.JSONArray(listOf(
                command.expectedVersion, command.action.name, command.jobId,
                command.atUtcMillis, command.timezone, command.offsetSeconds, command.origin.name,
            )).toString()
            db.rawQuery("SELECT fingerprint,version,conflict FROM commands WHERE id=?", arrayOf(command.id)).use {
                if (it.moveToFirst()) {
                    val result = if (it.getString(0) == fingerprint) {
                        Outcome(it.getLong(1), if (it.isNull(2)) null else it.getString(2))
                    } else Outcome(readSnapshot(db).version, "COMMAND_ID_REUSED")
                    db.setTransactionSuccessful()
                    return result
                }
            }
            val before = readSnapshot(db)
            val conflict = validate(db, before, command)
            val version = if (conflict == null) before.version + 1 else before.version
            if (conflict == null) {
                if (command.action != Action.START) {
                    db.execSQL("UPDATE sessions SET end_ms=? WHERE id=?", arrayOf(command.atUtcMillis, before.running!!.id))
                }
                if (command.action != Action.STOP) {
                    db.execSQL(
                        "INSERT INTO sessions(id,job_id,start_ms,timezone,offset_seconds,origin) VALUES (?,?,?,?,?,?)",
                        arrayOf(UUID.randomUUID().toString(), command.jobId, command.atUtcMillis,
                            command.timezone, command.offsetSeconds, command.origin.name),
                    )
                }
                db.execSQL("UPDATE state SET version=? WHERE id=1", arrayOf(version))
            }
            db.execSQL("INSERT INTO commands(id,fingerprint,version,conflict) VALUES (?,?,?,?)",
                arrayOf(command.id, fingerprint, version, conflict))
            db.setTransactionSuccessful()
            return Outcome(version, conflict)
        } finally { db.endTransaction() }
    }

    /** Native tap timestamp is captured once; retries reuse the persisted outcome. */
    @Synchronized
    fun executeFromSurface(id: String, version: Long, action: Action, jobId: String?, origin: Origin): Outcome {
        require(origin != Origin.GEOFENCE)
        helper.readableDatabase.rawQuery(
            "SELECT fingerprint,version,conflict FROM commands WHERE id=?", arrayOf(id),
        ).use {
            if (it.moveToFirst()) {
                val prior = org.json.JSONArray(it.getString(0))
                val same = prior.getLong(0) == version && prior.getString(1) == action.name &&
                    (if (prior.isNull(2)) null else prior.getString(2)) == jobId &&
                    prior.getString(6) == origin.name
                return if (same) Outcome(it.getLong(1), if (it.isNull(2)) null else it.getString(2))
                    else Outcome(readSnapshot(helper.readableDatabase).version, "COMMAND_ID_REUSED")
            }
        }
        val now = java.time.Instant.now()
        val zone = java.time.ZoneId.systemDefault()
        return execute(Command(id, version, action, jobId, now.toEpochMilli(), zone.id,
            zone.rules.getOffset(now).totalSeconds, origin))
    }

    @Synchronized
    internal fun close() { helper.close() }

    private fun validate(db: SQLiteDatabase, state: Snapshot, c: Command): String? {
        if (c.expectedVersion != state.version) return "STALE_STATE"
        if (c.origin == Origin.GEOFENCE) return "AUTOMATION_NOT_READY"
        if (c.action == Action.START && state.running != null) return "ALREADY_RUNNING"
        if (c.action != Action.START && state.running == null) return "NOT_RUNNING"
        if (state.running != null && c.atUtcMillis <= state.running.startUtcMillis) return "INVALID_BOUNDARY"
        if (c.action != Action.STOP) {
            val eligible = db.rawQuery("SELECT id FROM jobs WHERE id=? AND archived=0", arrayOf(c.jobId ?: "")).use { it.moveToFirst() }
            if (!eligible) return "JOB_UNAVAILABLE"
            if (state.running?.jobId == c.jobId) return "SAME_JOB"
            val overlaps = db.rawQuery("SELECT id FROM sessions WHERE end_ms IS NOT NULL AND end_ms>? LIMIT 1",
                arrayOf(c.atUtcMillis.toString())).use { it.moveToFirst() }
            if (overlaps) return "OVERLAP"
        }
        return null
    }

    private fun readSnapshot(db: SQLiteDatabase): Snapshot {
        val version = db.rawQuery("SELECT version FROM state WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }
        val running = db.rawQuery("SELECT s.id,s.job_id,s.start_ms,j.name FROM sessions s JOIN jobs j ON j.id=s.job_id WHERE s.end_ms IS NULL", null).use {
            if (it.moveToFirst()) Session(it.getString(0), it.getString(1), it.getLong(2), it.getString(3)) else null
        }
        val jobs = db.rawQuery("SELECT id,name FROM jobs WHERE archived=0 ORDER BY name,id", null).use {
            buildList { while (it.moveToNext()) add(Job(it.getString(0), it.getString(1))) }
        }
        return Snapshot(version, running, jobs)
    }

    private class Store(context: Context) : SQLiteOpenHelper(context, "clockrove.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)")
            db.execSQL("INSERT INTO state VALUES(1,0)")
            db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,name TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),start_ms INTEGER NOT NULL,end_ms INTEGER,timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL,origin TEXT NOT NULL,CHECK(end_ms IS NULL OR end_ms>start_ms))")
            db.execSQL("CREATE UNIQUE INDEX one_open_session ON sessions((1)) WHERE end_ms IS NULL")
            db.execSQL("CREATE INDEX session_dates ON sessions(start_ms,end_ms)")
            db.execSQL("CREATE TABLE commands(id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,version INTEGER NOT NULL,conflict TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("No released upgrade path from $oldVersion to $newVersion")
        }
    }

    companion object {
        @Volatile private var instance: TimerRepository? = null
        fun get(context: Context): TimerRepository = instance ?: synchronized(this) {
            instance ?: TimerRepository(context).also { instance = it }
        }
    }
}
