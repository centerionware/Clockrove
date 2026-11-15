package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import java.io.File
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
        assertEquals(runningBefore, repository.snapshot().running)
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

    private fun readAll(snapshotId: String): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(snapshotId, cursor, BackupExportStore.MAX_PAGE_BYTES)
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
