package io.github.centerionware.clockrove.backup

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackupDocumentStoreTest {
    private class ManualScheduler(private val elapsed: () -> Long) : BackupFileScheduler {
        private data class Entry(
            val dueAt: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val entries = mutableListOf<Entry>()
        var failNextSchedule = false

        override fun schedule(delayMillis: Long, action: () -> Unit): BackupFileScheduledTask {
            if (failNextSchedule) {
                failNextSchedule = false
                throw IllegalStateException("synthetic scheduler failure")
            }
            val entry = Entry(elapsed() + delayMillis, action)
            entries.add(entry)
            return BackupFileScheduledTask { entry.cancelled = true }
        }

        fun runDueTasks() {
            while (true) {
                val next = entries.filter { !it.cancelled && it.dueAt <= elapsed() }
                    .minByOrNull { it.dueAt } ?: return
                next.cancelled = true
                next.action()
            }
        }

        fun runNextTaskEarly() {
            val next = entries.filter { !it.cancelled }.minByOrNull { it.dueAt } ?: return
            next.cancelled = true
            next.action()
        }
    }

    private class CapturingOutputStream : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        var closed = false
            private set

        override fun write(value: Int) = bytes.write(value)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytes.write(buffer, offset, length)
        }

        override fun close() {
            closed = true
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private var elapsed = 10_000L
    private lateinit var scheduler: ManualScheduler
    private lateinit var output: CapturingOutputStream
    private lateinit var deletedUris: MutableList<Uri>
    private lateinit var store: BackupDocumentStore

    @Before
    fun setUp() {
        elapsed = 10_000L
        scheduler = ManualScheduler { elapsed }
        output = CapturingOutputStream()
        deletedUris = mutableListOf()
        store = BackupDocumentStore(
            app,
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
            scheduler = scheduler,
            openOutputStream = { output },
            deleteDocument = { deletedUris.add(it) },
        )
    }

    @Test
    fun chunksMustBeSequentialAndSuccessfulFinishIsIdempotentAndNeverDeleted() {
        val uri = Uri.parse("content://synthetic.provider/backup/one")
        val id = store.begin(uri, 5L)

        assertEquals(2L, store.append(id, 0L, byteArrayOf(1, 2)))
        assertFailure("INVALID_DOCUMENT_OFFSET") {
            store.append(id, 1L, byteArrayOf(3))
        }
        assertEquals(5L, store.append(id, 2L, byteArrayOf(3, 4, 5)))

        assertTrue(store.reserveFinish(id) == null)
        assertFailure("DOCUMENT_ACTION_PENDING") {
            store.append(id, 5L, byteArrayOf(6))
        }
        assertTrue(store.finishReserved(id))
        assertTrue(output.closed)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), output.toByteArray())
        assertTrue("finish retry must report the already-saved document", store.finish(id))
        assertTrue("late cancel must not delete the saved document", store.cancel(id))
        assertTrue(deletedUris.isEmpty())
        assertFalse(store.hasPendingAction())
    }

    @Test
    fun incompleteCancellationAndIdleExpiryDeleteTheirOwnDocuments() {
        val cancelledUri = Uri.parse("content://synthetic.provider/backup/cancelled")
        val cancelledId = store.begin(cancelledUri, 4L)
        assertTrue(store.cancel(cancelledId))
        assertTrue(store.cancel(cancelledId))
        assertEquals(listOf(cancelledUri), deletedUris)
        assertFalse(store.finish(cancelledId))

        val expiredUri = Uri.parse("content://synthetic.provider/backup/expired")
        store.begin(expiredUri, 4L)
        elapsed += 60_001L
        scheduler.runDueTasks()

        assertEquals(listOf(cancelledUri, expiredUri), deletedUris)
        assertFalse(store.hasPendingAction())
    }

    @Test
    fun failedEarlyExpiryRescheduleDeletesThePrivateStageAndDestination() {
        val uri = Uri.parse("content://synthetic.provider/backup/reschedule-failure")
        val id = store.begin(uri, 4L)
        val stagingDirectory = File(app.noBackupFilesDir, "clockrove-backup-document-staging")
        assertEquals(1, stagingDirectory.listFiles().orEmpty().count { it.name.startsWith("encrypted-") })

        elapsed += 10_000L
        scheduler.failNextSchedule = true
        scheduler.runNextTaskEarly()

        assertFalse(store.hasPendingAction())
        assertTrue(stagingDirectory.listFiles().orEmpty().none { it.name.startsWith("encrypted-") })
        assertEquals(listOf(uri), deletedUris)
        assertFalse("finish retry must report the terminal cancelled stage", store.finish(id))
        assertTrue("cancel retry must remain idempotent", store.cancel(id))
        assertEquals(listOf(uri), deletedUris)
    }

    @Test
    fun incompleteFinishDeletesTheSelectedDocument() {
        val uri = Uri.parse("content://synthetic.provider/backup/incomplete")
        val id = store.begin(uri, 3L)
        store.append(id, 0L, byteArrayOf(1, 2))

        assertFailure("INCOMPLETE_DOCUMENT") { store.finish(id) }
        assertEquals(listOf(uri), deletedUris)
        assertFalse(store.hasPendingAction())
    }

    @Test
    fun archiveAndChunkSizesAreBounded() {
        assertEquals(281_582_350_827_640L, BackupDocumentStore.MAX_ENCRYPTED_BYTES)
        val uri = Uri.parse("content://synthetic.provider/backup/too-large")
        try {
            store.begin(uri, BackupDocumentStore.MAX_ENCRYPTED_BYTES + 1L)
            fail("Expected oversize encrypted archive rejection")
        } catch (expected: IllegalArgumentException) {
            assertEquals("INVALID_DOCUMENT_SIZE", expected.message)
        }
        assertTrue(deletedUris.isEmpty())

        val id = store.begin(uri, BackupDocumentStore.MAX_CHUNK_BYTES.toLong() + 1L)
        assertFailure("INVALID_DOCUMENT_CHUNK") {
            store.append(id, 0L, ByteArray(BackupDocumentStore.MAX_CHUNK_BYTES + 1))
        }
        assertTrue(store.cancel(id))
        assertEquals(listOf(uri), deletedUris)
    }

    @Test
    fun cancellationDuringFinishDeletesOnlyTheIncompleteDocument() {
        val uri = Uri.parse("content://synthetic.provider/backup/cancel-during-save")
        lateinit var id: String
        var cancelRequested = false
        val cancellingOutput = object : OutputStream() {
            override fun write(value: Int) {
                if (!cancelRequested) {
                    cancelRequested = true
                    assertTrue(store.cancel(id))
                }
            }
        }
        store = BackupDocumentStore(
            app,
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
            scheduler = scheduler,
            openOutputStream = { cancellingOutput },
            deleteDocument = { deletedUris.add(it) },
        )
        id = store.begin(uri, 2L)
        store.append(id, 0L, byteArrayOf(1, 2))

        assertFalse(store.finish(id))
        assertTrue(cancelRequested)
        assertEquals(listOf(uri), deletedUris)
        assertTrue(store.cancel(id))
        assertFalse(store.finish(id))
    }

    @Test
    fun providerWriteFailureDeletesTheSelectedDocument() {
        val uri = Uri.parse("content://synthetic.provider/backup/write-failure")
        val failingOutput = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("synthetic provider failure")
            }
        }
        store = BackupDocumentStore(
            app,
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
            scheduler = scheduler,
            openOutputStream = { failingOutput },
            deleteDocument = { deletedUris.add(it) },
        )
        val id = store.begin(uri, 2L)
        store.append(id, 0L, byteArrayOf(1, 2))

        assertFailure("SAVE_UNAVAILABLE") { store.finish(id) }
        assertEquals(listOf(uri), deletedUris)
        assertFalse(store.hasPendingAction())
    }

    @Test
    fun providerCloseFailureDoesNotReportACompletedSave() {
        val uri = Uri.parse("content://synthetic.provider/backup/close-failure")
        val failingClose = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() {
                throw IOException("synthetic close failure")
            }
        }
        store = BackupDocumentStore(
            app,
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
            scheduler = scheduler,
            openOutputStream = { failingClose },
            deleteDocument = { deletedUris.add(it) },
        )
        val id = store.begin(uri, 1L)
        store.append(id, 0L, byteArrayOf(9))

        assertFailure("SAVE_UNAVAILABLE") { store.finish(id) }
        assertEquals(listOf(uri), deletedUris)
        assertFalse(store.hasPendingAction())
    }

    @Test
    fun startupRemovesStalePrivateStagingFilesOnly() {
        val directory = File(app.noBackupFilesDir, "clockrove-backup-document-staging")
        assertTrue(directory.exists() || directory.mkdirs())
        val staleStage = File(directory, "encrypted-stale-test.bin")
        staleStage.delete()
        assertTrue(staleStage.createNewFile())
        val deletions = mutableListOf<Uri>()

        BackupDocumentStore(
            app,
            elapsedClock = { elapsed },
            scheduler = scheduler,
            deleteDocument = { deletions.add(it) },
        )

        assertFalse(staleStage.exists())
        assertTrue("startup stage cleanup must not issue SAF deletes", deletions.isEmpty())
    }

    @Test
    fun completedHandleRemainsRetryableUntilItsTerminalTtlExpires() {
        val uri = Uri.parse("content://synthetic.provider/backup/terminal-ttl")
        val id = store.begin(uri, 1L)
        store.append(id, 0L, byteArrayOf(7))
        assertTrue(store.finish(id))

        elapsed += 59_999L
        scheduler.runDueTasks()
        assertTrue(store.finish(id))
        assertTrue(store.cancel(id))
        assertTrue(deletedUris.isEmpty())

        elapsed += 2L
        scheduler.runDueTasks()
        assertFailure("DOCUMENT_UNAVAILABLE") { store.finish(id) }
        assertFalse(store.cancel(id))
        assertTrue(deletedUris.isEmpty())
    }

    private fun assertFailure(code: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: BackupDocumentStore.Failure) {
            assertEquals(code, failure.code)
            return
        }
        fail("Expected backup document failure $code")
    }
}
