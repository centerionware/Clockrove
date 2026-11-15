package io.github.centerionware.clockrove.backup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/** SAF bridge for writing an already-encrypted backup without exposing filesystem paths. */
internal class BackupDocumentHostApi(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val documentActionPending: () -> Boolean = { false },
    private val store: BackupDocumentStore = BackupDocumentStoreProvider.get(activity.applicationContext),
) {
    companion object {
        private const val REQUEST_CREATE_BACKUP = 0xcc46
        private const val MAX_SUGGESTED_NAME_LENGTH = 128
    }

    private data class PendingCreate(
        val totalBytes: Long,
        val suggestedName: String,
        val result: MethodChannel.Result,
    )

    private val main = Handler(Looper.getMainLooper())
    private val channel = MethodChannel(
        messenger,
        "clockrove/backup-files/v1",
        io.flutter.plugin.common.StandardMethodCodec.INSTANCE,
        messenger.makeBackgroundTaskQueue(),
    )
    private val saveWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "clockrove-backup-document-save").apply { isDaemon = true }
    }
    private val lock = Any()
    private var pendingCreate: PendingCreate? = null
    @Volatile private var attached = true

    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "createBackupDocument" -> create(call, result)
                    "writeBackupDocumentChunk" -> {
                        val input = Input(call.arguments)
                        val next = store.append(input.string("documentId"), input.long("offset"), input.bytes("bytes"))
                        result.success(next)
                    }
                    "finishBackupDocument" -> {
                        val id = Input(call.arguments).string("documentId")
                        val alreadyTerminal = store.reserveFinish(id)
                        if (alreadyTerminal != null) {
                            result.success(alreadyTerminal)
                            return@setMethodCallHandler
                        }
                        // The channel uses a background queue, and this dedicated worker keeps
                        // provider I/O off Flutter and serializes final saves with each other.
                        saveWorker.execute {
                            try {
                                val saved = store.finishReserved(id)
                                reply { result.success(saved) }
                            } catch (failure: BackupDocumentStore.Failure) {
                                reply { result.error(failure.code, failure.message, null) }
                            } catch (_: Exception) {
                                reply { result.error("SAVE_UNAVAILABLE", "The encrypted backup could not be saved.", null) }
                            }
                        }
                    }
                    "cancelBackupDocument" -> {
                        val cancelled = store.cancel(Input(call.arguments).string("documentId"))
                        result.success(cancelled)
                    }
                    else -> result.notImplemented()
                }
            } catch (failure: BackupDocumentStore.Failure) {
                result.error(failure.code, failure.message, null)
            } catch (_: IllegalArgumentException) {
                result.error("INVALID_ARGUMENT", "Invalid backup document request", null)
            } catch (_: ClassCastException) {
                result.error("INVALID_ARGUMENT", "Invalid backup document request", null)
            } catch (_: Exception) {
                result.error("STORAGE_UNAVAILABLE", "Backup document storage is unavailable", null)
            }
        }
    }

    fun hasPendingAction(): Boolean = synchronized(lock) {
        pendingCreate != null || store.hasPendingAction()
    }

    /** Handles only this API's SAF request; raw URI values never leave this class. */
    fun activityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CREATE_BACKUP) return false
        val pending = synchronized(lock) { pendingCreate } ?: return true

        if (resultCode != Activity.RESULT_OK) {
            synchronized(lock) { if (pendingCreate === pending) pendingCreate = null }
            pending.result.success(null)
            return true
        }
        val uri = data?.data
        if (uri == null) {
            synchronized(lock) { if (pendingCreate === pending) pendingCreate = null }
            pending.result.error("SAVE_UNAVAILABLE", "The selected backup document is unavailable.", null)
            return true
        }

        saveWorker.execute {
            try {
                val stillPending = synchronized(lock) { attached && pendingCreate === pending }
                if (!stillPending) {
                    store.discardIncompleteDestination(uri)
                    return@execute
                }
                val id = store.begin(uri, pending.totalBytes)
                val canReply = synchronized(lock) {
                    if (pendingCreate === pending) pendingCreate = null
                    attached
                }
                if (canReply) {
                    reply {
                        if (attached) pending.result.success(id)
                        else {
                            saveWorker.execute { store.cancel(id) }
                            pending.result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to save the backup.", null)
                        }
                    }
                } else store.cancel(id)
            } catch (failure: BackupDocumentStore.Failure) {
                val canReply = synchronized(lock) {
                    if (pendingCreate === pending) pendingCreate = null
                    attached
                }
                if (canReply) reply { pending.result.error(failure.code, failure.message, null) }
            } catch (_: Exception) {
                store.discardIncompleteDestination(uri)
                val canReply = synchronized(lock) {
                    if (pendingCreate === pending) pendingCreate = null
                    attached
                }
                if (canReply) reply { pending.result.error("STORAGE_UNAVAILABLE", "Encrypted backup staging is unavailable.", null) }
            }
        }
        return true
    }

    fun detach() {
        channel.setMethodCallHandler(null)
        attached = false
        val pending = synchronized(lock) {
            pendingCreate.also { pendingCreate = null }
        }
        pending?.result?.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to save the backup.", null)
        // If a save is already committing, cancellation is deliberately ignored by the store.
        store.requestCancelActive()
        saveWorker.execute { store.cancelActive() }
    }

    private fun create(call: MethodCall, result: MethodChannel.Result) {
        val input = Input(call.arguments)
        val totalBytes = input.long("totalBytes")
        if (totalBytes !in 1L..BackupDocumentStore.MAX_ENCRYPTED_BYTES) {
            throw BackupDocumentStore.Failure("INVALID_DOCUMENT_SIZE", "The encrypted backup size is not supported.")
        }
        val suggestedName = sanitizeSuggestedName(input.string("suggestedName"))
        val pending = PendingCreate(totalBytes, suggestedName, result)
        synchronized(lock) {
            if (!attached) throw BackupDocumentStore.Failure("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to save the backup.")
            if (pendingCreate != null || store.hasPendingAction()) {
                throw BackupDocumentStore.Failure("DOCUMENT_ACTION_PENDING", "Finish or cancel the open backup export first.")
            }
            pendingCreate = pending
        }
        val posted = main.post {
            val stillPending = synchronized(lock) { attached && pendingCreate === pending }
            if (!stillPending) return@post
            if (activity.isFinishing) {
                synchronized(lock) { if (pendingCreate === pending) pendingCreate = null }
                result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to save the backup.", null)
            } else if (documentActionPending()) {
                synchronized(lock) { if (pendingCreate === pending) pendingCreate = null }
                result.error("DOCUMENT_PICKER_OPEN", "Finish the open document or photo action first.", null)
            } else {
                try {
                    activity.startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, suggestedName)
                        },
                        REQUEST_CREATE_BACKUP,
                    )
                } catch (_: ActivityNotFoundException) {
                    synchronized(lock) { if (pendingCreate === pending) pendingCreate = null }
                    result.error("NO_DOCUMENT_PICKER", "No document picker is installed.", null)
                } catch (_: SecurityException) {
                    synchronized(lock) { if (pendingCreate === pending) pendingCreate = null }
                    result.error("SAVE_UNAVAILABLE", "The backup document picker could not be opened.", null)
                } catch (_: RuntimeException) {
                    synchronized(lock) { if (pendingCreate === pending) pendingCreate = null }
                    result.error("SAVE_UNAVAILABLE", "The backup document picker could not be opened.", null)
                }
            }
        }
        if (!posted) {
            synchronized(lock) { if (pendingCreate === pending) pendingCreate = null }
            result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to save the backup.", null)
        }
    }

    private fun sanitizeSuggestedName(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_SUGGESTED_NAME_LENGTH)
        val sanitized = value
            .map { character ->
                if (character.isLetterOrDigit() || character in " ._-") character else '_'
            }
            .joinToString("")
            .trim()
            .trim('.')
        require(sanitized.isNotBlank())
        return sanitized
    }

    private fun reply(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private class Input(arguments: Any?) {
        private val values: Map<*, *> = when (arguments) {
            is Map<*, *> -> arguments
            else -> throw IllegalArgumentException("Expected a map")
        }

        fun string(key: String): String = (values[key] as? String)
            ?.also { require(it.isNotBlank() && it.length <= MAX_SUGGESTED_NAME_LENGTH) }
            ?: throw IllegalArgumentException("Missing string")

        fun long(key: String): Long {
            val raw = values[key]
            require(raw is Int || raw is Long)
            return (raw as Number).toLong()
        }

        fun bytes(key: String): ByteArray = values[key] as? ByteArray
            ?: throw IllegalArgumentException("Missing bytes")
    }
}
