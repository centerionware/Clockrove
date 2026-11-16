package io.github.centerionware.clockrove.tracking

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import org.json.JSONObject

internal typealias TimerGeoLocationPoint = TimerRepository.LocationPoint
internal typealias TimerGeoSampleResult = TimerRepository.SampleResult
internal typealias TimerGeoTrackingObservation = TimerRepository.TrackingObservation
internal typealias TimerGeoDeparture = TimerRepository.Departure
internal typealias TimerGeoDepartureResult = TimerRepository.DepartureResult
internal typealias TimerGeoSnapshot = TimerRepository.Snapshot
internal typealias TimerGeoCommand = TimerRepository.Command
internal typealias TimerGeoAction = TimerRepository.Action
internal typealias TimerGeoOrigin = TimerRepository.Origin
internal typealias TimerGeoOutcome = TimerRepository.Outcome
internal typealias TimerGeoGeofenceProblem = TimerRepository.GeofenceProblem
internal typealias TimerGeoGeofenceOutcome = TimerRepository.GeofenceOutcome
internal fun TimerRepository.geofenceEventCore(id: String, registrationKeys: Set<String>,
        presence: io.github.centerionware.clockrove.location.geofence.GeofenceMembership.Presence,
        observedUtcMillis: Long, receivedUtcMillis: Long): TimerGeoGeofenceOutcome {
        require(id.isNotBlank())
        val identity = try {
            geofenceCommandId(id) to eventDigest(org.json.JSONArray(listOf("GEOFENCE_EVENT", presence.name,
                observedUtcMillis, org.json.JSONArray(registrationKeys.sorted()))).toString())
        } catch (_: Exception) {
            return holdGeofenceForReview("GEOFENCE_RECEIPTS_UNAVAILABLE", receivedUtcMillis)
        }
        val (commandId, fingerprint) = identity
        val db = helper.writableDatabase
        val result: TimerGeoGeofenceOutcome
        var changed = false
        db.beginTransaction()
        try {
            val prior = priorOutcome(db, commandId, fingerprint)
            if (prior != null) {
                if (prior.conflict == "COMMAND_ID_REUSED") {
                    db.setTransactionSuccessful()
                    return TimerGeoGeofenceOutcome(prior.version, conflict = prior.conflict, replayed = true)
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
                result = TimerGeoGeofenceOutcome(version, conflict = "INVALID_GEOFENCE_EVENT")
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
                    val command = TimerGeoCommand("$commandId/start", before.version, TimerGeoAction.START, selection.startJobId,
                        observedUtcMillis, "Etc/UTC", 0, TimerGeoOrigin.GEOFENCE)
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
                result = TimerGeoGeofenceOutcome(version, started, selection.choices,
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

internal fun TimerRepository.markGeofenceReviewRows(db: SQLiteDatabase, reason: String, at: Long): Boolean {
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

internal fun TimerRepository.holdGeofenceForReview(reason: String, at: Long): TimerGeoGeofenceOutcome {
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
        return TimerGeoGeofenceOutcome(version, conflict = reason)
    }

internal fun TimerRepository.decodeGeofenceOutcome(text: String): TimerGeoGeofenceOutcome {
        val json = org.json.JSONObject(text)
        // Notification choices and raw warning keys are transient, not an idle trail.
        return TimerGeoGeofenceOutcome(json.getLong("version"),
            startedSessionId = if (json.isNull("started")) null else json.getString("started"),
            conflict = if (json.isNull("conflict")) null else json.getString("conflict"),
            choiceRequired = json.getBoolean("choiceRequired"))
    }
