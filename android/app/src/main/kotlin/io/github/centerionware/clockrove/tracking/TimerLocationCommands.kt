package io.github.centerionware.clockrove.tracking

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import org.json.JSONObject

internal typealias TimerLocLocationPoint = TimerRepository.LocationPoint
internal typealias TimerLocSampleResult = TimerRepository.SampleResult
internal typealias TimerLocTrackingObservation = TimerRepository.TrackingObservation
internal typealias TimerLocDeparture = TimerRepository.Departure
internal typealias TimerLocDepartureResult = TimerRepository.DepartureResult
internal typealias TimerLocSnapshot = TimerRepository.Snapshot
internal typealias TimerLocCommand = TimerRepository.Command
internal typealias TimerLocAction = TimerRepository.Action
internal typealias TimerLocOrigin = TimerRepository.Origin
internal typealias TimerLocOutcome = TimerRepository.Outcome
internal typealias TimerLocGeofenceProblem = TimerRepository.GeofenceProblem
internal typealias TimerLocGeofenceOutcome = TimerRepository.GeofenceOutcome

private const val MIN_LOCATION_SAMPLE_INTERVAL_MS = TimerRepository.MIN_LOCATION_SAMPLE_INTERVAL_MS

internal fun TimerRepository.recordLocationCore(point: TimerLocLocationPoint): TimerLocSampleResult {
        if (point.id.isBlank() || point.sessionId.isBlank() ||
            !point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
            !point.longitude.isFinite() || point.longitude !in -180.0..180.0 ||
            !point.accuracyMeters.isFinite() || point.accuracyMeters < 0 ||
            point.measuredUtcMillis > point.receivedUtcMillis) return TimerLocSampleResult.INVALID_SAMPLE
        val db = helper.writableDatabase
        var recorded = false
        db.beginTransaction()
        try {
            db.rawQuery("SELECT * FROM location_points WHERE id=?", arrayOf(point.id)).use {
                if (it.moveToFirst()) {
                    val result = if (decodePoint(it).copy(receivedUtcMillis = point.receivedUtcMillis) == point) TimerLocSampleResult.DUPLICATE else TimerLocSampleResult.ID_CONFLICT
                    db.setTransactionSuccessful()
                    return result
                }
            }
            val active = readSnapshot(db).running
            if (active == null || active.id != point.sessionId || point.measuredUtcMillis < active.startUtcMillis) {
                db.setTransactionSuccessful()
                return TimerLocSampleResult.OUTSIDE_SESSION
            }
            if (!LocationRecordingStore.enabled(db, active.jobId)) {
                db.setTransactionSuccessful()
                return TimerLocSampleResult.RECORDING_DISABLED
            }
            val tooSoon = db.rawQuery(
                "SELECT measured_ms FROM location_points WHERE session_id=? ORDER BY measured_ms DESC,id DESC LIMIT 1",
                arrayOf(point.sessionId),
            ).use { it.moveToFirst() && point.measuredUtcMillis - it.getLong(0) < MIN_LOCATION_SAMPLE_INTERVAL_MS }
            if (tooSoon) {
                db.setTransactionSuccessful()
                return TimerLocSampleResult.THROTTLED
            }
            db.execSQL("INSERT INTO location_points(id,session_id,measured_ms,received_ms,latitude,longitude,accuracy) VALUES (?,?,?,?,?,?,?)",
                arrayOf<Any?>(point.id, point.sessionId, point.measuredUtcMillis, point.receivedUtcMillis,
                    point.latitude, point.longitude, point.accuracyMeters))
            RevisionStore.record(
                db,
                "location",
                point.id,
                active.jobId,
                mapOf("id" to point.id, "session_id" to point.sessionId),
                point.receivedUtcMillis,
                origin = "LOCATION_OBSERVATION",
                additionalParents = GpsDeletionStore.parentsForPoint(db, active.jobId, point.measuredUtcMillis),
            )
            db.execSQL("UPDATE state SET version=version+1 WHERE id=1")
            db.setTransactionSuccessful()
            recorded = true
        } finally { db.endTransaction() }
        if (recorded) publishVersion(readSnapshot(db).version)
        return TimerLocSampleResult.RECORDED
    }

internal fun TimerRepository.trackingObservationCore(): TimerLocTrackingObservation {
        val running = readSnapshot(helper.readableDatabase).running
        return TimerLocTrackingObservation(running, running?.let { latestLocation(it.id) })
    }

internal fun TimerRepository.schedulableDepartureCore(): TimerLocDeparture? {
        val candidate = pendingDeparture() ?: return null
        return candidate.takeIf { readSnapshot(helper.readableDatabase).running?.id == it.sessionId &&
            !GeofenceStore.needsReview(helper.readableDatabase, it.sessionId) }
    }

internal fun TimerRepository.beginDepartureCore(sessionId: String, detectedUtcMillis: Long, graceMinutes: Int): TimerLocDepartureResult {
        require(graceMinutes in 0..1440)
        val running = readSnapshot(helper.readableDatabase).running
        if (running?.id != sessionId) return TimerLocDepartureResult.STALE_SESSION
        if (detectedUtcMillis <= running.startUtcMillis) return TimerLocDepartureResult.INVALID_BOUNDARY
        if (pendingDeparture() != null) return TimerLocDepartureResult.ALREADY_PENDING
        val deadline = Math.addExact(detectedUtcMillis, graceMinutes * 60_000L)
        return changeDeparture {
            it.execSQL("INSERT INTO pending_departure(session_id,detected_ms,deadline_ms) VALUES (?,?,?)",
                arrayOf<Any?>(sessionId, detectedUtcMillis, deadline))
            TimerLocDepartureResult.RECORDED
        }
    }

internal fun TimerRepository.reenterBeforeDeadlineCore(sessionId: String, detectedUtcMillis: Long): TimerLocDepartureResult {
        val candidate = pendingDeparture() ?: return TimerLocDepartureResult.NO_PENDING
        if (candidate.sessionId != sessionId || readSnapshot(helper.readableDatabase).running?.id != sessionId)
            return TimerLocDepartureResult.STALE_SESSION
        if (detectedUtcMillis < candidate.detectedUtcMillis) return TimerLocDepartureResult.INVALID_BOUNDARY
        if (detectedUtcMillis >= candidate.deadlineUtcMillis) return TimerLocDepartureResult.EXPIRED
        return changeDeparture {
            it.execSQL("DELETE FROM pending_departure WHERE session_id=?", arrayOf(sessionId))
            TimerLocDepartureResult.CANCELLED
        }
    }

internal fun TimerRepository.finalizeDepartureCore(sessionId: String, nowUtcMillis: Long, expectedDeadlineUtcMillis: Long? = null): TimerLocDepartureResult {
        val candidate = pendingDeparture() ?: return TimerLocDepartureResult.NO_PENDING
        if (expectedDeadlineUtcMillis != null && candidate.deadlineUtcMillis != expectedDeadlineUtcMillis)
            return TimerLocDepartureResult.STALE_CANDIDATE
        if (candidate.sessionId != sessionId || readSnapshot(helper.readableDatabase).running?.id != sessionId)
            return TimerLocDepartureResult.STALE_SESSION
        if (nowUtcMillis < candidate.deadlineUtcMillis) return TimerLocDepartureResult.NOT_DUE
        if (GeofenceStore.needsReview(helper.readableDatabase, sessionId)) return TimerLocDepartureResult.NEEDS_REVIEW
        return changeDeparture {
            finalizeDepartureRows(it, candidate)
            TimerLocDepartureResult.FINALIZED
        }
    }

internal fun TimerRepository.changeDeparture(change: (SQLiteDatabase) -> TimerLocDepartureResult): TimerLocDepartureResult {
        val db = helper.writableDatabase
        val result: TimerLocDepartureResult
        db.beginTransaction()
        try {
            result = change(db)
            db.execSQL("UPDATE state SET version=version+1 WHERE id=1")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publishVersion(readSnapshot(db).version)
        return result
    }

internal fun TimerRepository.applyTimerRows(db: SQLiteDatabase, before: TimerLocSnapshot, command: TimerLocCommand) {
        if (command.action != TimerLocAction.START) {
            val closing = requireNotNull(before.running)
            if (command.origin != TimerLocOrigin.GEOFENCE) GeofenceStore.manualStop(db, closing.jobId, command.atUtcMillis)
            db.execSQL("DELETE FROM pending_departure WHERE session_id=?", arrayOf(closing.id))
            db.execSQL("UPDATE sessions SET end_ms=? WHERE id=?", arrayOf<Any?>(command.atUtcMillis, closing.id))
            db.execSQL("DELETE FROM location_points WHERE session_id=? AND measured_ms>?", arrayOf<Any?>(closing.id, command.atUtcMillis))
            RevisionStore.snapshotRows(db, "sessions", "session", BusinessSchema.sessionColumns, command.atUtcMillis,
                "job_id", "id=?", arrayOf(closing.id))
        }
        if (command.action != TimerLocAction.STOP) {
            if (command.origin != TimerLocOrigin.GEOFENCE) GeofenceStore.manualStart(db, requireNotNull(command.jobId), command.atUtcMillis)
            val sessionId = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO sessions(id,job_id,start_ms,timezone,offset_seconds,origin) VALUES (?,?,?,?,?,?)",
                arrayOf<Any?>(sessionId, command.jobId, command.atUtcMillis,
                    command.timezone, command.offsetSeconds, command.origin.name))
            RevisionStore.snapshotRows(db, "sessions", "session", BusinessSchema.sessionColumns, command.atUtcMillis,
                "job_id", "id=?", arrayOf(sessionId))
        }
    }

internal fun TimerRepository.finalizeDepartureRows(db: SQLiteDatabase, candidate: TimerLocDeparture) {
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
