package io.github.centerionware.clockrove.backup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

/** Copies only encrypted SAF input into private, bounded staging. */
internal class BackupDocumentInputStore(
    context: Context,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val idleTtlMillis: Long = BackupDocumentStore.IDLE_EXPIRY_MILLIS,
    private val scheduler: BackupFileScheduler = inputFileScheduler,
    private val maxEncryptedBytes: Long = BackupDocumentStore.MAX_ENCRYPTED_BYTES,
    private val openInputStream: (Uri) -> InputStream? = {
        context.applicationContext.contentResolver.openInputStream(it)
    },
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    data class Opened(val documentId: String, val totalBytes: Long)

    class Failure(val code: String, message: String) : IllegalStateException(message)

    private enum class State { COPYING, READY, CLOSED }

    private class Stage(val id: String, val file: File, var lastAccess: Long) {
        @Volatile var cancelRequested = false
        var state = State.COPYING
        var totalBytes = 0L
        var expiryTask: BackupFileScheduledTask? = null
    }

    companion object {
        const val MAX_CHUNK_BYTES = 64 * 1024
        const val IDLE_EXPIRY_MILLIS = 15L * 60L * 1000L
        private const val DIRECTORY_NAME = "clockrove-backup-document-import-staging"
        private const val FILE_PREFIX = "encrypted-import-"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val ENVELOPE_MAGIC = byteArrayOf(0x43, 0x4c, 0x4b, 0x52, 0x4f, 0x56, 0x45, 0x32)
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }

    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)
    private var active: Stage? = null

    init {
        require(idleTtlMillis > 0 && maxEncryptedBytes > 0)
        cleanupStaleFiles()
    }

    @Synchronized
    fun hasPendingAction(): Boolean {
        expireIfIdleLocked()
        return active != null
    }

    fun stage(uri: Uri): Opened {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
            throw Failure("INVALID_DOCUMENT_URI", "Choose a readable backup document.")
        }
        val stage = beginStage()
        try {
            val input = openInputStream(uri)
                ?: throw Failure("STORAGE_UNAVAILABLE", "The selected backup could not be opened.")
            var total = 0L
            input.use { source ->
                FileOutputStream(stage.file, false).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        checkCopyActive(stage)
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (total > maxEncryptedBytes - count) {
                            throw Failure("DOCUMENT_SIZE_EXCEEDED", "This backup is larger than the supported limit.")
                        }
                        output.write(buffer, 0, count)
                        total += count
                        synchronized(this) {
                            checkCopyActiveLocked(stage)
                            stage.totalBytes = total
                            stage.lastAccess = elapsedClock()
                        }
                    }
                    output.flush()
                }
            }
            if (total < 1L || stage.file.length() != total) {
                throw Failure("INVALID_DOCUMENT_SIZE", "The selected backup file is empty or incomplete.")
            }
            if (!hasSupportedEnvelopeMagic(stage.file)) {
                throw Failure("UNSUPPORTED_BACKUP_ENVELOPE", "Choose a password-encrypted Clockrove backup.")
            }
            synchronized(this) {
                checkCopyActiveLocked(stage)
                stage.state = State.READY
                stage.totalBytes = total
                stage.lastAccess = elapsedClock()
                rescheduleExpiryLocked(stage)
            }
            return Opened(stage.id, total)
        } catch (failure: Failure) {
            release(stage)
            throw failure
        } catch (_: Exception) {
            release(stage)
            throw Failure("STORAGE_UNAVAILABLE", "The selected backup could not be staged securely.")
        }
    }

    fun readChunk(documentId: String, offset: Long, maxBytes: Int): ByteArray {
        validateId(documentId)
        if (offset < 0 || maxBytes !in 1..MAX_CHUNK_BYTES) {
            throw Failure("INVALID_DOCUMENT_CHUNK", "The backup read request is invalid.")
        }
        val stage = synchronized(this) {
            expireIfIdleLocked()
            val current = active
                ?: throw Failure("DOCUMENT_EXPIRED", "The selected backup is no longer available.")
            if (current.id != documentId || current.state != State.READY) {
                throw Failure("DOCUMENT_UNAVAILABLE", "The selected backup is no longer available.")
            }
            if (offset > current.totalBytes) {
                throw Failure("INVALID_DOCUMENT_OFFSET", "The backup read offset is invalid.")
            }
            current.lastAccess = elapsedClock()
            try {
                rescheduleExpiryLocked(current)
            } catch (_: Exception) {
                active = null
                current.state = State.CLOSED
                deleteFile(current.file)
                throw Failure("STORAGE_UNAVAILABLE", "Private backup staging expired unexpectedly.")
            }
            current
        }
        val remaining = stage.totalBytes - offset
        val count = minOf(maxBytes.toLong(), remaining).toInt()
        if (count == 0) return ByteArray(0)
        return try {
            RandomAccessFile(stage.file, "r").use { file ->
                if (file.length() != stage.totalBytes) {
                    throw Failure("STORAGE_UNAVAILABLE", "The staged backup changed during reading.")
                }
                file.seek(offset)
                ByteArray(count).also { bytes -> file.readFully(bytes) }
            }
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("STORAGE_UNAVAILABLE", "The staged backup could not be read.")
        }
    }

    fun close(documentId: String): Boolean {
        validateId(documentId)
        val stage = synchronized(this) {
            val current = active?.takeIf { it.id == documentId } ?: return false
            active = null
            current.cancelRequested = true
            current.expiryTask?.cancel()
            current.expiryTask = null
            if (current.state == State.COPYING) null else current.also { it.state = State.CLOSED }
        }
        stage?.let { deleteFile(it.file) }
        return true
    }

    fun requestCancelActive() {
        synchronized(this) { active?.cancelRequested = true }
    }

    fun closeActive() {
        val stage = synchronized(this) {
            val current = active ?: return
            active = null
            current.cancelRequested = true
            current.expiryTask?.cancel()
            current.expiryTask = null
            if (current.state == State.COPYING) null else current.also { it.state = State.CLOSED }
        }
        stage?.let { deleteFile(it.file) }
    }

    private fun beginStage(): Stage = synchronized(this) {
        expireIfIdleLocked()
        if (active != null) {
            throw Failure("DOCUMENT_ACTION_PENDING", "Finish or cancel the current backup action first.")
        }
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw Failure("STORAGE_UNAVAILABLE", "Private backup staging is unavailable.")
        }
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$FILE_PREFIX$id.bin")
        if (!file.createNewFile()) {
            throw Failure("STORAGE_UNAVAILABLE", "Private backup staging is unavailable.")
        }
        Stage(id, file, elapsedClock()).also { stage ->
            active = stage
            try {
                rescheduleExpiryLocked(stage)
            } catch (_: Exception) {
                active = null
                stage.state = State.CLOSED
                deleteFile(file)
                throw Failure("STORAGE_UNAVAILABLE", "Private backup staging is unavailable.")
            }
        }
    }

    private fun checkCopyActive(stage: Stage) = synchronized(this) {
        checkCopyActiveLocked(stage)
    }

    private fun checkCopyActiveLocked(stage: Stage) {
        if (active !== stage || stage.cancelRequested || stage.state != State.COPYING) {
            throw Failure("DOCUMENT_EXPIRED", "The selected backup could not be kept open.")
        }
    }

    private fun release(stage: Stage) {
        synchronized(this) {
            if (active === stage) active = null
            stage.cancelRequested = true
            stage.state = State.CLOSED
            stage.expiryTask?.cancel()
            stage.expiryTask = null
        }
        deleteFile(stage.file)
    }

    private fun rescheduleExpiryLocked(stage: Stage) {
        stage.expiryTask?.cancel()
        stage.expiryTask = scheduler.schedule(idleTtlMillis) { expire(stage) }
    }

    private fun expire(stage: Stage) {
        var delete = false
        synchronized(this) {
            if (active !== stage) return
            val remaining = idleTtlMillis - (elapsedClock() - stage.lastAccess)
            if (remaining > 0) {
                try {
                    stage.expiryTask = scheduler.schedule(remaining) { expire(stage) }
                } catch (_: Exception) {
                    active = null
                    stage.cancelRequested = true
                    stage.state = State.CLOSED
                    stage.expiryTask = null
                    delete = true
                }
            } else {
                active = null
                stage.cancelRequested = true
                stage.expiryTask = null
                if (stage.state != State.COPYING) {
                    stage.state = State.CLOSED
                    delete = true
                }
            }
        }
        if (delete) deleteFile(stage.file)
    }

    private fun expireIfIdleLocked() {
        val stage = active ?: return
        if (elapsedClock() - stage.lastAccess < idleTtlMillis) return
        active = null
        stage.cancelRequested = true
        stage.expiryTask?.cancel()
        stage.expiryTask = null
        if (stage.state != State.COPYING) {
            stage.state = State.CLOSED
            deleteFile(stage.file)
        }
    }

    private fun cleanupStaleFiles() {
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(FILE_PREFIX)) deleteFile(file)
        }
    }

    private fun hasSupportedEnvelopeMagic(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val header = ByteArray(ENVELOPE_MAGIC.size)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return false
                offset += count
            }
            header.contentEquals(ENVELOPE_MAGIC)
        }
    } catch (_: Exception) {
        false
    }

    private fun validateId(value: String) {
        if (!idPattern.matches(value)) {
            throw Failure("INVALID_DOCUMENT_HANDLE", "The backup document handle is invalid.")
        }
    }
}

private val inputFileScheduler = BackupFileScheduler { delay, action ->
    BackupDocumentInputExpiry.schedule(delay, action)
}

internal object BackupDocumentInputStoreProvider {
    @Volatile private var instance: BackupDocumentInputStore? = null

    fun get(context: Context): BackupDocumentInputStore {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: BackupDocumentInputStore(context.applicationContext).also { instance = it }
        }
    }
}

private object BackupDocumentInputExpiry {
    private val executor = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "clockrove-backup-input-expiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun schedule(delayMillis: Long, action: () -> Unit): BackupFileScheduledTask {
        val future = executor.schedule(action, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        return BackupFileScheduledTask { future.cancel(false) }
    }
}
