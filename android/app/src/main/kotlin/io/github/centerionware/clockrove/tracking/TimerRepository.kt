package io.github.centerionware.clockrove.tracking

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Native prototype boundary. Callers share this instance; Flutter never writes SQL. */
class TimerRepository internal constructor(context: Context,
    private val eventDigest: (String) -> String = GeofenceEventDigest::digest,
    private val wallClock: () -> Long = System::currentTimeMillis) {
    private val helper = Store(context.applicationContext, wallClock)
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
    enum class DepartureResult { RECORDED, ALREADY_PENDING, CANCELLED, FINALIZED, NOT_DUE, NO_PENDING, STALE_SESSION, STALE_CANDIDATE, INVALID_BOUNDARY, EXPIRED, NEEDS_REVIEW }
    data class TrackingObservation(val running: Session?, val latest: LocationPoint?)
    enum class SampleResult { RECORDED, DUPLICATE, ID_CONFLICT, INVALID_SAMPLE, OUTSIDE_SESSION }


    @Synchronized
    fun snapshot(): Snapshot = readSnapshot(helper.readableDatabase)

    /** Fixture convenience; production creation uses the versioned command below. */
    @Synchronized
    internal fun createJob(name: String): String {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO jobs(id,name,currency) SELECT ?,?,currency FROM app_settings WHERE id=1", arrayOf(id, name))
            BusinessSchema.seedBoard(db, id, wallClock())
            RevisionStore.snapshotRows(db, "jobs", "job", BusinessSchema.jobColumns, wallClock(), where = "id=?", args = arrayOf(id))
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
                    val result = if (OpaqueReceipt.matches(it.getString(0), fingerprint)) {
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
                val same = OpaqueReceipt.matchesSurface(it.getString(0), version, action.name, jobId, origin.name)
                return if (same) Outcome(it.getLong(1), if (it.isNull(2)) null else it.getString(2))
                    else Outcome(readSnapshot(helper.readableDatabase).version, "COMMAND_ID_REUSED")
            }
        }
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        return execute(Command(id, version, action, jobId, now, zone.id,
            zone.getOffset(now) / 1000, origin))
    }

    internal data class WidgetSnapshot(val state: Snapshot, val configuration: WidgetConfigurationStore.Configuration) {
        val defaultJob: Job? get() = configuration.jobId?.let { id -> state.jobs.firstOrNull { it.id == id } }
        val missingDefault: Boolean get() = configuration.jobId != null && defaultJob == null
    }
    @Synchronized
    internal fun widgetSnapshot(widgetId: Int): WidgetSnapshot = WidgetSnapshot(readSnapshot(helper.readableDatabase),
        WidgetConfigurationStore.read(helper.readableDatabase, widgetId))
    @Synchronized
    internal fun widgetTasks(widgetId: Int, now: Long = wallClock(), timezone: String = java.util.TimeZone.getDefault().id): WidgetTasksStore.Tasks =
        WidgetTasksStore.read(helper.readableDatabase, WidgetConfigurationStore.read(helper.readableDatabase, widgetId).jobId, now, timezone)

    @Synchronized
    internal fun saveWidgetConfiguration(id: String, version: Long, widgetId: Int, jobId: String?, available: Boolean = true): Outcome =
        // Receipt replay precedes current platform availability; removing a widget
        // cannot strand a lost save response or resurrect its deleted binding.
        boardChange(id, version, "WIDGET_CONFIGURATION", listOf(widgetId, jobId)) {
            if (!available) "WIDGET_UNAVAILABLE" else WidgetConfigurationStore.save(it, widgetId, jobId)
        }
    @Synchronized
    internal fun removeWidgetConfigurations(widgetIds: List<Int>) {
        val db = helper.writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            changed = WidgetConfigurationStore.remove(db, widgetIds)
            if (changed) db.execSQL("UPDATE state SET version=version+1 WHERE id=1")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) publishVersion(readSnapshot(db).version)
    }
    /** Re-check the binding inside the same serialized boundary as timer writes. */
    @Synchronized
    internal fun startConfiguredWidget(widgetId: Int, version: Long, token: String, jobId: String): Outcome {
        require(widgetId > 0 && version >= 0 && token.isNotBlank() && token.length <= 128 && jobId.isNotBlank()) { "INVALID_WIDGET" }
        val id = "widget-start-$widgetId-$token-$version"
        val previous = helper.readableDatabase.rawQuery("SELECT id FROM commands WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
        // A duplicate callback still returns its durable original outcome after
        // configuration changes or clock-out; it must never start new work.
        if (previous) return executeFromSurface(id, version, Action.START, jobId, Origin.WIDGET)
        val binding = WidgetConfigurationStore.read(helper.readableDatabase, widgetId)
        if (binding.token != token || binding.jobId != jobId) return Outcome(readSnapshot(helper.readableDatabase).version, "WIDGET_CONFIGURATION_CHANGED")
        return executeFromSurface(id, version, Action.START, jobId, Origin.WIDGET)
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

    /** One coherent read for the Android alarm adapter; review holds must not spin alarms. */
    @Synchronized
    internal fun schedulableDeparture(): Departure? {
        val candidate = pendingDeparture() ?: return null
        return candidate.takeIf { readSnapshot(helper.readableDatabase).running?.id == it.sessionId &&
            !GeofenceStore.needsReview(helper.readableDatabase, it.sessionId) }
    }

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
    internal fun finalizeDeparture(sessionId: String, nowUtcMillis: Long, expectedDeadlineUtcMillis: Long? = null): DepartureResult {
        val candidate = pendingDeparture() ?: return DepartureResult.NO_PENDING
        if (expectedDeadlineUtcMillis != null && candidate.deadlineUtcMillis != expectedDeadlineUtcMillis)
            return DepartureResult.STALE_CANDIDATE
        if (candidate.sessionId != sessionId || readSnapshot(helper.readableDatabase).running?.id != sessionId)
            return DepartureResult.STALE_SESSION
        if (nowUtcMillis < candidate.deadlineUtcMillis) return DepartureResult.NOT_DUE
        if (GeofenceStore.needsReview(helper.readableDatabase, sessionId)) return DepartureResult.NEEDS_REVIEW
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

    internal enum class GeofenceProblem { REGISTRATION_UNAVAILABLE, LOCATION_PERMISSION_MISSING, PROVIDER_UNAVAILABLE, RECOVERY_UNCERTAIN }
    internal data class GeofenceOutcome(val version: Long, val startedSessionId: String? = null,
        val choices: Set<String> = emptySet(), val warnings: Set<String> = emptySet(),
        val conflict: String? = null, val replayed: Boolean = false,
        val choiceRequired: Boolean = choices.isNotEmpty())

    internal data class WorkReview(val sessionId: String, val start: Long, val end: Long?, val historical: Boolean)
    internal data class AutomationSnapshot(val version: Long, val settings: GeofenceStore.Settings,
        val sites: List<GeofenceStore.Site>, val pending: Departure?, val reviews: List<WorkReview>,
        val armed: Boolean, val suppressed: Boolean, val working: Boolean)

    @Synchronized
    internal fun automationSnapshot(jobId: String): AutomationSnapshot {
        val db = helper.readableDatabase
        val state = readSnapshot(db)
        require(state.jobs.any { it.id == jobId })
        val policy = GeofenceStore.policies(db).firstOrNull { it.settings.jobId == jobId }
        val reviews = db.rawQuery("SELECT s.id,s.start_ms,s.end_ms,c.requires_history_review FROM geofence_conflicts c JOIN sessions s ON s.id=c.session_id WHERE c.job_id=? ORDER BY s.start_ms", arrayOf(jobId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(WorkReview(cursor.getString(0), cursor.getLong(1),
                if (cursor.isNull(2)) null else cursor.getLong(2), cursor.getInt(3) != 0)) }
        }
        return AutomationSnapshot(state.version, policy?.settings ?: GeofenceStore.Settings(jobId),
            GeofenceStore.sites(db).filter { it.jobId == jobId },
            pendingDeparture().takeIf { state.running?.jobId == jobId }, reviews,
            policy?.armed ?: false, policy?.suppressed ?: false, state.running?.jobId == jobId)
    }

    @Synchronized
    internal fun sites(): List<GeofenceStore.Site> = GeofenceStore.sites(helper.readableDatabase)
    @Synchronized
    internal fun sitesToRegister(): List<GeofenceStore.Site> = GeofenceStore.sites(helper.readableDatabase, true)
    @Synchronized
    internal fun automationPolicies(): List<GeofenceStore.Policy> = GeofenceStore.policies(helper.readableDatabase)
    @Synchronized
    internal fun geofenceReviewJobs(): Set<String> = GeofenceStore.unresolvedJobs(helper.readableDatabase)

    @Synchronized
    internal fun saveSite(id: String, version: Long, site: GeofenceStore.Site): Outcome = changeConfiguration(id,
        org.json.JSONArray(listOf(version, "SITE_SAVE", site.id, site.jobId, site.name,
            site.latitude.toString(), site.longitude.toString(), site.radiusMeters.toString(), site.enabled)).toString(), version) {
        val conflict = GeofenceStore.saveSite(it, site, wallClock())
        if (conflict == null) RevisionStore.snapshotRows(it, "work_sites", "site", BusinessSchema.siteColumns,
            wallClock(), "job_id", "id=?", arrayOf(site.id))
        conflict
    }
    @Synchronized
    internal fun removeSite(id: String, version: Long, siteId: String): Outcome = changeConfiguration(id,
        org.json.JSONArray(listOf(version, "SITE_REMOVE", siteId)).toString(), version) {
        val jobId = it.rawQuery("SELECT job_id FROM work_sites WHERE id=?", arrayOf(siteId)).use { row -> if (row.moveToFirst()) row.getString(0) else null }
        val conflict = GeofenceStore.removeSite(it, siteId, wallClock())
        if (conflict == null && jobId != null) RevisionStore.record(it, "site", siteId, jobId,
            mapOf("id" to siteId, "job_id" to jobId), wallClock(), deleted = true)
        conflict
    }
    @Synchronized
    internal fun saveAutomation(id: String, version: Long, settings: GeofenceStore.Settings): Outcome = changeConfiguration(id,
        org.json.JSONArray(listOf(version, "AUTOMATION_SAVE", settings.jobId, settings.enabled,
            settings.autoStart, settings.automaticDeparture, settings.graceMinutes)).toString(), version) {
        val conflict = GeofenceStore.saveSettings(it, settings, wallClock())
        if (conflict == null) BusinessSchema.snapshotAutomation(it, settings.jobId, wallClock())
        conflict
    }

    @Synchronized
    internal fun acceptGeofenceReview(id: String, version: Long, jobId: String): Outcome = changeConfiguration(id,
        org.json.JSONArray(listOf(version, "REVIEW_ACCEPT_CURRENT", jobId)).toString(), version) {
        GeofenceStore.acceptCurrentHistory(it, jobId, wallClock())
    }

    internal data class BoardSnapshot(val version: Long, val board: BoardStore.Board)
    @Synchronized
    internal fun boardSnapshot(jobId: String): BoardSnapshot = BoardSnapshot(readSnapshot(helper.readableDatabase).version,
        BoardStore.read(helper.readableDatabase, jobId))
    @Synchronized
    internal fun taskTemplates(search: String = ""): List<BoardStore.Template> = BoardStore.templates(helper.readableDatabase, search)
    @Synchronized
    internal fun createJob(id: String, version: Long, jobId: String, name: String): Outcome =
        boardChange(id, version, "JOB_CREATE", listOf(jobId, name)) { db ->
            if (jobId.isBlank() || jobId.length > 128 || name.isBlank() || name.length > 240) "INVALID_JOB"
            else if (db.rawQuery("SELECT id FROM jobs WHERE id=? UNION ALL SELECT entity_id FROM entity_heads WHERE kind='job' AND entity_id=? UNION ALL SELECT id FROM job_deletions WHERE id=? LIMIT 1",
                arrayOf(jobId, jobId, jobId)).use { it.moveToFirst() }) "JOB_ID_EXISTS"
            else {
                val at = wallClock()
                db.execSQL("INSERT INTO jobs(id,name,currency) SELECT ?,?,currency FROM app_settings WHERE id=1", arrayOf(jobId, name.trim()))
                BusinessSchema.seedBoard(db, jobId, at)
                RevisionStore.snapshotRows(db, "jobs", "job", BusinessSchema.jobColumns, at, where = "id=?", args = arrayOf(jobId), origin = "MANUAL")
                null
            }
        }
    @Synchronized
    internal fun saveColumn(id: String, version: Long, columnId: String, jobId: String, name: String, completed: Boolean): Outcome =
        boardChange(id, version, "COLUMN_SAVE", listOf(columnId, jobId, name, completed)) { BoardStore.saveColumn(it, columnId, jobId, name, completed, wallClock()) }
    @Synchronized
    internal fun reorderColumns(id: String, version: Long, jobId: String, columnIds: List<String>): Outcome =
        boardChange(id, version, "COLUMNS_ORDER", listOf(jobId, org.json.JSONArray(columnIds))) { BoardStore.reorderColumns(it, jobId, columnIds, wallClock()) }
    @Synchronized
    internal fun removeColumn(id: String, version: Long, jobId: String, columnId: String, destination: String?): Outcome =
        boardChange(id, version, "COLUMN_REMOVE", listOf(jobId, columnId, destination)) { BoardStore.removeColumn(it, jobId, columnId, destination, wallClock()) }
    @Synchronized
    internal fun saveCard(id: String, version: Long, value: BoardStore.Draft): Outcome =
        boardChange(id, version, "CARD_SAVE", listOf(value.id, value.jobId, value.columnId, value.title, value.description, value.dueDate,
            value.billable, value.priceMinor, value.currency)) { BoardStore.saveCard(it, value, wallClock()) }
    @Synchronized
    internal fun moveCard(id: String, version: Long, jobId: String, cardId: String, destination: String, position: Int): Outcome =
        boardChange(id, version, "CARD_MOVE", listOf(jobId, cardId, destination, position)) { BoardStore.moveCard(it, jobId, cardId, destination, position, wallClock()) }
    @Synchronized
    internal fun removeCard(id: String, version: Long, jobId: String, cardId: String): Outcome =
        boardChange(id, version, "CARD_REMOVE", listOf(jobId, cardId)) { BoardStore.removeCard(it, jobId, cardId, wallClock()) }
    @Synchronized
    internal fun copyTaskTemplate(id: String, version: Long, templateId: String, cardId: String, jobId: String, columnId: String, copyPrice: Boolean): Outcome =
        boardChange(id, version, "TEMPLATE_COPY", listOf(templateId, cardId, jobId, columnId, copyPrice)) { BoardStore.copyTemplate(it, templateId, cardId, jobId, columnId, copyPrice, wallClock()) }
    @Synchronized
    internal fun removeTaskTemplate(id: String, version: Long, templateId: String): Outcome =
        boardChange(id, version, "TEMPLATE_REMOVE", listOf(templateId)) { BoardStore.removeTemplate(it, templateId, wallClock()) }
    internal data class BusinessSnapshot(val version: Long, val settings: BusinessStore.Settings, val customers: List<BusinessStore.Customer>)
    internal data class JobDetailSnapshot(val version: Long, val job: BusinessStore.JobDetail, val canDelete: Boolean)
    @Synchronized
    internal fun businessSnapshot(): BusinessSnapshot = BusinessSnapshot(readSnapshot(helper.readableDatabase).version,
        BusinessStore.settings(helper.readableDatabase), BusinessStore.customers(helper.readableDatabase))
    @Synchronized
    internal fun jobDetail(jobId: String): JobDetailSnapshot {
        val job = BusinessStore.job(helper.readableDatabase, jobId)
        return JobDetailSnapshot(readSnapshot(helper.readableDatabase).version, job, JobLifecycleStore.eligible(job.archived, job.archivedMillis, wallClock()))
    }
    @Synchronized
    internal fun saveBusinessSettings(id: String, version: Long, value: BusinessStore.Settings): Outcome =
        boardChange(id, version, "BUSINESS_SETTINGS", value.values()) { BusinessStore.saveSettings(it, value, wallClock()) }
    @Synchronized
    internal fun saveCustomer(id: String, version: Long, value: BusinessStore.Customer): Outcome =
        boardChange(id, version, "CUSTOMER_SAVE", value.values()) { BusinessStore.saveCustomer(it, value, wallClock()) }
    @Synchronized
    internal fun removeCustomer(id: String, version: Long, customerId: String): Outcome =
        boardChange(id, version, "CUSTOMER_REMOVE", listOf(customerId)) { BusinessStore.removeCustomer(it, customerId, wallClock()) }
    @Synchronized
    internal fun saveJobSettings(id: String, version: Long, value: BusinessStore.JobSettings): Outcome =
        boardChange(id, version, "JOB_SETTINGS", value.values()) { BusinessStore.saveJob(it, value, wallClock()) }

    internal data class ReportSnapshot(val version: Long, val report: ReportStore.Report)
    @Synchronized
    internal fun report(selection: ReportStore.Selection): ReportSnapshot = ReportSnapshot(readSnapshot(helper.readableDatabase).version, ReportStore.read(helper.readableDatabase, selection))

    internal data class ArchiveSnapshot(val version: Long, val jobs: List<JobLifecycleStore.Archived>)
    @Synchronized
    internal fun archiveSnapshot(): ArchiveSnapshot = ArchiveSnapshot(readSnapshot(helper.readableDatabase).version, JobLifecycleStore.archived(helper.readableDatabase, wallClock()))
    @Synchronized
    internal fun reportChoices(): List<Triple<String, String, Boolean>> = helper.readableDatabase.rawQuery("SELECT id,name,archived FROM jobs ORDER BY archived,name,id", null).use { rows -> buildList {
        while (rows.moveToNext()) add(Triple(rows.getString(0), rows.getString(1), rows.getInt(2) != 0))
    } }
    @Synchronized
    internal fun archivedJobs(): List<JobLifecycleStore.Archived> = JobLifecycleStore.archived(helper.readableDatabase, wallClock())
    @Synchronized
    internal fun archiveJob(id: String, version: Long, jobId: String): Outcome = boardChange(id, version, "JOB_ARCHIVE", listOf(jobId)) { JobLifecycleStore.archive(it, jobId, wallClock()) }
    @Synchronized
    internal fun restoreJob(id: String, version: Long, jobId: String): Outcome = boardChange(id, version, "JOB_RESTORE", listOf(jobId)) { JobLifecycleStore.restore(it, jobId, wallClock()) }
    @Synchronized
    internal fun deleteJob(id: String, version: Long, jobId: String): Outcome = boardChange(id, version, "JOB_DELETE", listOf(jobId)) { JobLifecycleStore.remove(it, jobId, wallClock()) }

    internal data class SessionHistorySnapshot(val version: Long, val page: SessionHistoryStore.Page)
    @Synchronized
    internal fun sessionHistory(jobId: String, before: SessionHistoryStore.Cursor? = null, limit: Int = 200): SessionHistorySnapshot =
        SessionHistorySnapshot(readSnapshot(helper.readableDatabase).version, SessionHistoryStore.read(helper.readableDatabase, jobId, before, limit))
    @Synchronized
    internal fun sessionRevisions(jobId: String, sessionId: String): List<SessionHistoryStore.Revision> =
        SessionHistoryStore.revisions(helper.readableDatabase, jobId, sessionId)
    internal data class SessionDetail(val version: Long, val entry: SessionHistoryStore.Entry?)
    @Synchronized
    internal fun sessionDetail(jobId: String, sessionId: String): SessionDetail {
        // Validate the job even when a session has already been deleted.
        SessionHistoryStore.read(helper.readableDatabase, jobId, null, 1)
        val entry = SessionHistoryStore.entry(helper.readableDatabase, sessionId)
        return SessionDetail(readSnapshot(helper.readableDatabase).version, entry?.takeIf { it.jobId == jobId })
    }
    internal data class SessionAuditSnapshot(val version: Long, val page: SessionHistoryStore.AuditPage)
    @Synchronized
    internal fun sessionAudit(jobId: String, before: Long? = null, limit: Int = 100): SessionAuditSnapshot =
        SessionAuditSnapshot(readSnapshot(helper.readableDatabase).version, SessionHistoryStore.audit(helper.readableDatabase, jobId, before, limit))
    @Synchronized
    internal fun createManualSession(id: String, version: Long, value: SessionHistoryStore.Draft): Outcome =
        boardChange(id, version, "SESSION_CREATE", listOf(value.id, value.jobId, value.start, value.end, value.timezone, value.offsetSeconds)) {
            SessionHistoryStore.save(it, value, true, wallClock())
        }
    @Synchronized
    internal fun correctSession(id: String, version: Long, value: SessionHistoryStore.Draft): Outcome =
        boardChange(id, version, "SESSION_CORRECT", listOf(value.id, value.jobId, value.start, value.end, value.timezone, value.offsetSeconds)) {
            SessionHistoryStore.save(it, value, false, wallClock())
        }
    @Synchronized
    internal fun deleteSession(id: String, version: Long, sessionId: String): Outcome =
        boardChange(id, version, "SESSION_DELETE", listOf(sessionId)) { SessionHistoryStore.remove(it, sessionId, wallClock()) }

    @Synchronized
    internal fun prepareInvoice(id: String, version: Long, invoiceId: String, selection: ReportStore.Selection): Outcome =
        boardChange(id, version, "INVOICE_PREPARE", listOf(invoiceId, selection.firstDate, selection.lastDate, selection.timezone, org.json.JSONArray(selection.jobIds))) {
            InvoiceStore.prepare(it, invoiceId, selection, wallClock())
        }
    internal data class InvoiceSummary(val id: String, val number: String, val created: Long, val total: String, val currency: String, val style: String,
        val jobId: String, val jobName: String, val customer: String, val jobDeleted: Boolean, val legacy: Boolean)
    internal data class InvoiceIndex(val version: Long, val invoices: List<InvoiceSummary>)
    @Synchronized
    internal fun invoices(jobId: String? = null): InvoiceIndex = InvoiceIndex(readSnapshot(helper.readableDatabase).version,
        helper.readableDatabase.rawQuery("SELECT i.id,i.number,i.created_ms,i.payload,i.job_id,j.id,c.state FROM invoices i LEFT JOIN jobs j ON j.id=i.job_id LEFT JOIN invoice_coverage c ON c.invoice_id=i.id ${if (jobId == null) "" else "WHERE i.job_id=?"} ORDER BY i.created_ms DESC,i.id",
            if (jobId == null) null else arrayOf(jobId)).use { rows -> buildList {
            while (rows.moveToNext()) { val snapshot = InvoiceStore.Snapshot.decode(rows.getString(3)); add(InvoiceSummary(rows.getString(0), rows.getString(1), rows.getLong(2), snapshot.total, snapshot.currency, snapshot.style,
                rows.getString(4), snapshot.job, snapshot.customer, rows.isNull(5), rows.isNull(6) || rows.getString(6) == "LEGACY")) }
        } })
    internal data class InvoiceBillingPreview(val version: Long, val preview: InvoiceCoverageStore.Preview, val taxUnits: Int)
    @Synchronized
    internal fun invoiceBillingPreview(selection: ReportStore.Selection): InvoiceBillingPreview {
        require(selection.jobIds.size == 1) { "INVALID_INVOICE" }
        val db = helper.readableDatabase
        return InvoiceBillingPreview(readSnapshot(db).version, InvoiceCoverageStore.preview(db, ReportStore.read(db, selection)),
            BusinessStore.job(db, selection.jobIds.single()).effectiveTaxUnits)
    }
    internal data class LegacyInvoiceContext(val version: Long, val context: LegacyInvoiceStore.Context)
    @Synchronized
    internal fun legacyInvoiceContext(invoiceId: String): LegacyInvoiceContext = LegacyInvoiceContext(
        readSnapshot(helper.readableDatabase).version, LegacyInvoiceStore.context(helper.readableDatabase, invoiceId))
    @Synchronized
    internal fun reconcileInvoiceCoverage(id: String, version: Long, value: LegacyInvoiceStore.Draft): Outcome =
        boardChange(id, version, "INVOICE_COVERAGE_RECONCILE", value.values()) { LegacyInvoiceStore.reconcile(it, value, wallClock()) }
    @Synchronized
    internal fun invoice(id: String): InvoiceStore.Record = InvoiceStore.read(helper.readableDatabase, id)
    @Synchronized
    internal fun cacheInvoicePdf(id: String, payload: String, bytes: ByteArray): Boolean {
        require(bytes.size in 5..20_000_000 && bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))
        val db = helper.writableDatabase
        val values = android.content.ContentValues().apply { put("pdf", bytes) }
        return db.update("invoices", values, "id=? AND payload=? AND pdf IS NULL", arrayOf(id, payload)) == 1
    }

    @Synchronized
    internal fun logo(): LogoStore.Snapshot = helper.readableDatabase.rawQuery("SELECT l.id,l.width,l.height,l.bytes FROM app_settings s LEFT JOIN logo_assets l ON l.id=s.logo_id WHERE s.id=1", null).use {
        it.moveToFirst(); LogoStore.Snapshot(readSnapshot(helper.readableDatabase).version, if (it.isNull(0)) null else it.getString(0), if (it.isNull(1)) null else it.getInt(1), if (it.isNull(2)) null else it.getInt(2), if (it.isNull(3)) null else it.getBlob(3))
    }
    @Synchronized
    internal fun applyLogo(id: String, version: Long, token: String, load: () -> LogoStore.Asset?): Outcome = boardChange(id, version, "LOGO_APPLY", listOf(token)) { LogoStore.apply(it, token, load, wallClock()) }
    @Synchronized
    internal fun removeLogo(id: String, version: Long): Outcome = boardChange(id, version, "LOGO_REMOVE", emptyList()) { LogoStore.remove(it, wallClock()) }

    private fun boardChange(id: String, version: Long, action: String, values: List<Any?>, change: (SQLiteDatabase) -> String?): Outcome =
        changeConfiguration(id, org.json.JSONArray(listOf(version, action) + values).toString(), version, change)

    private fun changeConfiguration(id: String, fingerprint: String, expectedVersion: Long,
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
        if (!it.moveToFirst()) null else if (!OpaqueReceipt.matches(it.getString(0), fingerprint))
            Outcome(readSnapshot(db).version, "COMMAND_ID_REUSED")
        else Outcome(it.getLong(1), if (it.isNull(2)) null else it.getString(2))
    }

    /** Platform callbacks use this native boundary; Flutter cannot submit automation origins. */
    @Synchronized
    internal fun geofenceEvent(id: String, registrationKeys: Set<String>,
        presence: io.github.centerionware.clockrove.location.geofence.GeofenceMembership.Presence,
        observedUtcMillis: Long, receivedUtcMillis: Long): GeofenceOutcome {
        require(id.isNotBlank())
        val identity = try {
            geofenceCommandId(id) to eventDigest(org.json.JSONArray(listOf("GEOFENCE_EVENT", presence.name,
                observedUtcMillis, org.json.JSONArray(registrationKeys.sorted()))).toString())
        } catch (_: Exception) {
            return holdGeofenceForReview("GEOFENCE_RECEIPTS_UNAVAILABLE", receivedUtcMillis)
        }
        val (commandId, fingerprint) = identity
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
                changed = markGeofenceReviewRows(db, "INVALID_GEOFENCE_EVENT", receivedUtcMillis)
                val version = if (changed) before.version + 1 else before.version
                if (changed) db.execSQL("UPDATE state SET version=? WHERE id=1", arrayOf(version))
                result = GeofenceOutcome(version, conflict = "INVALID_GEOFENCE_EVENT")
            } else {
                val observation = GeofenceStore.observe(db, registrationKeys, presence, observedUtcMillis, before.running?.id)
                changed = observation.changed
                val membership = observation.membership
                var warning = if (membership.lateOrConflictingSites.isEmpty()) null else
                    if (GeofenceStore.unresolvedJobs(db).isEmpty()) "TRANSITION_IGNORED" else "TRANSITION_NEEDS_REVIEW"
                if (presence == io.github.centerionware.clockrove.location.geofence.GeofenceMembership.Presence.INSIDE) {
                    val policies = GeofenceStore.policies(db).associateBy { it.settings.jobId }
                    GeofenceStore.sites(db, true).filter { it.registrationKey in registrationKeys &&
                        observedUtcMillis >= policies.getValue(it.jobId).configuredUtcMillis }.map { it.jobId }.toSet().forEach { jobId ->
                        GeofenceStore.timelyFinalizedSessions(db, jobId, observedUtcMillis).forEach { sessionId ->
                            GeofenceStore.conflict(db, sessionId, observedUtcMillis, "REENTRY_AFTER_FINALIZATION", true)
                            warning = "TRANSITION_NEEDS_REVIEW"
                            changed = true
                        }
                    }
                }
                val running = before.running
                val policy = GeofenceStore.policies(db).firstOrNull { it.settings.jobId == running?.jobId }
                if (running != null && policy?.settings?.enabled == true) {
                    val candidate = pendingDeparture()
                    if (running.jobId in membership.enteredJobs && candidate != null &&
                        candidate.sessionId == running.id && observedUtcMillis >= candidate.detectedUtcMillis &&
                        observedUtcMillis < candidate.deadlineUtcMillis) {
                        db.execSQL("DELETE FROM pending_departure WHERE session_id=?", arrayOf(running.id))
                        db.execSQL("DELETE FROM geofence_conflicts WHERE session_id=? AND requires_history_review=0", arrayOf(running.id))
                        changed = true
                    }
                    if (!GeofenceStore.needsReview(db, running.id) &&
                        running.jobId in membership.departedJobs && policy.settings.automaticDeparture && pendingDeparture() == null) {
                        val boundary = GeofenceStore.departureBoundary(db, running.jobId)
                        if (boundary != null && boundary > running.startUtcMillis) {
                            val deadline = Math.addExact(boundary, policy.settings.graceMinutes * 60_000L)
                            db.execSQL("INSERT INTO pending_departure(session_id,detected_ms,deadline_ms) VALUES (?,?,?)",
                                arrayOf<Any?>(running.id, boundary, deadline))
                            changed = true
                        } else {
                            GeofenceStore.conflict(db, running.id, observedUtcMillis, "INVALID_DEPARTURE_BOUNDARY", true)
                            warning = "TRANSITION_NEEDS_REVIEW"
                            changed = true
                        }
                    }
                }
                val candidate = pendingDeparture()
                if (candidate != null && receivedUtcMillis >= candidate.deadlineUtcMillis &&
                    readSnapshot(db).running?.id == candidate.sessionId &&
                    !GeofenceStore.needsReview(db, candidate.sessionId)) {
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

    @Synchronized
    internal fun geofenceProblem(problem: GeofenceProblem): GeofenceOutcome = holdGeofenceForReview(problem.name, wallClock())

    private fun markGeofenceReviewRows(db: SQLiteDatabase, reason: String, at: Long): Boolean {
        val before = readSnapshot(db)
        val policies = GeofenceStore.policies(db).associateBy { it.settings.jobId }
        val jobs = GeofenceStore.sites(db, true).map { it.jobId }.toMutableSet()
        val candidate = pendingDeparture()
        if (candidate != null) before.running?.let { jobs.add(it.jobId) }
        jobs.forEach { GeofenceStore.suspendFacts(db, it, at) }
        before.running?.let {
            if (candidate != null || (it.jobId in jobs && policies[it.jobId]?.settings?.enabled == true &&
                    policies[it.jobId]?.settings?.automaticDeparture == true)) {
                GeofenceStore.conflict(db, it.id, at, reason)
            }
        }
        return jobs.isNotEmpty()
    }

    private fun holdGeofenceForReview(reason: String, at: Long): GeofenceOutcome {
        val db = helper.writableDatabase
        val version: Long
        var changed = false
        db.beginTransaction()
        try {
            val before = readSnapshot(db)
            changed = markGeofenceReviewRows(db, reason, at)
            version = if (changed) before.version + 1 else before.version
            if (changed) db.execSQL("UPDATE state SET version=? WHERE id=1", arrayOf(version))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) publishVersion(version)
        return GeofenceOutcome(version, conflict = reason)
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
            RevisionStore.snapshotRows(db, "sessions", "session", BusinessSchema.sessionColumns, command.atUtcMillis,
                "job_id", "id=?", arrayOf(closing.id))
        }
        if (command.action != Action.STOP) {
            if (command.origin != Origin.GEOFENCE) GeofenceStore.manualStart(db, requireNotNull(command.jobId), command.atUtcMillis)
            val sessionId = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO sessions(id,job_id,start_ms,timezone,offset_seconds,origin) VALUES (?,?,?,?,?,?)",
                arrayOf<Any?>(sessionId, command.jobId, command.atUtcMillis,
                    command.timezone, command.offsetSeconds, command.origin.name))
            RevisionStore.snapshotRows(db, "sessions", "session", BusinessSchema.sessionColumns, command.atUtcMillis,
                "job_id", "id=?", arrayOf(sessionId))
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
        RevisionStore.snapshotRows(db, "sessions", "session", BusinessSchema.sessionColumns, candidate.detectedUtcMillis,
            "job_id", "id=?", arrayOf(candidate.sessionId))
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

    private class Store(context: Context, private val now: () -> Long) : SQLiteOpenHelper(context, "clockrove.db", null, 7) {
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
            BusinessSchema.install(db, now())
            WidgetConfigurationStore.install(db)
            InvoiceCoverageStore.install(db, now())
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
            check(oldVersion in 1..6 && newVersion == 7) { "Unsupported schema upgrade $oldVersion -> $newVersion" }
            if (oldVersion < 2) createLocationTable(db)
            if (oldVersion < 3) createDepartureTable(db)
            if (oldVersion < 4) GeofenceStore.createTables(db)
            if (oldVersion < 5) BusinessSchema.install(db, now())
            if (oldVersion < 6) WidgetConfigurationStore.install(db)
            if (oldVersion < 7) InvoiceCoverageStore.install(db, now())
        }
    }

    companion object {
        @Volatile private var instance: TimerRepository? = null
        fun get(context: Context): TimerRepository = instance ?: synchronized(this) {
            instance ?: TimerRepository(context).also {
                io.github.centerionware.clockrove.location.geofence.GeofenceRuntime.attach(context.applicationContext, it)
                io.github.centerionware.clockrove.location.geofence.DepartureRuntime.attach(context.applicationContext, it)
                instance = it
            }
        }
    }
}
