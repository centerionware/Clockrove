package io.github.centerionware.clockrove.tracking

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Native prototype boundary. Callers share this instance; Flutter never writes SQL. */
class TimerRepository internal constructor(context: Context,
    private val eventDigest: (String) -> String = GeofenceEventDigest::digest,
    private val wallClock: () -> Long = System::currentTimeMillis) {
    private val helper = Store(context.applicationContext)
    private val listeners = mutableSetOf<(Long) -> Unit>()

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
    data class LocationPoint(
        val id: String, val sessionId: String,
        val measuredUtcMillis: Long, val receivedUtcMillis: Long,
        val latitude: Double, val longitude: Double, val accuracyMeters: Double,
    )
    data class Departure(val sessionId: String, val detectedUtcMillis: Long, val deadlineUtcMillis: Long)
    enum class DepartureResult { RECORDED, ALREADY_PENDING, CANCELLED, FINALIZED, NOT_DUE, NO_PENDING, STALE_SESSION, INVALID_BOUNDARY, EXPIRED, NEEDS_REVIEW }
    data class TrackingObservation(val running: Session?, val latest: LocationPoint?)
    enum class SampleResult { RECORDED, DUPLICATE, ID_CONFLICT, INVALID_SAMPLE, OUTSIDE_SESSION }


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
        publishVersion(readSnapshot(db).version)
        return id
    }

    @Synchronized
    fun execute(command: Command): Outcome {
        val before = readSnapshot(helper.readableDatabase).version
        val outcome = executeTransaction(command)
        val after = readSnapshot(helper.readableDatabase).version
        if (after != before) publishVersion(after)
        return outcome
    }

    private fun executeTransaction(command: Command): Outcome {
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
                applyTimerRows(db, before, command)
                db.execSQL("UPDATE state SET version=? WHERE id=1", arrayOf(version))
            }
            db.execSQL("INSERT INTO commands(id,fingerprint,version,conflict) VALUES (?,?,?,?)",
                arrayOf<Any?>(command.id, fingerprint, version, conflict))
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
                val prior = try { org.json.JSONArray(it.getString(0)) }
                    catch (_: org.json.JSONException) { return Outcome(readSnapshot(helper.readableDatabase).version, "COMMAND_ID_REUSED") }
                val same = prior.length() == 7 && prior.opt(0) is Number && prior.getLong(0) == version && prior.getString(1) == action.name &&
                    (if (prior.isNull(2)) null else prior.getString(2)) == jobId &&
                    prior.getString(6) == origin.name
                return if (same) Outcome(it.getLong(1), if (it.isNull(2)) null else it.getString(2))
                    else Outcome(readSnapshot(helper.readableDatabase).version, "COMMAND_ID_REUSED")
            }
        }
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        return execute(Command(id, version, action, jobId, now, zone.id,
            zone.getOffset(now) / 1000, origin))
    }

    /** Accept observations only for the session that requested them, never idle history. */
    @Synchronized
    fun recordLocation(point: LocationPoint): SampleResult {
        if (point.id.isBlank() || point.sessionId.isBlank() ||
            !point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
            !point.longitude.isFinite() || point.longitude !in -180.0..180.0 ||
            !point.accuracyMeters.isFinite() || point.accuracyMeters < 0 ||
            point.measuredUtcMillis > point.receivedUtcMillis) return SampleResult.INVALID_SAMPLE
        val db = helper.writableDatabase
        var recorded = false
        db.beginTransaction()
        try {
            db.rawQuery("SELECT * FROM location_points WHERE id=?", arrayOf(point.id)).use {
                if (it.moveToFirst()) {
                    val result = if (decodePoint(it).copy(receivedUtcMillis = point.receivedUtcMillis) == point) SampleResult.DUPLICATE else SampleResult.ID_CONFLICT
                    db.setTransactionSuccessful()
                    return result
                }
            }
            val active = readSnapshot(db).running
            if (active == null || active.id != point.sessionId || point.measuredUtcMillis < active.startUtcMillis) {
                db.setTransactionSuccessful()
                return SampleResult.OUTSIDE_SESSION
            }
            db.execSQL("INSERT INTO location_points(id,session_id,measured_ms,received_ms,latitude,longitude,accuracy) VALUES (?,?,?,?,?,?,?)",
                arrayOf<Any?>(point.id, point.sessionId, point.measuredUtcMillis, point.receivedUtcMillis,
                    point.latitude, point.longitude, point.accuracyMeters))
            db.execSQL("UPDATE state SET version=version+1 WHERE id=1")
            db.setTransactionSuccessful()
            recorded = true
        } finally { db.endTransaction() }
        if (recorded) publishVersion(readSnapshot(db).version)
        return SampleResult.RECORDED
    }

    @Synchronized
    fun locationPoints(sessionId: String): List<LocationPoint> =
        helper.readableDatabase.rawQuery(
            "SELECT * FROM location_points WHERE session_id=? ORDER BY measured_ms,id", arrayOf(sessionId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(decodePoint(cursor)) } }

    @Synchronized
    fun trackingObservation(): TrackingObservation {
        val running = readSnapshot(helper.readableDatabase).running
        return TrackingObservation(running, running?.let { latestLocation(it.id) })
    }

    @Synchronized
    fun latestLocation(sessionId: String): LocationPoint? =
        helper.readableDatabase.rawQuery(
            "SELECT * FROM location_points WHERE session_id=? ORDER BY measured_ms DESC,id DESC LIMIT 1",
            arrayOf(sessionId),
        ).use { if (it.moveToFirst()) decodePoint(it) else null }

    private fun decodePoint(cursor: android.database.Cursor) = LocationPoint(
        cursor.getString(cursor.getColumnIndexOrThrow("id")),
        cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
        cursor.getLong(cursor.getColumnIndexOrThrow("measured_ms")),
        cursor.getLong(cursor.getColumnIndexOrThrow("received_ms")),
        cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
        cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
        cursor.getDouble(cursor.getColumnIndexOrThrow("accuracy")),
    )

    @Synchronized
    internal fun pendingDeparture(): Departure? = helper.readableDatabase.rawQuery(
        "SELECT session_id,detected_ms,deadline_ms FROM pending_departure", null,
    ).use { if (it.moveToFirst()) Departure(it.getString(0), it.getLong(1), it.getLong(2)) else null }

    /** Called only after qualified native exits from all of a job's enabled sites. */
    @Synchronized
    internal fun beginDeparture(sessionId: String, detectedUtcMillis: Long, graceMinutes: Int): DepartureResult {
        require(graceMinutes in 0..1440)
        val running = readSnapshot(helper.readableDatabase).running
        if (running?.id != sessionId) return DepartureResult.STALE_SESSION
        if (detectedUtcMillis <= running.startUtcMillis) return DepartureResult.INVALID_BOUNDARY
        if (pendingDeparture() != null) return DepartureResult.ALREADY_PENDING
        val deadline = Math.addExact(detectedUtcMillis, graceMinutes * 60_000L)
        return changeDeparture {
            it.execSQL("INSERT INTO pending_departure(session_id,detected_ms,deadline_ms) VALUES (?,?,?)",
                arrayOf<Any?>(sessionId, detectedUtcMillis, deadline))
            DepartureResult.RECORDED
        }
    }

    @Synchronized
    internal fun reenterBeforeDeadline(sessionId: String, detectedUtcMillis: Long): DepartureResult {
        val candidate = pendingDeparture() ?: return DepartureResult.NO_PENDING
        if (candidate.sessionId != sessionId || readSnapshot(helper.readableDatabase).running?.id != sessionId)
            return DepartureResult.STALE_SESSION
        if (detectedUtcMillis < candidate.detectedUtcMillis) return DepartureResult.INVALID_BOUNDARY
        if (detectedUtcMillis >= candidate.deadlineUtcMillis) return DepartureResult.EXPIRED
        return changeDeparture {
            it.execSQL("DELETE FROM pending_departure WHERE session_id=?", arrayOf(sessionId))
            DepartureResult.CANCELLED
        }
    }

    @Synchronized
    internal fun finalizeDeparture(sessionId: String, nowUtcMillis: Long): DepartureResult {
        val candidate = pendingDeparture() ?: return DepartureResult.NO_PENDING
        if (candidate.sessionId != sessionId || readSnapshot(helper.readableDatabase).running?.id != sessionId)
            return DepartureResult.STALE_SESSION
        if (nowUtcMillis < candidate.deadlineUtcMillis) return DepartureResult.NOT_DUE
        val running = readSnapshot(helper.readableDatabase).running!!
        if (running.jobId in GeofenceStore.unresolvedJobs(helper.readableDatabase)) return DepartureResult.NEEDS_REVIEW
        return changeDeparture {
            finalizeDepartureRows(it, candidate)
            DepartureResult.FINALIZED
        }
    }

    private fun changeDeparture(change: (SQLiteDatabase) -> DepartureResult): DepartureResult {
        val db = helper.writableDatabase
        val result: DepartureResult
        db.beginTransaction()
        try {
            result = change(db)
            db.execSQL("UPDATE state SET version=version+1 WHERE id=1")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publishVersion(readSnapshot(db).version)
        return result
    }

    internal data class GeofenceOutcome(val version: Long, val startedSessionId: String? = null,
        val choices: Set<String> = emptySet(), val warnings: Set<String> = emptySet(),
        val conflict: String? = null, val replayed: Boolean = false,
        val choiceRequired: Boolean = choices.isNotEmpty())

    @Synchronized
    internal fun sites(): List<GeofenceStore.Site> = GeofenceStore.sites(helper.readableDatabase)
    @Synchronized
    internal fun sitesToRegister(): List<GeofenceStore.Site> = GeofenceStore.sites(helper.readableDatabase, true)
    @Synchronized
    internal fun automationPolicies(): List<GeofenceStore.Policy> = GeofenceStore.policies(helper.readableDatabase)
    @Synchronized
    internal fun geofenceReviewJobs(): Set<String> = GeofenceStore.unresolvedJobs(helper.readableDatabase)

    @Synchronized
    internal fun saveSite(id: String, version: Long, site: GeofenceStore.Site): Outcome = configureGeofences(id,
        org.json.JSONArray(listOf(version, "SITE_SAVE", site.id, site.jobId, site.name,
            site.latitude.toString(), site.longitude.toString(), site.radiusMeters.toString(), site.enabled)).toString(), version) {
        GeofenceStore.saveSite(it, site, wallClock())
    }
    @Synchronized
    internal fun removeSite(id: String, version: Long, siteId: String): Outcome = configureGeofences(id,
        org.json.JSONArray(listOf(version, "SITE_REMOVE", siteId)).toString(), version) {
        GeofenceStore.removeSite(it, siteId, wallClock())
    }
    @Synchronized
    internal fun saveAutomation(id: String, version: Long, settings: GeofenceStore.Settings): Outcome = configureGeofences(id,
        org.json.JSONArray(listOf(version, "AUTOMATION_SAVE", settings.jobId, settings.enabled,
            settings.autoStart, settings.automaticDeparture, settings.graceMinutes)).toString(), version) {
        GeofenceStore.saveSettings(it, settings, wallClock())
    }

    @Synchronized
    internal fun acceptGeofenceReview(id: String, version: Long, jobId: String): Outcome = configureGeofences(id,
        org.json.JSONArray(listOf(version, "REVIEW_ACCEPT_CURRENT", jobId)).toString(), version) {
        GeofenceStore.acceptCurrentHistory(it, jobId, wallClock())
    }

    private fun configureGeofences(id: String, fingerprint: String, expectedVersion: Long,
        change: (SQLiteDatabase) -> String?): Outcome {
        require(id.isNotBlank())
        val db = helper.writableDatabase
        val outcome: Outcome
        var changed = false
        db.beginTransaction()
        try {
            priorOutcome(db, id, fingerprint)?.let { db.setTransactionSuccessful(); return it }
            val before = readSnapshot(db).version
            // Helpers reject invalid configurations before writing. Unexpected failures
            // roll back this whole transaction, including the outcome ledger.
            val conflict = if (before != expectedVersion) "STALE_STATE" else change(db)
            val version = if (conflict == null) before + 1 else before
            changed = version != before
            if (changed) db.execSQL("UPDATE state SET version=? WHERE id=1", arrayOf(version))
            db.execSQL("INSERT INTO commands(id,fingerprint,version,conflict) VALUES (?,?,?,?)",
                arrayOf<Any?>(id, fingerprint, version, conflict))
            outcome = Outcome(version, conflict)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) publishVersion(outcome.version)
        return outcome
    }

    private fun priorOutcome(db: SQLiteDatabase, id: String, fingerprint: String): Outcome? = db.rawQuery(
        "SELECT fingerprint,version,conflict FROM commands WHERE id=?", arrayOf(id),
    ).use {
        if (!it.moveToFirst()) null else if (it.getString(0) != fingerprint)
            Outcome(readSnapshot(db).version, "COMMAND_ID_REUSED")
        else Outcome(it.getLong(1), if (it.isNull(2)) null else it.getString(2))
    }

    /** Platform callbacks use this native boundary; Flutter cannot submit automation origins. */
    @Synchronized
    internal fun geofenceEvent(id: String, registrationKeys: Set<String>,
        presence: io.github.centerionware.clockrove.location.geofence.GeofenceMembership.Presence,
        observedUtcMillis: Long, receivedUtcMillis: Long): GeofenceOutcome {
        require(id.isNotBlank())
        val commandId = geofenceCommandId(id)
        val fingerprint = eventDigest(org.json.JSONArray(listOf("GEOFENCE_EVENT", presence.name, observedUtcMillis,
            org.json.JSONArray(registrationKeys.sorted()))).toString())
        val db = helper.writableDatabase
        val result: GeofenceOutcome
        var changed = false
        db.beginTransaction()
        try {
            val prior = priorOutcome(db, commandId, fingerprint)
            if (prior != null) {
                if (prior.conflict == "COMMAND_ID_REUSED") {
                    db.setTransactionSuccessful()
                    return GeofenceOutcome(prior.version, conflict = prior.conflict, replayed = true)
                }
                val payload = db.rawQuery("SELECT payload FROM geofence_event_results WHERE id=?", arrayOf(commandId)).use {
                    check(it.moveToFirst()) { "Missing persisted geofence result" }; it.getString(0)
                }
                db.setTransactionSuccessful()
                return decodeGeofenceOutcome(payload).copy(replayed = true)
            }
            val before = readSnapshot(db)
            val invalid = presence == io.github.centerionware.clockrove.location.geofence.GeofenceMembership.Presence.UNKNOWN ||
                registrationKeys.isEmpty() || registrationKeys.size > 100 || observedUtcMillis > receivedUtcMillis
            if (invalid) {
                result = GeofenceOutcome(before.version, conflict = "INVALID_GEOFENCE_EVENT")
            } else {
                val observation = GeofenceStore.observe(db, registrationKeys, presence, observedUtcMillis, before.running?.id)
                changed = observation.changed
                val membership = observation.membership
                var warning = if (membership.lateOrConflictingSites.isEmpty()) null else "TRANSITION_NEEDS_REVIEW"
                membership.enteredJobs.forEach { jobId ->
                    if (GeofenceStore.timelyEntryAfterFinalization(db, jobId, observedUtcMillis)) {
                        GeofenceStore.conflict(db, jobId, observedUtcMillis, "REENTRY_AFTER_FINALIZATION")
                        warning = "TRANSITION_NEEDS_REVIEW"
                        changed = true
                    }
                }
                val running = before.running
                val policy = GeofenceStore.policies(db).firstOrNull { it.settings.jobId == running?.jobId }
                if (running != null && policy?.settings?.enabled == true &&
                    running.jobId !in GeofenceStore.unresolvedJobs(db)) {
                    val candidate = pendingDeparture()
                    if (running.jobId in membership.enteredJobs && candidate != null &&
                        candidate.sessionId == running.id && observedUtcMillis >= candidate.detectedUtcMillis &&
                        observedUtcMillis < candidate.deadlineUtcMillis) {
                        db.execSQL("DELETE FROM pending_departure WHERE session_id=?", arrayOf(running.id))
                        changed = true
                    }
                    if (running.jobId in membership.departedJobs && policy.settings.automaticDeparture && pendingDeparture() == null) {
                        val boundary = GeofenceStore.departureBoundary(db, running.jobId)
                        if (boundary != null && boundary > running.startUtcMillis) {
                            val deadline = Math.addExact(boundary, policy.settings.graceMinutes * 60_000L)
                            db.execSQL("INSERT INTO pending_departure(session_id,detected_ms,deadline_ms) VALUES (?,?,?)",
                                arrayOf<Any?>(running.id, boundary, deadline))
                            changed = true
                        } else {
                            GeofenceStore.conflict(db, running.jobId, observedUtcMillis, "INVALID_DEPARTURE_BOUNDARY")
                            warning = "TRANSITION_NEEDS_REVIEW"
                            changed = true
                        }
                    }
                }
                val candidate = pendingDeparture()
                if (candidate != null && receivedUtcMillis >= candidate.deadlineUtcMillis &&
                    readSnapshot(db).running?.id == candidate.sessionId &&
                    readSnapshot(db).running!!.jobId !in GeofenceStore.unresolvedJobs(db)) {
                    finalizeDepartureRows(db, candidate)
                    changed = true
                }
                val current = readSnapshot(db)
                val selection = if (warning == null) GeofenceStore.select(db, membership, current.running?.id)
                    else io.github.centerionware.clockrove.location.geofence.GeofenceMembership.StartSelection()
                var started: String? = null
                var conflict = warning
                if (selection.startJobId != null) {
                    val command = Command("$commandId/start", before.version, Action.START, selection.startJobId,
                        observedUtcMillis, "Etc/UTC", 0, Origin.GEOFENCE)
                    val error = validate(db, current, command, true)
                    if (error == null) {
                        applyTimerRows(db, current, command)
                        started = readSnapshot(db).running!!.id
                        changed = true
                    } else {
                        GeofenceStore.conflict(db, selection.startJobId, observedUtcMillis, error)
                        changed = true
                        conflict = error
                    }
                }
                val version = if (changed) before.version + 1 else before.version
                if (changed) db.execSQL("UPDATE state SET version=? WHERE id=1", arrayOf(version))
                result = GeofenceOutcome(version, started, selection.choices,
                    membership.lateOrConflictingSites, conflict)
            }
            db.execSQL("INSERT INTO commands(id,fingerprint,version,conflict) VALUES (?,?,?,?)",
                arrayOf<Any?>(commandId, fingerprint, result.version, result.conflict))
            db.execSQL("INSERT INTO geofence_event_results(id,payload) VALUES (?,?)", arrayOf(commandId, encodeGeofenceOutcome(result)))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) publishVersion(result.version)
        return result
    }

    private fun encodeGeofenceOutcome(value: GeofenceOutcome) = org.json.JSONObject().apply {
        put("version", value.version)
        put("started", value.startedSessionId ?: org.json.JSONObject.NULL)
        put("choiceRequired", value.choiceRequired)
        put("conflict", value.conflict ?: org.json.JSONObject.NULL)
    }.toString()
    private fun decodeGeofenceOutcome(text: String): GeofenceOutcome {
        val json = org.json.JSONObject(text)
        // Notification choices and raw warning keys are transient, not an idle trail.
        return GeofenceOutcome(json.getLong("version"),
            startedSessionId = if (json.isNull("started")) null else json.getString("started"),
            conflict = if (json.isNull("conflict")) null else json.getString("conflict"),
            choiceRequired = json.getBoolean("choiceRequired"))
    }

    @Synchronized
    internal fun geofenceCommandId(id: String): String = "geofence/" + eventDigest("id/$id")

    private fun applyTimerRows(db: SQLiteDatabase, before: Snapshot, command: Command) {
        if (command.action != Action.START) {
            val closing = requireNotNull(before.running)
            if (command.origin != Origin.GEOFENCE) GeofenceStore.manualStop(db, closing.jobId, command.atUtcMillis)
            db.execSQL("DELETE FROM pending_departure WHERE session_id=?", arrayOf(closing.id))
            db.execSQL("UPDATE sessions SET end_ms=? WHERE id=?", arrayOf<Any?>(command.atUtcMillis, closing.id))
            db.execSQL("DELETE FROM location_points WHERE session_id=? AND measured_ms>?", arrayOf<Any?>(closing.id, command.atUtcMillis))
        }
        if (command.action != Action.STOP) {
            db.execSQL("INSERT INTO sessions(id,job_id,start_ms,timezone,offset_seconds,origin) VALUES (?,?,?,?,?,?)",
                arrayOf<Any?>(UUID.randomUUID().toString(), command.jobId, command.atUtcMillis,
                    command.timezone, command.offsetSeconds, command.origin.name))
        }
    }

    private fun finalizeDepartureRows(db: SQLiteDatabase, candidate: Departure) {
        db.execSQL("INSERT OR IGNORE INTO finalized_departures(session_id,detected_ms,deadline_ms) VALUES (?,?,?)",
            arrayOf<Any?>(candidate.sessionId, candidate.detectedUtcMillis, candidate.deadlineUtcMillis))
        db.execSQL("UPDATE sessions SET end_ms=? WHERE id=? AND end_ms IS NULL",
            arrayOf<Any?>(candidate.detectedUtcMillis, candidate.sessionId))
        db.execSQL("DELETE FROM location_points WHERE session_id=? AND measured_ms>?",
            arrayOf<Any?>(candidate.sessionId, candidate.detectedUtcMillis))
        db.execSQL("DELETE FROM pending_departure WHERE session_id=?", arrayOf(candidate.sessionId))
    }

    @Synchronized
    internal fun close() { listeners.clear(); helper.close() }

    @Synchronized
    fun subscribe(listener: (Long) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(readSnapshot(helper.readableDatabase).version)
        return AutoCloseable { synchronized(this) { listeners.remove(listener) } }
    }

    private fun publishVersion(version: Long) {
        // Subscriber failures must not turn a durable commit into an apparent failure.
        listeners.toList().forEach { listener ->
            try { listener(version) } catch (_: Exception) { }
        }
    }

    private fun validate(db: SQLiteDatabase, state: Snapshot, c: Command, qualifiedAutomation: Boolean = false): String? {
        if (c.expectedVersion != state.version) return "STALE_STATE"
        if (c.origin == Origin.GEOFENCE && !qualifiedAutomation) return "AUTOMATION_NOT_READY"
        if (c.origin == Origin.GEOFENCE && c.action != Action.START) return "NO_AUTO_SWITCH"
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

    private class Store(context: Context) : SQLiteOpenHelper(context, "clockrove.db", null, 4) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)")
            db.execSQL("INSERT INTO state VALUES(1,0)")
            db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,name TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),start_ms INTEGER NOT NULL,end_ms INTEGER,timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL,origin TEXT NOT NULL,CHECK(end_ms IS NULL OR end_ms>start_ms))")
            db.execSQL("CREATE UNIQUE INDEX one_open_session ON sessions((1)) WHERE end_ms IS NULL")
            db.execSQL("CREATE INDEX session_dates ON sessions(start_ms,end_ms)")
            db.execSQL("CREATE TABLE commands(id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,version INTEGER NOT NULL,conflict TEXT)")
            createLocationTable(db)
            createDepartureTable(db)
            GeofenceStore.createTables(db)
        }
        private fun createLocationTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE location_points(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),measured_ms INTEGER NOT NULL,received_ms INTEGER NOT NULL,latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),accuracy REAL NOT NULL CHECK(accuracy>=0))")
            db.execSQL("CREATE INDEX location_session_date ON location_points(session_id,measured_ms)")
        }
        private fun createDepartureTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE pending_departure(session_id TEXT PRIMARY KEY REFERENCES sessions(id),detected_ms INTEGER NOT NULL,deadline_ms INTEGER NOT NULL CHECK(deadline_ms>=detected_ms))")
            db.execSQL("CREATE UNIQUE INDEX one_departure_candidate ON pending_departure((1))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..3 && newVersion == 4) { "Unsupported schema upgrade $oldVersion -> $newVersion" }
            if (oldVersion < 2) createLocationTable(db)
            if (oldVersion < 3) createDepartureTable(db)
            GeofenceStore.createTables(db)
        }
    }

    companion object {
        @Volatile private var instance: TimerRepository? = null
        fun get(context: Context): TimerRepository = instance ?: synchronized(this) {
            instance ?: TimerRepository(context).also { instance = it }
        }
    }
}
