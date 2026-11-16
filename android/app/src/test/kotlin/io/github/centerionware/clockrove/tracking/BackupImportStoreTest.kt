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
    fun mediaSizesAtConsumerLimitsStageAndTheNextByteIsRejected() {
        data class BlobCase(
            val table: String,
            val values: Map<String, Any?>,
            val column: String,
            val maximumBytes: Int,
        )

        val cases = listOf(
            BlobCase("logo_assets", mapOf(
                "id" to "bounded-logo",
                "mime" to "image/png",
                "width" to 1,
                "height" to 1,
                "content_hash" to "synthetic-hash",
            ), "bytes", ImageLimits.NORMALIZED_BYTES),
            BlobCase("task_attachments", mapOf(
                "id" to "bounded-task-image",
                "task_id" to "synthetic-task",
                "comment_id" to null,
                "created_ms" to now,
                "mime" to "image/png",
            ), "data", ImageLimits.NORMALIZED_BYTES),
            BlobCase("invoices", mapOf(
                "id" to "bounded-invoice-pdf",
                "job_id" to jobId,
                "customer_id" to null,
                "number" to "INV-BOUNDED",
                "created_ms" to now,
                "payload" to "{}",
                "logo_id" to null,
            ), "pdf", InvoiceBlobs.MAX_DOCUMENT_BYTES),
            BlobCase("invoice_copies", mapOf(
                "id" to "bounded-invoice-copy",
                "invoice_id" to "synthetic-invoice",
                "kind" to "BALANCE",
                "created_ms" to now,
                "command_version" to 1,
                "metadata" to "{}",
            ), "pdf", InvoiceBlobs.MAX_DOCUMENT_BYTES),
            BlobCase("quote_pdfs", mapOf(
                "id" to "bounded-quote-pdf",
                "quote_id" to "synthetic-quote",
                "revision" to 1,
            ), "bytes", InvoiceBlobs.MAX_DOCUMENT_BYTES),
        )

        cases.forEach { case ->
            val atLimit = blobRecord(case.table, case.values, case.column, case.maximumBytes)
            val accepted = importStore.begin(
                database,
                BackupExportStore.ARCHIVE_FORMAT_VERSION,
                BackupExportStore.CURRENT_SCHEMA_VERSION,
                "c".repeat(64),
                1,
                1,
                case.maximumBytes.toLong(),
            )
            assertEquals(1L, importStore.appendPage(
                accepted.importId,
                0,
                listOf(atLimit),
                encodedAppendRequest(accepted.importId, 0, listOf(atLimit)),
            ))
            assertTrue(importStore.close(accepted.importId))

            val overLimitBytes = case.maximumBytes + 1
            val overLimit = blobRecord(case.table, case.values, case.column, overLimitBytes)
            val rejected = importStore.begin(
                database,
                BackupExportStore.ARCHIVE_FORMAT_VERSION,
                BackupExportStore.CURRENT_SCHEMA_VERSION,
                "d".repeat(64),
                1,
                1,
                overLimitBytes.toLong(),
            )
            assertFailure("INVALID_ARCHIVE") {
                importStore.appendPage(
                    rejected.importId,
                    0,
                    listOf(overLimit),
                    encodedAppendRequest(rejected.importId, 0, listOf(overLimit)),
                )
            }
            assertTrue(importStore.close(rejected.importId))
        }
    }

    @Test
    fun schema17PreviewAcceptsGpsDeletionRangeAndCausalRevisionRecords() {
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
        assertEquals(17, source.schemaVersion)
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
        assertEquals(17, started.schemaVersion)
        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)

        assertEquals(17, preview.schemaVersion)
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
    fun omittingConflictedInvoiceRequiresExplicitOmissionOfDependentClaim() {
        database.execSQL(
            "INSERT INTO invoices(id,job_id,number,created_ms,payload) VALUES(?,?,?,?,?)",
            arrayOf<Any?>("local-number-owner", jobId, "INV-DEPENDENCY-OWNER", now, "{}"),
        )
        val backupInvoiceId = "backup-number-collision"
        val started = stageImport(listOf(
            invoiceRecord(backupInvoiceId, jobId, "INV-DEPENDENCY-OWNER"),
            invoiceTimeClaimRecord(
                "backup-claim-referencing-omitted-invoice", backupInvoiceId, jobId, 100, 200,
            ),
        ))
        val preview = importStore.finish(database, started.importId)
        assertEquals(2L, preview.conflictCount)
        val invoiceConflict = requireNotNull(findConflict(started.importId, preview, "UNIQUE_VALUE_IN_USE"))
        val dependentConflict = requireNotNull(findConflict(started.importId, preview, "DEPENDENT_ON_CONFLICT"))
        assertEquals(listOf("OMIT_BACKUP_RECORD"), dependentConflict["allowedChoices"])
        assertNull(dependentConflict["defaultChoice"])
        assertFalse(dependentConflict["resolved"] as Boolean)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            invoiceConflict["conflictId"] as String, "OMIT_BACKUP_RECORD",
        )
        val resolvedDependent = requireNotNull(findConflict(
            started.importId, preview, "DEPENDENT_ON_CONFLICT",
        ))
        assertEquals(dependentConflict["conflictId"], resolvedDependent["conflictId"])
        assertTrue(resolvedDependent["resolved"] as Boolean)
        assertEquals("OMIT_BACKUP_RECORD", resolvedDependent["selectedChoice"])
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(0L, number("SELECT COUNT(*) FROM invoices WHERE id='backup-number-collision'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM invoice_time_claims WHERE id='backup-claim-referencing-omitted-invoice'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM invoices WHERE id='local-number-owner'"))
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
    fun rejectsUnknownRevisionKindsAndMalformedObjectPayloadsBeforePreview() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("unknown-kind-revision", "future_kind", "future-entity", null),
            headRecord("future_kind", "future-entity", "unknown-kind-revision"),
        ))

        listOf("not-json", "[]").forEachIndexed { index, payload ->
            val revisionId = "malformed-object-payload-$index"
            assertInvalidRevisionGraph(listOf(
                revisionRecord(
                    revisionId, "quote_decision", "malformed-quote:$index", null, payload = payload,
                ),
                headRecord("quote_decision", "malformed-quote:$index", revisionId),
            ))
        }
    }

    @Test
    fun conflictingHeadsClassifyAncestorDescendantAndDivergentHistoryExplicitly() {
        data class Node(val id: String, val parent: String? = null)
        data class Case(
            val entityId: String,
            val localNodes: List<Node>,
            val localHead: String,
            val backupNodes: List<Node>,
            val backupHead: String,
            val relationship: String,
        )
        val kind = "quote_decision"
        val cases = listOf(
            Case(
                "backup-descendant-case",
                listOf(Node("descendant-base")),
                "descendant-base",
                listOf(Node("descendant-base"), Node("descendant-backup", "descendant-base")),
                "descendant-backup",
                "BACKUP_DESCENDANT",
            ),
            Case(
                "backup-ancestor-case",
                listOf(Node("ancestor-base"), Node("ancestor-local", "ancestor-base")),
                "ancestor-local",
                listOf(Node("ancestor-base")),
                "ancestor-base",
                "BACKUP_ANCESTOR",
            ),
            Case(
                "divergent-history-case",
                listOf(Node("divergent-base"), Node("divergent-local", "divergent-base")),
                "divergent-local",
                listOf(Node("divergent-base"), Node("divergent-backup", "divergent-base")),
                "divergent-backup",
                "DIVERGENT",
            ),
        )

        cases.forEach { case ->
            case.localNodes.forEach { node ->
                insertSyntheticRevision(node.id, kind, case.entityId, node.parent)
            }
            database.execSQL(
                "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES(?,?,?)",
                arrayOf(kind, case.entityId, case.localHead),
            )
            val backupRecords = case.backupNodes.flatMap { node ->
                buildList {
                    add(revisionRecord(node.id, kind, case.entityId, null))
                    node.parent?.let { add(parentRecord(node.id, it)) }
                }
            } + headRecord(kind, case.entityId, case.backupHead)
            val started = stageImport(backupRecords)
            val preview = importStore.finish(database, started.importId)
            val conflict = requireNotNull(
                findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"),
            )
            assertEquals(case.relationship, conflict["revisionRelationship"])
            assertFalse(conflict["resolved"] as Boolean)
            if (case.relationship == "DIVERGENT") {
                assertEquals(listOf("KEEP_LOCAL"), conflict["allowedChoices"])
            }
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                conflict["conflictId"] as String, "KEEP_LOCAL",
            )
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
            val committedHead = database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
                arrayOf(kind, case.entityId),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
            assertEquals(case.localHead, committedHead)
            assertTrue(importStore.close(started.importId))
        }
    }

    @Test
    fun rejectsARevisionIdReusedForDifferentImmutableContent() {
        val kind = "quote_decision"
        val entityId = "revision-id-collision-entity"
        insertSyntheticRevision("shared-revision-id", kind, entityId, null, payload = "{}")
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES(?,?,?)",
            arrayOf(kind, entityId, "shared-revision-id"),
        )
        val started = stageImport(listOf(
            revisionRecord(
                "shared-revision-id", kind, entityId, null,
                payload = "{\"different\":true}",
            ),
            headRecord(kind, entityId, "shared-revision-id"),
        ))

        assertFailure("INVALID_REVISION_GRAPH") {
            importStore.finish(database, started.importId)
        }
        assertTrue(importStore.close(started.importId))
        assertEquals("shared-revision-id", database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
    }

    @Test
    fun postChoiceRevisionCycleFailsAtomicallyWithoutChangingTheDatabase() {
        val kind = "quote_decision"
        val entityId = "post-choice-cycle-entity"
        val parentFirstRevision = "post-choice-cycle-a"
        val parentSecondRevision = "post-choice-cycle-b"
        insertSyntheticRevision(parentSecondRevision, kind, entityId, null)
        insertSyntheticRevision(parentFirstRevision, kind, entityId, parentSecondRevision)
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES(?,?,?)",
            arrayOf(kind, entityId, parentFirstRevision),
        )
        val started = stageImport(listOf(
            revisionRecord(parentFirstRevision, kind, entityId, null),
            revisionRecord(parentSecondRevision, kind, entityId, null),
            parentRecord(parentSecondRevision, parentFirstRevision),
            headRecord(kind, entityId, parentSecondRevision),
        ))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        assertEquals("BACKUP_DESCENDANT", conflict["revisionRelationship"])
        assertEquals(listOf("KEEP_LOCAL"), conflict["allowedChoices"])
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "KEEP_LOCAL",
        )

        val beforeApply = repository.snapshot()
        val parentCountBeforeApply = number("SELECT COUNT(*) FROM revision_parents")
        assertTrue(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(parentFirstRevision, parentSecondRevision),
        ).use { it.moveToFirst() })
        assertFalse(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(parentSecondRevision, parentFirstRevision),
        ).use { it.moveToFirst() })

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(beforeApply, repository.snapshot())
        assertEquals(parentCountBeforeApply, number("SELECT COUNT(*) FROM revision_parents"))
        assertFalse(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(parentSecondRevision, parentFirstRevision),
        ).use { it.moveToFirst() })
        assertEquals(parentFirstRevision, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun unchangedBackupDescendantFastForwardsItsHeadWithoutAConflict() {
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()
        val localHead = database.rawQuery(
            "SELECT h.revision_id,r.payload FROM entity_heads h " +
                "JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='job' AND h.entity_id=?",
            arrayOf(jobId),
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            rows.getString(0) to if (rows.isNull(1)) null else rows.getString(1)
        }
        val backupHead = UUID.fromString("d0000000-0000-4000-8000-000000000001").toString()
        records.removeAll { record ->
            record["table"] == "entity_heads" &&
                values(record)["kind"] == "job" && values(record)["entity_id"] == jobId
        }
        records.add(revisionRecord(
            backupHead,
            "job",
            jobId,
            jobId,
            payload = localHead.second,
        ))
        records.add(parentRecord(backupHead, localHead.first))
        records.add(headRecord("job", jobId, backupHead))

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        val versionBeforeApply = repository.snapshot().version

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        val committedHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='job' AND entity_id=?",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        assertEquals(backupHead, committedHead)
        assertEquals(versionBeforeApply + 1L, repository.snapshot().version)
        assertTrue(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(backupHead, localHead.first),
        ).use { it.moveToFirst() })
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun divergentIdenticalHeadsRequireAndCommitAMergeWithBothParents() {
        val base = database.rawQuery(
            "SELECT h.revision_id,r.payload FROM entity_heads h " +
                "JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='job' AND h.entity_id=?",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) to rows.getString(1) }
        val localHead = UUID.fromString("d0000000-0000-4000-8000-000000000002").toString()
        val backupHead = UUID.fromString("d0000000-0000-4000-8000-000000000003").toString()
        insertSyntheticRevision(localHead, "job", jobId, base.first, base.second)
        database.execSQL(
            "UPDATE entity_heads SET revision_id=? WHERE kind='job' AND entity_id=?",
            arrayOf(localHead, jobId),
        )
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()
        records.removeAll { record ->
            record["table"] == "entity_heads" &&
                values(record)["kind"] == "job" && values(record)["entity_id"] == jobId
        }
        records.add(revisionRecord(backupHead, "job", jobId, jobId, payload = base.second))
        records.add(parentRecord(backupHead, base.first))
        records.add(headRecord("job", jobId, backupHead))

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        assertEquals("DIVERGENT", conflict["revisionRelationship"])
        assertEquals(listOf("KEEP_LOCAL", "MERGE_IDENTICAL_CONTENT"), conflict["allowedChoices"])
        assertFalse(conflict["resolved"] as Boolean)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "MERGE_IDENTICAL_CONTENT",
        )

        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        val mergedHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='job' AND entity_id=?",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val parents = database.rawQuery(
            "SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id",
            arrayOf(mergedHead),
        ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        assertNotEquals(localHead, mergedHead)
        assertNotEquals(backupHead, mergedHead)
        assertEquals(setOf(localHead, backupHead), parents.toSet())
        assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
        assertEquals(applied.stateVersion, repository.snapshot().version)
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun divergentCustomerHeadsCanSelectEitherSideAndReimportWithoutAnotherMerge() {
        data class Case(
            val id: String,
            val baseRevision: String,
            val localRevision: String,
            val backupRevision: String,
            val localName: String,
            val backupName: String,
            val selectedChoice: String,
        )
        val cases = listOf(
            Case(
                "divergent-customer-local", "divergent-customer-local-base",
                "divergent-customer-local-head", "divergent-customer-local-backup",
                "Local customer", "Backup customer", "MERGE_KEEP_LOCAL_CONTENT",
            ),
            Case(
                "divergent-customer-backup", "divergent-customer-backup-base",
                "divergent-customer-backup-head", "divergent-customer-backup-branch",
                "Another local customer", "Selected backup customer", "MERGE_USE_BACKUP_CONTENT",
            ),
        )
        fun customerValues(id: String, name: String) = mapOf<String, Any?>(
            "id" to id,
            "name" to name,
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        )
        fun payload(values: Map<String, Any?>) = JSONObject(values.toSortedMap()).toString()

        val records = mutableListOf<Map<String, Any?>>()
        cases.forEach { case ->
            val baseValues = customerValues(case.id, "Common customer")
            val localValues = customerValues(case.id, case.localName)
            val backupValues = customerValues(case.id, case.backupName)
            database.execSQL(
                "INSERT INTO customers(id,name,email,phone,address,notes,archived) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(case.id, case.localName, "", "", "", "", 0),
            )
            insertSyntheticRevision(
                case.baseRevision, "customer", case.id, null, payload(baseValues),
            )
            insertSyntheticRevision(
                case.localRevision, "customer", case.id, case.baseRevision, payload(localValues),
            )
            database.execSQL(
                "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES('customer',?,?)",
                arrayOf(case.id, case.localRevision),
            )
            records.add(revisionRecord(
                case.baseRevision, "customer", case.id, null, payload = payload(baseValues),
            ))
            records.add(revisionRecord(
                case.backupRevision, "customer", case.id, null, payload = payload(backupValues),
            ))
            records.add(parentRecord(case.backupRevision, case.baseRevision))
            records.add(stagedRow("customers", backupValues))
            records.add(headRecord("customer", case.id, case.backupRevision))
        }

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        var cursor: String? = null
        val conflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val headConflicts = conflicts.filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[1].toString() }
        assertEquals(cases.size, headConflicts.size)
        cases.forEach { case ->
            val conflict = requireNotNull(headConflicts[case.id])
            assertEquals("DIVERGENT", conflict["revisionRelationship"])
            assertEquals(
                listOf("MERGE_KEEP_LOCAL_CONTENT", "MERGE_USE_BACKUP_CONTENT"),
                conflict["allowedChoices"],
            )
            assertEquals("MERGE_KEEP_LOCAL_CONTENT", conflict["defaultChoice"])
        }

        val beforeInvalidChoice = repository.snapshot()
        val revisionCountBeforeInvalidChoice = number("SELECT COUNT(*) FROM entity_revisions")
        val invalidTarget = requireNotNull(headConflicts[cases.first().id])
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                invalidTarget["conflictId"] as String, "MERGE_TAKE_BACKUP",
            )
        }
        assertEquals(beforeInvalidChoice, repository.snapshot())
        assertEquals(revisionCountBeforeInvalidChoice, number("SELECT COUNT(*) FROM entity_revisions"))
        cases.forEach { case ->
            assertEquals(case.localName, customerName(case.id))
            assertEquals(case.localRevision, database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind='customer' AND entity_id=?",
                arrayOf(case.id),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        }

        cases.forEach { case ->
            val conflict = requireNotNull(headConflicts[case.id])
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                conflict["conflictId"] as String, case.selectedChoice,
            )
        }
        val beforeInjectedRollback = repository.snapshot()
        val revisionCountBeforeRollback = number("SELECT COUNT(*) FROM entity_revisions")
        val parentCountBeforeRollback = number("SELECT COUNT(*) FROM revision_parents")
        database.execSQL(
            "CREATE TRIGGER reject_backup_merge_parent BEFORE INSERT ON revision_parents " +
                "WHEN (SELECT origin FROM entity_revisions WHERE id=NEW.revision_id)='BACKUP_MERGE' " +
                "BEGIN SELECT RAISE(ABORT,'synthetic divergent merge failure'); END",
        )
        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        database.execSQL("DROP TRIGGER reject_backup_merge_parent")
        assertEquals(beforeInjectedRollback, repository.snapshot())
        assertEquals(revisionCountBeforeRollback, number("SELECT COUNT(*) FROM entity_revisions"))
        assertEquals(parentCountBeforeRollback, number("SELECT COUNT(*) FROM revision_parents"))
        cases.forEach { case ->
            assertEquals(case.localName, customerName(case.id))
            assertEquals(case.localRevision, database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind='customer' AND entity_id=?",
                arrayOf(case.id),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        }

        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
        val mergedHeads = mutableMapOf<String, String>()
        cases.forEach { case ->
            val expectedName = if (case.selectedChoice == "MERGE_KEEP_LOCAL_CONTENT") {
                case.localName
            } else {
                case.backupName
            }
            assertEquals(expectedName, customerName(case.id))
            val committed = database.rawQuery(
                "SELECT h.revision_id,r.payload,r.origin FROM entity_heads h " +
                    "JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='customer' AND h.entity_id=?",
                arrayOf(case.id),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                Triple(rows.getString(0), rows.getString(1), rows.getString(2))
            }
            assertEquals("BACKUP_MERGE", committed.third)
            assertEquals(expectedName, JSONObject(committed.second).getString("name"))
            assertNotEquals(case.localRevision, committed.first)
            assertNotEquals(case.backupRevision, committed.first)
            val parents = database.rawQuery(
                "SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id",
                arrayOf(committed.first),
            ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
            assertEquals(setOf(case.localRevision, case.backupRevision), parents)
            mergedHeads[case.id] = committed.first
        }

        val repeatStarted = stageImport(records)
        val repeatPreview = importStore.finish(database, repeatStarted.importId)
        var repeatCursor: String? = null
        val repeatConflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(
                database, repeatStarted.importId, repeatPreview.previewToken, repeatCursor, null,
            )
            repeatConflicts.addAll(page.conflicts)
            repeatCursor = page.nextCursor
            if (page.done) break
        } while (true)
        repeatConflicts.forEach { conflict ->
            val choice = conflict["defaultChoice"] as? String
                ?: throw AssertionError("A repeat-import conflict needs an explicit safe default")
            importStore.resolveConflict(
                database, repeatStarted.importId, repeatPreview.previewToken,
                repeatPreview.localStateVersion, conflict["conflictId"] as String, choice,
            )
        }
        val versionBeforeRepeat = repository.snapshot().version
        val revisionCountBeforeRepeat = number("SELECT COUNT(*) FROM entity_revisions")
        val repeated = importStore.apply(
            database, repeatStarted.importId, repeatPreview.previewToken, repeatPreview.localStateVersion,
        )
        assertEquals(versionBeforeRepeat, repeated.stateVersion)
        assertEquals(revisionCountBeforeRepeat, number("SELECT COUNT(*) FROM entity_revisions"))
        cases.forEach { case ->
            assertEquals(mergedHeads.getValue(case.id), database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind='customer' AND entity_id=?",
                arrayOf(case.id),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
            assertEquals(
                if (case.selectedChoice == "MERGE_KEEP_LOCAL_CONTENT") case.localName else case.backupName,
                customerName(case.id),
            )
        }
    }

    @Test
    fun simpleMutableRevisionKindsPreviewValidatedTwoSidedChoices() {
        data class Case(
            val kind: String,
            val table: String,
            val id: String,
            val jobId: String?,
            val columns: List<String>,
            val field: String,
            val localValue: String,
            val backupValue: String,
        )

        val boardColumnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val cardId = "merge-matrix-card"
        database.execSQL(
            "INSERT INTO customers(id,name,email,phone,address,notes,archived) VALUES(?,?,?,?,?,?,0)",
            arrayOf<Any?>("merge-matrix-customer", "Base customer", "", "", "", ""),
        )
        database.execSQL(
            "INSERT INTO work_sites(id,job_id,name,latitude,longitude,radius,enabled,registration_key) " +
                "VALUES(?,?,?,0,0,50,0,?)",
            arrayOf("merge-matrix-site", jobId, "Base site", UUID.randomUUID().toString()),
        )
        database.execSQL(
            "INSERT INTO kanban_columns(id,job_id,name,position,completed) VALUES(?,?,?,91,0)",
            arrayOf("merge-matrix-column", jobId, "Base column"),
        )
        database.execSQL(
            "INSERT INTO kanban_cards(id,job_id,column_id,title,description,due_date,position,completed_ms," +
                "billable,price_minor,currency,template_id,charge_type) VALUES(?,?,?,?,'',NULL,92,NULL,1,NULL,'USD',NULL,'UNCLASSIFIED')",
            arrayOf(cardId, jobId, boardColumnId, "Base card"),
        )
        database.execSQL(
            "INSERT INTO task_templates(id,title,description,price_minor,currency,source_job_id,source_card_id,charge_type) " +
                "VALUES(?,?, '',NULL,'USD',NULL,NULL,'UNCLASSIFIED')",
            arrayOf("merge-matrix-template", "Base template"),
        )
        database.execSQL(
            "INSERT INTO sessions(id,job_id,start_ms,end_ms,timezone,offset_seconds,origin) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("merge-matrix-session", jobId, now + 1_000_000L, now + 1_001_000L, "Etc/UTC", 0, "MANUAL"),
        )
        database.execSQL(
            "INSERT INTO task_comments(id,task_id,body,created_ms,updated_ms) VALUES(?,?,?,?,?)",
            arrayOf("merge-matrix-comment", cardId, "Base comment", now, now),
        )
        database.execSQL(
            "INSERT INTO actual_costs(id,job_id,job_name,customer_id,customer_name,task_id,task_name," +
                "amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note," +
                "created_ms,updated_ms,deleted_ms) VALUES(?,NULL,'',NULL,'',NULL,'','123','USD','MATERIALS',?," +
                "'Etc/UTC',0,'Base vendor','','',?,?,NULL)",
            arrayOf("merge-matrix-cost", now, now, now),
        )

        val cases = listOf(
            Case("customer", "customers", "merge-matrix-customer", null, BusinessStore.customerColumns,
                "name", "Local customer", "Backup customer"),
            Case("site", "work_sites", "merge-matrix-site", jobId, BusinessSchema.siteColumns,
                "name", "Local site", "Backup site"),
            Case("column", "kanban_columns", "merge-matrix-column", jobId, BusinessSchema.columnColumns,
                "name", "Local column", "Backup column"),
            Case("card", "kanban_cards", cardId, jobId, BoardStore.cardColumns,
                "title", "Local card", "Backup card"),
            Case("template", "task_templates", "merge-matrix-template", null, BoardStore.templateColumns,
                "title", "Local template", "Backup template"),
            Case("session", "sessions", "merge-matrix-session", jobId, BusinessSchema.sessionColumns,
                "origin", "REPAIR", "WIDGET"),
            Case("task_comment", "task_comments", "merge-matrix-comment", jobId,
                listOf("id", "task_id", "body", "created_ms", "updated_ms"),
                "body", "Local comment", "Backup comment"),
            Case("actual_cost", "actual_costs", "merge-matrix-cost", null, listOf(
                "id", "job_id", "job_name", "customer_id", "customer_name", "task_id", "task_name",
                "amount_minor", "currency", "category", "occurred_ms", "timezone", "offset_seconds",
                "vendor", "reference", "note", "created_ms", "updated_ms", "deleted_ms",
            ), "vendor", "Local vendor", "Backup vendor"),
        )

        val records = mutableListOf<Map<String, Any?>>()
        data class Expected(val case: Case, val localRevision: String, val backupRevision: String)
        val expected = cases.mapIndexed { index, case ->
            val baseRow = readRowValues(case.table, "id", case.id)
            val baseValues = baseRow.filterKeys(case.columns.toSet()::contains)
            val baseRevision = RevisionStore.record(database, case.kind, case.id, case.jobId, baseValues, now)
            database.execSQL(
                "UPDATE ${case.table} SET ${case.field}=? WHERE id=?",
                arrayOf(case.localValue, case.id),
            )
            val localValues = readRowValues(case.table, "id", case.id).filterKeys(case.columns.toSet()::contains)
            RevisionStore.record(database, case.kind, case.id, case.jobId, localValues, now + 1L)
                .also { assertNotEquals(baseRevision, it) }
            val localHead = database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
                arrayOf(case.kind, case.id),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }

            val backupRow = baseRow.toMutableMap().apply { put(case.field, case.backupValue) }
            val backupValues = backupRow.filterKeys(case.columns.toSet()::contains)
            val backupRevision = "merge-matrix-backup-$index"
            records.add(stagedRow("entity_revisions", readRowValues("entity_revisions", "id", baseRevision)))
            records.add(revisionRecord(
                backupRevision, case.kind, case.id, case.jobId,
                payload = JSONObject(backupValues.toSortedMap()).toString(),
            ))
            records.add(parentRecord(backupRevision, baseRevision))
            records.add(stagedRow(case.table, backupRow))
            records.add(headRecord(case.kind, case.id, backupRevision))
            Expected(case, localHead, backupRevision)
        }

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val headConflicts = conflicts.filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[1].toString() }
        assertEquals(expected.size, headConflicts.size)
        expected.forEach { value ->
            val conflict = requireNotNull(headConflicts[value.case.id])
            assertEquals("DIVERGENT", conflict["revisionRelationship"])
            assertEquals(
                listOf("MERGE_KEEP_LOCAL_CONTENT", "MERGE_USE_BACKUP_CONTENT"),
                conflict["allowedChoices"],
            )
        }
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun cardMoneyAndQuantityConflictsUseOneBackupSideAndPreserveBothParents() {
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val cardId = "aggregate-card-money-quantity"
        assertNull(BoardStore.saveCard(
            database,
            BoardStore.Draft(
                cardId, jobId, columnId, "Base card", priceMinor = MoneyMath.parse("100"),
                currency = "USD", quantityUnits = 10_000L,
            ),
            now,
        ))
        val templateId = readRowValues("kanban_cards", "id", cardId).getValue("template_id").toString()
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()

        fun currentHead(kind: String, id: String): String = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, id),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }

        fun archiveValues(table: String, keyColumn: String, key: String): MutableMap<String, Any?> {
            val record = records.first { it["table"] == table && values(it)[keyColumn]?.toString() == key }
            return values(record).entries.associate { it.key.toString() to it.value }.toMutableMap()
        }

        fun replaceArchiveRow(table: String, keyColumn: String, key: String, replacement: Map<String, Any?>) {
            records.removeAll { it["table"] == table && values(it)[keyColumn]?.toString() == key }
            records.add(stagedRow(table, replacement))
        }

        fun addBackupHead(
            kind: String,
            entityId: String,
            entityJobId: String?,
            baseRevision: String,
            backupRevision: String,
            payload: Map<String, Any?>,
        ) {
            records.add(revisionRecord(
                backupRevision, kind, entityId, entityJobId,
                payload = JSONObject(payload.toSortedMap()).toString(),
            ))
            records.add(parentRecord(backupRevision, baseRevision))
            records.removeAll {
                it["table"] == "entity_heads" && values(it)["kind"] == kind &&
                    values(it)["entity_id"] == entityId
            }
            records.add(headRecord(kind, entityId, backupRevision))
        }

        val baseCardHead = currentHead("card", cardId)
        val baseMoneyHead = currentHead("card_money", cardId)
        val baseQuantityHead = currentHead("task_quantity", cardId)
        val baseTemplateHead = currentHead("template", templateId)
        val baseTemplateMoneyHead = currentHead("template_money", templateId)
        val baseTemplateQuantityHead = currentHead("template_quantity", templateId)
        val baseCard = archiveValues("kanban_cards", "id", cardId)
            .filterKeys(BoardStore.cardColumns.toSet()::contains)
        val baseMoney = archiveValues("card_money", "id", cardId)
        val baseQuantity = archiveValues("task_quantities", "card_id", cardId)
        val baseTemplate = archiveValues("task_templates", "id", templateId)
            .filterKeys(BoardStore.templateColumns.toSet()::contains)
        val baseTemplateMoney = archiveValues("template_money", "id", templateId)
        val baseTemplateQuantity = archiveValues("template_quantities", "template_id", templateId)

        database.execSQL("UPDATE kanban_cards SET title=? WHERE id=?", arrayOf("Local card", cardId))
        val localCard = readRowValues("kanban_cards", "id", cardId).filterKeys(BoardStore.cardColumns.toSet()::contains)
        val localCardHead = RevisionStore.record(database, "card", cardId, jobId, localCard, now + 1L)
        MoneyStore.save(
            database, "card_money", cardId, jobId, MoneyMath.parse("200"), now + 1L,
        )
        val localMoneyHead = currentHead("card_money", cardId)
        database.execSQL("UPDATE task_quantities SET quantity_units=? WHERE card_id=?", arrayOf(20_000L, cardId))
        val localQuantityHead = RevisionStore.record(
            database,
            "task_quantity",
            cardId,
            jobId,
            mapOf("card_id" to cardId, "job_id" to jobId, "quantity_units" to 20_000L),
            now + 1L,
        )
        database.execSQL("UPDATE task_templates SET title=? WHERE id=?", arrayOf("Local template", templateId))
        val localTemplate = readRowValues("task_templates", "id", templateId)
            .filterKeys(BoardStore.templateColumns.toSet()::contains)
        val localTemplateHead = RevisionStore.record(
            database, "template", templateId, null, localTemplate, now + 1L,
        )
        MoneyStore.save(
            database, "template_money", templateId, null, MoneyMath.parse("200"), now + 1L,
        )
        val localTemplateMoneyHead = currentHead("template_money", templateId)
        database.execSQL(
            "UPDATE template_quantities SET quantity_units=? WHERE template_id=?",
            arrayOf(20_000L, templateId),
        )
        val localTemplateQuantityHead = RevisionStore.record(
            database, "template_quantity", templateId, null,
            mapOf("template_id" to templateId, "quantity_units" to 20_000L), now + 1L,
        )
        assertNotEquals(baseCardHead, localCardHead)
        assertNotEquals(baseMoneyHead, localMoneyHead)
        assertNotEquals(baseQuantityHead, localQuantityHead)
        assertNotEquals(baseTemplateHead, localTemplateHead)
        assertNotEquals(baseTemplateMoneyHead, localTemplateMoneyHead)
        assertNotEquals(baseTemplateQuantityHead, localTemplateQuantityHead)

        val backupCard = baseCard.toMutableMap().apply { put("title", "Backup card") }
        val backupMoney = baseMoney.toMutableMap().apply { put("minor_text", "300") }
        val backupQuantity = baseQuantity.toMutableMap().apply { put("quantity_units", 30_000L) }
        val backupTemplate = baseTemplate.toMutableMap().apply { put("title", "Backup template") }
        val backupTemplateMoney = baseTemplateMoney.toMutableMap().apply { put("minor_text", "300") }
        val backupTemplateQuantity = baseTemplateQuantity.toMutableMap().apply { put("quantity_units", 30_000L) }
        replaceArchiveRow("kanban_cards", "id", cardId, backupCard)
        replaceArchiveRow("card_money", "id", cardId, backupMoney)
        replaceArchiveRow("task_quantities", "card_id", cardId, backupQuantity)
        replaceArchiveRow("task_templates", "id", templateId, backupTemplate)
        replaceArchiveRow("template_money", "id", templateId, backupTemplateMoney)
        replaceArchiveRow("template_quantities", "template_id", templateId, backupTemplateQuantity)
        addBackupHead("card", cardId, jobId, baseCardHead, "aggregate-backup-card-head", backupCard)
        addBackupHead(
            "card_money", cardId, jobId, baseMoneyHead, "aggregate-backup-card-money-head",
            mapOf("id" to cardId, "minor_text" to "300", "job_id" to jobId),
        )
        addBackupHead(
            "task_quantity", cardId, jobId, baseQuantityHead, "aggregate-backup-card-quantity-head",
            mapOf("card_id" to cardId, "job_id" to jobId, "quantity_units" to 30_000L),
        )
        addBackupHead(
            "template", templateId, null, baseTemplateHead, "aggregate-backup-template-head", backupTemplate,
        )
        addBackupHead(
            "template_money", templateId, null, baseTemplateMoneyHead, "aggregate-backup-template-money-head",
            mapOf("id" to templateId, "minor_text" to "300", "job_id" to null),
        )
        addBackupHead(
            "template_quantity", templateId, null, baseTemplateQuantityHead,
            "aggregate-backup-template-quantity-head",
            mapOf("template_id" to templateId, "quantity_units" to 30_000L),
        )

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val headConflicts = conflicts.filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[0].toString() }
        assertEquals(
            setOf("card", "card_money", "task_quantity", "template", "template_money", "template_quantity"),
            headConflicts.keys,
        )
        val cardConflict = requireNotNull(headConflicts["card"])
        val cardGroups = cardConflict["aggregateKeys"] as List<*>
        assertTrue(cardGroups.contains("card-fields:$cardId"))

        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            cardConflict["conflictId"] as String,
            "MERGE_USE_BACKUP_CONTENT",
        )
        val templateConflict = requireNotNull(headConflicts["template"])
        assertTrue((templateConflict["aggregateKeys"] as List<*>).contains("template-fields:$templateId"))
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            templateConflict["conflictId"] as String,
            "MERGE_KEEP_LOCAL_CONTENT",
        )
        val resolvedConflicts = mutableListOf<Map<String, Any?>>()
        cursor = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            resolvedConflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        resolvedConflicts.filter { it["table"] == "entity_heads" }.forEach { conflict ->
            assertTrue(conflict["resolved"] as Boolean)
            val kind = (conflict["primaryKey"] as List<*>)[0].toString()
            val expected = if (kind in setOf("card", "card_money", "task_quantity")) {
                "MERGE_USE_BACKUP_CONTENT"
            } else {
                "MERGE_KEEP_LOCAL_CONTENT"
            }
            assertEquals(expected, conflict["selectedChoice"])
        }

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals("Backup card", readRowValues("kanban_cards", "id", cardId)["title"])
        assertEquals("300", readRowValues("card_money", "id", cardId)["minor_text"])
        assertEquals(30_000L, readRowValues("task_quantities", "card_id", cardId)["quantity_units"])
        assertEquals("Local template", readRowValues("task_templates", "id", templateId)["title"])
        assertEquals("200", readRowValues("template_money", "id", templateId)["minor_text"])
        assertEquals(20_000L, readRowValues("template_quantities", "template_id", templateId)["quantity_units"])
        listOf(
            Triple("template", localTemplateHead, "aggregate-backup-template-head"),
            Triple("template_money", localTemplateMoneyHead, "aggregate-backup-template-money-head"),
            Triple("template_quantity", localTemplateQuantityHead, "aggregate-backup-template-quantity-head"),
        ).forEach { (kind, localRevision, backupRevision) ->
            val committed = currentHead(kind, templateId)
            assertNotEquals(backupRevision, committed)
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(committed, backupRevision),
            ).use { it.moveToFirst() })
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(committed, localRevision),
            ).use { it.moveToFirst() })
        }
        listOf(
            Triple("card", localCardHead, "aggregate-backup-card-head"),
            Triple("card_money", localMoneyHead, "aggregate-backup-card-money-head"),
            Triple("task_quantity", localQuantityHead, "aggregate-backup-card-quantity-head"),
        ).forEach { (kind, localRevision, backupRevision) ->
            val committed = currentHead(kind, cardId)
            assertNotEquals(backupRevision, committed)
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(committed, backupRevision),
            ).use { it.moveToFirst() })
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(committed, localRevision),
            ).use { it.moveToFirst() })
        }
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun globalSettingsConflictsResolveAsOneLocalSnapshot() {
        data class SettingCase(
            val kind: String,
            val table: String,
            val field: String,
            val localValue: Any,
            val backupValue: Any,
            val revisionColumns: List<String>,
        )

        val cases = listOf(
            SettingCase(
                "settings", "app_settings", "company_name", "Local company", "Backup company",
                BusinessSchema.settingsColumns,
            ),
            SettingCase(
                "settings_money", "settings_money", "minor_text", "7200", "8400",
                listOf("id", "minor_text", "job_id"),
            ),
            SettingCase(
                "billing_tax_settings", "billing_tax_settings", "default_tax_units", 1_250, 1_500,
                listOf("id", "default_tax_units"),
            ),
        )
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()

        fun archiveValues(table: String): MutableMap<String, Any?> {
            val record = records.first { it["table"] == table && values(it)["id"].toString() == "1" }
            return values(record).entries.associate { it.key.toString() to it.value }.toMutableMap()
        }
        fun replaceArchiveRow(table: String, replacement: Map<String, Any?>) {
            records.removeAll { it["table"] == table && values(it)["id"].toString() == "1" }
            records.add(stagedRow(table, replacement))
        }
        fun currentHead(kind: String): String = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id='1'",
            arrayOf(kind),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        fun rowValues(table: String): Map<String, Any?> = readRowValues(table, "id", "1")

        data class Expected(val case: SettingCase, val localHead: String, val backupHead: String)
        val expected = cases.mapIndexed { index, case ->
            val backupRow = archiveValues(case.table)
            val baseHead = currentHead(case.kind)
            database.execSQL(
                "UPDATE ${case.table} SET ${case.field}=? WHERE id=1",
                arrayOf(case.localValue),
            )
            val localRow = rowValues(case.table)
            val localPayload = case.revisionColumns.associateWith { column ->
                when {
                    column == "job_id" -> null
                    case.kind == "settings_money" && column == "id" -> "1"
                    else -> localRow[column]
                }
            }
            val localHead = RevisionStore.record(
                database, case.kind, "1", null, localPayload, now + 1L,
            )

            val backupRevisionId = "global-settings-backup-$index"
            val backupRowUpdated = backupRow.toMutableMap().apply { put(case.field, case.backupValue) }
            val backupPayload = case.revisionColumns.associateWith { column ->
                when {
                    column == "job_id" -> null
                    case.kind == "settings_money" && column == "id" -> "1"
                    else -> backupRowUpdated[column]
                }
            }
            records.add(revisionRecord(
                backupRevisionId, case.kind, "1", null,
                payload = JSONObject(backupPayload.toSortedMap()).toString(),
            ))
            records.add(parentRecord(backupRevisionId, baseHead))
            records.removeAll {
                it["table"] == "entity_heads" && values(it)["kind"] == case.kind &&
                    values(it)["entity_id"].toString() == "1"
            }
            records.add(headRecord(case.kind, "1", backupRevisionId))
            replaceArchiveRow(case.table, backupRowUpdated)
            Expected(case, localHead, backupRevisionId)
        }

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val heads = conflicts.filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[0].toString() }
        assertEquals(cases.map { it.kind }.toSet(), heads.keys)
        heads.values.forEach { conflict ->
            assertTrue((conflict["aggregateKeys"] as List<*>).contains("global:settings"))
            assertEquals(
                listOf("KEEP_LOCAL", "MERGE_KEEP_LOCAL_CONTENT", "MERGE_USE_BACKUP_CONTENT"),
                conflict["allowedChoices"],
            )
        }

        val selected = requireNotNull(heads["settings"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            selected["conflictId"] as String, "MERGE_KEEP_LOCAL_CONTENT",
        )
        val resolved = mutableListOf<Map<String, Any?>>()
        cursor = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            resolved.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        resolved.filter { it["aggregateKeys"] is List<*> &&
            (it["aggregateKeys"] as List<*>).contains("global:settings") }.forEach { conflict ->
            assertTrue(conflict["resolved"] as Boolean)
            assertTrue(conflict["selectedChoice"] == "MERGE_KEEP_LOCAL_CONTENT" ||
                conflict["selectedChoice"] == "KEEP_LOCAL")
        }

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertEquals("Local company", rowValues("app_settings")["company_name"])
        assertEquals("7200", rowValues("settings_money")["minor_text"])
        assertEquals(1_250L, rowValues("billing_tax_settings")["default_tax_units"])
        expected.forEach { item ->
            val mergedHead = currentHead(item.case.kind)
            val parents = database.rawQuery(
                "SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id",
                arrayOf(mergedHead),
            ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
            assertEquals(setOf(item.localHead, item.backupHead), parents)
        }
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun quoteInvoiceAggregateIncludesOriginalInvoiceForRowsAndRevisionHeads() {
        val quoteId = "aggregate-quote-origin"
        val invoiceJobId = "aggregate-quote-origin-job"
        val taskId = "aggregate-quote-origin-task"
        val quotedInvoiceId = "aggregate-quote-origin-invoice"
        val replacementInvoiceId = "aggregate-quote-origin-replacement"
        assertNull(repository.saveQuote(
            "aggregate-quote-origin-save",
            repository.snapshot().version,
            QuoteStore.Draft(
                quoteId, null, null, "Quoted tile installation", "USD", 75_000,
                listOf(QuoteStore.Line("tile", "Tile", "Synthetic tile work", "ITEM", "260000", "1250")),
            ),
        ).conflict)
        assertNull(repository.issueQuote(
            "aggregate-quote-origin-issue", repository.snapshot().version, quoteId, true,
        ).conflict)
        assertNull(repository.decideQuote(
            "aggregate-quote-origin-accept", repository.snapshot().version, quoteId, 1,
            "ACCEPTED", "Explicit aggregate fixture acceptance", true,
        ).conflict)
        assertNull(repository.applyQuoteScope(
            "aggregate-quote-origin-scope",
            repository.snapshot().version,
            QuoteScopeStore.Draft(
                quoteId, 1, invoiceJobId,
                listOf(QuoteScopeStore.Item("tile", taskId, "Install tile")),
                "Reviewed scope for aggregate fixture", true, "Aggregate fixture job",
                jobDifferencesReviewed = true,
            ),
        ).conflict)
        val board = repository.boardSnapshot(invoiceJobId).board
        val completedColumn = board.columns.single { it.completed }.id
        assertNull(repository.saveCard(
            "aggregate-quote-origin-complete",
            repository.snapshot().version,
            BoardStore.Draft(
                taskId, invoiceJobId, completedColumn, "Install tile",
                priceMinor = java.math.BigInteger("999"), currency = "USD", quantityUnits = 260_000L,
            ),
        ).conflict)
        val request = QuoteInvoiceReviewStore.Request(
            quoteId, 1,
            ReportStore.Selection("2026-01-01", "2026-12-31", "UTC", listOf(invoiceJobId)),
        )
        val choices = QuoteInvoiceReviewStore.Choices(
            listOf(taskId), emptyList(), note = "Reviewed actual tile work", confirmed = true,
        )
        assertNull(repository.prepareQuoteInvoice(
            "aggregate-quote-origin-prepare",
            repository.snapshot().version,
            QuoteInvoiceStore.Draft(quotedInvoiceId, request, choices),
        ).conflict)
        val originalInvoice = repository.invoice(quotedInvoiceId)
        val originalDocument = InvoiceStore.Snapshot.decode(originalInvoice.payload)
        val originalLine = originalDocument.lines.single()
        assertNull(repository.replaceInvoice(
            "aggregate-quote-origin-replace",
            repository.snapshot().version,
            InvoiceLifecycleStore.ReplacementDraft(
                quotedInvoiceId, replacementInvoiceId,
                listOf(InvoiceLifecycleStore.LineEdit(
                    0, originalLine.title, originalLine.detail, "39000", "260000", "1500",
                )),
                75_000, "Synthetic correction for aggregate fixture", "UTC", true,
            ),
        ).conflict)

        val localLink = database.rawQuery(
            "SELECT quote_id,revision,original_invoice_id,created_ms,payload FROM quote_invoice_links WHERE invoice_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            listOf(rows.getString(0), rows.getInt(1), rows.getString(2), rows.getLong(3), rows.getString(4))
        }
        assertEquals(quotedInvoiceId, localLink[2])
        val localLinkHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='quote_invoice_link' AND entity_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val linkJobId = repository.invoice(replacementInvoiceId).jobId
        val baseRevisionId = "aggregate-quote-origin-common-base"
        val basePayload = JSONObject(mapOf(
            "invoice_id" to replacementInvoiceId,
            "quote_id" to quoteId,
            "revision" to localLink[1],
            "original_invoice_id" to quotedInvoiceId,
            "created_ms" to localLink[3],
            "payload" to localLink[4],
        )).toString()
        database.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,'quote_invoice_link',?,?,?,0,?,'MUTATION')",
            arrayOf(baseRevisionId, replacementInvoiceId, linkJobId, basePayload, now - 2L),
        )
        database.execSQL(
            "INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)",
            arrayOf(localLinkHead, baseRevisionId),
        )
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()

        fun replaceArchiveRow(table: String, keyColumn: String, key: String, replacement: Map<String, Any?>) {
            records.removeAll { it["table"] == table && values(it)[keyColumn]?.toString() == key }
            records.add(stagedRow(table, replacement))
        }
        fun archiveValues(table: String, predicate: (Map<*, *>) -> Boolean): MutableMap<String, Any?> {
            val record = records.first { it["table"] == table && predicate(values(it)) }
            return values(record).entries.associate { it.key.toString() to it.value }.toMutableMap()
        }

        val backupCreatedMs = (localLink[3] as Long) + 1L
        val replacementLink = archiveValues("quote_invoice_links") {
            it["invoice_id"] == replacementInvoiceId
        }.apply { put("created_ms", backupCreatedMs) }
        replaceArchiveRow("quote_invoice_links", "invoice_id", replacementInvoiceId, replacementLink)

        val changedInvoiceNumber = "INV-9997"
        val staleInvoice = archiveValues("invoices") { it["id"] == quotedInvoiceId }.apply {
            put("number", changedInvoiceNumber)
            put("payload", originalDocument.copy(number = changedInvoiceNumber).encode())
        }
        replaceArchiveRow("invoices", "id", quotedInvoiceId, staleInvoice)
        val staleDecision = archiveValues("quote_decisions") { it["quote_id"] == quoteId }.apply {
            put("note", "Stale backup acceptance note")
        }
        records.removeAll { it["table"] == "quote_decisions" && values(it)["quote_id"] == quoteId }
        records.add(stagedRow("quote_decisions", staleDecision))
        val staleScopeRevision = archiveValues("quote_scope_revisions") { it["quote_id"] == quoteId }.apply {
            put("note", "Stale backup scope note")
        }
        records.removeAll { it["table"] == "quote_scope_revisions" && values(it)["quote_id"] == quoteId }
        records.add(stagedRow("quote_scope_revisions", staleScopeRevision))

        val backupHeadRevisionId = "aggregate-quote-origin-backup-head"
        val backupPayload = JSONObject(mapOf(
            "invoice_id" to replacementInvoiceId,
            "quote_id" to quoteId,
            "revision" to localLink[1],
            "original_invoice_id" to quotedInvoiceId,
            "created_ms" to backupCreatedMs,
            "payload" to localLink[4],
        )).toString()
        records.add(revisionRecord(
            backupHeadRevisionId, "quote_invoice_link", replacementInvoiceId,
            linkJobId, payload = backupPayload,
        ))
        records.add(parentRecord(backupHeadRevisionId, baseRevisionId))
        records.removeAll {
            it["table"] == "entity_heads" && values(it)["kind"] == "quote_invoice_link" &&
                values(it)["entity_id"] == replacementInvoiceId
        }
        records.add(headRecord("quote_invoice_link", replacementInvoiceId, backupHeadRevisionId))

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = importStore.readConflictPage(
                database, started.importId, preview.previewToken, cursor, null,
            )
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        fun conflict(table: String, matches: (List<*>) -> Boolean): Map<String, Any?> =
            requireNotNull(conflicts.singleOrNull { row ->
                row["table"] == table && matches(row["primaryKey"] as List<*>)
            }) { "Missing $table aggregate fixture conflict" }

        val linkRowConflict = conflict("quote_invoice_links") { it.singleOrNull() == replacementInvoiceId }
        val linkHeadConflict = conflict("entity_heads") {
            it.getOrNull(0) == "quote_invoice_link" && it.getOrNull(1) == replacementInvoiceId
        }
        val originalInvoiceConflict = conflict("invoices") { it.singleOrNull() == quotedInvoiceId }
        val quoteDecisionConflict = conflict("quote_decisions") { it.getOrNull(0) == quoteId }
        val quoteScopeConflict = conflict("quote_scope_revisions") { it.getOrNull(0) == quoteId }
        listOf(linkRowConflict, linkHeadConflict).forEach { row ->
            val aggregateKeys = row["aggregateKeys"] as List<*>
            assertTrue(aggregateKeys.contains("invoice:$quotedInvoiceId"))
            assertTrue(aggregateKeys.contains("invoice:$replacementInvoiceId"))
            assertTrue(aggregateKeys.contains("quote:$quoteId"))
        }
        assertEquals(listOf("KEEP_LOCAL"), linkHeadConflict["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            linkHeadConflict["conflictId"] as String, "KEEP_LOCAL",
        )
        val resolved = mutableListOf<Map<String, Any?>>()
        cursor = null
        do {
            val page = importStore.readConflictPage(
                database, started.importId, preview.previewToken, cursor, null,
            )
            resolved.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        listOf(linkRowConflict, linkHeadConflict, originalInvoiceConflict, quoteDecisionConflict, quoteScopeConflict)
            .forEach { original ->
                val current = requireNotNull(resolved.singleOrNull { it["conflictId"] == original["conflictId"] })
                assertTrue(current["resolved"] as Boolean)
                assertEquals("KEEP_LOCAL", current["selectedChoice"])
            }

        val stateBeforeApply = repository.snapshot()
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertEquals(stateBeforeApply, repository.snapshot())
        assertEquals(localLinkHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='quote_invoice_link' AND entity_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(originalInvoice.number, repository.invoice(quotedInvoiceId).number)
        assertEquals(quotedInvoiceId, database.rawQuery(
            "SELECT original_invoice_id FROM quote_invoice_links WHERE invoice_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$backupHeadRevisionId'"))
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun nonIdenticalDivergentHeadsOfferOnlyAnExplicitSafeLocalChoice() {
        val kind = "quote_decision"
        val entityId = "nonidentical-divergent-entity"
        val baseRevision = "nonidentical-divergent-base"
        val localRevision = "nonidentical-divergent-local"
        val backupRevision = "nonidentical-divergent-backup"
        insertSyntheticRevision(baseRevision, kind, entityId, null)
        insertSyntheticRevision(localRevision, kind, entityId, baseRevision, "{\"side\":\"local\"}")
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES(?,?,?)",
            arrayOf(kind, entityId, localRevision),
        )
        val started = stageImport(listOf(
            revisionRecord(baseRevision, kind, entityId, null),
            revisionRecord(backupRevision, kind, entityId, null, payload = "{\"side\":\"backup\"}"),
            parentRecord(backupRevision, baseRevision),
            headRecord(kind, entityId, backupRevision),
        ))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        assertEquals("DIVERGENT", conflict["revisionRelationship"])
        assertEquals(listOf("KEEP_LOCAL"), conflict["allowedChoices"])
        assertEquals("KEEP_LOCAL", conflict["defaultChoice"])
        assertFalse(conflict["resolved"] as Boolean)

        val before = repository.snapshot()
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "KEEP_LOCAL",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(before, repository.snapshot())
        assertEquals(localRevision, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$backupRevision'"))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun oldBackupPointCoveredByLocalRangeRequiresExplicitPreserveChoice() {
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
        assertEquals("PRESERVE_DELETION", conflict["defaultChoice"])
        assertEquals(listOf("PRESERVE_DELETION"), conflict["allowedChoices"])
        assertEquals(false, conflict["resolved"])
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "PRESERVE_DELETION",
        )
        val headConflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            headConflict["conflictId"] as String,
            "KEEP_LOCAL",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertNoCoordinates(conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
        assertFalse(preview.applyReady)
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun backupOnlyPointCoveredByLocalRangeOmitsItsCompleteRevisionGraph() {
        val (sessionId, measuredAt) = startAndStopSessionWithPoint("local-point-for-backup-only-preserve")
        assertNull(repository.deleteLocationRange(
            "create-empty-local-deletion-range",
            repository.snapshot().version,
            GpsDeletionStore.Range(jobId, measuredAt, measuredAt + 1L, "Etc/UTC"),
        ).conflict)
        val localStateBeforePreview = repository.snapshot()
        val incomingPointId = "backup-only-covered-point"
        val incomingPointRevision = "backup-only-covered-point-revision"
        val started = stageImport(listOf(
            revisionRecord(
                incomingPointRevision,
                "location",
                incomingPointId,
                jobId,
                payload = JSONObject().put("id", incomingPointId)
                    .put("session_id", sessionId).toString(),
            ),
            headRecord("location", incomingPointId, incomingPointRevision),
            stagedRow("location_points", mapOf(
                "id" to incomingPointId,
                "session_id" to sessionId,
                "measured_ms" to measuredAt,
                "received_ms" to measuredAt,
                "latitude" to 12.0,
                "longitude" to -34.0,
                "accuracy" to 5.0,
            )),
        ))

        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "GPS_DELETION_COVERAGE"))
        assertEquals(listOf("PRESERVE_DELETION"), conflict["allowedChoices"])
        assertFalse(conflict["resolved"] as Boolean)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "PRESERVE_DELETION",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(localStateBeforePreview, repository.snapshot())
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
        assertEquals(1L, number("SELECT COUNT(*) FROM sessions WHERE id='$sessionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$incomingPointId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_heads WHERE kind='location' AND entity_id='$incomingPointId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$incomingPointRevision'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM revision_parents WHERE revision_id='$incomingPointRevision' OR parent_id='$incomingPointRevision'",
        ))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun stagedRangeConflictCanKeepAnUnparentedBackupPointByOmittingTheRange() {
        val (sessionId, measuredAt) = sessionWithoutLivePoint("staged-range-keep-point")
        val rangeId = "staged-range-keep-point-range"
        val rangeRevisionId = "staged-range-keep-point-range-revision"
        val pointId = "staged-range-keep-point-point"
        val pointRevisionId = "staged-range-keep-point-point-revision"
        val started = stageImport(backupRangeAndUnparentedPointRecords(
            rangeId, rangeRevisionId, pointId, pointRevisionId, sessionId, measuredAt,
        ))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "GPS_DELETION_COVERAGE"))
        assertEquals("location_deletion_ranges", conflict["table"])
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), conflict["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "KEEP_LOCAL",
        )

        val applied = importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$rangeRevisionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_heads WHERE kind='location_deletion_range' AND entity_id='$rangeId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(1L, number(
            "SELECT COUNT(*) FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind='location' AND h.entity_id='$pointId' AND r.deleted=0",
        ))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun stagedRangeConflictCanApplyDeletionAndOmitUnparentedBackupPointGraph() {
        val (sessionId, measuredAt) = sessionWithoutLivePoint("staged-range-apply-delete")
        val rangeId = "staged-range-apply-delete-range"
        val rangeRevisionId = "staged-range-apply-delete-range-revision"
        val pointId = "staged-range-apply-delete-point"
        val pointRevisionId = "staged-range-apply-delete-point-revision"
        val started = stageImport(backupRangeAndUnparentedPointRecords(
            rangeId, rangeRevisionId, pointId, pointRevisionId, sessionId, measuredAt,
        ))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "GPS_DELETION_COVERAGE"))
        assertEquals("location_deletion_ranges", conflict["table"])
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), conflict["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "APPLY_BACKUP_DELETION",
        )

        val applied = importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        assertEquals(1L, number(
            "SELECT COUNT(*) FROM entity_heads WHERE kind='location_deletion_range' AND entity_id='$rangeId' AND revision_id='$rangeRevisionId'",
        ))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$pointRevisionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_heads WHERE kind='location' AND entity_id='$pointId'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM entity_heads h LEFT JOIN entity_revisions r ON r.id=h.revision_id WHERE r.id IS NULL",
        ))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM revision_parents p LEFT JOIN entity_revisions child ON child.id=p.revision_id " +
                "LEFT JOIN entity_revisions parent ON parent.id=p.parent_id WHERE child.id IS NULL OR parent.id IS NULL",
        ))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun manualGpsTombstoneRequiresExplicitPreservationAndKeepsThePointDeleted() {
        val (sessionId, measuredAt) = startAndStopSessionWithPoint("manual-tombstone-import-point")
        val source = exportStore.start(database)
        val sessionEnd = database.rawQuery(
            "SELECT end_ms FROM sessions WHERE id=?",
            arrayOf(sessionId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getLong(0) }
        val correctedStart = measuredAt + 1L
        assertNull(repository.correctSession(
            "correct-session-before-backup-restore",
            repository.snapshot().version,
            SessionHistoryStore.Draft(
                sessionId, jobId, correctedStart, sessionEnd, "Etc/UTC", 0,
            ),
        ).conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))

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
        val tombstone = requireNotNull(findConflict(started.importId, preview, "GPS_TOMBSTONE"))
        assertEquals("PRESERVE_LOCAL_DELETION", tombstone["defaultChoice"])
        assertEquals(listOf("PRESERVE_LOCAL_DELETION"), tombstone["allowedChoices"])
        assertFalse(tombstone["resolved"] as Boolean)

        val conflicts = importStore.readConflictPage(
            database, started.importId, preview.previewToken, null, null,
        )
        assertTrue(conflicts.done)
        conflicts.conflicts.forEach { conflict ->
            val choice = when (conflict["kind"]) {
                "GPS_TOMBSTONE" -> "PRESERVE_LOCAL_DELETION"
                else -> "KEEP_LOCAL"
            }
            assertTrue((conflict["allowedChoices"] as List<*>).contains(choice))
            importStore.resolveConflict(
                database,
                started.importId,
                preview.previewToken,
                preview.localStateVersion,
                conflict["conflictId"] as String,
                choice,
            )
        }

        val versionBeforeApply = repository.snapshot().version
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(0L, applied.insertedCount)
        assertEquals(versionBeforeApply, applied.stateVersion)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(correctedStart, database.rawQuery(
            "SELECT start_ms FROM sessions WHERE id=?",
            arrayOf(sessionId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getLong(0) })
        assertNull(repository.snapshot().running)
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun importedRangeCanBeKeptLocalWithoutChangingThePointGraph() {
        val pointId = "local-point-covered-by-import"
        val session = startAndStopSessionWithPoint(pointId)
        val rangeId = "synthetic-imported-range"
        val revisionId = "synthetic-imported-range-revision"
        val rangeStart = session.second
        val rangeEnd = session.second + 1L
        val range = mapOf<String, Any?>(
            "id" to rangeId,
            "job_id" to jobId,
            "start_ms" to rangeStart,
            "end_ms" to rangeEnd,
            "timezone_id" to "Etc/UTC",
            "deleted_ms" to now,
            "revision_id" to revisionId,
        )
        val records = listOf(
            revisionRecord(
                revisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
                payload = JSONObject().put("id", rangeId).put("job_id", jobId)
                    .put("start_ms", rangeStart).put("end_ms", rangeEnd)
                    .put("timezone_id", "Etc/UTC").toString(),
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
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), conflict["allowedChoices"])
        assertEquals(false, conflict["resolved"])
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        val localHeadBefore = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "KEEP_LOCAL",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertEquals(before, repository.snapshot())
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
        assertEquals(localHeadBefore, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$revisionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_heads WHERE kind='location_deletion_range' AND entity_id='$rangeId'"))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun importedRangeDeletionAddsCausalParentsAndRemovesCoveredLocalPoint() {
        val (pointId, measuredAt) = startAndStopSessionWithPoint("local-point-deleted-by-import")
        val rangeId = "synthetic-import-delete-range"
        val rangeRevisionId = "synthetic-import-delete-range-revision"
        val rangeStart = measuredAt
        val rangeEnd = measuredAt + 1L
        val range = mapOf<String, Any?>(
            "id" to rangeId,
            "job_id" to jobId,
            "start_ms" to rangeStart,
            "end_ms" to rangeEnd,
            "timezone_id" to "Etc/UTC",
            "deleted_ms" to now,
            "revision_id" to rangeRevisionId,
        )
        val records = listOf(
            revisionRecord(
                rangeRevisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
                payload = JSONObject().put("id", rangeId).put("job_id", jobId)
                    .put("start_ms", rangeStart).put("end_ms", rangeEnd)
                    .put("timezone_id", "Etc/UTC").toString(),
            ),
            headRecord("location_deletion_range", rangeId, rangeRevisionId),
            stagedRow("location_deletion_ranges", range),
        )
        val localHeadBefore = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val before = repository.snapshot()
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "GPS_DELETION_COVERAGE"))
        assertEquals("location_deletion_ranges", conflict["table"])
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), conflict["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "APPLY_BACKUP_DELETION",
        )

        val applied = importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(before.version + 1L, applied.stateVersion)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        val tombstoneRevisionId = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        assertNotEquals(localHeadBefore, tombstoneRevisionId)
        assertEquals(1L, number(
            "SELECT COUNT(*) FROM entity_revisions WHERE id='$tombstoneRevisionId' AND deleted=1 AND origin='GPS_RANGE_DELETE'",
        ))
        val parents = database.rawQuery(
            "SELECT parent_id FROM revision_parents WHERE revision_id=?",
            arrayOf(tombstoneRevisionId),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
        assertEquals(setOf(localHeadBefore, rangeRevisionId), parents)
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM revision_parents p LEFT JOIN entity_revisions child ON child.id=p.revision_id " +
                "LEFT JOIN entity_revisions parent ON parent.id=p.parent_id WHERE child.id IS NULL OR parent.id IS NULL",
        ))
        assertEquals(applied.stateVersion, repository.snapshot().version)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun failedImportedRangeTombstoneRollsBackRangePointAndRevisionGraph() {
        val (pointId, measuredAt) = startAndStopSessionWithPoint("range-rollback-local-point")
        val rangeId = "range-rollback-imported-range"
        val rangeRevisionId = "range-rollback-imported-range-revision"
        val range = mapOf<String, Any?>(
            "id" to rangeId,
            "job_id" to jobId,
            "start_ms" to measuredAt,
            "end_ms" to measuredAt + 1L,
            "timezone_id" to "Etc/UTC",
            "deleted_ms" to now,
            "revision_id" to rangeRevisionId,
        )
        val started = stageImport(listOf(
            revisionRecord(
                rangeRevisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
                payload = JSONObject().put("id", rangeId).put("job_id", jobId)
                    .put("start_ms", measuredAt).put("end_ms", measuredAt + 1L)
                    .put("timezone_id", "Etc/UTC").toString(),
            ),
            headRecord("location_deletion_range", rangeId, rangeRevisionId),
            stagedRow("location_deletion_ranges", range),
        ))
        val preview = importStore.finish(database, started.importId)
        val coverageConflict = requireNotNull(findConflict(
            started.importId, preview, "GPS_DELETION_COVERAGE",
        ))
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            coverageConflict["conflictId"] as String, "APPLY_BACKUP_DELETION",
        )
        val localPointHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val versionBefore = repository.snapshot().version
        database.execSQL(
            "CREATE TRIGGER reject_imported_gps_tombstone BEFORE INSERT ON entity_revisions " +
                "WHEN NEW.kind='location' AND NEW.entity_id='$pointId' AND NEW.origin='GPS_RANGE_DELETE' " +
                "BEGIN SELECT RAISE(ABORT,'synthetic imported GPS tombstone failure'); END",
        )

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(versionBefore, repository.snapshot().version)
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$rangeRevisionId'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM entity_heads WHERE kind='location_deletion_range' AND entity_id='$rangeId'",
        ))
        assertEquals(localPointHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM entity_revisions WHERE kind='location' AND entity_id='$pointId' " +
                "AND origin='GPS_RANGE_DELETE'",
        ))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM revision_parents WHERE revision_id='$rangeRevisionId' OR parent_id='$rangeRevisionId'",
        ))

        database.execSQL("DROP TRIGGER reject_imported_gps_tombstone")
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(versionBefore + 1L, applied.stateVersion)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        val tombstoneRevisionId = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        assertEquals(1L, number(
            "SELECT COUNT(*) FROM entity_revisions WHERE id='$tombstoneRevisionId' " +
                "AND deleted=1 AND origin='GPS_RANGE_DELETE'",
        ))
        assertEquals(setOf(localPointHead, rangeRevisionId), database.rawQuery(
            "SELECT parent_id FROM revision_parents WHERE revision_id=?",
            arrayOf(tombstoneRevisionId),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } })
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
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun staleBackupCannotResurrectDeletedBoardCard() {
        val cardId = "stale-deleted-card"
        val commentId = "stale-deleted-card-comment"
        val attachmentId = "stale-deleted-card-attachment"
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertNull(repository.saveCard(
            "add-card-for-stale-restore", repository.snapshot().version,
            BoardStore.Draft(
                cardId, jobId, columnId, "Card to delete",
                priceMinor = java.math.BigInteger.valueOf(2_500L),
                currency = currency,
                quantityUnits = 25_000L,
            ),
        ).conflict)
        assertNull(repository.saveTaskComment(
            "comment-for-stale-restore", repository.snapshot().version,
            jobId, cardId, commentId, "This comment and image must stay deleted.",
        ).conflict)
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
        assertNull(repository.attachTaskImage(
            "image-for-stale-restore", repository.snapshot().version,
            jobId, cardId, commentId, attachmentId, png,
        ).conflict)
        val templateId = database.rawQuery(
            "SELECT template_id FROM kanban_cards WHERE id=?", arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val source = exportStore.start(database)
        val staleRecords = readAllRows(source.snapshotId)
        assertTrue(staleRecords.any { it["table"] == "task_comments" && values(it)["id"] == commentId })
        assertTrue(staleRecords.any { it["table"] == "task_attachments" && values(it)["id"] == attachmentId })
        assertTrue(staleRecords.any { it["table"] == "task_quantities" && values(it)["card_id"] == cardId })
        assertTrue(staleRecords.any { it["table"] == "card_money" && values(it)["id"] == cardId })
        assertTrue(exportStore.close(source.snapshotId))

        assertNull(repository.removeCard(
            "delete-card-before-stale-restore", repository.snapshot().version, jobId, cardId,
        ).conflict)
        val deletedVersion = repository.snapshot().version
        val deletedHead = database.rawQuery(
            "SELECT h.revision_id FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind='card' AND h.entity_id=? AND r.deleted=1",
            arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val fencedEntities = listOf(
            "card" to cardId,
            "card_money" to cardId,
            "task_quantity" to cardId,
            "task_comment" to commentId,
            "task_attachment" to attachmentId,
        )
        fun revisionFenceSnapshot(): Triple<List<List<String?>>, List<List<String?>>, List<List<String?>>> {
            val identityFilter = fencedEntities.joinToString(" OR ") { "(kind=? AND entity_id=?)" }
            val identityArgs = fencedEntities.flatMap { listOf(it.first, it.second) }.toTypedArray()
            val revisions = database.rawQuery(
                "SELECT id,kind,entity_id,job_id,payload,deleted,changed_ms,origin " +
                    "FROM entity_revisions WHERE $identityFilter ORDER BY kind,entity_id,id",
                identityArgs,
            ).use { rows -> buildList {
                while (rows.moveToNext()) add((0 until rows.columnCount).map { index ->
                    if (rows.isNull(index)) null else rows.getString(index)
                })
            } }
            val revisionIds = revisions.map { requireNotNull(it.first()) }
            val graphArgs = revisionIds.toTypedArray()
            val graphFilter = revisionIds.joinToString(",") { "?" }
            val parents = if (revisionIds.isEmpty()) emptyList() else database.rawQuery(
                "SELECT revision_id,parent_id FROM revision_parents " +
                    "WHERE revision_id IN ($graphFilter) OR parent_id IN ($graphFilter) " +
                    "ORDER BY revision_id,parent_id",
                graphArgs + graphArgs,
            ).use { rows -> buildList {
                while (rows.moveToNext()) add(listOf(rows.getString(0), rows.getString(1)))
            } }
            val heads = database.rawQuery(
                "SELECT kind,entity_id,revision_id FROM entity_heads WHERE $identityFilter ORDER BY kind,entity_id",
                identityArgs,
            ).use { rows -> buildList {
                while (rows.moveToNext()) add(listOf(rows.getString(0), rows.getString(1), rows.getString(2)))
            } }
            return Triple(revisions, parents, heads)
        }
        val localRevisionFence = revisionFenceSnapshot()

        val started = stageImport(staleRecords)
        val preview = importStore.finish(database, started.importId)
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
        val tombstoneConflict = requireNotNull(conflicts.singleOrNull { conflict ->
            conflict["kind"] == "ENTITY_DELETION_TOMBSTONE" && conflict["table"] == "kanban_cards" &&
                (conflict["primaryKey"] as List<*>).singleOrNull() == cardId
        })
        assertEquals("kanban_cards", tombstoneConflict["table"])
        assertEquals(listOf("PRESERVE_LOCAL_DELETION"), tombstoneConflict["allowedChoices"])
        assertEquals("PRESERVE_LOCAL_DELETION", tombstoneConflict["defaultChoice"])
        assertFalse(preview.applyReady)
        val tombstoneTables = conflicts
            .filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }
            .map { it["table"] }
            .toSet()
        assertTrue(tombstoneTables.containsAll(setOf(
            "kanban_cards", "task_quantities", "card_money", "task_comments", "task_attachments",
        )))
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(deletedVersion, repository.snapshot().version)
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_cards WHERE id='$cardId'"))
        assertEquals(deletedHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='card' AND entity_id=?",
            arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comment_events WHERE task_id='$cardId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_attachments WHERE id='$attachmentId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_quantities WHERE card_id='$cardId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM card_money WHERE id='$cardId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM task_templates WHERE id='$templateId'"))
        assertEquals(localRevisionFence, revisionFenceSnapshot())
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun staleBackupCannotResurrectIndividuallyDeletedCommentOrImage() {
        val cardId = "stale-individual-delete-card"
        val commentId = "stale-individual-delete-comment"
        val commentImageId = "stale-individual-delete-comment-image"
        val standaloneImageId = "stale-individual-delete-standalone-image"
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertNull(repository.saveCard(
            "stale-individual-delete-card-create", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Deletion test card", currency = currency),
        ).conflict)
        assertNull(repository.saveTaskComment(
            "stale-individual-delete-comment-create", repository.snapshot().version,
            jobId, cardId, commentId, "This note will be deleted.",
        ).conflict)
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
        assertNull(repository.attachTaskImage(
            "stale-individual-delete-comment-image-create", repository.snapshot().version,
            jobId, cardId, commentId, commentImageId, png,
        ).conflict)
        assertNull(repository.attachTaskImage(
            "stale-individual-delete-standalone-image-create", repository.snapshot().version,
            jobId, cardId, null, standaloneImageId, png,
        ).conflict)

        val source = exportStore.start(database)
        val staleRecords = readAllRows(source.snapshotId)
        assertTrue(staleRecords.any { it["table"] == "task_comments" && values(it)["id"] == commentId })
        assertTrue(staleRecords.any { it["table"] == "task_attachments" && values(it)["id"] == commentImageId })
        assertTrue(staleRecords.any { it["table"] == "task_attachments" && values(it)["id"] == standaloneImageId })
        assertTrue(exportStore.close(source.snapshotId))

        assertNull(repository.removeTaskComment(
            "stale-individual-delete-comment-remove", repository.snapshot().version, jobId, cardId, commentId,
        ).conflict)
        assertNull(repository.removeTaskImage(
            "stale-individual-delete-image-remove", repository.snapshot().version, jobId, cardId, standaloneImageId,
        ).conflict)
        val deletedVersion = repository.snapshot().version
        val started = stageImport(staleRecords)
        val preview = importStore.finish(database, started.importId)
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
        val deletedEntityIds = conflicts
            .filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }
            .map { (it["primaryKey"] as List<*>).single() }
            .toSet()
        assertTrue(deletedEntityIds.containsAll(setOf(commentId, commentImageId, standaloneImageId)))
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(deletedVersion, repository.snapshot().version)
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_attachments WHERE id IN('$commentImageId','$standaloneImageId')"))
        assertEquals(3L, number(
            "SELECT COUNT(*) FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE (h.kind='task_comment' AND h.entity_id='$commentId' AND r.deleted=1) OR " +
                "(h.kind='task_attachment' AND h.entity_id IN('$commentImageId','$standaloneImageId') AND r.deleted=1)",
        ))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun staleBackupDeletionFencesCoverUnusedCustomerSiteAndEmptyColumn() {
        val customerId = "stale-deleted-customer"
        val siteId = "stale-deleted-site"
        val columnId = "stale-deleted-column"
        assertNull(repository.saveCustomer(
            "add-customer-for-stale-restore", repository.snapshot().version,
            BusinessStore.Customer(customerId, "Unused stale customer", email = "stale@example.test"),
        ).conflict)
        assertNull(repository.saveSite(
            "add-site-for-stale-restore", repository.snapshot().version,
            GeofenceStore.Site(siteId, jobId, "Stale site", 12.0, -34.0, enabled = false),
        ).conflict)
        assertNull(repository.saveColumn(
            "add-column-for-stale-restore", repository.snapshot().version,
            columnId, jobId, "Stale column", false,
        ).conflict)

        val source = exportStore.start(database)
        val staleRecords = readAllRows(source.snapshotId)
        assertTrue(staleRecords.any { it["table"] == "customers" && values(it)["id"] == customerId })
        assertTrue(staleRecords.any { it["table"] == "work_sites" && values(it)["id"] == siteId })
        assertTrue(staleRecords.any { it["table"] == "kanban_columns" && values(it)["id"] == columnId })
        assertTrue(exportStore.close(source.snapshotId))

        assertNull(repository.removeCustomer(
            "delete-customer-before-stale-restore", repository.snapshot().version, customerId,
        ).conflict)
        assertNull(repository.removeSite(
            "delete-site-before-stale-restore", repository.snapshot().version, siteId,
        ).conflict)
        assertNull(repository.removeColumn(
            "delete-column-before-stale-restore", repository.snapshot().version, jobId, columnId, null,
        ).conflict)
        val deletedVersion = repository.snapshot().version

        val started = stageImport(staleRecords)
        val preview = importStore.finish(database, started.importId)
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
        listOf(
            "customers" to customerId,
            "work_sites" to siteId,
            "kanban_columns" to columnId,
        ).forEach { (table, entityId) ->
            val conflict = conflicts.single { item ->
                item["kind"] == "ENTITY_DELETION_TOMBSTONE" && item["table"] == table &&
                    (item["primaryKey"] as List<*>).singleOrNull() == entityId
            }
            assertEquals(listOf("PRESERVE_LOCAL_DELETION"), conflict["allowedChoices"])
            assertEquals("PRESERVE_LOCAL_DELETION", conflict["defaultChoice"])
        }
        assertFalse(preview.applyReady)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(deletedVersion, repository.snapshot().version)
        assertEquals(0L, number("SELECT COUNT(*) FROM customers WHERE id='$customerId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM work_sites WHERE id='$siteId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_columns WHERE id='$columnId'"))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun backupJobDeletionCannotFenceStillLiveLocalJob() {
        val currentJob = database.rawQuery(
            "SELECT id FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val parentRevision = "active-job-deletion-parent"
        val deletionRevision = "active-job-deletion-head"
        val deletionPayload = JSONObject().put("id", currentJob).put("job_id", currentJob).toString()
        val records = listOf(
            revisionRecord(
                parentRevision, "job", currentJob, currentJob, payload = null,
            ),
            revisionRecord(
                deletionRevision, "job", currentJob, currentJob,
                deleted = 1L, payload = deletionPayload,
            ),
            parentRecord(deletionRevision, parentRevision),
            headRecord("job", currentJob, deletionRevision),
            stagedRow("job_deletions", mapOf(
                "id" to currentJob,
                "revision_id" to deletionRevision,
                "deleted_ms" to now,
            )),
        )
        val versionBefore = repository.snapshot().version
        val localHeadBefore = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='job' AND entity_id=?",
            arrayOf(currentJob),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "ACTIVE_JOB_DELETION"))
        assertEquals("job_deletions", conflict["table"])
        assertEquals(listOf("KEEP_LOCAL"), conflict["allowedChoices"])
        assertNull(conflict["defaultChoice"])
        assertFalse(preview.applyReady)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "KEEP_LOCAL",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(versionBefore, repository.snapshot().version)
        assertEquals(1L, number("SELECT COUNT(*) FROM jobs WHERE id='$currentJob'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM job_deletions WHERE id='$currentJob'"))
        assertEquals(localHeadBefore, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='job' AND entity_id=?",
            arrayOf(currentJob),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun backupCommentAndImageDeletionCanBeKeptWithoutDeletingTheLocalGraph() {
        val cardId = "backup-delete-choice-card"
        val commentId = "backup-delete-choice-comment"
        val attachmentId = "backup-delete-choice-image"
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertNull(repository.saveCard(
            "backup-delete-choice-card-create", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Keep local content", currency = currency),
        ).conflict)
        assertNull(repository.saveTaskComment(
            "backup-delete-choice-comment-create", repository.snapshot().version,
            jobId, cardId, commentId, "Local comment stays live.",
        ).conflict)
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
        assertNull(repository.attachTaskImage(
            "backup-delete-choice-image-create", repository.snapshot().version,
            jobId, cardId, commentId, attachmentId, png,
        ).conflict)
        val commentHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='task_comment' AND entity_id=?",
            arrayOf(commentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val attachmentHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='task_attachment' AND entity_id=?",
            arrayOf(attachmentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val commentDeletion = "backup-delete-choice-comment-head"
        val attachmentDeletion = "backup-delete-choice-image-head"
        val records = listOf(
            revisionRecord(
                commentDeletion, "task_comment", commentId, jobId, deleted = 1L,
                payload = JSONObject().put("id", commentId).put("task_id", cardId).toString(),
            ),
            parentRecord(commentDeletion, commentHead),
            headRecord("task_comment", commentId, commentDeletion),
            revisionRecord(
                attachmentDeletion, "task_attachment", attachmentId, jobId, deleted = 1L,
                payload = JSONObject().put("id", attachmentId).put("task_id", cardId)
                    .put("comment_id", commentId).toString(),
            ),
            parentRecord(attachmentDeletion, attachmentHead),
            headRecord("task_attachment", attachmentId, attachmentDeletion),
            stagedRow("task_comment_events", mapOf(
                "id" to "backup-delete-choice-event",
                "task_id" to cardId,
                "comment_id" to commentId,
                "action" to "DELETED",
                "recorded_ms" to now + 1L,
            )),
        )
        val versionBefore = repository.snapshot().version
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
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
        assertTrue(conflicts.any { it["kind"] == "BACKUP_COMMENT_DELETION" })
        val deletionHeads = conflicts.filter { it["kind"] == "BACKUP_ENTITY_DELETION" }
        assertEquals(2, deletionHeads.size)
        assertTrue(deletionHeads.all { it["allowedChoices"] == listOf("KEEP_LOCAL") })
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(versionBefore, repository.snapshot().version)
        assertEquals(1L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM task_attachments WHERE id='$attachmentId'"))
        assertEquals(commentHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='task_comment' AND entity_id=?",
            arrayOf(commentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(attachmentHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='task_attachment' AND entity_id=?",
            arrayOf(attachmentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM task_comment_events WHERE id='backup-delete-choice-event'",
        ))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun sessionsOverlappingWithinOneBackupRequireExplicitOmission() {
        val firstSessionId = "backup-overlap-first-session"
        val secondSessionId = "backup-overlap-second-session"
        val records = listOf(
            stagedRow("sessions", mapOf(
                "id" to firstSessionId,
                "job_id" to jobId,
                "start_ms" to now,
                "end_ms" to now + 10_000L,
                "timezone" to "Etc/UTC",
                "offset_seconds" to 0,
                "origin" to "MANUAL",
            )),
            stagedRow("sessions", mapOf(
                "id" to secondSessionId,
                "job_id" to jobId,
                "start_ms" to now + 5_000L,
                "end_ms" to now + 15_000L,
                "timezone" to "Etc/UTC",
                "offset_seconds" to 0,
                "origin" to "MANUAL",
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.conflictCount)
        assertFalse(preview.applyReady)
        val conflict = requireNotNull(findConflict(started.importId, preview, "SESSION_OVERLAP"))
        assertEquals("BACKUP:$firstSessionId", conflict["localIdentifier"])
        assertEquals(listOf("KEEP_LOCAL", "OMIT_BACKUP_SESSION"), conflict["allowedChoices"])
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "OMIT_BACKUP_SESSION",
        )
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(1L, applied.insertedCount)
        assertEquals(1L, number("SELECT COUNT(*) FROM sessions WHERE id='$firstSessionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM sessions WHERE id='$secondSessionId'"))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun repeatingAnIdenticalBackupImportDoesNotChangeCommittedState() {
        val records = listOf(stagedRow("customers", mapOf(
            "id" to "repeat-import-customer",
            "name" to "Repeat Import Customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        )))
        val first = stageImport(records)
        val firstPreview = importStore.finish(database, first.importId)
        assertEquals(0L, firstPreview.conflictCount)
        val firstApplied = importStore.apply(
            database, first.importId, firstPreview.previewToken, firstPreview.localStateVersion,
        )
        assertEquals(1L, firstApplied.insertedCount)
        val committedAfterFirstImport = repository.snapshot()
        assertEquals(1L, number("SELECT COUNT(*) FROM customers WHERE id='repeat-import-customer'"))
        assertTrue(importStore.close(first.importId))

        val second = stageImport(records)
        val secondPreview = importStore.finish(database, second.importId)
        assertEquals(0L, secondPreview.conflictCount)
        assertEquals(1L, secondPreview.unchangedCount)
        val secondApplied = importStore.apply(
            database, second.importId, secondPreview.previewToken, secondPreview.localStateVersion,
        )

        assertEquals(0L, secondApplied.insertedCount)
        assertEquals(1L, secondApplied.skippedCount)
        assertEquals(committedAfterFirstImport, repository.snapshot())
        assertEquals(1L, number("SELECT COUNT(*) FROM customers WHERE id='repeat-import-customer'"))
        assertTrue(importStore.close(second.importId))
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
        val jobRevisionId = "backup-job-revision"
        val automationRevisionId = "backup-automation-revision"
        val siteRevisionId = "backup-site-revision"
        val customerPayload = JSONObject().apply {
            put("id", importedCustomerId)
            put("name", "Backup customer")
            put("email", "backup@example.invalid")
            put("phone", "")
            put("address", "")
            put("notes", "synthetic fixture")
            put("archived", 0)
        }.toString()
        val jobPayload = JSONObject().apply {
            put("id", importedJobId)
            put("name", "Backup job")
            put("archived", 0)
            put("archived_ms", JSONObject.NULL)
            put("color_argb", 4279724885L)
            put("billing_mode", "HOURLY")
            put("currency", "USD")
            put("hourly_rate_minor", JSONObject.NULL)
            put("customer_id", JSONObject.NULL)
            put("customer_email_override", JSONObject.NULL)
        }.toString()
        val automationPayload = JSONObject().apply {
            put("job_id", importedJobId)
            put("enabled", 1)
            put("auto_start", 1)
            put("automatic_departure", 1)
            put("grace_minutes", 30)
        }.toString()
        val sitePayload = JSONObject().apply {
            put("id", "backup-disabled-site")
            put("job_id", importedJobId)
            put("name", "Imported site")
            put("latitude", 12.5)
            put("longitude", -34.5)
            put("radius", 100.0)
            put("enabled", 1)
        }.toString()
        val records = listOf(
            headRecord("customer", importedCustomerId, revisionId),
            revisionRecord(revisionId, "customer", importedCustomerId, null, payload = customerPayload),
            headRecord("job", importedJobId, jobRevisionId),
            revisionRecord(jobRevisionId, "job", importedJobId, importedJobId, payload = jobPayload),
            headRecord("automation", importedJobId, automationRevisionId),
            revisionRecord(
                automationRevisionId, "automation", importedJobId, importedJobId, payload = automationPayload,
            ),
            headRecord("site", "backup-disabled-site", siteRevisionId),
            revisionRecord(
                siteRevisionId, "site", "backup-disabled-site", importedJobId, payload = sitePayload,
            ),
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
        listOf(
            "automation" to (importedJobId to automationRevisionId),
            "site" to ("backup-disabled-site" to siteRevisionId),
        ).forEach { (kind, entity) ->
            val normalizedRevision = database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
                arrayOf(kind, entity.first),
            ).use { assertTrue(it.moveToFirst()); it.getString(0) }
            assertNotEquals(entity.second, normalizedRevision)
            val revision = database.rawQuery(
                "SELECT payload,origin FROM entity_revisions WHERE id=?",
                arrayOf(normalizedRevision),
            ).use { assertTrue(it.moveToFirst()); it.getString(0) to it.getString(1) }
            assertEquals("BACKUP_RESTORE_NORMALIZE", revision.second)
            val payload = JSONObject(revision.first)
            assertEquals(0, payload.getInt("enabled"))
            if (kind == "automation") assertEquals(0, payload.getInt("auto_start"))
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(normalizedRevision, entity.second),
            ).use { it.moveToFirst() })
        }
        assertFailure("IMPORT_UNAVAILABLE") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
    }

    @Test
    fun previewRejectsRevisionedRowsWithoutMatchingHeads() {
        val started = stageImport(listOf(stagedRow("customers", mapOf(
            "id" to "missing-revision-head-customer",
            "name" to "Untracked customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))))

        assertFailure("INVALID_REVISION_GRAPH") {
            importStore.finish(database, started.importId)
        }
        assertTrue(importStore.close(started.importId))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM customers WHERE id='missing-revision-head-customer'",
        ))
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
    fun applyRejectsAndRollsBackWhenLocalRevisionHeadOwnershipIsInvalid() {
        val unrelatedRevision = RevisionStore.record(
            database,
            "customer",
            "synthetic-unrelated-head-owner",
            null,
            mapOf("id" to "synthetic-unrelated-head-owner"),
            now,
        )
        database.execSQL(
            "UPDATE entity_heads SET revision_id=? WHERE kind='job' AND entity_id=?",
            arrayOf(unrelatedRevision, jobId),
        )
        val started = stageImport(listOf(stagedRow("customers", mapOf(
            "id" to "must-rollback-on-invalid-resulting-graph",
            "name" to "Must not commit",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))))
        val preview = importStore.finish(database, started.importId)
        val versionBeforeApply = repository.snapshot().version

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(0L, number(
            "SELECT COUNT(*) FROM customers WHERE id='must-rollback-on-invalid-resulting-graph'",
        ))
        assertEquals(versionBeforeApply, repository.snapshot().version)
        assertTrue(importStore.close(started.importId))
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

    private fun readRowValues(table: String, keyColumn: String, keyValue: String): Map<String, Any?> {
        require(table.matches(Regex("[a-z_]+")) && keyColumn.matches(Regex("[a-z_]+")))
        return database.rawQuery(
            "SELECT * FROM $table WHERE $keyColumn=? LIMIT 1",
            arrayOf(keyValue),
        ).use { cursor ->
            assertTrue("Missing $table row", cursor.moveToFirst())
            cursor.columnNames.mapIndexed { index, column ->
                column to when (cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                    else -> cursor.getString(index)
                }
            }.toMap()
        }
    }

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

    private fun insertSyntheticRevision(
        id: String,
        kind: String,
        entityId: String,
        parentId: String?,
        payload: String = "{}",
    ) {
        database.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,?,?,NULL,?,0,?,'MUTATION')",
            arrayOf(id, kind, entityId, payload, now),
        )
        if (parentId != null) {
            database.execSQL(
                "INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)",
                arrayOf(id, parentId),
            )
        }
    }

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

    private fun blobRecord(
        table: String,
        values: Map<String, Any?>,
        column: String,
        sizeBytes: Int,
    ): Map<String, Any?> = mapOf(
        "type" to "row",
        "table" to table,
        "values" to values,
        "blobs" to listOf(mapOf(
            "id" to "a".repeat(43),
            "column" to column,
            "sizeBytes" to sizeBytes,
        )),
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

    private fun sessionWithoutLivePoint(commandPrefix: String): Pair<String, Long> {
        val (sessionId, originalPointTime) = startAndStopSessionWithPoint("$commandPrefix-original")
        val end = database.rawQuery("SELECT end_ms FROM sessions WHERE id=?", arrayOf(sessionId)).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }
        assertNull(repository.correctSession(
            "$commandPrefix-remove-original-point",
            repository.snapshot().version,
            SessionHistoryStore.Draft(
                sessionId, jobId, originalPointTime + 1L, end, "Etc/UTC", 0,
            ),
        ).conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE session_id='$sessionId'"))
        return sessionId to originalPointTime + 10L
    }

    private fun backupRangeAndUnparentedPointRecords(
        rangeId: String,
        rangeRevisionId: String,
        pointId: String,
        pointRevisionId: String,
        sessionId: String,
        measuredAt: Long,
    ): List<Map<String, Any?>> {
        val rangeStart = measuredAt
        val rangeEnd = measuredAt + 1L
        val range = mapOf<String, Any?>(
            "id" to rangeId,
            "job_id" to jobId,
            "start_ms" to rangeStart,
            "end_ms" to rangeEnd,
            "timezone_id" to "Etc/UTC",
            "deleted_ms" to now,
            "revision_id" to rangeRevisionId,
        )
        return listOf(
            revisionRecord(
                rangeRevisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
                payload = JSONObject().put("id", rangeId).put("job_id", jobId)
                    .put("start_ms", rangeStart).put("end_ms", rangeEnd)
                    .put("timezone_id", "Etc/UTC").toString(),
            ),
            headRecord("location_deletion_range", rangeId, rangeRevisionId),
            stagedRow("location_deletion_ranges", range),
            revisionRecord(
                pointRevisionId, "location", pointId, jobId,
                payload = JSONObject().put("id", pointId).put("session_id", sessionId).toString(),
            ),
            headRecord("location", pointId, pointRevisionId),
            stagedRow("location_points", mapOf(
                "id" to pointId,
                "session_id" to sessionId,
                "measured_ms" to measuredAt,
                "received_ms" to measuredAt,
                "latitude" to 12.0,
                "longitude" to -34.0,
                "accuracy" to 5.0,
            )),
        )
    }

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

    private fun resolveSafeConflictChoices(
        importId: String,
        preview: BackupImportStore.Preview,
        conflicts: List<Map<String, Any?>>,
    ) {
        conflicts.forEach { conflict ->
            @Suppress("UNCHECKED_CAST")
            val choices = conflict["allowedChoices"] as? List<String> ?: emptyList()
            val preferred = when (conflict["kind"]) {
                "JOB_DELETION_TOMBSTONE", "ENTITY_DELETION_TOMBSTONE" -> "PRESERVE_LOCAL_DELETION"
                "ACTIVE_JOB_DELETION", "BACKUP_ENTITY_DELETION" -> "KEEP_LOCAL"
                "GPS_TOMBSTONE" -> "PRESERVE_LOCAL_DELETION"
                "GPS_DELETION_COVERAGE" -> "PRESERVE_DELETION"
                "DEPENDENT_ON_CONFLICT" -> "OMIT_BACKUP_RECORD"
                else -> conflict["defaultChoice"] as? String
            }
            val choice = preferred?.takeIf(choices::contains)
                ?: choices.firstOrNull { it == "KEEP_LOCAL" }
                ?: choices.firstOrNull { it == "OMIT_BACKUP_RECORD" }
                ?: choices.firstOrNull()
            assertNotNull("No explicit safe choice for ${conflict["kind"]} / ${conflict["table"]}", choice)
            importStore.resolveConflict(
                database,
                importId,
                preview.previewToken,
                preview.localStateVersion,
                conflict["conflictId"] as String,
                requireNotNull(choice),
            )
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
