package io.github.centerionware.clockrove.backup

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class BackupDocumentInputStoreTest {
    private val envelope = byteArrayOf(0x43, 0x4c, 0x4b, 0x52, 0x4f, 0x56, 0x45, 0x32, 2, 4, 6, 8, 10)
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
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private var elapsed = 5_000L
    private lateinit var scheduler: ManualScheduler
    private lateinit var store: BackupDocumentInputStore
    private lateinit var deletedFiles: MutableList<File>

    @Before
    fun setUp() {
        elapsed = 5_000L
        scheduler = ManualScheduler { elapsed }
        deletedFiles = mutableListOf()
        store = BackupDocumentInputStore(
            app,
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
            scheduler = scheduler,
            openInputStream = { ByteArrayInputStream(envelope) },
            deleteFile = { file -> deletedFiles.add(file); file.delete() },
        )
    }

    @Test
    fun stagesOnlyPrivateEncryptedBytesAndReadsBoundedChunks() {
        val opened = store.stage(Uri.parse("content://synthetic.provider/backup"))
        assertEquals(envelope.size.toLong(), opened.totalBytes)
        val directory = File(app.noBackupFilesDir, "clockrove-backup-document-import-staging")
        val staged = directory.listFiles().orEmpty().single()
        assertTrue(staged.name.startsWith("encrypted-import-"))
        assertEquals(envelope.size.toLong(), staged.length())
        assertArrayEquals(envelope.copyOfRange(0, 3), store.readChunk(opened.documentId, 0, 3))
        assertArrayEquals(envelope.copyOfRange(3, 6), store.readChunk(opened.documentId, 3, 3))
        assertTrue(store.close(opened.documentId))
        assertFalse(staged.exists())
        assertFalse(store.hasPendingAction())
    }

    @Test
    fun rejectsInvalidUriOffsetsAndOversizedReadRequests() {
        assertFailure("INVALID_DOCUMENT_URI") {
            store.stage(Uri.parse("file:///private/backup"))
        }
        val opened = store.stage(Uri.parse("content://synthetic.provider/backup"))
        assertArrayEquals(
            envelope.copyOfRange(6, 7),
            store.readChunk(opened.documentId, 6, 1),
        )
        assertFailure("INVALID_DOCUMENT_OFFSET") {
            store.readChunk(opened.documentId, opened.totalBytes + 1, 1)
        }
        assertFailure("INVALID_DOCUMENT_CHUNK") {
            store.readChunk(opened.documentId, 0, BackupDocumentInputStore.MAX_CHUNK_BYTES + 1)
        }
        assertTrue(store.close(opened.documentId))
    }

    @Test
    fun rejectsNonEncryptedFilesBeforeExposingAReadableHandle() {
        store = BackupDocumentInputStore(
            app,
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
            scheduler = scheduler,
            openInputStream = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) },
            deleteFile = { file -> deletedFiles.add(file); file.delete() },
        )
        assertFailure("UNSUPPORTED_BACKUP_ENVELOPE") {
            store.stage(Uri.parse("content://synthetic.provider/plaintext"))
        }
        assertFalse(store.hasPendingAction())
        assertEquals(1, deletedFiles.size)
    }

    @Test
    fun rejectsEncryptedInputsPastTheConfiguredBoundAndDeletesTheirStage() {
        val source = object : InputStream() {
            private var remaining = 11
            override fun read(): Int {
                if (remaining == 0) return -1
                remaining--
                return 7
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(length, remaining)
                buffer.fill(7, offset, offset + count)
                remaining -= count
                return count
            }
        }
        store = BackupDocumentInputStore(
            app,
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
            scheduler = scheduler,
            maxEncryptedBytes = 10,
            openInputStream = { source },
            deleteFile = { file -> deletedFiles.add(file); file.delete() },
        )
        assertFailure("DOCUMENT_SIZE_EXCEEDED") {
            store.stage(Uri.parse("content://synthetic.provider/too-large"))
        }
        assertEquals(1, deletedFiles.size)
        assertFalse(store.hasPendingAction())
    }

    @Test
    fun expiryAndSchedulingFailureRemovePrivateCiphertext() {
        val opened = store.stage(Uri.parse("content://synthetic.provider/backup"))
        val directory = File(app.noBackupFilesDir, "clockrove-backup-document-import-staging")
        val staged = directory.listFiles().orEmpty().single()
        elapsed += 60_001L
        scheduler.runDueTasks()
        assertFalse(staged.exists())
        assertFalse(store.hasPendingAction())
        assertTrue(deletedFiles.contains(staged))
        assertFalse(store.close(opened.documentId))

        val failingStore = BackupDocumentInputStore(
            app,
            elapsedClock = { elapsed },
            idleTtlMillis = 60_000L,
            scheduler = scheduler,
            openInputStream = { ByteArrayInputStream(envelope) },
            deleteFile = { file -> deletedFiles.add(file); file.delete() },
        )
        scheduler.failNextSchedule = true
        try {
            failingStore.stage(Uri.parse("content://synthetic.provider/failing"))
            fail("Expected expiry scheduling to fail closed")
        } catch (failure: BackupDocumentInputStore.Failure) {
            assertEquals("STORAGE_UNAVAILABLE", failure.code)
        }
        assertFalse(failingStore.hasPendingAction())
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("encrypted-import-") })
    }

    @Test
    fun startupRemovesStaleEncryptedFiles() {
        val directory = File(app.noBackupFilesDir, "clockrove-backup-document-import-staging")
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val stale = File(directory, "encrypted-import-stale.bin")
        stale.writeBytes(byteArrayOf(1, 2, 3))
        BackupDocumentInputStore(app, scheduler = scheduler)
        assertFalse(stale.exists())
        assertNotNull(directory.listFiles())
    }

    private fun assertFailure(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (failure: BackupDocumentInputStore.Failure) {
            assertEquals(code, failure.code)
        }
    }
}
