package io.github.centerionware.clockrove.tracking

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.centerionware.clockrove.localizedContext
import io.github.centerionware.clockrove.widget.TimerWidget
import java.util.UUID

/** Native prototype boundary. Callers share this instance; Flutter never writes SQL. */
class TimerRepository internal constructor(context: Context,
    internal val eventDigest: (String) -> String = GeofenceEventDigest::digest,
    internal val wallClock: () -> Long = System::currentTimeMillis) {
    private val appContext = context.applicationContext
    internal val helper = TimerDatabase(context.applicationContext, wallClock)
    private val backupExportStoreDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupExportStore(context.applicationContext, wallClock)
    }
    private val backupExportStore by backupExportStoreDelegate
    private val backupImportStoreDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupImportStore(context.applicationContext, catalogProvider = { db -> backupExportStore.importCatalog(db) })
    }
    private val backupImportStore by backupImportStoreDelegate
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
    enum class SampleResult { RECORDED, DUPLICATE, ID_CONFLICT, INVALID_SAMPLE, OUTSIDE_SESSION, THROTTLED, RECORDING_DISABLED }


    @Synchronized
    fun snapshot(): Snapshot = readSnapshot(helper.readableDatabase)

    @Synchronized
    internal fun getBackupSnapshotInfo(): BackupExportStore.Info =
        backupExportStore.info(helper.readableDatabase)

    @Synchronized
    internal fun startBackupSnapshot(): BackupExportStore.Started =
        backupExportStore.start(helper.readableDatabase)

    @Synchronized
    internal fun readBackupSnapshotPage(
        snapshotId: String,
        cursor: String?,
        maxBytes: Int?,
    ): BackupExportStore.Page = backupExportStore.readPage(snapshotId, cursor, maxBytes)

    @Synchronized
    internal fun readBackupBlob(
        snapshotId: String,
        blobId: String,
        offset: Long,
        maxBytes: Int?,
    ): BackupExportStore.BlobChunk = backupExportStore.readBlob(snapshotId, blobId, offset, maxBytes)

    @Synchronized
    internal fun closeBackupSnapshot(snapshotId: String): Boolean =
        backupExportStore.close(snapshotId)

    @Synchronized
    internal fun beginBackupImport(
        archiveFormatVersion: Int,
        schemaVersion: Int,
        archiveDigest: String,
        recordCount: Long,
        mediaCount: Long,
        mediaBytes: Long,
    ): BackupImportStore.Started = backupImportStore.begin(
        helper.readableDatabase, archiveFormatVersion, schemaVersion, archiveDigest,
        recordCount, mediaCount, mediaBytes,
    )

    @Synchronized
    internal fun appendBackupImportPage(
        importId: String,
        firstOrdinal: Long,
        records: List<*>,
        encodedRequestBytes: Int,
    ): Long = backupImportStore.appendPage(importId, firstOrdinal, records, encodedRequestBytes)

    @Synchronized
    internal fun writeBackupImportBlob(
        importId: String,
        blobId: String,
        offset: Long,
        bytes: ByteArray,
        encodedRequestBytes: Int,
    ): Long = backupImportStore.writeBlob(importId, blobId, offset, bytes, encodedRequestBytes)

    @Synchronized
    internal fun finishBackupImport(importId: String): BackupImportStore.Preview =
        backupImportStore.finish(helper.readableDatabase, importId)

    @Synchronized
    internal fun readBackupImportConflictPage(
        importId: String,
        previewToken: String,
        cursor: String?,
        maxBytes: Int?,
    ): BackupImportStore.ConflictPage = backupImportStore.readConflictPage(
        helper.readableDatabase, importId, previewToken, cursor, maxBytes,
    )

    @Synchronized
    internal fun resolveBackupImportConflict(
        importId: String,
        previewToken: String,
        expectedLocalStateVersion: Long,
        conflictId: String,
        choice: String,
    ) = backupImportStore.resolveConflict(
        helper.readableDatabase, importId, previewToken, expectedLocalStateVersion, conflictId, choice,
    )

    @Synchronized
    internal fun applyBackupImport(
        importId: String,
        previewToken: String,
        expectedLocalStateVersion: Long,
    ): BackupImportStore.Applied {
        val applied = backupImportStore.apply(
            helper.writableDatabase, importId, previewToken, expectedLocalStateVersion,
        )
        if (applied.stateVersion != expectedLocalStateVersion) {
            publishVersion(applied.stateVersion)
            try { TimerWidget.refreshAll(appContext) } catch (_: Exception) { }
        }
        return applied
    }

    @Synchronized
    internal fun closeBackupImport(importId: String): Boolean = backupImportStore.close(importId)

    @Synchronized
    internal fun discardBackupOperations() {
        var failure: Exception? = null
        if (backupExportStoreDelegate.isInitialized()) {
            try { backupExportStore.closeAll() } catch (exception: Exception) { failure = exception }
        }
        if (backupImportStoreDelegate.isInitialized()) {
            try { backupImportStore.closeAll() } catch (exception: Exception) { if (failure == null) failure = exception }
        }
        failure?.let { throw it }
    }

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
    fun recordLocation(point: LocationPoint): SampleResult = recordLocationCore(point)

    @Synchronized
    fun locationPoints(sessionId: String): List<LocationPoint> =
        helper.readableDatabase.rawQuery(
            "SELECT * FROM location_points WHERE session_id=? ORDER BY measured_ms,id", arrayOf(sessionId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(decodePoint(cursor)) } }

    @Synchronized
    fun trackingObservation(): TrackingObservation = trackingObservationCore()

    @Synchronized
    fun latestLocation(sessionId: String): LocationPoint? =
        helper.readableDatabase.rawQuery(
            "SELECT * FROM location_points WHERE session_id=? ORDER BY measured_ms DESC,id DESC LIMIT 1",
            arrayOf(sessionId),
        ).use { if (it.moveToFirst()) decodePoint(it) else null }

    @Synchronized
    internal fun locationRecordingEnabled(jobId: String): Boolean =
        LocationRecordingStore.enabled(helper.readableDatabase, jobId)

    @Synchronized
    internal fun defaultLocationRecordingEnabled(): Boolean =
        LocationRecordingStore.defaultEnabled(helper.readableDatabase)

    internal fun decodePoint(cursor: android.database.Cursor) = LocationPoint(
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
    internal fun schedulableDeparture(): Departure? = schedulableDepartureCore()

    /** Called only after qualified native exits from all of a job's enabled sites. */
    @Synchronized
    internal fun beginDeparture(sessionId: String, detectedUtcMillis: Long, graceMinutes: Int): DepartureResult = beginDepartureCore(sessionId, detectedUtcMillis, graceMinutes)

    @Synchronized
    internal fun reenterBeforeDeadline(sessionId: String, detectedUtcMillis: Long): DepartureResult = reenterBeforeDeadlineCore(sessionId, detectedUtcMillis)

    @Synchronized
    internal fun finalizeDeparture(sessionId: String, nowUtcMillis: Long, expectedDeadlineUtcMillis: Long? = null): DepartureResult = finalizeDepartureCore(sessionId, nowUtcMillis, expectedDeadlineUtcMillis)



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
    internal fun saveSite(id: String, version: Long, site: GeofenceStore.Site,
        currentFix: GeofenceStore.CurrentFix? = null): Outcome = changeConfiguration(id,
        org.json.JSONArray(listOf(version, "SITE_SAVE", site.id, site.jobId, site.name,
            site.latitude.toString(), site.longitude.toString(), site.radiusMeters.toString(), site.enabled,
            currentFix?.let { fix -> OpaqueReceipt.compact(org.json.JSONArray(listOf(
                fix.latitude, fix.longitude, fix.accuracyMeters, fix.measuredUtcMillis,
            )).toString()) })).toString(), version) {
        val conflict = GeofenceStore.saveSite(it, site, wallClock(), currentFix)
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
    internal data class TaskConversationSnapshot(val version: Long, val conversation: TaskConversationStore.Conversation)
    @Synchronized
    internal fun taskConversation(jobId: String, taskId: String): TaskConversationSnapshot = TaskConversationSnapshot(
        readSnapshot(helper.readableDatabase).version, TaskConversationStore.read(helper.readableDatabase, jobId, taskId),
    )
    @Synchronized
    internal fun taskAttachment(attachmentId: String): TaskConversationStore.Attachment? =
        TaskConversationStore.attachment(helper.readableDatabase, attachmentId)
    @Synchronized
    internal fun saveTaskComment(id: String, version: Long, jobId: String, taskId: String, commentId: String, body: String): Outcome =
        boardChange(id, version, "TASK_COMMENT_SAVE", listOf(jobId, taskId, commentId, body)) {
            TaskConversationStore.saveComment(it, jobId, taskId, commentId, body, wallClock())
        }
    @Synchronized
    internal fun removeTaskComment(id: String, version: Long, jobId: String, taskId: String, commentId: String): Outcome =
        boardChange(id, version, "TASK_COMMENT_REMOVE", listOf(jobId, taskId, commentId)) {
            TaskConversationStore.removeComment(it, jobId, taskId, commentId, wallClock())
        }
    @Synchronized
    internal fun attachTaskImage(id: String, version: Long, jobId: String, taskId: String, commentId: String?, attachmentId: String, image: ByteArray): Outcome {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        return boardChange(id, version, "TASK_IMAGE_ADD", listOf(jobId, taskId, commentId, attachmentId, digest)) {
            TaskConversationStore.addAttachment(it, jobId, taskId, commentId, attachmentId, image, wallClock())
        }
    }
    @Synchronized
    internal fun attachTaskImages(id: String, version: Long, jobId: String, taskId: String, commentId: String?, attachments: List<Pair<String, ByteArray>>): Outcome {
        val digests = attachments.map { (attachmentId, image) ->
            listOf(attachmentId, java.security.MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) })
        }
        return boardChange(id, version, "TASK_IMAGES_ADD", listOf(jobId, taskId, commentId, digests)) {
            TaskConversationStore.addAttachments(it, jobId, taskId, commentId, attachments, wallClock())
        }
    }
    @Synchronized
    internal fun removeTaskImage(id: String, version: Long, jobId: String, taskId: String, attachmentId: String): Outcome =
        boardChange(id, version, "TASK_IMAGE_REMOVE", listOf(jobId, taskId, attachmentId)) {
            TaskConversationStore.removeAttachment(it, jobId, taskId, attachmentId, wallClock())
        }
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
            value.billable, MoneyMath.fingerprint(value.priceMinor), value.currency) +
            (if (value.quantityUnits == TaskQuantityStore.one) emptyList() else listOf(value.quantityUnits)) +
            // Keep the historical LABOR default fingerprint stable so an
            // in-flight command from an older app version remains idempotent.
            (if (value.chargeType == TaskClassification.LABOR) emptyList() else listOf(value.chargeType))) {
            BoardStore.saveCard(it, value, wallClock())
        }
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

    internal data class ActualCostsSnapshot(val version: Long, val page: ActualCostStore.Page)
    @Synchronized
    internal fun actualCosts(before: ActualCostStore.Cursor? = null, limit: Int = 100): ActualCostsSnapshot =
        ActualCostsSnapshot(readSnapshot(helper.readableDatabase).version, ActualCostStore.list(helper.readableDatabase, before, limit))
    @Synchronized
    internal fun saveActualCost(id: String, version: Long, value: ActualCostStore.Draft): Outcome =
        boardChange(id, version, "ACTUAL_COST_SAVE", listOf(value.id, value.jobId, value.taskId,
            value.amountMinor, value.currency, value.category, value.occurred, value.timezone,
            value.vendor, value.reference, value.note)) { ActualCostStore.save(it, value, wallClock()) }
    @Synchronized
    internal fun removeActualCost(id: String, version: Long, costId: String): Outcome =
        boardChange(id, version, "ACTUAL_COST_REMOVE", listOf(costId)) { ActualCostStore.remove(it, costId, wallClock()) }

    internal data class ReportSnapshot(val version: Long, val report: ReportStore.Report)
    @Synchronized
    internal fun report(selection: ReportStore.Selection): ReportSnapshot = ReportSnapshot(readSnapshot(helper.readableDatabase).version, ReportStore.read(helper.readableDatabase, selection))
    internal data class BusinessReportSnapshot(val version: Long, val report: BusinessReportStore.Report)
    @Synchronized
    internal fun businessReport(selection: BusinessReportStore.Selection): BusinessReportSnapshot =
        BusinessReportSnapshot(readSnapshot(helper.readableDatabase).version, BusinessReportStore.read(helper.readableDatabase, selection))

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

    internal data class LocationRangeDeletionPreview(val version: Long, val pointCount: Long)
    @Synchronized
    internal fun previewLocationRangeDeletion(range: GpsDeletionStore.Range): LocationRangeDeletionPreview {
        val db = helper.readableDatabase
        GpsDeletionStore.validationProblem(db, range)?.let { throw IllegalArgumentException(it) }
        return LocationRangeDeletionPreview(readSnapshot(db).version, GpsDeletionStore.count(db, range))
    }
    @Synchronized
    internal fun deleteLocationRange(id: String, version: Long, range: GpsDeletionStore.Range): Outcome =
        boardChange(id, version, "GPS_RANGE_DELETE", listOf(range.jobId, range.startUtcMillis,
            range.endUtcMillis, range.timezoneId)) { GpsDeletionStore.delete(it, range, wallClock()) }

    internal data class QuoteContext(val version: Long, val draft: QuoteStore.Draft?, val latest: QuoteStore.Revision?,
        val draftDigits: Int?, val today: String, val timezone: String,
        val draftCustomer: String, val draftEmail: String, val draftCompany: String, val draftStyle: String)
    @Synchronized
    internal fun quote(id: String): QuoteContext {
        val db = helper.readableDatabase; val draft = QuoteStore.draft(db, id)
        val zone = java.util.TimeZone.getDefault()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = zone }.format(java.util.Date(wallClock()))
        val customer = draft?.customerId?.let { customerId -> BusinessStore.customers(db).firstOrNull { it.id == customerId } }
        val company = draft?.let { BusinessStore.settings(db) }
        return QuoteContext(readSnapshot(db).version, draft, QuoteStore.latest(db, id),
            draft?.let { QuoteStore.currencyDigits(db, id, it.currency) }, today, zone.id,
            customer?.name.orEmpty(), customer?.email.orEmpty(), company?.companyName.orEmpty(), company?.invoiceStyle.orEmpty())
    }
    @Synchronized
    internal fun quoteRevision(id: String, revision: Int): QuoteStore.Revision? = QuoteStore.revision(helper.readableDatabase, id, revision)
    @Synchronized
    internal fun quoteCurrencyDigits(id: String?, currency: String): Int = QuoteStore.currencyDigits(helper.readableDatabase, id, currency)
    @Synchronized
    internal fun quoteDocument(id: String, revision: Int): InvoiceStore.Record = QuoteStore.document(helper.readableDatabase, id, revision)
    @Synchronized
    internal fun cacheQuotePdf(id: String, revision: Int, payload: String, bytes: ByteArray): Boolean = QuoteStore.cachePdf(helper.writableDatabase, id, revision, payload, bytes)
    @Synchronized
    internal fun quoteHistory(id: String, before: Int? = null, limit: Int = 20): List<QuoteStore.RevisionSummary> =
        QuoteStore.history(helper.readableDatabase, id, before, limit)
    internal data class QuoteIndex(val version: Long, val today: String, val timezone: String, val page: QuoteStore.Page)
    @Synchronized
    internal fun quotes(before: QuoteStore.Cursor? = null, limit: Int = 20, status: String? = null): QuoteIndex {
        val db = helper.readableDatabase; val zone = java.util.TimeZone.getDefault()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = zone }.format(java.util.Date(wallClock()))
        return QuoteIndex(readSnapshot(db).version, today, zone.id, QuoteStore.index(db, today, before, limit, status))
    }
    @Synchronized
    internal fun applyQuoteScope(id: String, version: Long, value: QuoteScopeStore.Draft): Outcome =
        changeConfiguration(id, OpaqueReceipt.compact(org.json.JSONArray(listOf(version, "QUOTE_SCOPE_APPLY") + value.values()).toString()), version) { QuoteScopeStore.apply(it, value, wallClock()) }
    @Synchronized
    internal fun quoteScope(id: String): Pair<Long, List<QuoteScopeStore.Binding>> =
        readSnapshot(helper.readableDatabase).version to QuoteScopeStore.bindings(helper.readableDatabase, id)
    internal data class ScopeJobChoice(val id: String, val name: String, val mode: String, val currency: String, val archived: Boolean)
    internal data class QuoteScopeContext(val version: Long, val issued: QuoteStore.Revision, val latestRevision: Int,
        val bindings: List<QuoteScopeStore.Binding>, val linkedLines: Set<String>, val jobs: List<ScopeJobChoice>,
        val target: BusinessStore.JobDetail?, val board: BoardStore.Board?, val taskOwners: Map<String, Pair<String, String>>)
    @Synchronized
    internal fun quoteScopeContext(id: String, revision: Int, jobId: String?): QuoteScopeContext {
        val db = helper.readableDatabase
        val issued = requireNotNull(QuoteStore.revision(db, id, revision)) { "QUOTE_UNAVAILABLE" }
        val jobs = db.rawQuery("SELECT id,name,billing_mode,currency,archived FROM jobs ORDER BY name,id", null).use { rows -> buildList {
            while (rows.moveToNext()) add(ScopeJobChoice(rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4) != 0))
        } }
        val board = jobId?.takeIf { candidate -> jobs.any { it.id == candidate } }?.let { BoardStore.read(db, it) }
        val linked = db.rawQuery("SELECT line_id FROM quote_scope_revisions WHERE quote_id=? AND revision=?", arrayOf(id, revision.toString())).use { rows -> buildSet {
            while (rows.moveToNext()) add(rows.getString(0))
        } }
        val owners = if (board == null) emptyMap() else db.rawQuery("SELECT task_id,quote_id,line_id FROM quote_scope_lines WHERE job_id=?", arrayOf(board.jobId)).use { rows -> buildMap {
            while (rows.moveToNext()) put(rows.getString(0), rows.getString(1) to rows.getString(2))
        } }
        return QuoteScopeContext(readSnapshot(db).version, issued, requireNotNull(QuoteStore.latest(db, id)).revision,
            QuoteScopeStore.bindings(db, id), linked, jobs, board?.let { BusinessStore.job(db, it.jobId) }, board, owners)
    }
    internal data class QuoteActualReview(val version: Long, val result: QuoteInvoiceReviewStore.Read)
    @Synchronized
    internal fun quoteActualReview(request: QuoteInvoiceReviewStore.Request): QuoteActualReview =
        QuoteActualReview(readSnapshot(helper.readableDatabase).version, QuoteInvoiceReviewStore.read(helper.readableDatabase, request))
    internal data class QuoteInvoiceCalculation(val version: Long, val result: QuoteInvoiceReviewStore.Review)
    @Synchronized
    internal fun calculateQuoteInvoice(request: QuoteInvoiceReviewStore.Request, choices: QuoteInvoiceReviewStore.Choices): QuoteInvoiceCalculation =
        QuoteInvoiceCalculation(readSnapshot(helper.readableDatabase).version, QuoteInvoiceReviewStore.review(helper.readableDatabase, request, choices))
    @Synchronized
    internal fun prepareQuoteInvoice(id: String, version: Long, value: QuoteInvoiceStore.Draft): Outcome =
        changeConfiguration(id, OpaqueReceipt.compact(org.json.JSONArray(listOf(version, "QUOTE_INVOICE_PREPARE", value.fingerprint())).toString()), version) {
            QuoteInvoiceStore.prepare(it, value, wallClock(), localizedContext(appContext))
        }
    @Synchronized
    internal fun quoteInvoices(id: String, before: Pair<Long, String>? = null, limit: Int = 20): List<QuoteInvoiceStore.Summary> = QuoteInvoiceStore.links(helper.readableDatabase, id, before, limit)
    @Synchronized
    internal fun saveQuote(id: String, version: Long, value: QuoteStore.Draft): Outcome =
        boardChange(id, version, "QUOTE_SAVE", listOf(value.encode())) { QuoteStore.save(it, value, wallClock()) }
    @Synchronized
    internal fun issueQuote(id: String, version: Long, quoteId: String, confirmed: Boolean): Outcome =
        boardChange(id, version, "QUOTE_ISSUE", listOf(quoteId, confirmed)) {
            QuoteStore.issue(it, quoteId, confirmed, wallClock(), localizedContext(appContext))
        }
    @Synchronized
    internal fun decideQuote(id: String, version: Long, quoteId: String, revision: Int, decision: String, note: String, confirmed: Boolean): Outcome =
        boardChange(id, version, "QUOTE_DECIDE", listOf(quoteId, revision, decision, note, confirmed)) { QuoteStore.decide(it, quoteId, revision, decision, note, confirmed, wallClock()) }
    @Synchronized
    internal fun prepareInvoice(id: String, version: Long, invoiceId: String, selection: ReportStore.Selection): Outcome =
        boardChange(id, version, "INVOICE_PREPARE", listOf(invoiceId, selection.firstDate, selection.lastDate, selection.timezone, org.json.JSONArray(selection.jobIds))) {
            InvoiceStore.prepare(it, invoiceId, selection, wallClock(), localizedContext(appContext))
        }
    @Synchronized
    internal fun voidInvoice(id: String, version: Long, value: InvoiceLifecycleStore.VoidDraft): Outcome =
        boardChange(id, version, "INVOICE_VOID", value.values()) { InvoiceLifecycleStore.void(it, value, wallClock()) }
    @Synchronized
    internal fun replaceInvoice(id: String, version: Long, value: InvoiceLifecycleStore.ReplacementDraft): Outcome =
        boardChange(id, version, "INVOICE_REPLACE", value.values()) {
            InvoiceLifecycleStore.replace(it, value, wallClock(), localizedContext(appContext))
        }
    @Synchronized
    internal fun invoicePayments(invoiceId: String): Pair<Long, InvoicePaymentStore.Snapshot> =
        readSnapshot(helper.readableDatabase).version to InvoicePaymentStore.read(helper.readableDatabase, invoiceId)
    @Synchronized
    internal fun recordInvoicePayment(id: String, version: Long, value: InvoicePaymentStore.Draft): Outcome =
        boardChange(id, version, "INVOICE_PAYMENT", value.values()) { InvoicePaymentStore.record(it, value, wallClock()) }
    @Synchronized
    internal fun reverseInvoicePayment(id: String, version: Long, value: InvoicePaymentStore.Reversal): Outcome =
        boardChange(id, version, "INVOICE_PAYMENT_REVERSE", value.values()) { InvoicePaymentStore.reverse(it, value, wallClock()) }
    internal data class InvoicePaymentContext(val version: Long, val now: Long, val timezone: String,
        val localNow: SessionTime.Formatted, val accountingEmail: String, val legacy: Boolean, val jobDeleted: Boolean,
        val ledger: InvoicePaymentStore.Snapshot, val copies: List<InvoiceCopyStore.Summary>,
        val voidInfo: InvoiceLifecycleStore.VoidInfo?, val replacesInvoiceId: String?)
    @Synchronized
    internal fun invoicePaymentContext(invoiceId: String): InvoicePaymentContext {
        val db = helper.readableDatabase
        val ledger = InvoicePaymentStore.read(db, invoiceId)
        val company = BusinessStore.settings(db)
        val timezone = java.util.TimeZone.getDefault().id
        val now = wallClock()
        val legacy = db.rawQuery("SELECT state FROM invoice_coverage WHERE invoice_id=?", arrayOf(invoiceId)).use { !it.moveToFirst() || it.getString(0) == "LEGACY" }
        val deleted = db.rawQuery("SELECT 1 FROM jobs WHERE id=?", arrayOf(ledger.invoice.jobId)).use { !it.moveToFirst() }
        return InvoicePaymentContext(readSnapshot(db).version, now, timezone, SessionTime.format(now, timezone),
            company.receivablesEmail.ifEmpty { company.companyEmail }, legacy, deleted, ledger, InvoiceCopyStore.list(db, invoiceId),
            InvoiceLifecycleStore.voidInfo(db, invoiceId), db.rawQuery("SELECT invoice_id FROM invoice_voids WHERE replacement_id=?", arrayOf(invoiceId)).use { if (it.moveToFirst()) it.getString(0) else null })
    }
    internal data class InvoiceVoidContext(val info: InvoiceLifecycleStore.VoidInfo, val replacementNumber: String?)
    @Synchronized
    internal fun invoiceVoidContext(invoiceId: String): InvoiceVoidContext? {
        val db = helper.readableDatabase
        val info = InvoiceLifecycleStore.voidInfo(db, invoiceId) ?: return null
        val number = info.replacementId?.let { id -> db.rawQuery("SELECT number FROM invoices WHERE id=?", arrayOf(id)).use { check(it.moveToFirst()); it.getString(0) } }
        return InvoiceVoidContext(info, number)
    }
    internal data class InvoiceLifecycleContext(val payment: InvoicePaymentContext, val claims: List<InvoiceLifecycleStore.Claim>)
    @Synchronized
    internal fun invoiceLifecycleContext(invoiceId: String): InvoiceLifecycleContext =
        InvoiceLifecycleContext(invoicePaymentContext(invoiceId), InvoiceLifecycleStore.claims(helper.readableDatabase, invoiceId))
    @Synchronized
    internal fun prepareInvoiceBalanceCopy(id: String, version: Long, copyToken: String, invoiceId: String): Outcome =
        boardChange(id, version, "INVOICE_BALANCE_COPY", listOf(copyToken, invoiceId)) { InvoiceCopyStore.prepareBalance(it, copyToken, invoiceId, wallClock()) }
    @Synchronized
    internal fun invoiceCopies(invoiceId: String): List<InvoiceCopyStore.Summary> = InvoiceCopyStore.list(helper.readableDatabase, invoiceId)
    @Synchronized
    internal fun invoiceCopy(copyId: String): InvoiceStore.Record = InvoiceCopyStore.read(helper.readableDatabase, copyId)
    @Synchronized
    internal fun cacheInvoiceCopyPdf(copyId: String, payload: String, bytes: ByteArray): Boolean {
        require(bytes.size in 5..20_000_000 && bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))
        val db = helper.writableDatabase
        if (InvoiceCopyStore.read(db, copyId).payload != payload) return false
        return db.update("invoice_copies", android.content.ContentValues().apply { put("pdf", bytes) }, "id=? AND pdf IS NULL", arrayOf(copyId)) == 1
    }
    internal data class InvoiceSummary(val id: String, val number: String, val created: Long, val total: String, val currency: String, val style: String,
        val jobId: String, val jobName: String, val customer: String, val jobDeleted: Boolean, val legacy: Boolean,
        val paidMinor: String, val dueMinor: String, val status: String, val digits: Int)
    internal data class InvoiceIndex(val version: Long, val invoices: List<InvoiceSummary>)
    @Synchronized
    internal fun invoices(jobId: String? = null): InvoiceIndex = InvoiceIndex(readSnapshot(helper.readableDatabase).version,
        helper.readableDatabase.rawQuery("SELECT i.id,i.number,i.created_ms,i.payload,i.job_id,j.id,c.state FROM invoices i LEFT JOIN jobs j ON j.id=i.job_id LEFT JOIN invoice_coverage c ON c.invoice_id=i.id ${if (jobId == null) "" else "WHERE i.job_id=?"} ORDER BY i.created_ms DESC,i.id",
            if (jobId == null) null else arrayOf(jobId)).use { rows -> buildList {
            while (rows.moveToNext()) { val snapshot = InvoiceStore.Snapshot.decode(rows.getString(3)); val balance = InvoicePaymentStore.balance(helper.readableDatabase, rows.getString(0), snapshot); add(InvoiceSummary(rows.getString(0), rows.getString(1), rows.getLong(2), snapshot.total, snapshot.currency, snapshot.style,
                rows.getString(4), snapshot.job, snapshot.customer, rows.isNull(5), rows.isNull(6) || rows.getString(6) == "LEGACY", balance.paid.toString(), balance.due.toString(), balance.status, snapshot.digits)) }
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

    internal fun priorOutcome(db: SQLiteDatabase, id: String, fingerprint: String): Outcome? = db.rawQuery(
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
        observedUtcMillis: Long, receivedUtcMillis: Long): GeofenceOutcome = geofenceEventCore(id, registrationKeys, presence, observedUtcMillis, receivedUtcMillis)

    @Synchronized
    internal fun geofenceProblem(problem: GeofenceProblem): GeofenceOutcome = holdGeofenceForReview(problem.name, wallClock())





    internal fun encodeGeofenceOutcome(value: GeofenceOutcome) = org.json.JSONObject().apply {
        put("version", value.version)
        put("started", value.startedSessionId ?: org.json.JSONObject.NULL)
        put("choiceRequired", value.choiceRequired)
        put("conflict", value.conflict ?: org.json.JSONObject.NULL)
    }.toString()


    @Synchronized
    internal fun geofenceCommandId(id: String): String = "geofence/" + eventDigest("id/$id")





    @Synchronized
    internal fun close() {
        listeners.clear()
        if (backupExportStoreDelegate.isInitialized()) {
            try { backupExportStore.closeAll() } catch (_: Exception) { }
        }
        if (backupImportStoreDelegate.isInitialized()) {
            try { backupImportStore.closeAll() } catch (_: Exception) { }
        }
        helper.close()
    }

    @Synchronized
    fun subscribe(listener: (Long) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(readSnapshot(helper.readableDatabase).version)
        return AutoCloseable { synchronized(this) { listeners.remove(listener) } }
    }

    internal fun publishVersion(version: Long) {
        // Subscriber failures must not turn a durable commit into an apparent failure.
        listeners.toList().forEach { listener ->
            try { listener(version) } catch (_: Exception) { }
        }
    }

    internal fun validate(db: SQLiteDatabase, state: Snapshot, c: Command, qualifiedAutomation: Boolean = false): String? {
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

    internal fun readSnapshot(db: SQLiteDatabase): Snapshot {
        val version = db.rawQuery("SELECT version FROM state WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }
        val running = db.rawQuery("SELECT s.id,s.job_id,s.start_ms,j.name FROM sessions s JOIN jobs j ON j.id=s.job_id WHERE s.end_ms IS NULL", null).use {
            if (it.moveToFirst()) Session(it.getString(0), it.getString(1), it.getLong(2), it.getString(3)) else null
        }
        val jobs = db.rawQuery("SELECT id,name FROM jobs WHERE archived=0 ORDER BY name,id", null).use {
            buildList { while (it.moveToNext()) add(Job(it.getString(0), it.getString(1))) }
        }
        return Snapshot(version, running, jobs)
    }



    companion object {
        internal const val MIN_LOCATION_SAMPLE_INTERVAL_MS = 300_000L
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
