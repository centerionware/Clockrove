package io.github.centerionware.clockrove.backup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun interface BackupFileScheduledTask {
    fun cancel()
}

internal fun interface BackupFileScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): BackupFileScheduledTask
}

private object ExecutorBackupFileScheduler : BackupFileScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "clockrove-backup-file-expiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun schedule(delayMillis: Long, action: () -> Unit): BackupFileScheduledTask {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return BackupFileScheduledTask { future.cancel(false) }
    }
}

/** Keeps terminal save results stable if Flutter reattaches to a new Activity instance. */
internal object BackupDocumentStoreProvider {
    @Volatile private var instance: BackupDocumentStore? = null

    fun get(context: Context): BackupDocumentStore {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: BackupDocumentStore(context.applicationContext).also { instance = it }
        }
    }
}

/** Stages only encrypted archive bytes before copying them to a user-selected SAF URI. */
internal class BackupDocumentStore(
    context: Context,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val idleTtlMillis: Long = IDLE_EXPIRY_MILLIS,
    private val scheduler: BackupFileScheduler = ExecutorBackupFileScheduler,
    private val openOutputStream: (Uri) -> OutputStream? = {
        context.applicationContext.contentResolver.openOutputStream(it, "w")
    },
    private val deleteDocument: (Uri) -> Unit = { uri ->
        val resolver = context.applicationContext.contentResolver
        val deleted = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
            false
        }
        if (!deleted) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
                // Some providers do not support deleting a just-created document.
            }
        }
    },
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    class Failure(val code: String, message: String) : IllegalStateException(message)

    private enum class State { RECEIVING, FINISHING, COMMITTING, COMPLETE, CANCELLED }

    private class Stage(
        val id: String,
        val uri: Uri,
        val expectedBytes: Long,
        val file: File,
        var lastAccessElapsedMillis: Long,
    ) {
        @Volatile var cancelRequested: Boolean = false
        var writtenBytes: Long = 0
        var state: State = State.RECEIVING
        var terminalized: Boolean = false
        var expiryTask: BackupFileScheduledTask? = null
    }

    private class Terminal(
        val saved: Boolean,
        val expiresAtElapsedMillis: Long,
        var expiryTask: BackupFileScheduledTask? = null,
    )

    companion object {
        const val MAX_ENCRYPTED_BYTES = 64L * 1024L * 1024L + 72L
        const val MAX_CHUNK_BYTES = 192 * 1024
        const val IDLE_EXPIRY_MILLIS = 15L * 60L * 1000L
        private const val DIRECTORY_NAME = "clockrove-backup-document-staging"
        private const val FILE_PREFIX = "encrypted-"
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir, DIRECTORY_NAME)
    private val terminalIds = LinkedHashMap<String, Terminal>()
    private var active: Stage? = null

    init {
        require(idleTtlMillis > 0)
        cleanupStaleStagingFiles()
    }

    @Synchronized
    fun hasPendingAction(): Boolean {
        purgeExpiredTerminalIdsLocked()
        return active != null
    }

    fun begin(uri: Uri, expectedBytes: Long): String {
        require(expectedBytes in 1L..MAX_ENCRYPTED_BYTES) { "INVALID_DOCUMENT_SIZE" }
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "INVALID_DOCUMENT_URI" }

        var expired: Stage? = null
        var id = ""
        var actionPending = false
        synchronized(this) {
            expired = expireIfIdleLocked()
            if (active != null) {
                actionPending = true
            } else try {
                if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
                    id = ""
                } else {
                    val generated = UUID.randomUUID().toString()
                    val file = File(directory, "$FILE_PREFIX$generated.bin")
                    if (file.createNewFile()) {
                        val stage = Stage(generated, uri, expectedBytes, file, elapsedClock())
                        active = stage
                        try {
                            scheduleExpiryLocked(stage)
                            id = generated
                        } catch (_: Exception) {
                            active = null
                            deleteStageFile(file)
                        }
                    }
                }
            } catch (_: Exception) {
                id = ""
            }
        }
        expired?.let(::deleteIncomplete)
        if (actionPending) {
            discardIncompleteDestination(uri)
            throw Failure("DOCUMENT_ACTION_PENDING", "Finish or cancel the open backup export first.")
        }
        if (id.isEmpty()) {
            discardIncompleteDestination(uri)
            throw Failure("STORAGE_UNAVAILABLE", "Encrypted backup staging is unavailable.")
        }
        return id
    }

    fun append(id: String, offset: Long, bytes: ByteArray): Long {
        validateId(id)
        var expired: Stage? = null
        var failedWrite: Stage? = null
        var writeFailure: Failure? = null
        var nextOffset: Long? = null
        synchronized(this) {
            expired = expireIfIdleLocked()
            if (expired?.id != id) {
                val stage = activeForLocked(id)
                if (stage.state != State.RECEIVING) {
                    throw Failure("DOCUMENT_ACTION_PENDING", "The backup document is already being saved.")
                }
                if (offset < 0 || offset != stage.writtenBytes) {
                    throw Failure("INVALID_DOCUMENT_OFFSET", "Backup chunks must be written in sequence.")
                }
                if (bytes.isEmpty() || bytes.size > MAX_CHUNK_BYTES) {
                    throw Failure("INVALID_DOCUMENT_CHUNK", "The encrypted backup chunk size is not supported.")
                }
                val end = offset + bytes.size
                if (end < offset || end > stage.expectedBytes || end > MAX_ENCRYPTED_BYTES) {
                    throw Failure("DOCUMENT_SIZE_EXCEEDED", "The encrypted backup exceeds the supported size.")
                }
                try {
                    if (!stage.file.isFile || stage.file.length() != stage.writtenBytes) {
                        throw java.io.IOException("Staging file length changed")
                    }
                    FileOutputStream(stage.file, true).use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    if (stage.file.length() != end) throw java.io.IOException("Staging write was incomplete")
                    stage.writtenBytes = end
                    stage.lastAccessElapsedMillis = elapsedClock()
                    scheduleExpiryLocked(stage)
                    nextOffset = end
                } catch (_: Exception) {
                    if (terminalizeLocked(stage, saved = false)) failedWrite = stage
                    writeFailure = Failure("STORAGE_UNAVAILABLE", "Encrypted backup staging failed.")
                }
            }
        }
        expired?.let(::deleteIncomplete)
        if (expired?.id == id) throw Failure("DOCUMENT_EXPIRED", "The backup document staging session expired.")
        failedWrite?.let(::deleteIncomplete)
        writeFailure?.let { throw it }
        return requireNotNull(nextOffset)
    }

    fun finish(id: String): Boolean {
        val alreadyTerminal = reserveFinish(id)
        if (alreadyTerminal != null) return alreadyTerminal
        return finishReserved(id)
    }

    /** Atomically prevents later chunks from racing ahead of the off-thread SAF copy. */
    fun reserveFinish(id: String): Boolean? {
        validateId(id)
        var expired: Stage? = null
        var incomplete: Stage? = null
        var terminalResult: Boolean? = null
        synchronized(this) {
            purgeExpiredTerminalIdsLocked()
            expired = expireIfIdleLocked()
            if (expired?.id == id) {
                terminalResult = false
            } else if (terminalIds.containsKey(id)) {
                terminalResult = terminalIds[id]?.saved
            } else {
                val current = activeForLocked(id)
                if (current.state != State.RECEIVING) {
                    throw Failure("DOCUMENT_ACTION_PENDING", "The backup document is already being saved.")
                }
                if (current.writtenBytes != current.expectedBytes || current.file.length() != current.expectedBytes) {
                    if (terminalizeLocked(current, saved = false)) incomplete = current
                    terminalResult = false
                } else {
                    current.state = State.FINISHING
                    current.expiryTask?.cancel()
                    current.expiryTask = null
                }
            }
        }
        expired?.let(::deleteIncomplete)
        incomplete?.let(::deleteIncomplete)
        if (expired?.id == id) throw Failure("DOCUMENT_EXPIRED", "The backup document staging session expired.")
        if (terminalResult != null) {
            if (terminalResult == false && incomplete != null) {
                throw Failure("INCOMPLETE_DOCUMENT", "The encrypted backup was not fully staged.")
            }
            return terminalResult
        }

        return null
    }

    /** Copies a stage previously reserved by [reserveFinish] to its SAF destination. */
    fun finishReserved(id: String): Boolean {
        validateId(id)
        val current = synchronized(this) {
            val stage = activeForLocked(id)
            if (stage.state != State.FINISHING) {
                throw Failure("DOCUMENT_ACTION_PENDING", "The backup document is not ready to be saved.")
            }
            stage
        }
        val input = try {
            FileInputStream(current.file)
        } catch (_: Exception) {
            failFinish(current)
            throw Failure("STORAGE_UNAVAILABLE", "The encrypted backup staging file is unavailable.")
        }
        var output: OutputStream? = null
        var outputClosed = false
        try {
            output = openOutputStream(current.uri)
                ?: throw Failure("SAVE_UNAVAILABLE", "The selected backup document could not be opened.")
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var copied = 0L
            while (true) {
                if (current.cancelRequested) throw Failure("DOCUMENT_CANCELLED", "The backup export was cancelled.")
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > current.expectedBytes) {
                    throw Failure("DOCUMENT_SIZE_EXCEEDED", "The encrypted backup exceeds the supported size.")
                }
                output.write(buffer, 0, count)
            }
            if (copied != current.expectedBytes) {
                throw Failure("INCOMPLETE_DOCUMENT", "The encrypted backup was not fully staged.")
            }
            output.flush()
            synchronized(this) {
                if (current.cancelRequested) throw Failure("DOCUMENT_CANCELLED", "The backup export was cancelled.")
                current.state = State.COMMITTING
            }
            output.close()
            outputClosed = true
        } catch (failure: Failure) {
            if (!outputClosed) try { output?.close() } catch (_: Exception) { }
            try { input.close() } catch (_: Exception) { }
            failFinish(current)
            if (failure.code == "DOCUMENT_CANCELLED") return false
            throw failure
        } catch (_: Exception) {
            if (!outputClosed) try { output?.close() } catch (_: Exception) { }
            try { input.close() } catch (_: Exception) { }
            failFinish(current)
            throw Failure("SAVE_UNAVAILABLE", "The encrypted backup could not be saved.")
        }
        try { input.close() } catch (_: Exception) { }

        synchronized(this) {
            terminalizeLocked(current, saved = true)
        }
        deleteStageFile(current.file)
        return true
    }

    fun cancel(id: String): Boolean {
        validateId(id)
        var expired: Stage? = null
        var cleanup: Stage? = null
        val cancelled: Boolean
        synchronized(this) {
            purgeExpiredTerminalIdsLocked()
            expired = expireIfIdleLocked()
            if (terminalIds.containsKey(id)) {
                cancelled = true
            } else {
                val current = active
                if (current == null || current.id != id) {
                    cancelled = false
                } else {
                    when (current.state) {
                        State.RECEIVING -> {
                            current.cancelRequested = true
                            if (terminalizeLocked(current, saved = false)) cleanup = current
                            cancelled = true
                        }
                        State.FINISHING -> {
                            current.cancelRequested = true
                            cancelled = true
                        }
                        State.COMMITTING -> cancelled = false
                        State.COMPLETE, State.CANCELLED -> cancelled = true
                    }
                }
            }
        }
        expired?.let(::deleteIncomplete)
        cleanup?.let(::deleteIncomplete)
        return cancelled
    }

    /** Marks active work for cleanup without performing provider I/O on Activity teardown. */
    @Synchronized
    fun requestCancelActive() {
        active?.let { stage ->
            if (stage.state != State.COMMITTING) stage.cancelRequested = true
        }
    }

    fun cancelActive() {
        val id = synchronized(this) { active?.id } ?: return
        cancel(id)
    }

    fun discardIncompleteDestination(uri: Uri) {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return
        try { deleteDocument(uri) } catch (_: Exception) { }
    }

    private fun failFinish(stage: Stage) {
        val cleanup = synchronized(this) {
            if (terminalizeLocked(stage, saved = false)) stage else null
        }
        cleanup?.let(::deleteIncomplete)
    }

    private fun activeForLocked(id: String): Stage {
        if (!idPattern.matches(id)) throw Failure("INVALID_ARGUMENT", "Invalid backup document handle.")
        val current = active
        if (current == null || current.id != id) throw Failure("DOCUMENT_UNAVAILABLE", "The backup document is no longer available.")
        return current
    }

    private fun validateId(id: String) {
        if (!idPattern.matches(id)) throw Failure("INVALID_ARGUMENT", "Invalid backup document handle.")
    }

    private fun expireIfIdleLocked(): Stage? {
        val current = active ?: return null
        if (current.state != State.RECEIVING) return null
        val now = elapsedClock()
        if (now >= current.lastAccessElapsedMillis && now - current.lastAccessElapsedMillis >= idleTtlMillis) {
            terminalizeLocked(current, saved = false)
            return current
        }
        return null
    }

    private fun scheduleExpiryLocked(stage: Stage) {
        stage.expiryTask?.cancel()
        val idle = (elapsedClock() - stage.lastAccessElapsedMillis).coerceAtLeast(0)
        val delay = (idleTtlMillis - idle).coerceAtLeast(1)
        stage.expiryTask = scheduler.schedule(delay) { expire(stage.id) }
    }

    private fun expire(id: String) {
        val cleanup = synchronized(this) {
            val current = active?.takeIf { it.id == id } ?: return
            if (current.state != State.RECEIVING) return
            val idle = elapsedClock() - current.lastAccessElapsedMillis
            if (idle < idleTtlMillis) {
                try {
                    scheduleExpiryLocked(current)
                    null
                } catch (_: Exception) {
                    // If the scheduler cannot retain the expiry, fail closed and
                    // remove the private stage instead of stranding it indefinitely.
                    if (terminalizeLocked(current, saved = false)) current else null
                }
            } else if (terminalizeLocked(current, saved = false)) {
                current
            } else {
                null
            }
        }
        cleanup?.let(::deleteIncomplete)
    }

    private fun terminalizeLocked(stage: Stage, saved: Boolean): Boolean {
        if (stage.terminalized) return false
        stage.terminalized = true
        stage.state = if (saved) State.COMPLETE else State.CANCELLED
        stage.expiryTask?.cancel()
        stage.expiryTask = null
        if (active === stage) active = null
        val expiresAt = elapsedClock() + idleTtlMillis
        val terminal = Terminal(saved, expiresAt)
        terminalIds[stage.id] = terminal
        terminal.expiryTask = try {
            scheduler.schedule(idleTtlMillis) { expireTerminal(stage.id, expiresAt) }
        } catch (_: Exception) {
            null
        }
        return true
    }

    private fun purgeExpiredTerminalIdsLocked() {
        val now = elapsedClock()
        val expiredIds = terminalIds.filterValues { now >= it.expiresAtElapsedMillis }.keys.toList()
        expiredIds.forEach { id -> terminalIds.remove(id)?.expiryTask?.cancel() }
    }

    private fun expireTerminal(id: String, expiresAt: Long) {
        synchronized(this) {
            val terminal = terminalIds[id] ?: return
            if (terminal.expiresAtElapsedMillis != expiresAt) return
            if (elapsedClock() < expiresAt) {
                terminal.expiryTask = try {
                    scheduler.schedule(expiresAt - elapsedClock()) { expireTerminal(id, expiresAt) }
                } catch (_: Exception) {
                    null
                }
            } else {
                terminalIds.remove(id)
            }
        }
    }

    private fun deleteIncomplete(stage: Stage) {
        deleteStageFile(stage.file)
        discardIncompleteDestination(stage.uri)
    }

    private fun deleteStageFile(file: File) {
        try { deleteFile(file) } catch (_: Exception) { }
    }

    private fun cleanupStaleStagingFiles() {
        try {
            directory.listFiles()?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) }?.forEach(::deleteStageFile)
        } catch (_: Exception) {
            // A stale backup stage must never prevent manual timekeeping or app startup.
        }
    }
}
