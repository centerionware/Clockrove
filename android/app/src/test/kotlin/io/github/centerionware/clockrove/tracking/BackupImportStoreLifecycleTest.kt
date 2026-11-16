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
class BackupImportStoreLifecycleTest : BackupImportStoreTestBase() {
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
    fun databaseCloseFailureStillAttemptsToDeletePrivateImportArtifacts() {
        var openedStageDatabase: SQLiteDatabase? = null
        val closeFailingStore = BackupImportStore(
            app,
            catalogProvider = exportStore::importCatalog,
            closeDatabase = { stagedDatabase ->
                openedStageDatabase = stagedDatabase
                throw IllegalStateException("synthetic database close failure")
            },
        )
        val started = closeFailingStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "9".repeat(64),
            0,
            0,
            0,
        )
        val directory = File(app.noBackupFilesDir, "clockrove-backup-imports")
        val stageDb = File(directory, "clockrove-import-${started.importId}.db")
        val media = File(directory, "clockrove-import-${started.importId}-media.bin")
        assertTrue(stageDb.exists())
        assertTrue(media.exists())

        assertFailure("CLEANUP_FAILED") { closeFailingStore.close(started.importId) }

        assertFalse(stageDb.exists())
        assertFalse(media.exists())
        assertFailure("IMPORT_UNAVAILABLE") {
            closeFailingStore.finish(database, started.importId)
        }
        openedStageDatabase?.close()
    }
}
