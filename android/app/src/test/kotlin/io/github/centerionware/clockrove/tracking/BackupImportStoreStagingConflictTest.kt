package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackupImportStoreStagingConflictTest : BackupImportStoreTestBase() {
    @Test
    fun aDuplicateRowRollsBackTheWholePageBeforeAValidRetry() {
        val archiveRecords = prepareSyntheticArchiveRecords(listOf(stagedRow("customers", mapOf(
            "id" to "duplicate-page-customer",
            "name" to "Duplicate page customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))))
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "d".repeat(64),
            archiveRecords.size.toLong(),
            0,
            0,
        )
        val duplicatePage = listOf(archiveRecords.first(), archiveRecords.first())
        assertFailure("DUPLICATE_RECORD") {
            importStore.appendPage(
                started.importId,
                0,
                duplicatePage,
                encodedAppendRequest(started.importId, 0, duplicatePage),
            )
        }
        val validPage = archiveRecords
        assertEquals(
            archiveRecords.size.toLong(),
            importStore.appendPage(
                started.importId,
                0,
                validPage,
                encodedAppendRequest(started.importId, 0, validPage),
            ),
        )
        val preview = importStore.finish(database, started.importId)
        assertEquals(archiveRecords.size.toLong(), preview.recordCount)
        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun rejectsUnsupportedTablesAndIncompleteArchiveBeforePreview() {
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "b".repeat(64),
            1,
            0,
            0,
        )
        val unsupported = mapOf(
            "type" to "row",
            "table" to "commands",
            "values" to emptyMap<String, Any?>(),
            "blobs" to emptyList<Any>(),
        )
        assertFailure("TABLE_NOT_ALLOWED") {
            importStore.appendPage(
                started.importId,
                0,
                listOf(unsupported),
                encodedAppendRequest(started.importId, 0, listOf(unsupported)),
            )
        }
        assertFailure("INCOMPLETE_ARCHIVE") { importStore.finish(database, started.importId) }
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun conflictingExistingRowGetsOpaqueChoicesAndAuthenticatedCursor() {
        val jobs = mutableListOf(jobId)
        repeat(24) { jobs.add(repository.createJob("Synthetic conflict job $it")) }
        val source = exportStore.start(database)
        jobs.forEachIndexed { index, id ->
            val localVersion = repository.snapshot().version
            val changed = repository.jobDetail(id).job.settings.copy(name = "Local edit $index")
            assertNull(repository.saveJobSettings("local-edit-before-preview-$index", localVersion, changed).conflict)
        }

        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "c".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)
        assertTrue(preview.conflictCount > 0)
        assertTrue(preview.changedCount > 0L)
        assertFalse(preview.applyReady)
        val page = importStore.readConflictPage(
            database, started.importId, preview.previewToken, null, 1024,
        )
        val encodedPage = StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(mapOf(
            "importId" to page.importId,
            "previewToken" to page.previewToken,
            "nextCursor" to page.nextCursor,
            "done" to page.done,
            "conflicts" to page.conflicts,
        ))
        assertTrue(encodedPage.position() <= 1024)
        assertTrue(page.conflicts.isNotEmpty())
        assertNotNull(page.nextCursor)
        val conflict = page.conflicts.first()
        assertNotNull(conflict["conflictId"])
        assertNotNull(conflict["allowedChoices"])
        assertNotNull(conflict["defaultChoice"])
        assertEquals(false, conflict["resolved"])
        assertNotNull(conflict["localSummary"])
        assertNotNull(conflict["backupSummary"])
        val next = page.nextCursor
        if (next != null) {
            val forgedBytes = android.util.Base64.decode(
                next,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            forgedBytes[forgedBytes.lastIndex] = (forgedBytes.last().toInt() xor 1).toByte()
            val forged = android.util.Base64.encodeToString(
                forgedBytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            assertFailure("INVALID_CURSOR") {
                importStore.readConflictPage(database, started.importId, preview.previewToken, forged, null)
            }
        }
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun duplicateUniqueValuesInsideBackupRemainUnresolvedAndBlockReadiness() {
        val before = repository.snapshot()
        val records = listOf(
            stagedRow("quotes", mapOf("id" to "quote-one", "number" to "Q-100", "created_ms" to now)),
            stagedRow("quotes", mapOf("id" to "quote-two", "number" to "Q-100", "created_ms" to now + 1L)),
        )
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "8".repeat(64),
            records.size.toLong(),
            0,
            0,
        )
        importStore.appendPage(
            started.importId,
            0,
            records,
            encodedAppendRequest(started.importId, 0, records),
        )

        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.addedCount)
        assertEquals(1L, preview.conflictCount)
        assertFalse(preview.applyReady)
        val conflict = requireNotNull(findConflict(started.importId, preview, "UNIQUE_VALUE_IN_USE"))
        assertEquals("BACKUP:0", conflict["localIdentifier"])
        assertEquals(false, conflict["resolved"])
        assertEquals("KEEP_LOCAL", conflict["defaultChoice"])
        assertEquals(0L, validationTableCount())
        assertEquals(before, repository.snapshot())
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun importedUniqueValueAlreadyUsedLocallyRemainsUnresolved() {
        database.execSQL(
            "INSERT INTO quotes(id,number,created_ms) VALUES(?,?,?)",
            arrayOf<Any?>("local-quote", "Q-200", now),
        )
        val records = listOf(
            stagedRow("quotes", mapOf("id" to "backup-quote", "number" to "Q-200", "created_ms" to now)),
        )
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "7".repeat(64),
            1,
            0,
            0,
        )
        importStore.appendPage(
            started.importId,
            0,
            records,
            encodedAppendRequest(started.importId, 0, records),
        )

        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.conflictCount)
        assertEquals(1L, preview.timeOverlapCount)
        assertFalse(preview.applyReady)
        val conflict = requireNotNull(findConflict(started.importId, preview, "UNIQUE_VALUE_IN_USE"))
        assertEquals("local-quote", conflict["localIdentifier"])
        assertEquals(false, conflict["resolved"])
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun importedSessionOverlappingLocalOpenSessionRemainsUnresolved() {
        val localStart = now
        assertNull(repository.execute(TimerRepository.Command(
            "start-local-open-session-for-import-overlap",
            repository.snapshot().version,
            TimerRepository.Action.START,
            jobId,
            localStart,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val localSessionId = requireNotNull(repository.snapshot().running).id
        now += 10_000L
        val importedStart = localStart + 1_000L
        val records = listOf(stagedRow("sessions", mapOf(
            "id" to "overlapping-import-session",
            "job_id" to jobId,
            "start_ms" to importedStart,
            "end_ms" to importedStart + 1_000L,
            "timezone" to "Etc/UTC",
            "offset_seconds" to 0,
            "origin" to "MANUAL",
        )))
        val started = stageImport(records)

        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.conflictCount)
        assertFalse(preview.applyReady)
        val conflict = requireNotNull(findConflict(started.importId, preview, "SESSION_OVERLAP"))
        assertEquals("LOCAL_OPEN:$localSessionId", conflict["localIdentifier"])
        assertEquals(false, conflict["resolved"])
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun sessionOverlapChoiceOmitsTheIncomingIntervalAtomically() {
        val localStart = now
        val localSessionId = "closed-local-overlap-session"
        assertNull(repository.createManualSession(
            "create-closed-local-overlap-session",
            repository.snapshot().version,
            SessionHistoryStore.Draft(
                localSessionId, jobId, localStart, localStart + 5_000L, "Etc/UTC", 0,
            ),
        ).conflict)
        val incomingId = "explicitly-omitted-overlap-session"
        val started = stageImport(listOf(stagedRow("sessions", mapOf(
            "id" to incomingId,
            "job_id" to jobId,
            "start_ms" to localStart + 1_000L,
            "end_ms" to localStart + 2_000L,
            "timezone" to "Etc/UTC",
            "offset_seconds" to 0,
            "origin" to "MANUAL",
        ))))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "SESSION_OVERLAP"))
        assertEquals(listOf("KEEP_LOCAL", "OMIT_BACKUP_SESSION"), conflict["allowedChoices"])
        assertNull(conflict["selectedChoice"])
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "OMIT_BACKUP_SESSION",
        )
        val beforeApply = repository.snapshot().version
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(0L, number("SELECT COUNT(*) FROM sessions WHERE id='$incomingId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM sessions WHERE id='$localSessionId'"))
        assertEquals(beforeApply, applied.stateVersion)
    }

    @Test
    fun omittingOverlappingBackupSessionAlsoOmitsItsGpsRevisionGraph() {
        val localSessionId = startAndStopSessionWithPoint("local-overlap-gps-point").first
        val backupSessionId = "omitted-backup-session-with-gps"
        val backupPointId = "omitted-backup-session-point"
        val sessionValues = mapOf<String, Any?>(
            "id" to backupSessionId,
            "job_id" to jobId,
            "start_ms" to now + 500L,
            "end_ms" to now + 1_500L,
            "timezone" to "Etc/UTC",
            "offset_seconds" to 0,
            "origin" to "MANUAL",
        )
        val pointValues = mapOf<String, Any?>(
            "id" to backupPointId,
            "session_id" to backupSessionId,
            "measured_ms" to now + 1_000L,
            "received_ms" to now + 1_000L,
            "latitude" to 12.0,
            "longitude" to -34.0,
            "accuracy" to 5.0,
        )
        val records = listOf(
            stagedRow("sessions", sessionValues),
            revisionRecord(
                "omitted-backup-session-revision", "session", backupSessionId, jobId,
                payload = JSONObject(sessionValues.toSortedMap()).toString(),
            ),
            headRecord("session", backupSessionId, "omitted-backup-session-revision"),
            stagedRow("location_points", pointValues),
            revisionRecord(
                "omitted-backup-point-revision", "location", backupPointId, jobId,
                payload = JSONObject().put("id", backupPointId).put("session_id", backupSessionId).toString(),
            ),
            headRecord("location", backupPointId, "omitted-backup-point-revision"),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val sessionConflict = requireNotNull(findConflict(started.importId, preview, "SESSION_OVERLAP"))
        val pointConflict = requireNotNull(findConflict(started.importId, preview, "DEPENDENT_ON_CONFLICT"))
        assertEquals("LOCAL:$localSessionId", sessionConflict["localIdentifier"])
        assertEquals(listOf("OMIT_BACKUP_RECORD"), pointConflict["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            sessionConflict["conflictId"] as String, "OMIT_BACKUP_SESSION",
        )
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            pointConflict["conflictId"] as String, "OMIT_BACKUP_RECORD",
        )

        val countsBefore = importRelevantCounts()
        val versionBefore = repository.snapshot().version
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(versionBefore, applied.stateVersion)
        assertEquals(countsBefore, importRelevantCounts())
        assertEquals(0L, number("SELECT COUNT(*) FROM sessions WHERE id='$backupSessionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$backupPointId'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM entity_heads WHERE (kind='session' AND entity_id='$backupSessionId') " +
                "OR (kind='location' AND entity_id='$backupPointId')",
        ))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM entity_revisions WHERE id IN(" +
                "'omitted-backup-session-revision','omitted-backup-point-revision')",
        ))
    }

    @Test
    fun stalePrePurgeBackupCannotRestoreJobOrItsChildren() {
        val siteId = "stale-backup-purged-site"
        val cardId = "stale-backup-purged-card"
        val sessionId = "stale-backup-purged-session"
        val jobSettings = repository.jobDetail(jobId).job.settings.copy(
            hourlyRateMinor = java.math.BigInteger.valueOf(3_000L),
            taxUnits = 1_500,
        )
        assertNull(repository.saveJobSettings(
            "set-stale-backup-job-sidecars", repository.snapshot().version, jobSettings,
        ).conflict)
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertNull(repository.saveSite(
            "add-stale-backup-site", repository.snapshot().version,
            GeofenceStore.Site(siteId, jobId, "Stale backup site", 12.0, -34.0, enabled = false),
        ).conflict)
        assertNull(repository.saveCard(
            "add-stale-backup-card", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Stale backup card", currency = currency),
        ).conflict)
        assertNull(repository.createManualSession(
            "add-stale-backup-session", repository.snapshot().version,
            SessionHistoryStore.Draft(
                sessionId, jobId, now + 1_000L, now + 2_000L, "Etc/UTC", 0,
            ),
        ).conflict)

        val source = exportStore.start(database)
        val staleRecords = readAllRows(source.snapshotId)
        assertTrue(staleRecords.any { it["table"] == "work_sites" && values(it)["id"] == siteId })
        assertTrue(staleRecords.any { it["table"] == "kanban_cards" && values(it)["id"] == cardId })
        assertTrue(staleRecords.any { it["table"] == "sessions" && values(it)["id"] == sessionId })
        assertTrue(exportStore.close(source.snapshotId))

        assertNull(repository.archiveJob("archive-before-stale-backup-purge", repository.snapshot().version, jobId).conflict)
        now += JobLifecycleStore.retentionMillis
        assertNull(repository.deleteJob("purge-before-stale-backup-restore", repository.snapshot().version, jobId).conflict)
        val deletedVersion = repository.snapshot().version
        assertEquals(1L, number("SELECT COUNT(*) FROM job_deletions WHERE id='$jobId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM jobs WHERE id='$jobId'"))

        val staleRevisionPayloads = staleRecords
            .filter { it["table"] == "entity_revisions" }
            .map { values(it) }
            .filter {
                it["job_id"] == jobId && it["payload"] != null &&
                    it["kind"]?.toString() !in JobLifecycleStore.financialRevisionKinds
            }
            .map { it["id"].toString() }
        val started = stageImport(staleRecords)
        val preview = importStore.finish(database, started.importId)
        val deletionConflict = requireNotNull(
            findConflict(started.importId, preview, "JOB_DELETION_TOMBSTONE"),
        )
        assertEquals("jobs", deletionConflict["table"])
        assertEquals(listOf("PRESERVE_LOCAL_DELETION"), deletionConflict["allowedChoices"])
        assertEquals("PRESERVE_LOCAL_DELETION", deletionConflict["defaultChoice"])
        assertFalse(preview.applyReady)
        var cursor: String? = null
        val conflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(
                database, started.importId, preview.previewToken, cursor, null,
            )
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val blockedTables = conflicts
            .filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }
            .map { it["table"] }
            .toSet()
        assertTrue(blockedTables.containsAll(setOf("work_sites", "kanban_cards", "sessions")))
        assertTrue(blockedTables.containsAll(setOf("automation_policies", "job_money", "job_tax_rates")))
        assertTrue(conflicts.filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }
            .all { it["allowedChoices"] == listOf("PRESERVE_LOCAL_DELETION") })
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(deletedVersion, repository.snapshot().version)
        assertEquals(1L, number("SELECT COUNT(*) FROM job_deletions WHERE id='$jobId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM jobs WHERE id='$jobId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM work_sites WHERE id='$siteId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_cards WHERE id='$cardId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM sessions WHERE id='$sessionId'"))
        staleRevisionPayloads.forEach { revisionId ->
            assertEquals(0L, number(
                "SELECT COUNT(*) FROM entity_revisions WHERE id='$revisionId' AND payload IS NOT NULL",
            ))
        }
        assertFalse(importStore.close(started.importId))
    }
}
