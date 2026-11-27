package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import io.flutter.plugin.common.StandardMethodCodec
import java.io.File
import java.security.MessageDigest
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
class BackupExportStoreTest {
    private class ManualScheduler(private val elapsed: () -> Long) : BackupScheduler {
        private data class Entry(val dueAt: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()
        var failNextSchedule = false
        var scheduleCalls = 0
            private set

        override fun schedule(delayMillis: Long, action: () -> Unit): BackupScheduledTask {
            scheduleCalls++
            if (failNextSchedule) {
                failNextSchedule = false
                throw IllegalStateException("Synthetic scheduler failure")
            }
            val entry = Entry(elapsed() + delayMillis, action)
            entries.add(entry)
            return BackupScheduledTask { entry.cancelled = true }
        }

        fun runNextTaskEarly() {
            val next = entries.firstOrNull { !it.cancelled } ?: error("No pending scheduled task")
            next.cancelled = true
            next.action()
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
    private var now = 1_793_494_800_000L
    private var elapsed = 10_000L
    private lateinit var jobId: String

    @Before
    fun setUp() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now })
        jobId = repository.createJob("Synthetic backup job")
        database = openDatabase()
        exportStore = BackupExportStore(
            app,
            wallClock = { now },
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
        )
    }

    @After
    fun tearDown() {
        try {
            exportStore.closeAll()
        } catch (_: Exception) {
        }
        if (::database.isInitialized) database.close()
        if (::repository.isInitialized) repository.close()
    }

    @Test
    fun detachedSnapshotKeepsItsOriginalStateAndLeavesAnOpenSessionRunning() {
        assertEquals(
            281_474_976_645_120L,
            exportStore.info(database).maxSnapshotBytes,
        )
        val beforeStart = repository.snapshot().version
        assertNull(repository.execute(TimerRepository.Command(
            "start-synthetic-session",
            beforeStart,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val runningBefore = requireNotNull(repository.snapshot().running)
        val versionBefore = repository.snapshot().version

        val started = exportStore.start(database)

        assertEquals(versionBefore, started.stateVersion)
        assertEquals(1L, started.openSessionCount)
        assertEquals(runningBefore, repository.snapshot().running)
        assertEquals(versionBefore, repository.snapshot().version)

        assertNull(repository.saveJobSettings(
            "rename-after-snapshot",
            repository.snapshot().version,
            repository.jobDetail(jobId).job.settings.copy(name = "Changed after snapshot"),
        ).conflict)

        val records = readAll(started.snapshotId)
        val job = records.single { it["table"] == "jobs" && values(it)["id"] == jobId }
        assertEquals("Synthetic backup job", values(job)["name"])
        val session = records.single { it["table"] == "sessions" && values(it)["id"] == runningBefore.id }
        assertNull(values(session)["end_ms"])
        assertEquals(runningBefore.copy(jobName = "Changed after snapshot"), repository.snapshot().running)
        assertTrue(exportStore.close(started.snapshotId))
    }

    @Test
    fun rejectsRevisionHeadsWhoseOwnerDoesNotMatchBeforeSnapshotCopy() {
        val revisionId = RevisionStore.record(
            database,
            "export-test",
            "revision-owner",
            null,
            mapOf("id" to "revision-owner"),
            now,
        )
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES (?,?,?)",
            arrayOf("export-test", "different-owner", revisionId),
        )

        val failure = try {
            exportStore.start(database)
            throw AssertionError(
                "Expected the malformed revision head to be rejected.",
            )
        } catch (failure: BackupExportStore.Failure) {
            failure
        }

        assertEquals("INVALID_REVISION_GRAPH", failure.code)
    }

    @Test
    fun rejectsRevisionParentsFromAnotherEntityBeforeSnapshotCopy() {
        val childRevision = RevisionStore.record(
            database,
            "export-test",
            "child-owner",
            null,
            mapOf("id" to "child-owner"),
            now,
        )
        val parentRevision = RevisionStore.record(
            database,
            "export-test",
            "parent-owner",
            null,
            mapOf("id" to "parent-owner"),
            now,
        )
        database.execSQL(
            "INSERT INTO revision_parents(revision_id,parent_id) VALUES (?,?)",
            arrayOf(childRevision, parentRevision),
        )

        val failure = try {
            exportStore.start(database)
            throw AssertionError(
                "Expected the cross-entity parent edge to be rejected.",
            )
        } catch (failure: BackupExportStore.Failure) {
            failure
        }

        assertEquals("INVALID_REVISION_GRAPH", failure.code)
    }

    @Test
    fun rejectsRevisionCyclesBeforeSnapshotCopy() {
        val firstRevision = RevisionStore.record(
            database,
            "export-test",
            "same-owner",
            null,
            mapOf("id" to "same-owner", "revision" to 1),
            now,
        )
        val secondRevision = RevisionStore.record(
            database,
            "export-test",
            "same-owner",
            null,
            mapOf("id" to "same-owner", "revision" to 2),
            now + 1,
        )
        database.execSQL(
            "INSERT INTO revision_parents(revision_id,parent_id) VALUES (?,?)",
            arrayOf(firstRevision, secondRevision),
        )

        val failure = try {
            exportStore.start(database)
            throw AssertionError("Expected the revision cycle to be rejected.")
        } catch (failure: BackupExportStore.Failure) {
            failure
        }

        assertEquals("INVALID_REVISION_GRAPH", failure.code)
        val snapshotDirectory = File(app.noBackupFilesDir, "clockrove-backup-snapshots")
        assertFalse(
            snapshotDirectory.listFiles().orEmpty().any { it.name.contains("-graph.sqlite") },
        )
    }

    @Test
    fun schema17ExportPreservesDeletionCoverageAndCoordinateFreeCausalHistory() {
        val beforeStart = repository.snapshot().version
        assertNull(repository.execute(TimerRepository.Command(
            "start-synthetic-gps-export-session",
            beforeStart,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val sessionId = requireNotNull(repository.snapshot().running).id
        val pointId = "synthetic-gps-export-point"
        val measuredAt = now + 1_000L
        assertEquals(
            TimerRepository.SampleResult.RECORDED,
            repository.recordLocation(TimerRepository.LocationPoint(
                pointId, sessionId, measuredAt, measuredAt, 12.0, -34.0, 5.0,
            )),
        )
        assertNull(repository.execute(TimerRepository.Command(
            "stop-synthetic-gps-export-session",
            repository.snapshot().version,
            TimerRepository.Action.STOP,
            null,
            measuredAt + 1_000L,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val range = GpsDeletionStore.Range(jobId, measuredAt, measuredAt + 1L, "Etc/UTC")
        assertNull(repository.deleteLocationRange(
            "delete-synthetic-gps-export-range", repository.snapshot().version, range,
        ).conflict)

        val info = exportStore.info(database)
        assertEquals(17, info.schemaVersion)
        assertTrue(info.portableTables.contains("location_deletion_ranges"))
        val started = exportStore.start(database)
        assertEquals(17, started.schemaVersion)
        val records = readAll(started.snapshotId)
        val rangeRecord = records.single {
            it["table"] == "location_deletion_ranges" && values(it)["job_id"] == jobId
        }
        val rangeValues = values(rangeRecord)
        assertEquals(measuredAt, rangeValues["start_ms"])
        assertEquals(measuredAt + 1L, rangeValues["end_ms"])
        val rangeRevisionId = rangeValues["revision_id"] as String
        val rangeRevision = records.single {
            it["table"] == "entity_revisions" && values(it)["id"] == rangeRevisionId
        }
        val rangePayload = JSONObject(values(rangeRevision)["payload"] as String)
        assertEquals(setOf("id", "job_id", "start_ms", "end_ms", "timezone_id"),
            rangePayload.keys().asSequence().toSet())
        assertFalse(rangePayload.has("latitude"))
        assertFalse(rangePayload.has("longitude"))

        val pointTombstone = records.single {
            it["table"] == "entity_revisions" && values(it)["kind"] == "location" &&
                values(it)["entity_id"] == pointId &&
                (values(it)["deleted"] as Number).toInt() == 1
        }
        val tombstoneValues = values(pointTombstone)
        val tombstonePayload = JSONObject(tombstoneValues["payload"] as String)
        assertEquals(setOf("id", "session_id"), tombstonePayload.keys().asSequence().toSet())
        assertFalse(tombstonePayload.has("latitude"))
        assertFalse(tombstonePayload.has("longitude"))
        val parentRows = records.filter {
            it["table"] == "revision_parents" && values(it)["revision_id"] == tombstoneValues["id"]
        }
        val parentIds = parentRows.map { values(it)["parent_id"] as String }.toSet()
        assertEquals(2, parentIds.size)
        assertTrue(rangeRevisionId in parentIds)
        assertTrue(records.any {
            it["table"] == "entity_revisions" && (values(it)["id"] as String) in parentIds &&
                values(it)["entity_id"] == pointId &&
                (values(it)["deleted"] as Number).toInt() == 0
        })
        assertTrue(exportStore.close(started.snapshotId))
    }

    @Test
    fun exportsReceiptPdfAndInvoiceRetainedAfterJobPurgeAsBoundedMediaChunks() {
        val invoicePdf = ByteArray(2 * 1024 * 1024 + 37) { (it % 251).toByte() }
        val receiptPdf = ByteArray(350_000) { ((it * 3) % 251).toByte() }
        assertNull(repository.saveJobSettings(
            "task-billing",
            repository.snapshot().version,
            repository.jobDetail(jobId).job.settings.copy(billingMode = "TASK"),
        ).conflict)
        val board = repository.boardSnapshot(jobId).board
        assertNull(repository.saveCard(
            "invoice-task",
            repository.snapshot().version,
            BoardStore.Draft(
                "invoice-task",
                jobId,
                board.columns.last().id,
                "Synthetic invoice task",
                priceMinor = java.math.BigInteger.valueOf(1_000),
                currency = board.currency,
            ),
        ).conflict)
        assertNull(repository.prepareInvoice(
            "prepare-retained-invoice",
            repository.snapshot().version,
            "retained-invoice",
            ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(jobId)),
        ).conflict)
        assertNull(repository.recordInvoicePayment(
            "record-retained-receipt",
            repository.snapshot().version,
            InvoicePaymentStore.Draft(
                "receipt-payment",
                "retained-invoice",
                "100",
                board.currency,
                now,
                "Etc/UTC",
                "Cash",
                "Synthetic receipt reference",
                "Synthetic receipt note",
            ),
        ).conflict)
        database.setForeignKeyConstraintsEnabled(true)
        database.beginTransaction()
        try {
            database.execSQL("UPDATE invoices SET pdf=? WHERE id=?", arrayOf<Any?>(invoicePdf, "retained-invoice"))
            database.execSQL("UPDATE invoice_copies SET pdf=? WHERE id=?", arrayOf<Any?>(receiptPdf, "receipt-receipt-payment"))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        assertNull(repository.archiveJob("archive-for-purge", repository.snapshot().version, jobId).conflict)
        database.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf(jobId))
        now += JobLifecycleStore.retentionMillis + 100
        assertNull(repository.deleteJob("purge-after-retention", repository.snapshot().version, jobId).conflict)

        val tables = exportStore.info(database).portableTables
        assertTrue(tables.contains("invoices"))
        assertFalse(tables.contains("invoices_retained"))
        val started = exportStore.start(database)
        assertEquals(2L, started.mediaCount)
        assertEquals((invoicePdf.size + receiptPdf.size).toLong(), started.mediaBytes)
        val records = readAll(started.snapshotId)
        assertTrue(records.none { it["table"] == "jobs" && values(it)["id"] == jobId })

        val invoice = records.single { it["table"] == "invoices" && values(it)["id"] == "retained-invoice" }
        val receipt = records.single { it["table"] == "invoice_copies" && values(it)["id"] == "receipt-receipt-payment" }
        assertEquals("RECEIPT", values(receipt)["kind"])
        assertArrayEquals(invoicePdf, readBlob(started.snapshotId, blob(invoice, "pdf")))
        assertArrayEquals(receiptPdf, readBlob(started.snapshotId, blob(receipt, "pdf")))
        assertTrue(exportStore.close(started.snapshotId))
    }

    @Test
    fun invalidCursorsForeignBlobIdsAndClosedHandlesAreRejected() {
        repeat(24) { repository.createJob("Synthetic cursor job $it") }
        val started = exportStore.start(database)
        val page = exportStore.readPage(started.snapshotId, null, 8 * 1024)
        assertFalse(page.done)
        val cursor = requireNotNull(page.nextCursor)
        val forgedBytes = Base64.decode(cursor, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        forgedBytes[forgedBytes.lastIndex] = (forgedBytes.last().toInt() xor 1).toByte()
        val forged = Base64.encodeToString(forgedBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        assertFailure("INVALID_CURSOR") {
            exportStore.readPage(started.snapshotId, forged, BackupExportStore.MIN_PAGE_BYTES)
        }
        assertFailure("SNAPSHOT_UNAVAILABLE") {
            exportStore.readPage("00000000-0000-0000-0000-000000000000", null, BackupExportStore.MAX_PAGE_BYTES)
        }
        assertFailure("INVALID_BLOB") {
            exportStore.readBlob(started.snapshotId, "foreign-blob-id", 0, null)
        }
        assertTrue(exportStore.close(started.snapshotId))
        assertFailure("SNAPSHOT_UNAVAILABLE") {
            exportStore.readPage(started.snapshotId, null, BackupExportStore.MAX_PAGE_BYTES)
        }
        assertFalse(exportStore.close(started.snapshotId))
    }

    @Test
    fun snapshotBlobDescriptorsCarrySha256ForEmptyShortAndChunkBoundaryMedia() {
        val chunkBoundaryBytes = ByteArray(64 * 1024 + 13) { (it * 31).toByte() }
        val fixtures = listOf(
            "digest-empty" to ByteArray(0),
            "digest-short" to "abc".toByteArray(Charsets.UTF_8),
            "digest-boundary" to chunkBoundaryBytes,
        )
        fixtures.forEach { (id, bytes) ->
            database.execSQL(
                "INSERT INTO logo_assets(id,mime,width,height,content_hash,bytes) VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(id, "image/png", 1, 1, "synthetic-$id", bytes),
            )
        }

        val started = exportStore.start(database)
        val records = readAll(started.snapshotId, BackupExportStore.MIN_PAGE_BYTES)
        val byId = records.filter { it["table"] == "logo_assets" }
            .associateBy { values(it)["id"] as String }
        assertEquals(fixtures.size, byId.size)
        fixtures.forEach { (id, bytes) ->
            val record = requireNotNull(byId[id])
            val descriptor = (record["blobs"] as List<*>).single() as Map<*, *>
            val expectedDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val expectedBase64 = Base64.encodeToString(expectedDigest, Base64.NO_WRAP)
            assertEquals(expectedBase64, descriptor["sha256"])
            assertArrayEquals(bytes, readBlob(started.snapshotId, descriptor["id"] as String))
        }
        assertEquals(
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
            ((byId.getValue("digest-empty")["blobs"] as List<*>).single() as Map<*, *>)["sha256"],
        )
        assertEquals(
            "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
            ((byId.getValue("digest-short")["blobs"] as List<*>).single() as Map<*, *>)["sha256"],
        )
        assertTrue(exportStore.close(started.snapshotId))
    }

    @Test
    fun mediaDigestDescriptorsAreIncludedInEncodedPageLimitAndSplitPages() {
        val expectedMediaRows = 18
        repeat(expectedMediaRows) { index ->
            val id = "digest-page-${index.toString().padStart(3, '0')}"
            database.execSQL(
                "INSERT INTO logo_assets(id,mime,width,height,content_hash,bytes) VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(id, "image/png", 1, 1, "synthetic-$id", byteArrayOf(index.toByte())),
            )
        }

        val started = exportStore.start(database)
        val maxPageBytes = BackupExportStore.MIN_PAGE_BYTES
        val pages = mutableListOf<BackupExportStore.Page>()
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(started.snapshotId, cursor, maxPageBytes)
            pages.add(page)
            cursor = page.nextCursor
            done = page.done
        }

        val mediaPages = pages.filter { page ->
            page.records.any { it["table"] == "logo_assets" }
        }
        assertTrue("media descriptors should force a page boundary", mediaPages.size > 1)
        assertEquals(
            expectedMediaRows,
            pages.sumOf { page -> page.records.count { it["table"] == "logo_assets" } },
        )
        pages.forEach { page ->
            page.records.filter { it["table"] == "logo_assets" }.forEach { record ->
                val descriptor = (record["blobs"] as List<*>).single() as Map<*, *>
                assertEquals(44, (descriptor["sha256"] as String).length)
            }
            val wirePage = mapOf(
                "snapshotId" to page.snapshotId,
                "nextCursor" to page.nextCursor,
                "done" to page.done,
                "records" to page.records,
            )
            val encodedBytes = StandardMethodCodec.INSTANCE
                .encodeSuccessEnvelope(wirePage)
                .position()
            assertTrue(
                "encoded page size $encodedBytes exceeded requested $maxPageBytes bytes",
                encodedBytes <= maxPageBytes,
            )
        }
        assertTrue(exportStore.close(started.snapshotId))
    }

    @Test
    fun expiryUsesMonotonicTimeEvenIfWallClockMovesBackwards() {
        val scheduler = ManualScheduler { elapsed }
        val shortLived = BackupExportStore(
            app,
            wallClock = { now },
            elapsedClock = { elapsed },
            idleTtlMillis = 20L,
            scheduler = scheduler,
        )
        val started = shortLived.start(database)
        now = 1L
        elapsed += 21L
        assertFailure("SNAPSHOT_EXPIRED") {
            shortLived.readPage(started.snapshotId, null, BackupExportStore.MAX_PAGE_BYTES)
        }
        assertFailure("SNAPSHOT_UNAVAILABLE") {
            shortLived.readPage(started.snapshotId, null, BackupExportStore.MAX_PAGE_BYTES)
        }
    }

    @Test
    fun scheduledExpiryRemovesStagingWithoutAnotherChannelCall() {
        val scheduler = ManualScheduler { elapsed }
        val scheduled = BackupExportStore(
            app,
            wallClock = { now },
            elapsedClock = { elapsed },
            idleTtlMillis = 50L,
            scheduler = scheduler,
        )
        val started = scheduled.start(database)
        val rowsFile = File(app.noBackupFilesDir, "clockrove-backup-snapshots/clockrove-backup-" + started.snapshotId + "-rows.bin")
        assertTrue(rowsFile.exists())
        now = 1L
        elapsed += 51L
        scheduler.runDueTasks()
        assertFalse(rowsFile.exists())
        assertFailure("SNAPSHOT_UNAVAILABLE") {
            scheduled.readPage(started.snapshotId, null, BackupExportStore.MAX_PAGE_BYTES)
        }
    }

    @Test
    fun failedExpiryRescheduleClosesHandleAndDefersFailedFileCleanup() {
        var failingPath: String? = null
        var failuresRemaining = 1
        val scheduler = ManualScheduler { elapsed }
        val scheduled = BackupExportStore(
            app,
            wallClock = { now },
            elapsedClock = { elapsed },
            idleTtlMillis = 50L,
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
        val started = scheduled.start(database)
        val snapshotDirectory = File(app.noBackupFilesDir, "clockrove-backup-snapshots")
        val rowsFile = File(snapshotDirectory, "clockrove-backup-" + started.snapshotId + "-rows.bin")
        val mediaFile = File(snapshotDirectory, "clockrove-backup-" + started.snapshotId + "-media.bin")
        assertTrue(rowsFile.exists())
        assertTrue(mediaFile.exists())
        failingPath = mediaFile.absolutePath

        scheduler.failNextSchedule = true
        assertFailure("STORAGE_UNAVAILABLE") { scheduler.runNextTaskEarly() }

        assertEquals(2, scheduler.scheduleCalls)
        assertFalse(rowsFile.exists())
        assertTrue(mediaFile.exists())
        assertFailure("SNAPSHOT_UNAVAILABLE") {
            scheduled.readPage(started.snapshotId, null, BackupExportStore.MAX_PAGE_BYTES)
        }
        scheduled.info(database)
        assertFalse(mediaFile.exists())
        assertEquals(2, scheduler.scheduleCalls)
    }

    @Test
    fun cleanupFailureClosesTheHandleAndRetriesBothStagingFiles() {
        var failingPath: String? = null
        var failuresRemaining = 1
        val scheduler = ManualScheduler { elapsed }
        val cleanupStore = BackupExportStore(
            app,
            wallClock = { now },
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
        val started = cleanupStore.start(database)
        val rowsFile = File(app.noBackupFilesDir, "clockrove-backup-snapshots/clockrove-backup-" + started.snapshotId + "-rows.bin")
        val mediaFile = File(app.noBackupFilesDir, "clockrove-backup-snapshots/clockrove-backup-" + started.snapshotId + "-media.bin")
        failingPath = mediaFile.absolutePath

        assertFailure("CLEANUP_FAILED") { cleanupStore.close(started.snapshotId) }
        assertFalse(rowsFile.exists())
        assertTrue(mediaFile.exists())
        assertFailure("SNAPSHOT_UNAVAILABLE") {
            cleanupStore.readPage(started.snapshotId, null, BackupExportStore.MAX_PAGE_BYTES)
        }
        assertTrue(cleanupStore.close(started.snapshotId))
        assertFalse(rowsFile.exists())
        assertFalse(mediaFile.exists())
    }

    @Test
    fun unavailableBackupStagingDoesNotDisableManualTimekeeping() {
        exportStore.closeAll()
        database.close()
        repository.close()
        val stagingPath = File(app.noBackupFilesDir, "clockrove-backup-snapshots")
        stagingPath.deleteRecursively()
        assertTrue(stagingPath.createNewFile())
        repository = TimerRepository(app, wallClock = { now })
        jobId = repository.createJob("Synthetic job with unavailable backup storage")

        assertFailure("STORAGE_UNAVAILABLE") { repository.getBackupSnapshotInfo() }
        val beforeStart = repository.snapshot().version
        assertNull(repository.execute(TimerRepository.Command(
            "manual-start-without-backup-storage",
            beforeStart,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val beforeStop = repository.snapshot().version
        assertNull(repository.execute(TimerRepository.Command(
            "manual-stop-without-backup-storage",
            beforeStop,
            TimerRepository.Action.STOP,
            null,
            now + 1_000,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)

        repository.close()
        assertTrue(stagingPath.delete())
    }

    private fun readAll(
        snapshotId: String,
        maxPageBytes: Int = BackupExportStore.MAX_PAGE_BYTES,
    ): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(snapshotId, cursor, maxPageBytes)
            result.addAll(page.records)
            cursor = page.nextCursor
            done = page.done
        }
        return result
    }

    private fun readBlob(snapshotId: String, blobId: String): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        var offset = 0L
        var done = false
        while (!done) {
            val chunk = exportStore.readBlob(snapshotId, blobId, offset, BackupExportStore.MAX_BLOB_CHUNK_BYTES)
            assertEquals(offset, chunk.offset)
            assertTrue(chunk.bytes.size <= BackupExportStore.MAX_BLOB_CHUNK_BYTES)
            result.write(chunk.bytes)
            offset = chunk.nextOffset
            done = chunk.done
        }
        return result.toByteArray()
    }

    private fun blob(record: Map<String, Any?>, column: String): String {
        val blobs = record["blobs"] as List<*>
        val descriptor = blobs.map { it as Map<*, *> }.single { it["column"] == column }
        return descriptor["id"] as String
    }

    private fun values(record: Map<String, Any?>): Map<*, *> = record["values"] as Map<*, *>

    private fun assertFailure(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected backup failure " + code)
        } catch (failure: BackupExportStore.Failure) {
            assertEquals(code, failure.code)
        }
    }

    private fun openDatabase(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        app.getDatabasePath("clockrove.db").path,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    )
}
