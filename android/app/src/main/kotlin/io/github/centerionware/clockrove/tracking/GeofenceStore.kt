package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.github.centerionware.clockrove.location.geofence.GeofenceMembership
import java.util.UUID

/** SQL helpers used only under TimerRepository's serialized transaction boundary. */
internal object GeofenceStore {
    data class Site(val id: String, val jobId: String, val name: String,
        val latitude: Double, val longitude: Double, val radiusMeters: Double = 150.0,
        val enabled: Boolean = true, val registrationKey: String = "")
    data class Settings(val jobId: String, val enabled: Boolean = false,
        val autoStart: Boolean = false, val automaticDeparture: Boolean = true,
        val graceMinutes: Int = 30)
    data class Policy(val settings: Settings, val armed: Boolean, val suppressed: Boolean,
        val lastPresence: GeofenceMembership.Presence, val configuredUtcMillis: Long,
        val factsSinceUtcMillis: Long, val manualBarrierUtcMillis: Long?)
    data class Observation(val membership: GeofenceMembership.Result,
        val selection: GeofenceMembership.StartSelection, val changed: Boolean)

    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE automation_policies(job_id TEXT PRIMARY KEY REFERENCES jobs(id),enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN(0,1)),auto_start INTEGER NOT NULL DEFAULT 0 CHECK(auto_start IN(0,1)),automatic_departure INTEGER NOT NULL DEFAULT 1 CHECK(automatic_departure IN(0,1)),grace_minutes INTEGER NOT NULL DEFAULT 30 CHECK(grace_minutes BETWEEN 0 AND 1440),armed INTEGER NOT NULL DEFAULT 0 CHECK(armed IN(0,1)),suppressed INTEGER NOT NULL DEFAULT 0 CHECK(suppressed IN(0,1)),last_presence TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(last_presence IN('UNKNOWN','INSIDE','OUTSIDE')),configured_ms INTEGER NOT NULL,facts_since_ms INTEGER NOT NULL,manual_barrier_ms INTEGER)")
        db.execSQL("CREATE TABLE work_sites(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),name TEXT NOT NULL,latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),radius REAL NOT NULL CHECK(radius BETWEEN 150 AND 10000),enabled INTEGER NOT NULL CHECK(enabled IN(0,1)),registration_key TEXT NOT NULL UNIQUE,presence TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(presence IN('UNKNOWN','INSIDE','OUTSIDE')),observed_ms INTEGER,CHECK((presence='UNKNOWN' AND observed_ms IS NULL) OR (presence!='UNKNOWN' AND observed_ms IS NOT NULL)))")
        db.execSQL("CREATE INDEX sites_job ON work_sites(job_id)")
        db.execSQL("CREATE TABLE geofence_event_results(id TEXT PRIMARY KEY REFERENCES commands(id),payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE geofence_conflicts(session_id TEXT PRIMARY KEY REFERENCES sessions(id),job_id TEXT NOT NULL REFERENCES jobs(id),observed_ms INTEGER NOT NULL,reason TEXT NOT NULL)")
        db.execSQL("CREATE TABLE finalized_departures(session_id TEXT PRIMARY KEY REFERENCES sessions(id),detected_ms INTEGER NOT NULL,deadline_ms INTEGER NOT NULL)")
    }

    fun sites(db: SQLiteDatabase, enabledOnly: Boolean = false): List<Site> {
        val filter = if (enabledOnly) " AND s.enabled=1 AND p.enabled=1" else ""
        return db.rawQuery("SELECT s.id,s.job_id,s.name,s.latitude,s.longitude,s.radius,s.enabled,s.registration_key FROM work_sites s JOIN jobs j ON j.id=s.job_id LEFT JOIN automation_policies p ON p.job_id=s.job_id WHERE j.archived=0$filter ORDER BY s.job_id,s.id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Site(cursor.getString(0), cursor.getString(1),
                cursor.getString(2), cursor.getDouble(3), cursor.getDouble(4), cursor.getDouble(5),
                cursor.getInt(6) != 0, cursor.getString(7))) }
        }
    }

    fun policies(db: SQLiteDatabase): List<Policy> = db.rawQuery(
        "SELECT p.* FROM automation_policies p JOIN jobs j ON j.id=p.job_id WHERE j.archived=0 ORDER BY p.job_id", null,
    ).use { c -> buildList { while (c.moveToNext()) {
        fun int(name: String) = c.getInt(c.getColumnIndexOrThrow(name))
        fun long(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        add(Policy(Settings(c.getString(c.getColumnIndexOrThrow("job_id")), int("enabled") != 0,
            int("auto_start") != 0, int("automatic_departure") != 0, int("grace_minutes")),
            int("armed") != 0, int("suppressed") != 0,
            GeofenceMembership.Presence.valueOf(c.getString(c.getColumnIndexOrThrow("last_presence"))),
            long("configured_ms"), long("facts_since_ms"), if (c.isNull(c.getColumnIndexOrThrow("manual_barrier_ms"))) null else long("manual_barrier_ms")))
    } } }

    private fun ensurePolicy(db: SQLiteDatabase, jobId: String, now: Long) {
        db.execSQL("INSERT OR IGNORE INTO automation_policies(job_id,configured_ms,facts_since_ms) VALUES (?,?,?)", arrayOf<Any?>(jobId, now, now))
    }
    private fun jobAvailable(db: SQLiteDatabase, id: String) = db.rawQuery(
        "SELECT id FROM jobs WHERE id=? AND archived=0", arrayOf(id),
    ).use { it.moveToFirst() }

    /** Configuration changes invalidate every old registration for this job. */
    private fun invalidate(db: SQLiteDatabase, jobId: String, now: Long) {
        ensurePolicy(db, jobId, now)
        sites(db).filter { it.jobId == jobId }.forEach {
            db.execSQL("UPDATE work_sites SET registration_key=?,presence='UNKNOWN',observed_ms=NULL WHERE id=?",
                arrayOf(UUID.randomUUID().toString(), it.id))
        }
        db.execSQL("UPDATE automation_policies SET armed=0,last_presence='UNKNOWN',configured_ms=?,facts_since_ms=? WHERE job_id=?",
            arrayOf<Any?>(now, now, jobId))
        db.execSQL("DELETE FROM pending_departure WHERE session_id IN (SELECT id FROM sessions WHERE job_id=?)", arrayOf(jobId))
    }

    fun saveSite(db: SQLiteDatabase, site: Site, now: Long): String? {
        if (site.id.isBlank() || site.name.isBlank() || !site.latitude.isFinite() || site.latitude !in -90.0..90.0 ||
            !site.longitude.isFinite() || site.longitude !in -180.0..180.0 ||
            !site.radiusMeters.isFinite() || site.radiusMeters !in 150.0..10000.0) return "INVALID_SITE"
        if (!jobAvailable(db, site.jobId)) return "JOB_UNAVAILABLE"
        val previous = db.rawQuery("SELECT id,job_id,name,latitude,longitude,radius,enabled,registration_key FROM work_sites WHERE id=?", arrayOf(site.id)).use {
            if (!it.moveToFirst()) null else Site(it.getString(0), it.getString(1), it.getString(2),
                it.getDouble(3), it.getDouble(4), it.getDouble(5), it.getInt(6) != 0, it.getString(7))
        }
        if (previous != null && previous.jobId != site.jobId) return "SITE_JOB_IMMUTABLE"
        if (previous?.copy(registrationKey = "") == site.copy(registrationKey = "")) return null
        val active = policies(db).firstOrNull { it.settings.jobId == site.jobId }?.settings?.enabled == true
        val projected = sites(db, true).size - (if (active && previous?.enabled == true) 1 else 0) +
            (if (active && site.enabled) 1 else 0)
        if (projected > 100) return "SITE_CAPACITY_EXCEEDED"
        if (previous == null) {
            db.execSQL("INSERT INTO work_sites(id,job_id,name,latitude,longitude,radius,enabled,registration_key) VALUES (?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(site.id, site.jobId, site.name, site.latitude, site.longitude, site.radiusMeters,
                    if (site.enabled) 1 else 0, UUID.randomUUID().toString()))
        } else {
            db.execSQL("UPDATE work_sites SET name=?,latitude=?,longitude=?,radius=?,enabled=? WHERE id=?",
                arrayOf<Any?>(site.name, site.latitude, site.longitude, site.radiusMeters, if (site.enabled) 1 else 0, site.id))
        }
        if (previous == null || previous.copy(name = site.name, registrationKey = "") != site.copy(registrationKey = "")) {
            invalidate(db, site.jobId, now)
        }
        return null
    }

    fun saveSettings(db: SQLiteDatabase, settings: Settings, now: Long): String? {
        if (settings.graceMinutes !in 0..1440) return "INVALID_GRACE"
        if (!jobAvailable(db, settings.jobId)) return "JOB_UNAVAILABLE"
        val previous = policies(db).firstOrNull { it.settings.jobId == settings.jobId }
        if (previous?.settings == settings) return null
        val siteCount = sites(db).count { it.jobId == settings.jobId && it.enabled }
        val projected = sites(db, true).size - (if (previous?.settings?.enabled == true) siteCount else 0) +
            (if (settings.enabled) siteCount else 0)
        if (projected > 100) return "SITE_CAPACITY_EXCEEDED"
        ensurePolicy(db, settings.jobId, now)
        db.execSQL("UPDATE automation_policies SET enabled=?,auto_start=?,automatic_departure=?,grace_minutes=? WHERE job_id=?",
            arrayOf<Any?>(if (settings.enabled) 1 else 0, if (settings.autoStart) 1 else 0,
                if (settings.automaticDeparture) 1 else 0, settings.graceMinutes, settings.jobId))
        invalidate(db, settings.jobId, now)
        return null
    }

    fun removeSite(db: SQLiteDatabase, siteId: String, now: Long): String? {
        val site = sites(db).firstOrNull { it.id == siteId } ?: return "SITE_UNAVAILABLE"
        db.execSQL("DELETE FROM work_sites WHERE id=?", arrayOf(siteId))
        invalidate(db, site.jobId, now)
        return null
    }

    /** Caller runs this in the same transaction that commits the manual stop/switch. */
    fun manualStop(db: SQLiteDatabase, jobId: String, at: Long) {
        db.execSQL("DELETE FROM geofence_conflicts WHERE session_id IN (SELECT id FROM sessions WHERE job_id=? AND end_ms IS NULL)", arrayOf(jobId))
        val outside = policies(db).firstOrNull { it.settings.jobId == jobId }?.lastPresence == GeofenceMembership.Presence.OUTSIDE
        db.execSQL("UPDATE automation_policies SET suppressed=?,armed=?,manual_barrier_ms=CASE WHEN manual_barrier_ms IS NULL OR manual_barrier_ms<? THEN ? ELSE manual_barrier_ms END WHERE job_id=?",
            arrayOf<Any?>(if (outside) 0 else 1, if (outside) 1 else 0, at, at, jobId))
    }

    /** Explicit acceptance preserves all recorded history and waits for new qualified entry. */
    fun acceptCurrentHistory(db: SQLiteDatabase, jobId: String, now: Long): String? {
        if (!jobAvailable(db, jobId)) return "JOB_UNAVAILABLE"
        db.execSQL("DELETE FROM geofence_conflicts WHERE job_id=?", arrayOf(jobId))
        invalidate(db, jobId, now)
        db.execSQL("UPDATE automation_policies SET manual_barrier_ms=CASE WHEN manual_barrier_ms IS NULL OR manual_barrier_ms<? THEN ? ELSE manual_barrier_ms END WHERE job_id=?", arrayOf<Any?>(now, now, jobId))
        return null
    }

    fun needsReview(db: SQLiteDatabase, sessionId: String): Boolean = db.rawQuery(
        "SELECT session_id FROM geofence_conflicts WHERE session_id=?", arrayOf(sessionId),
    ).use { it.moveToFirst() }

    fun manualStart(db: SQLiteDatabase, jobId: String, at: Long) {
        db.execSQL("UPDATE automation_policies SET manual_barrier_ms=CASE WHEN manual_barrier_ms IS NULL OR manual_barrier_ms<? THEN ? ELSE manual_barrier_ms END WHERE job_id=?",
            arrayOf<Any?>(at, at, jobId))
    }

    fun unresolvedJobs(db: SQLiteDatabase): Set<String> = db.rawQuery(
        "SELECT DISTINCT job_id FROM geofence_conflicts", null,
    ).use { buildSet { while (it.moveToNext()) add(it.getString(0)) } }

    fun conflict(db: SQLiteDatabase, sessionId: String, at: Long, reason: String) {
        // Alerts concern recorded work, never a retained idle movement log.
        db.execSQL("INSERT OR IGNORE INTO geofence_conflicts(session_id,job_id,observed_ms,reason) SELECT id,job_id,?,? FROM sessions WHERE id=?",
            arrayOf<Any?>(at, reason, sessionId))
        db.execSQL("UPDATE geofence_conflicts SET observed_ms=?,reason=? WHERE session_id=? AND observed_ms<=?",
            arrayOf<Any?>(at, reason, sessionId, at))
    }

    fun suspendFacts(db: SQLiteDatabase, jobId: String, at: Long) {
        db.execSQL("UPDATE work_sites SET presence='UNKNOWN',observed_ms=NULL WHERE job_id=?", arrayOf(jobId))
        db.execSQL("UPDATE automation_policies SET armed=0,last_presence='UNKNOWN',facts_since_ms=MAX(facts_since_ms,?) WHERE job_id=?",
            arrayOf<Any?>(at, jobId))
    }

    fun observe(db: SQLiteDatabase, keys: Set<String>, presence: GeofenceMembership.Presence,
        observed: Long, activeSessionId: String?): Observation {
        val sites = sites(db, true)
        val policyByJob = policies(db).associateBy { it.settings.jobId }
        val records = db.rawQuery("SELECT registration_key,presence,observed_ms FROM work_sites WHERE presence!='UNKNOWN'", null).use {
            buildMap { while (it.moveToNext()) put(it.getString(0), GeofenceMembership.Observation(
                GeofenceMembership.Presence.valueOf(it.getString(1)), it.getLong(2))) }
        }
        val before = GeofenceMembership.State(records, policyByJob.filterValues {
            it.lastPresence != GeofenceMembership.Presence.UNKNOWN
        }.mapValues { it.value.lastPresence })
        val olderFactsKeys = sites.filter { site -> site.registrationKey in keys &&
            observed < policyByJob.getValue(site.jobId).factsSinceUtcMillis }.map { it.registrationKey }.toSet()
        val barrierKeys = sites.filter { site -> site.registrationKey in keys && policyByJob[site.jobId]?.let {
            it.manualBarrierUtcMillis != null && observed <= it.manualBarrierUtcMillis
        } == true }.map { it.registrationKey }.toSet()
        val result = GeofenceMembership.apply(before, sites.map { GeofenceMembership.Site(it.registrationKey, it.jobId) },
            keys - barrierKeys - olderFactsKeys, presence, observed)
        val warnings = result.lateOrConflictingSites + barrierKeys
        if (activeSessionId != null && warnings.isNotEmpty()) {
            db.rawQuery("SELECT job_id,start_ms FROM sessions WHERE id=? AND end_ms IS NULL", arrayOf(activeSessionId)).use { active ->
                if (active.moveToFirst()) {
                    val jobId = active.getString(0)
                    if (observed > active.getLong(1) && policyByJob[jobId]?.settings?.automaticDeparture == true &&
                        sites.any { it.jobId == jobId && it.registrationKey in warnings - barrierKeys }) {
                        conflict(db, activeSessionId, observed, "LATE_OR_CONFLICTING_TRANSITION")
                    }
                }
            }
        }
        result.state.sites.forEach { (key, value) ->
            db.execSQL("UPDATE work_sites SET presence=?,observed_ms=? WHERE registration_key=?",
                arrayOf<Any?>(value.presence.name, value.observedUtcMillis, key))
        }
        result.state.lastDefiniteJobs.forEach { (job, value) ->
            db.execSQL("UPDATE automation_policies SET last_presence=? WHERE job_id=?", arrayOf(value.name, job))
            if (value == GeofenceMembership.Presence.OUTSIDE && sites.filter { it.jobId == job }.all {
                result.state.sites[it.registrationKey]?.presence == GeofenceMembership.Presence.OUTSIDE
            }) {
                db.execSQL("UPDATE automation_policies SET armed=1,suppressed=0 WHERE job_id=?", arrayOf(job))
            }
        }
        val enriched = result.copy(lateOrConflictingSites = warnings)
        return Observation(enriched, select(db, enriched, activeSessionId), result.state != before || warnings.isNotEmpty())
    }

    fun select(db: SQLiteDatabase, result: GeofenceMembership.Result, activeSessionId: String?): GeofenceMembership.StartSelection {
        val next = policies(db)
        return GeofenceMembership.selectAutoStart(result,
            next.filter { it.settings.enabled && it.settings.autoStart }.map { it.settings.jobId }.toSet(),
            next.filter { it.armed }.map { it.settings.jobId }.toSet(),
            next.filter { it.suppressed }.map { it.settings.jobId }.toSet() + unresolvedJobs(db), activeSessionId)
    }

    fun departureBoundary(db: SQLiteDatabase, jobId: String): Long? = db.rawQuery(
        "SELECT MAX(s.observed_ms) FROM work_sites s JOIN automation_policies p ON p.job_id=s.job_id WHERE s.job_id=? AND s.enabled=1 AND p.enabled=1", arrayOf(jobId),
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun timelyFinalizedSessions(db: SQLiteDatabase, jobId: String, observed: Long): Set<String> = db.rawQuery(
        "SELECT d.session_id FROM finalized_departures d JOIN sessions s ON s.id=d.session_id WHERE s.job_id=? AND d.detected_ms<=? AND d.deadline_ms>?",
        arrayOf(jobId, observed.toString(), observed.toString()),
    ).use { buildSet { while (it.moveToNext()) add(it.getString(0)) } }
}
