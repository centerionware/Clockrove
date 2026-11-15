package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
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
        val exportedRecords = readAll(source.snapshotId)
        assertTrue(exportedRecords.any { it["table"] == "location_deletion_ranges" })
        assertTrue(exportedRecords.any {
            it["table"] == "entity_revisions" && values(it)["kind"] == "location_deletion_range"
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
        assertFalse(preview.applyReady)
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
        assertFalse(preview.applyReady)
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
        assertEquals("OMIT_OPEN_SESSION", openSession["defaultChoice"])
        assertEquals(listOf("OMIT_OPEN_SESSION", "CLOSE_OPEN_SESSION"), openSession["allowedChoices"])
        assertEquals(runningBefore, repository.snapshot().running)
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
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
