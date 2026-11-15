package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.io.File
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
class BackupImportStoreTest {
    private class ManualScheduler(private val elapsed: () -> Long) : BackupScheduler {
        private data class Entry(val dueAt: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, action: () -> Unit): BackupScheduledTask {
            val entry = Entry(elapsed() + delayMillis, action)
            entries.add(entry)
            return BackupScheduledTask { entry.cancelled = true }
        }

        fun runDueTasks() {
            while (true) {
                val next = entries.filter { !it.cancelled && it.dueAt <= elapsed() }
                    .minByOrNull { it.dueAt } ?: return
                next.cancelled = true
                next.action()
            }
        }
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var database: SQLiteDatabase
    private lateinit var exportStore: BackupExportStore
    private lateinit var importStore: BackupImportStore
    private lateinit var jobId: String
    private var now = 1_793_494_800_000L

    @Before
    fun setUp() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now })
        jobId = repository.createJob("Synthetic import job")
        database = SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        exportStore = BackupExportStore(app, wallClock = { now })
        importStore = BackupImportStore(app, catalogProvider = exportStore::importCatalog)
    }

    @After
    fun tearDown() {
        try { importStore.closeAll() } catch (_: Exception) { }
        try { exportStore.closeAll() } catch (_: Exception) { }
        if (::database.isInitialized) database.close()
        if (::repository.isInitialized) repository.close()
    }

    @Test
    fun failedExpirySchedulingDoesNotLeaveAStaleActiveImport() {
        val failedScheduler = BackupScheduler { _, _ ->
            throw IllegalStateException("synthetic scheduler failure")
        }
        val failingStore = BackupImportStore(
            app,
            catalogProvider = exportStore::importCatalog,
            scheduler = failedScheduler,
        )
        assertFailure("STORAGE_UNAVAILABLE") {
            failingStore.begin(
                database,
                BackupExportStore.ARCHIVE_FORMAT_VERSION,
                BackupExportStore.CURRENT_SCHEMA_VERSION,
                "e".repeat(64),
                0,
                0,
                0,
            )
        }
        val directory = File(app.noBackupFilesDir, "clockrove-backup-imports")
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("clockrove-import-") })

        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "f".repeat(64),
            0,
            0,
            0,
        )
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun failedExpiryRescheduleDiscardsTheStagedImport() {
        var scheduleCalls = 0
        var firstTaskCancelled = false
        val rescheduleFailingScheduler = BackupScheduler { _, _ ->
            scheduleCalls++
            if (scheduleCalls > 1) throw IllegalStateException("synthetic reschedule failure")
            BackupScheduledTask { firstTaskCancelled = true }
        }
        val failingStore = BackupImportStore(
            app,
            catalogProvider = exportStore::importCatalog,
            scheduler = rescheduleFailingScheduler,
        )
        val records = listOf(stagedRow("customers", mapOf(
            "id" to "reschedule-cleanup-customer",
            "name" to "Reschedule cleanup customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        )))
        val started = failingStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "a".repeat(64),
            records.size.toLong(),
            0,
            0,
        )

        assertFailure("STORAGE_UNAVAILABLE") {
            failingStore.appendPage(
                started.importId,
                0,
                records,
                encodedAppendRequest(started.importId, 0, records),
            )
        }

        assertTrue(firstTaskCancelled)
        assertFailure("IMPORT_UNAVAILABLE") { failingStore.finish(database, started.importId) }
        val directory = File(app.noBackupFilesDir, "clockrove-backup-imports")
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("clockrove-import-") })
    }

    @Test
    fun fractionalMediaSizeIsRejectedBeforeItCanBeStaged() {
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "d".repeat(64),
            1,
            1,
            1,
        )
        val malformedRecord = mapOf(
            "type" to "row",
            "table" to "logo_assets",
            "values" to mapOf(
                "id" to "synthetic-logo",
                "mime" to "image/png",
                "width" to 1,
                "height" to 1,
                "content_hash" to "synthetic-hash",
            ),
            "blobs" to listOf(mapOf(
                "id" to "a".repeat(43),
                "column" to "bytes",
                "sizeBytes" to 1.5f,
            )),
        )
        assertFailure("INVALID_ARCHIVE") {
            importStore.appendPage(started.importId, 0, listOf(malformedRecord), 128)
        }
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun schema16PreviewAcceptsGpsDeletionRangeAndCausalRevisionRecords() {
        val beforeStart = repository.snapshot().version
        assertNull(repository.execute(TimerRepository.Command(
            "start-synthetic-gps-import-session",
            beforeStart,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val sessionId = requireNotNull(repository.snapshot().running).id
        val pointId = "synthetic-gps-import-point"
        val measuredAt = now + 1_000L
        assertEquals(
            TimerRepository.SampleResult.RECORDED,
            repository.recordLocation(TimerRepository.LocationPoint(
                pointId, sessionId, measuredAt, measuredAt, 12.0, -34.0, 5.0,
            )),
        )
        assertNull(repository.execute(TimerRepository.Command(
            "stop-synthetic-gps-import-session",
            repository.snapshot().version,
            TimerRepository.Action.STOP,
            null,
            measuredAt + 1_000L,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        assertNull(repository.deleteLocationRange(
            "delete-synthetic-gps-import-range",
            repository.snapshot().version,
            GpsDeletionStore.Range(jobId, measuredAt, measuredAt + 1L, "Etc/UTC"),
        ).conflict)

        val source = exportStore.start(database)
        assertEquals(16, source.schemaVersion)
        val exportedRecords = readAllRows(source.snapshotId)
        assertTrue(exportedRecords.any { it["table"] == "location_deletion_ranges" })
        val rangeRevision = exportedRecords.first {
            it["table"] == "entity_revisions" && values(it)["kind"] == "location_deletion_range"
        }
        val rangeRevisionId = values(rangeRevision)["id"]
        val pointTombstone = exportedRecords.first {
            it["table"] == "entity_revisions" && values(it)["kind"] == "location" &&
                (values(it)["deleted"] as Number).toInt() == 1
        }
        val pointTombstoneId = values(pointTombstone)["id"]
        assertTrue(exportedRecords.any {
            it["table"] == "revision_parents" && values(it)["revision_id"] == pointTombstoneId &&
                values(it)["parent_id"] == rangeRevisionId
        })
        val localBeforePreview = repository.snapshot()
        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "b".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        assertEquals(16, started.schemaVersion)
        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)

        assertEquals(16, preview.schemaVersion)
        assertEquals(source.recordCount, preview.recordCount)
        assertEquals(source.recordCount, preview.unchangedCount)
        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        assertEquals(0L, validationTableCount())
        assertEquals(localBeforePreview, repository.snapshot())
        val conflicts = importStore.readConflictPage(
            database, started.importId, preview.previewToken, null, null,
        )
        assertTrue(conflicts.done)
        assertTrue(conflicts.conflicts.isEmpty())
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun rejectsUnsupportedCrossEntityRevisionParents() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("job-revision", "job", "job-one", "job-one"),
            revisionRecord("customer-revision", "customer", "customer-one", null),
            parentRecord("job-revision", "customer-revision"),
            headRecord("job", "job-one", "job-revision"),
            headRecord("customer", "customer-one", "customer-revision"),
        ))
    }

    @Test
    fun rejectsMissingRevisionParentsAndCycles() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("child", "job", "job-one", "job-one"),
            parentRecord("child", "missing-parent"),
            headRecord("job", "job-one", "child"),
        ))

        assertInvalidRevisionGraph(listOf(
            revisionRecord("first", "job", "job-one", "job-one"),
            revisionRecord("second", "job", "job-one", "job-one"),
            parentRecord("first", "second"),
            parentRecord("second", "first"),
            headRecord("job", "job-one", "first"),
        ))
    }

    @Test
    fun rejectsRevisionsWithoutAnOwnedHead() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("unheaded-revision", "job", "job-one", "job-one"),
        ))
    }

    @Test
    fun rejectsGpsRevisionPayloadsContainingCoordinatesOrOtherFields() {
        val pointId = "privacy-sensitive-point"
        val validPrefix = "\"id\":\"$pointId\",\"session_id\":\"synthetic-session\""
        val invalidPayloads = listOf(
            "{malformed",
            "[]",
            "{\"id\":\"different-point\",\"session_id\":\"synthetic-session\"}",
            "{$validPrefix,\"private\":\"gps-payload-canary\"}",
            "{$validPrefix,\"latitude\":12.0}",
            "{$validPrefix,\"longitude\":-34.0}",
            "{$validPrefix,\"accuracy\":5.0}",
            "{$validPrefix,\"measured_ms\":123}",
            "{$validPrefix,\"received_ms\":123}",
        )
        invalidPayloads.forEachIndexed { index, payload ->
            val revisionId = "invalid-location-payload-$index"
            assertInvalidRevisionGraph(
                listOf(
                    revisionRecord(
                        revisionId, "location", pointId, jobId, payload = payload,
                    ),
                    headRecord("location", pointId, revisionId),
                ),
                forbiddenDiagnostic = "gps-payload-canary",
            )
        }
    }

    @Test
    fun acceptsRedactedNullRevisionPayload() {
        val revisionId = "redacted-job-revision"
        val records = listOf(
            revisionRecord(revisionId, "job", "redacted-job", "redacted-job", payload = null),
            headRecord("job", "redacted-job", revisionId),
        )
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "c".repeat(64),
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

        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun rejectsRowsWithUnresolvedForeignKeys() {
        assertInvalidReference(listOf(stagedRow("sessions", mapOf(
            "id" to "orphan-session",
            "job_id" to "missing-job",
            "start_ms" to now,
            "end_ms" to null,
            "timezone" to "Etc/UTC",
            "offset_seconds" to 0,
            "origin" to "MANUAL",
        ))))
    }

    @Test
    fun previewWithoutInvoiceTimeClaimsIsValid() {
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
            0,
            0,
            0,
        )

        val preview = importStore.finish(database, started.importId)

        assertEquals(0L, preview.recordCount)
        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun rejectsInvoiceTaskClaimWhoseJobDiffersFromItsInvoice() {
        assertInvalidReference(listOf(
            invoiceRecord("mismatch-task-invoice", jobId, "INV-SYNTHETIC-TASK"),
            stagedRow("invoice_task_claims", mapOf(
                "id" to "mismatch-task-claim",
                "invoice_id" to "mismatch-task-invoice",
                "job_id" to "synthetic-other-job",
                "task_id" to "synthetic-task",
                "released_ms" to null,
            )),
        ))
    }

    @Test
    fun rejectsInvoiceTimeClaimWhoseJobDiffersFromItsInvoice() {
        assertInvalidReference(listOf(
            invoiceRecord("mismatch-time-invoice", jobId, "INV-SYNTHETIC-TIME"),
            invoiceTimeClaimRecord(
                "mismatch-time-claim", "mismatch-time-invoice", "synthetic-other-job", 100, 200,
            ),
        ))
    }

    @Test
    fun rejectsOverlappingActiveImportedInvoiceTimeClaims() {
        assertInvalidReference(listOf(
            invoiceRecord("overlap-import-invoice", jobId, "INV-SYNTHETIC-OVERLAP"),
            invoiceTimeClaimRecord("overlap-import-claim-a", "overlap-import-invoice", jobId, 100, 200),
            invoiceTimeClaimRecord("overlap-import-claim-b", "overlap-import-invoice", jobId, 199, 300),
        ))
    }

    @Test
    fun rejectsImportedInvoiceTimeClaimOverlappingAnExistingLocalClaim() {
        database.execSQL(
            "INSERT INTO invoices(id,job_id,number,created_ms,payload) VALUES(?,?,?,?,?)",
            arrayOf<Any?>("local-overlap-invoice", jobId, "INV-LOCAL-OVERLAP", now, "{}"),
        )
        database.execSQL(
            "INSERT INTO invoice_time_claims(id,invoice_id,job_id,session_id,start_ms,end_ms,timezone) " +
                "VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                "local-overlap-claim", "local-overlap-invoice", jobId, "local-overlap-session", 100, 200, "Etc/UTC",
            ),
        )
        assertInvalidReference(listOf(
            invoiceRecord("incoming-overlap-invoice", jobId, "INV-INCOMING-OVERLAP"),
            invoiceTimeClaimRecord("incoming-overlap-claim", "incoming-overlap-invoice", jobId, 150, 250),
        ))
    }

    @Test
    fun acceptsDisjointAndReleasedInvoiceTimeClaims() {
        val records = listOf(
            invoiceRecord("valid-time-claims-invoice", jobId, "INV-SYNTHETIC-VALID-TIME"),
            invoiceTimeClaimRecord("valid-time-claim-a", "valid-time-claims-invoice", jobId, 100, 200),
            invoiceTimeClaimRecord("valid-time-claim-b", "valid-time-claims-invoice", jobId, 200, 300),
            invoiceTimeClaimRecord(
                "released-time-claim", "valid-time-claims-invoice", jobId, 150, 250, releasedMs = now,
            ),
        )
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "b".repeat(64),
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

        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun localOpenTimerBlocksApplyReadinessUntilClockedOutAndPreviewedAgain() {
        assertNull(repository.execute(TimerRepository.Command(
            "start-local-timer-before-backup-preview",
            repository.snapshot().version,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val runningState = repository.snapshot()
        assertNotNull(runningState.running)

        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "d".repeat(64),
            0,
            0,
            0,
        )
        val preview = importStore.finish(database, started.importId)
        assertEquals(0L, preview.openSessionCount)
        assertEquals(1L, preview.localOpenSessionCount)
        assertFalse(preview.applyReady)
        assertEquals(runningState, repository.snapshot())
        assertTrue(importStore.close(started.importId))

        now += 1_000L
        assertNull(repository.execute(TimerRepository.Command(
            "explicit-clock-out-before-backup-preview",
            repository.snapshot().version,
            TimerRepository.Action.STOP,
            null,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val stoppedState = repository.snapshot()
        assertNull(stoppedState.running)

        val fresh = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
            0,
            0,
            0,
        )
        val freshPreview = importStore.finish(database, fresh.importId)
        assertEquals(0L, freshPreview.localOpenSessionCount)
        assertTrue(freshPreview.applyReady)
        assertEquals(stoppedState, repository.snapshot())
        assertTrue(importStore.close(fresh.importId))
    }

    @Test
    fun rejectsOutOfRangeAndNoncanonicalPortableValues() {
        val (sessionId, measuredAt) = startAndStopSessionWithPoint("invalid-coordinate-reference")
        val invalidPointId = "invalid-coordinate-point"
        val invalidPointRevisionId = "invalid-coordinate-revision"
        assertInvalidArchive(listOf(
            revisionRecord(invalidPointRevisionId, "location", invalidPointId, jobId),
            headRecord("location", invalidPointId, invalidPointRevisionId),
            stagedRow("location_points", mapOf(
                "id" to invalidPointId,
                "session_id" to sessionId,
                "measured_ms" to measuredAt,
                "received_ms" to measuredAt,
                "latitude" to 91.0,
                "longitude" to -34.0,
                "accuracy" to 5.0,
            )),
        ))
        assertInvalidArchive(listOf(stagedRow("quotes", mapOf(
            "id" to "noncanonical-quote",
            "number" to "Q-INVALID",
            "created_ms" to "not-an-integer",
        ))))
        assertInvalidArchive(listOf(stagedRow("customers", mapOf(
            "id" to "missing-customer-name",
            "name" to null,
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0L,
        ))))
    }

    @Test
    fun integralRealLocationValuesRoundTripThroughPreview() {
        startAndStopSessionWithPoint("integral-real-preview-point")
        val before = repository.snapshot()
        val source = exportStore.start(database)
        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "e".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )

        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)

        assertEquals(source.recordCount, preview.recordCount)
        assertEquals(0L, preview.conflictCount)
        assertEquals(before, repository.snapshot())
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun rejectsRevisionHeadsOwnedByAnotherEntity() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("job-revision", "job", "job-one", "job-one"),
            headRecord("job", "job-one", "job-revision"),
            headRecord("customer", "customer-one", "job-revision"),
        ))
    }

    @Test
    fun oldBackupPointCoveredByLocalRangeBlocksUntilCausalResolutionExists() {
        val session = startAndStopSessionWithPoint("old-backup-covered-point")
        val pointCountBeforeExport = number("SELECT COUNT(*) FROM location_points")
        val source = exportStore.start(database)
        assertEquals(1L, pointCountBeforeExport)
        assertNull(repository.deleteLocationRange(
            "delete-after-old-backup-export",
            repository.snapshot().version,
            GpsDeletionStore.Range(jobId, session.second, session.second + 1L, "Etc/UTC"),
        ).conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges"))

        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "d".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)
        val coverageConflict = findConflict(started.importId, preview, "GPS_DELETION_COVERAGE")
        assertNotNull(coverageConflict)
        val conflict = requireNotNull(coverageConflict)
        assertNull(conflict["defaultChoice"])
        assertEquals(emptyList<String>(), conflict["allowedChoices"])
        assertEquals(false, conflict["resolved"])
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database,
                started.importId,
                preview.previewToken,
                preview.localStateVersion,
                conflict["conflictId"] as String,
                "PRESERVE_DELETION",
            )
        }
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        assertNoCoordinates(conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
        assertFalse(preview.applyReady)
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun importedRangeChoiceThatNeedsCausalRewriteFailsClosed() {
        val session = startAndStopSessionWithPoint("local-point-covered-by-import")
        val rangeId = "synthetic-imported-range"
        val revisionId = "synthetic-imported-range-revision"
        val range = mapOf(
            "id" to rangeId,
            "job_id" to jobId,
            "start_ms" to session.second,
            "end_ms" to session.second + 1L,
            "timezone_id" to "Etc/UTC",
            "deleted_ms" to now,
            "revision_id" to revisionId,
        )
        val records = listOf(
            revisionRecord(
                revisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
            ),
            headRecord("location_deletion_range", rangeId, revisionId),
            stagedRow("location_deletion_ranges", range),
        )
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges"))

        val before = repository.snapshot()
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
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
        val coverageConflict = findConflict(started.importId, preview, "GPS_DELETION_COVERAGE")
        assertNotNull(coverageConflict)
        val conflict = requireNotNull(coverageConflict)
        assertNull(conflict["defaultChoice"])
        assertEquals(emptyList<String>(), conflict["allowedChoices"])
        assertEquals(false, conflict["resolved"])
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database,
                started.importId,
                preview.previewToken,
                preview.localStateVersion,
                conflict["conflictId"] as String,
                "KEEP_LOCAL_POINTS",
            )
        }
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        assertEquals(
            "Unproven local GPS points are covered",
            (conflict["localSummary"] as Map<*, *>)["identifier"],
        )
        assertNoCoordinates(conflict)
        assertEquals(before, repository.snapshot())
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
        assertFalse(preview.applyReady)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun stagingFullRowsAndMediaProducesBoundedReadOnlyPreview() {
        val logo = ByteArray(420_123) { (it * 17 % 251).toByte() }
        database.execSQL(
            "INSERT INTO logo_assets(id,mime,width,height,content_hash,bytes) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>("synthetic-logo", "image/png", 32, 32, "synthetic-hash", logo),
        )
        val source = exportStore.start(database)
        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "a".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        assertEquals(1L, source.mediaCount)
        assertEquals(logo.size.toLong(), source.mediaBytes)
        val stateBeforePreview = repository.snapshot()

        val blobs = mutableListOf<Pair<String, Long>>()
        var firstOrdinal = 0L
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(source.snapshotId, cursor, started.maxPageBytes)
            assertTrue(encodedAppendRequest(started.importId, firstOrdinal, page.records) <= BackupImportStore.MAX_REQUEST_BYTES)
            page.records.forEach { record ->
                val descriptors = record["blobs"] as List<*>
                descriptors.forEach { raw ->
                    val descriptor = raw as Map<*, *>
                    val blobId = descriptor["id"] as String
                    val sizeBytes = descriptor["sizeBytes"] as Number
                    blobs.add(blobId to sizeBytes.toLong())
                }
            }
            firstOrdinal = importStore.appendPage(
                started.importId,
                firstOrdinal,
                page.records,
                encodedAppendRequest(started.importId, firstOrdinal, page.records),
            )
            cursor = page.nextCursor
            done = page.done
        }

        blobs.forEach { (blobId, size) ->
            var offset = 0L
            while (offset < size) {
                val sourceChunk = exportStore.readBlob(
                    source.snapshotId,
                    blobId,
                    offset,
                    BackupImportStore.MAX_BLOB_CHUNK_BYTES,
                )
                val requestBytes = StandardMethodCodec.INSTANCE.encodeMethodCall(MethodCall(
                    "writeBackupImportBlob",
                    mapOf("importId" to started.importId, "blobId" to blobId, "offset" to offset, "bytes" to sourceChunk.bytes),
                )).position()
                assertTrue(requestBytes <= BackupImportStore.MAX_REQUEST_BYTES)
                if (offset == 0L) {
                    assertFailure("INVALID_BLOB_OFFSET") {
                        importStore.writeBlob(started.importId, blobId, sourceChunk.nextOffset, sourceChunk.bytes, requestBytes)
                    }
                }
                offset = importStore.writeBlob(
                    started.importId,
                    blobId,
                    offset,
                    sourceChunk.bytes,
                    requestBytes,
                )
                if (sourceChunk.offset == 0L) {
                    assertFailure("INVALID_BLOB_OFFSET") {
                        importStore.writeBlob(started.importId, blobId, 0, sourceChunk.bytes, requestBytes)
                    }
                }
            }
            assertEquals(size, offset)
        }

        val preview = importStore.finish(database, started.importId)
        assertEquals(source.recordCount, preview.recordCount)
        assertEquals(source.mediaCount, preview.mediaCount)
        assertEquals(0L, preview.addedCount)
        assertEquals(source.recordCount, preview.unchangedCount)
        assertEquals(0L, preview.conflictCount)
        assertEquals(0L, preview.openSessionCount)
        assertTrue(preview.applyReady)
        assertEquals(stateBeforePreview, repository.snapshot())
        val conflicts = importStore.readConflictPage(database, started.importId, preview.previewToken, null, null)
        assertTrue(conflicts.done)
        assertTrue(conflicts.conflicts.isEmpty())

        val localVersion = repository.snapshot().version
        assertEquals(localVersion, preview.localStateVersion)
        val settings = repository.jobDetail(jobId).job.settings.copy(name = "Changed after preview")
        assertNull(repository.saveJobSettings("change-after-preview", localVersion, settings).conflict)
        assertFailure("PREVIEW_STALE") {
            importStore.readConflictPage(database, started.importId, preview.previewToken, null, null)
        }
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun aDuplicateRowRollsBackTheWholePageBeforeAValidRetry() {
        val source = exportStore.start(database)
        val sourcePage = exportStore.readPage(source.snapshotId, null, BackupImportStore.MAX_PAGE_BYTES)
        assertTrue(sourcePage.records.size >= 2)
        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "d".repeat(64),
            2,
            0,
            0,
        )
        val duplicatePage = listOf(sourcePage.records.first(), sourcePage.records.first())
        assertFailure("DUPLICATE_RECORD") {
            importStore.appendPage(
                started.importId,
                0,
                duplicatePage,
                encodedAppendRequest(started.importId, 0, duplicatePage),
            )
        }
        val validPage = sourcePage.records.take(2)
        assertEquals(
            2L,
            importStore.appendPage(
                started.importId,
                0,
                validPage,
                encodedAppendRequest(started.importId, 0, validPage),
            ),
        )
        val preview = importStore.finish(database, started.importId)
        assertEquals(2L, preview.recordCount)
        assertEquals(2L, preview.unchangedCount)
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
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
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "6".repeat(64),
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
        assertEquals(1L, preview.conflictCount)
        assertFalse(preview.applyReady)
        val conflict = requireNotNull(findConflict(started.importId, preview, "SESSION_OVERLAP"))
        assertEquals("LOCAL_OPEN:$localSessionId", conflict["localIdentifier"])
        assertEquals(false, conflict["resolved"])
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun openBackupSessionIsProvisionalAndRequiresAnExplicitPreviewChoice() {
        val version = repository.snapshot().version
        assertNull(repository.execute(TimerRepository.Command(
            "start-session-for-import-preview",
            version,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val runningBefore = requireNotNull(repository.snapshot().running)
        val source = exportStore.start(database)
        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "9".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.openSessionCount)
        assertFalse(preview.applyReady)
        val page = importStore.readConflictPage(database, started.importId, preview.previewToken, null, null)
        val openSession = page.conflicts.single { it["kind"] == "OPEN_SESSION" }
        assertEquals(false, openSession["resolved"])
        assertEquals("OMIT_OPEN_SESSION", openSession["defaultChoice"])
        assertEquals(listOf("OMIT_OPEN_SESSION"), openSession["allowedChoices"])
        assertEquals(runningBefore, repository.snapshot().running)
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun applyCanOmitAnOpenBackupSessionAndRestoreOtherRowsWithoutStartingATimer() {
        val importedCustomerId = "open-session-omitted-customer"
        val importedSessionId = "open-session-omitted-session"
        val records = listOf(
            stagedRow("customers", mapOf(
                "id" to importedCustomerId,
                "name" to "Restored beside omitted timer",
                "email" to "",
                "phone" to "",
                "address" to "",
                "notes" to "",
                "archived" to 0,
            )),
            stagedRow("sessions", mapOf(
                "id" to importedSessionId,
                "job_id" to jobId,
                "start_ms" to now,
                "end_ms" to null,
                "timezone" to "Etc/UTC",
                "offset_seconds" to 0,
                "origin" to "MANUAL",
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.openSessionCount)
        assertEquals(1L, preview.conflictCount)
        assertFalse(preview.applyReady)
        val conflict = requireNotNull(findConflict(started.importId, preview, "OPEN_SESSION"))
        assertEquals(listOf("OMIT_OPEN_SESSION"), conflict["allowedChoices"])

        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "OMIT_OPEN_SESSION",
        )
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )

        assertEquals(1L, applied.insertedCount)
        assertEquals(1L, applied.skippedCount)
        assertEquals("Restored beside omitted timer", customerName(importedCustomerId))
        assertEquals(0L, number("SELECT COUNT(*) FROM sessions WHERE id='$importedSessionId'"))
        assertNull(repository.snapshot().running)
    }

    @Test
    fun applyRestoresRowsAtomicallyAndKeepsImportedAutomationDisabled() {
        val before = repository.snapshot()
        val importedJobId = "backup-job"
        val importedCustomerId = "backup-customer"
        val revisionId = "backup-customer-revision"
        val records = listOf(
            headRecord("customer", importedCustomerId, revisionId),
            revisionRecord(revisionId, "customer", importedCustomerId, null),
            stagedRow("customers", mapOf(
                "id" to importedCustomerId,
                "name" to "Backup customer",
                "email" to "backup@example.invalid",
                "phone" to "",
                "address" to "",
                "notes" to "synthetic fixture",
                "archived" to 0,
            )),
            stagedRow("jobs", mapOf(
                "id" to importedJobId,
                "name" to "Backup job",
                "archived" to 0,
                "archived_ms" to null,
                "color_argb" to 4279724885L,
                "billing_mode" to "HOURLY",
                "currency" to "USD",
                "hourly_rate_minor" to null,
                "customer_id" to null,
                "customer_email_override" to null,
            )),
            stagedRow("automation_policies", mapOf(
                "job_id" to importedJobId,
                "enabled" to 1,
                "auto_start" to 1,
                "automatic_departure" to 1,
                "grace_minutes" to 30,
                "configured_ms" to now,
            )),
            stagedRow("work_sites", mapOf(
                "id" to "backup-disabled-site",
                "job_id" to importedJobId,
                "name" to "Imported site",
                "latitude" to 12.5,
                "longitude" to -34.5,
                "radius" to 100.0,
                "enabled" to 1,
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertTrue(preview.applyReady)

        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )

        assertEquals(records.size.toLong(), applied.insertedCount)
        assertEquals(before.version + 1L, applied.stateVersion)
        assertEquals(applied.stateVersion, repository.snapshot().version)
        assertNull(repository.snapshot().running)
        assertEquals("Backup customer", database.rawQuery(
            "SELECT name FROM customers WHERE id=?", arrayOf(importedCustomerId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(listOf(0, 0, 0), database.rawQuery(
            "SELECT enabled,auto_start,armed FROM automation_policies WHERE job_id=?",
            arrayOf(importedJobId),
        ).use { assertTrue(it.moveToFirst()); listOf(it.getInt(0), it.getInt(1), it.getInt(2)) })
        assertEquals(0L, database.rawQuery(
            "SELECT facts_since_ms FROM automation_policies WHERE job_id=?", arrayOf(importedJobId),
        ).use { assertTrue(it.moveToFirst()); it.getLong(0) })
        val siteRuntime = database.rawQuery(
            "SELECT enabled,registration_key,presence,observed_ms FROM work_sites WHERE id=?",
            arrayOf("backup-disabled-site"),
        ).use {
            assertTrue(it.moveToFirst())
            listOf(it.getInt(0), it.getString(1), it.getString(2), if (it.isNull(3)) null else it.getLong(3))
        }
        assertEquals(0, siteRuntime[0])
        assertTrue((siteRuntime[1] as String).matches(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        ))
        assertEquals("UNKNOWN", siteRuntime[2])
        assertNull(siteRuntime[3])
        assertTrue(database.rawQuery(
            "SELECT 1 FROM entity_heads WHERE kind='customer' AND entity_id=? AND revision_id=?",
            arrayOf(importedCustomerId, revisionId),
        ).use { it.moveToFirst() })
        assertFailure("IMPORT_UNAVAILABLE") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
    }

    @Test
    fun repositoryPublishesTheCommittedImportVersion() {
        val record = stagedRow("customers", mapOf(
            "id" to "published-customer",
            "name" to "Published customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))
        val records = listOf(record)
        val started = repository.beginBackupImport(
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
            1,
            0,
            0,
        )
        repository.appendBackupImportPage(
            started.importId, 0, records, encodedAppendRequest(started.importId, 0, records),
        )
        val preview = repository.finishBackupImport(started.importId)
        val published = mutableListOf<Long>()
        val subscription = repository.subscribe(published::add)
        try {
            val applied = repository.applyBackupImport(
                started.importId, preview.previewToken, preview.localStateVersion,
            )
            assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
            assertEquals(applied.stateVersion, published.last())
            assertEquals("Published customer", customerName("published-customer"))
        } finally {
            subscription.close()
        }
    }

    @Test
    fun applyRestoresTaskCommentMediaAndRollsBackIfALaterAttachmentFails() {
        val firstBytes = byteArrayOf(10, 20, 30)
        val secondBytes = byteArrayOf(40, 50, 60, 70)
        val firstBlobId = "a".repeat(43)
        val secondBlobId = "b".repeat(43)
        val columnId = "backup-media-column"
        val cardId = "backup-media-card"
        val commentId = "backup-media-comment"
        val firstAttachmentId = "backup-media-first-attachment"
        val secondAttachmentId = "backup-media-second-attachment"
        fun attachmentRecord(id: String, blobId: String, bytes: ByteArray) = mapOf(
            "type" to "row",
            "table" to "task_attachments",
            "values" to mapOf(
                "id" to id,
                "task_id" to cardId,
                "comment_id" to commentId,
                "created_ms" to now,
                "mime" to "image/png",
            ),
            "blobs" to listOf(mapOf(
                "id" to blobId,
                "column" to "data",
                "sizeBytes" to bytes.size,
            )),
        )
        val records = listOf(
            stagedRow("kanban_columns", mapOf(
                "id" to columnId,
                "job_id" to jobId,
                "name" to "To do",
                "position" to 0,
                "completed" to 0,
            )),
            stagedRow("kanban_cards", mapOf(
                "id" to cardId,
                "job_id" to jobId,
                "column_id" to columnId,
                "title" to "Backup photo task",
                "description" to "",
                "due_date" to null,
                "position" to 0,
                "completed_ms" to null,
                "billable" to 1,
                "price_minor" to null,
                "currency" to "USD",
                "template_id" to null,
                "charge_type" to "UNCLASSIFIED",
            )),
            stagedRow("task_comments", mapOf(
                "id" to commentId,
                "task_id" to cardId,
                "body" to "Photo attached to this comment",
                "created_ms" to now,
                "updated_ms" to now,
            )),
            attachmentRecord(firstAttachmentId, firstBlobId, firstBytes),
            attachmentRecord(secondAttachmentId, secondBlobId, secondBytes),
        )
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "f".repeat(64),
            records.size.toLong(),
            2,
            (firstBytes.size + secondBytes.size).toLong(),
        )
        importStore.appendPage(
            started.importId,
            0,
            records,
            encodedAppendRequest(started.importId, 0, records),
        )
        listOf(firstBlobId to firstBytes, secondBlobId to secondBytes).forEach { (blobId, bytes) ->
            val writeCall = MethodCall("writeBackupImportBlob", mapOf(
                "importId" to started.importId,
                "blobId" to blobId,
                "offset" to 0,
                "bytes" to bytes,
            ))
            assertEquals(
                bytes.size.toLong(),
                importStore.writeBlob(
                    started.importId,
                    blobId,
                    0,
                    bytes,
                    StandardMethodCodec.INSTANCE.encodeMethodCall(writeCall).position(),
                ),
            )
        }
        val preview = importStore.finish(database, started.importId)
        assertTrue(preview.applyReady)
        val versionBefore = repository.snapshot().version
        database.execSQL(
            "CREATE TRIGGER reject_second_imported_attachment BEFORE INSERT ON task_attachments " +
                "WHEN NEW.id='$secondAttachmentId' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END",
        )

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(versionBefore, repository.snapshot().version)
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_columns WHERE id='$columnId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_cards WHERE id='$cardId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM task_attachments WHERE id IN ('$firstAttachmentId','$secondAttachmentId')",
        ))

        database.execSQL("DROP TRIGGER reject_second_imported_attachment")
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(records.size.toLong(), applied.insertedCount)
        assertEquals(2L, number(
            "SELECT COUNT(*) FROM task_attachments WHERE id IN ('$firstAttachmentId','$secondAttachmentId')",
        ))
        mapOf(firstAttachmentId to firstBytes, secondAttachmentId to secondBytes).forEach { (id, bytes) ->
            val restored = database.rawQuery(
                "SELECT task_id,comment_id,data FROM task_attachments WHERE id=?", arrayOf(id),
            ).use {
                assertTrue(it.moveToFirst())
                Triple(it.getString(0), it.getString(1), it.getBlob(2))
            }
            assertEquals(cardId, restored.first)
            assertEquals(commentId, restored.second)
            assertArrayEquals(bytes, restored.third)
        }
    }

    @Test
    fun applyRequiresFreshTokenVersionAndNoLocalOpenTimer() {
        val records = listOf(stagedRow("customers", mapOf(
            "id" to "freshness-customer",
            "name" to "Freshness customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        )))
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertFailure("INVALID_PREVIEW_TOKEN") {
            importStore.apply(database, started.importId, "bad-token", preview.localStateVersion)
        }
        assertFailure("PREVIEW_STALE") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion + 1L)
        }

        // Simulate a writer that failed to advance the state counter. The dedicated live-timer
        // guard must still prevent restore from racing or replacing the open session.
        database.execSQL(
            "INSERT INTO sessions(id,job_id,start_ms,end_ms,timezone,offset_seconds,origin) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("local-open-for-apply", jobId, now, null, "Etc/UTC", 0, "MANUAL"),
        )
        val versionBefore = repository.snapshot().version
        assertEquals(preview.localStateVersion, versionBefore)
        assertFailure("LOCAL_TIMER_RUNNING") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        assertEquals(0L, number("SELECT COUNT(*) FROM customers WHERE id='freshness-customer'"))
        assertEquals("local-open-for-apply", repository.snapshot().running?.id)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun applyRejectsUnresolvedOrUnsupportedConflictChoices() {
        database.execSQL(
            "INSERT INTO customers(id,name,email,phone,address,notes,archived) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("conflicted-customer", "Local value", "", "", "", "", 0),
        )
        val started = stageImport(listOf(stagedRow("customers", mapOf(
            "id" to "conflicted-customer",
            "name" to "Backup value",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        val conflictId = conflict["conflictId"] as String
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                conflictId, "TAKE_BACKUP",
            )
        }
        assertEquals("Local value", customerName("conflicted-customer"))
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflictId, "KEEP_LOCAL",
        )
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(0L, applied.insertedCount)
        assertEquals(1L, applied.skippedCount)
        assertEquals(preview.localStateVersion, applied.stateVersion)
        assertEquals("Local value", customerName("conflicted-customer"))
    }

    @Test
    fun applyFailureRollsBackEarlierRowsAndLeavesPreviewRetryable() {
        val records = listOf(
            stagedRow("customers", mapOf(
                "id" to "rollback-first", "name" to "First", "email" to "", "phone" to "",
                "address" to "", "notes" to "", "archived" to 0,
            )),
            stagedRow("customers", mapOf(
                "id" to "rollback-second", "name" to "Second", "email" to "", "phone" to "",
                "address" to "", "notes" to "", "archived" to 0,
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val versionBefore = repository.snapshot().version
        database.execSQL(
            "CREATE TRIGGER reject_second_imported_customer BEFORE INSERT ON customers " +
                "WHEN NEW.id='rollback-second' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END",
        )

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(0L, number("SELECT COUNT(*) FROM customers WHERE id IN ('rollback-first','rollback-second')"))
        assertEquals(versionBefore, repository.snapshot().version)
        database.execSQL("DROP TRIGGER reject_second_imported_customer")
        val retried = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(2L, retried.insertedCount)
        assertEquals(2L, number("SELECT COUNT(*) FROM customers WHERE id IN ('rollback-first','rollback-second')"))
    }

    @Test
    fun scheduledExpiryClosesAndDeletesPrivateImportFiles() {
        var elapsed = 100L
        val scheduler = ManualScheduler { elapsed }
        val expiring = BackupImportStore(
            app,
            catalogProvider = exportStore::importCatalog,
            elapsedClock = { elapsed },
            idleTtlMillis = 50L,
            scheduler = scheduler,
        )
        val started = expiring.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
            0,
            0,
            0,
        )
        val directory = File(app.noBackupFilesDir, "clockrove-backup-imports")
        val stageDb = File(directory, "clockrove-import-${started.importId}.db")
        val media = File(directory, "clockrove-import-${started.importId}-media.bin")
        assertTrue(stageDb.exists())
        assertTrue(media.exists())
        elapsed += 51L
        scheduler.runDueTasks()
        assertFalse(stageDb.exists())
        assertFalse(media.exists())
        assertFailure("IMPORT_UNAVAILABLE") { expiring.finish(database, started.importId) }
    }

    @Test
    fun failedImportCleanupRetriesDatabaseSidecarsAndMedia() {
        var elapsed = 2_000L
        var failingPath: String? = null
        var failuresRemaining = 1
        val scheduler = ManualScheduler { elapsed }
        val cleanupStore = BackupImportStore(
            app,
            catalogProvider = exportStore::importCatalog,
            elapsedClock = { elapsed },
            scheduler = scheduler,
            deleteFile = { file ->
                if (file.absolutePath == failingPath && failuresRemaining > 0) {
                    failuresRemaining--
                    false
                } else {
                    file.delete()
                }
            },
        )
        val started = cleanupStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "f".repeat(64),
            0,
            0,
            0,
        )
        val directory = File(app.noBackupFilesDir, "clockrove-backup-imports")
        val stageDb = File(directory, "clockrove-import-${started.importId}.db")
        val sidecar = File(stageDb.path + "-wal")
        val media = File(directory, "clockrove-import-${started.importId}-media.bin")
        assertTrue(sidecar.createNewFile())
        failingPath = media.absolutePath

        assertFailure("CLEANUP_FAILED") { cleanupStore.close(started.importId) }
        assertFalse(stageDb.exists())
        assertFalse(sidecar.exists())
        assertTrue(media.exists())
        assertFailure("IMPORT_UNAVAILABLE") { cleanupStore.finish(database, started.importId) }
        assertTrue(cleanupStore.close(started.importId))
        assertFalse(media.exists())
    }

    private fun transferRows(snapshotId: String, importId: String) {
        var ordinal = 0L
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(snapshotId, cursor, BackupImportStore.MAX_PAGE_BYTES)
            ordinal = importStore.appendPage(importId, ordinal, page.records,
                encodedAppendRequest(importId, ordinal, page.records))
            cursor = page.nextCursor
            done = page.done
        }
    }

    private fun stageImport(records: List<Map<String, Any?>>): BackupImportStore.Started {
        require(records.isNotEmpty())
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "d".repeat(64),
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
        return started
    }

    private fun customerName(id: String): String = database.rawQuery(
        "SELECT name FROM customers WHERE id=?", arrayOf(id),
    ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }

    private fun values(record: Map<String, Any?>): Map<*, *> = record["values"] as Map<*, *>

    private fun readAllRows(snapshotId: String): List<Map<String, Any?>> {
        val records = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(snapshotId, cursor, BackupImportStore.MAX_PAGE_BYTES)
            records.addAll(page.records)
            cursor = page.nextCursor
            done = page.done
        }
        return records
    }

    private fun revisionRecord(
        id: String,
        kind: String,
        entityId: String,
        jobId: String?,
        deleted: Long = 0L,
        origin: String = "MUTATION",
        payload: String? = if (kind == "location") {
            JSONObject().put("id", entityId).put("session_id", "synthetic-session").toString()
        } else "{}",
    ): Map<String, Any?> = stagedRow("entity_revisions", mapOf(
        "id" to id,
        "kind" to kind,
        "entity_id" to entityId,
        "job_id" to jobId,
        "payload" to payload,
        "deleted" to deleted,
        "changed_ms" to now,
        "origin" to origin,
    ))

    private fun parentRecord(revisionId: String, parentId: String): Map<String, Any?> =
        stagedRow("revision_parents", mapOf("revision_id" to revisionId, "parent_id" to parentId))

    private fun headRecord(kind: String, entityId: String, revisionId: String): Map<String, Any?> =
        stagedRow("entity_heads", mapOf("kind" to kind, "entity_id" to entityId, "revision_id" to revisionId))

    private fun stagedRow(table: String, values: Map<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "row",
        "table" to table,
        "values" to values,
        "blobs" to emptyList<Any>(),
    )

    private fun invoiceRecord(id: String, invoiceJobId: String, number: String): Map<String, Any?> =
        stagedRow("invoices", mapOf(
            "id" to id,
            "job_id" to invoiceJobId,
            "customer_id" to null,
            "number" to number,
            "created_ms" to now,
            "payload" to "{}",
            "pdf" to null,
            "logo_id" to null,
        ))

    private fun invoiceTimeClaimRecord(
        id: String,
        invoiceId: String,
        claimJobId: String,
        start: Long,
        end: Long,
        releasedMs: Long? = null,
    ): Map<String, Any?> = stagedRow("invoice_time_claims", mapOf(
        "id" to id,
        "invoice_id" to invoiceId,
        "job_id" to claimJobId,
        "session_id" to "synthetic-session-$id",
        "start_ms" to start,
        "end_ms" to end,
        "timezone" to "Etc/UTC",
        "released_ms" to releasedMs,
    ))

    private fun startAndStopSessionWithPoint(pointId: String): Pair<String, Long> {
        assertNull(repository.execute(TimerRepository.Command(
            "start-$pointId",
            repository.snapshot().version,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val sessionId = requireNotNull(repository.snapshot().running).id
        val measuredAt = now + 1_000L
        assertEquals(
            TimerRepository.SampleResult.RECORDED,
            repository.recordLocation(TimerRepository.LocationPoint(
                pointId, sessionId, measuredAt, measuredAt, 12.0, -34.0, 5.0,
            )),
        )
        assertNull(repository.execute(TimerRepository.Command(
            "stop-$pointId",
            repository.snapshot().version,
            TimerRepository.Action.STOP,
            null,
            measuredAt + 1_000L,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        return sessionId to measuredAt
    }

    private fun findConflict(importId: String, preview: BackupImportStore.Preview, kind: String): Map<String, Any?>? {
        var cursor: String? = null
        while (true) {
            val page = importStore.readConflictPage(database, importId, preview.previewToken, cursor, null)
            page.conflicts.firstOrNull { it["kind"] == kind }?.let { return it }
            if (page.done) return null
            cursor = page.nextCursor
        }
    }

    private fun number(sql: String): Long = database.rawQuery(sql, null).use { rows ->
        assertTrue(rows.moveToFirst())
        rows.getLong(0)
    }

    private fun importRelevantCounts(): Map<String, Long> = listOf(
        "sessions", "location_points", "entity_revisions", "invoices",
        "invoice_task_claims", "invoice_time_claims",
    ).associateWith { table -> number("SELECT COUNT(*) FROM $table") }

    private fun validationTableCount(): Long {
        val activeField = BackupImportStore::class.java.getDeclaredField("active").apply { isAccessible = true }
        val handle = requireNotNull(activeField.get(importStore))
        val databaseField = handle.javaClass.getDeclaredField("database").apply { isAccessible = true }
        val stagedDatabase = databaseField.get(handle) as SQLiteDatabase
        return stagedDatabase.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND " +
                "(name LIKE 'validation_%' OR name='preview_unique_keys')",
            null,
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            rows.getLong(0)
        }
    }

    private fun assertNoCoordinates(conflict: Map<String, Any?>) {
        listOf("localSummary", "backupSummary").forEach { key ->
            val summary = conflict[key] as? Map<*, *> ?: return@forEach
            assertFalse(summary.containsKey("latitude"))
            assertFalse(summary.containsKey("longitude"))
        }
    }

    private fun assertInvalidRevisionGraph(
        records: List<Map<String, Any?>>,
        forbiddenDiagnostic: String? = null,
    ) {
        val before = repository.snapshot()
        val countsBefore = importRelevantCounts()
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "c".repeat(64),
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
        val failure = try {
            importStore.finish(database, started.importId)
            fail("Expected import failure INVALID_REVISION_GRAPH")
            null
        } catch (failure: BackupImportStore.Failure) {
            failure
        }
        assertEquals("INVALID_REVISION_GRAPH", failure?.code)
        if (forbiddenDiagnostic != null) {
            assertFalse(failure?.message.orEmpty().contains(forbiddenDiagnostic))
        }
        assertEquals(before, repository.snapshot())
        assertEquals(countsBefore, importRelevantCounts())
        assertTrue(importStore.close(started.importId))
    }

    private fun assertInvalidReference(records: List<Map<String, Any?>>) {
        val before = repository.snapshot()
        val countsBefore = importRelevantCounts()
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "f".repeat(64),
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
        assertFailure("INVALID_REFERENCE") { importStore.finish(database, started.importId) }
        assertEquals(before, repository.snapshot())
        assertEquals(countsBefore, importRelevantCounts())
        assertTrue(importStore.close(started.importId))
    }

    private fun assertInvalidArchive(records: List<Map<String, Any?>>) {
        val before = repository.snapshot()
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "a".repeat(64),
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
        assertFailure("INVALID_ARCHIVE") { importStore.finish(database, started.importId) }
        assertEquals(before, repository.snapshot())
        assertTrue(importStore.close(started.importId))
    }

    private fun encodedAppendRequest(importId: String, ordinal: Long, rows: List<*>): Int =
        StandardMethodCodec.INSTANCE.encodeMethodCall(MethodCall(
            "appendBackupImportPage",
            mapOf("importId" to importId, "firstOrdinal" to ordinal, "records" to rows),
        )).position()

    private fun assertFailure(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected import failure " + code)
        } catch (failure: BackupImportStore.Failure) {
            assertEquals(code, failure.code)
        }
    }
}
