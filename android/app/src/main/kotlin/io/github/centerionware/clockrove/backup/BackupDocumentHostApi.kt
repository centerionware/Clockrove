package io.github.centerionware.clockrove.backup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
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
    private val inputStore: BackupDocumentInputStore = BackupDocumentInputStoreProvider.get(activity.applicationContext),
) {
    companion object {
        private const val REQUEST_CREATE_BACKUP = 0xcc46
        private const val REQUEST_OPEN_BACKUP = 0xcc47
        private const val MAX_SUGGESTED_NAME_LENGTH = 128
    }

    private data class PendingCreate(
        val totalBytes: Long,
        val suggestedName: String,
        val result: MethodChannel.Result,
    )

    private data class PendingOpen(val result: MethodChannel.Result)

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
    private var pendingOpen: PendingOpen? = null
    @Volatile private var attached = true

    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "createBackupDocument" -> create(call, result)
                    "openBackupDocumentForImport" -> openForImport(result)
                    "readBackupDocumentChunk" -> {
                        val input = Input(call.arguments)
                        val offset = input.long("offset")
                        val bytes = inputStore.readChunk(
                            input.string("documentId"),
                            offset,
                            input.int("maxBytes"),
                        )
                        result.success(mapOf("offset" to offset, "bytes" to bytes))
                    }
                    "closeBackupDocumentImport" -> result.success(
                        inputStore.close(Input(call.arguments).string("documentId")),
                    )
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
            } catch (failure: BackupDocumentInputStore.Failure) {
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
        pendingCreate != null || pendingOpen != null || store.hasPendingAction() || inputStore.hasPendingAction()
    }

    /** Handles only this API's SAF request; raw URI values never leave this class. */
    fun activityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_OPEN_BACKUP) return openedForImport(resultCode, data)
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

    private fun openForImport(result: MethodChannel.Result) {
        val pending = PendingOpen(result)
        synchronized(lock) {
            if (!attached) throw BackupDocumentInputStore.Failure("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to restore a backup.")
            if (pendingCreate != null || pendingOpen != null || store.hasPendingAction() || inputStore.hasPendingAction()) {
                throw BackupDocumentInputStore.Failure("DOCUMENT_ACTION_PENDING", "Finish or close the current backup action first.")
            }
            pendingOpen = pending
        }
        val posted = main.post {
            val stillPending = synchronized(lock) { attached && pendingOpen === pending }
            if (!stillPending) return@post
            if (activity.isFinishing) {
                synchronized(lock) { if (pendingOpen === pending) pendingOpen = null }
                result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to restore a backup.", null)
            } else if (documentActionPending()) {
                synchronized(lock) { if (pendingOpen === pending) pendingOpen = null }
                result.error("DOCUMENT_PICKER_OPEN", "Finish the open document or photo action first.", null)
            } else {
                try {
                    activity.startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            if (showAdvancedPickerRoots()) {
                                putExtra("android.provider.extra.SHOW_ADVANCED", true)
                            }
                        },
                        REQUEST_OPEN_BACKUP,
                    )
                } catch (_: ActivityNotFoundException) {
                    synchronized(lock) { if (pendingOpen === pending) pendingOpen = null }
                    result.error("NO_DOCUMENT_PICKER", "No document picker is installed.", null)
                } catch (_: SecurityException) {
                    synchronized(lock) { if (pendingOpen === pending) pendingOpen = null }
                    result.error("OPEN_UNAVAILABLE", "The backup document picker could not be opened.", null)
                } catch (_: RuntimeException) {
                    synchronized(lock) { if (pendingOpen === pending) pendingOpen = null }
                    result.error("OPEN_UNAVAILABLE", "The backup document picker could not be opened.", null)
                }
            }
        }
        if (!posted) {
            synchronized(lock) { if (pendingOpen === pending) pendingOpen = null }
            result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to restore a backup.", null)
        }
    }

    /** Copies selected ciphertext into private staging before returning an opaque handle. */
    private fun openedForImport(resultCode: Int, data: Intent?): Boolean {
        val pending = synchronized(lock) { pendingOpen } ?: return true
        if (resultCode != Activity.RESULT_OK) {
            synchronized(lock) { if (pendingOpen === pending) pendingOpen = null }
            pending.result.success(null)
            return true
        }
        val uri = data?.data
        if (uri == null) {
            synchronized(lock) { if (pendingOpen === pending) pendingOpen = null }
            pending.result.error("OPEN_UNAVAILABLE", "The selected backup document is unavailable.", null)
            return true
        }
        saveWorker.execute {
            try {
                val staged = inputStore.stage(uri)
                val canReply = synchronized(lock) {
                    if (pendingOpen === pending) pendingOpen = null
                    attached
                }
                if (canReply) {
                    reply {
                        if (attached) pending.result.success(
                            mapOf("documentId" to staged.documentId, "totalBytes" to staged.totalBytes),
                        ) else {
                            inputStore.close(staged.documentId)
                            pending.result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to restore a backup.", null)
                        }
                    }
                } else inputStore.close(staged.documentId)
            } catch (failure: BackupDocumentInputStore.Failure) {
                val canReply = synchronized(lock) {
                    if (pendingOpen === pending) pendingOpen = null
                    attached
                }
                if (canReply) reply { pending.result.error(failure.code, failure.message, null) }
            } catch (_: Exception) {
                val canReply = synchronized(lock) {
                    if (pendingOpen === pending) pendingOpen = null
                    attached
                }
                if (canReply) reply {
                    pending.result.error("STORAGE_UNAVAILABLE", "The selected backup could not be staged securely.", null)
                }
            }
        }
        return true
    }

    fun detach() {
        channel.setMethodCallHandler(null)
        attached = false
        val pending = synchronized(lock) {
            (pendingCreate to pendingOpen).also {
                pendingCreate = null
                pendingOpen = null
            }
        }
        pending.first?.result?.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to save the backup.", null)
        val pendingImport = pending.second
        pendingImport?.result?.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to restore a backup.", null)
        // If a save is already committing, cancellation is deliberately ignored by the store.
        store.requestCancelActive()
        inputStore.requestCancelActive()
        saveWorker.execute { store.cancelActive() }
        saveWorker.execute { inputStore.closeActive() }
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
            if (pendingCreate != null || pendingOpen != null || store.hasPendingAction() || inputStore.hasPendingAction()) {
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

    /** Test-only switch so headless API 24 can reach device storage without its hidden menu. */
    private fun showAdvancedPickerRoots(): Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            activity.getSharedPreferences("clockrove_ci_test_flags", Activity.MODE_PRIVATE)
                .getBoolean("document_picker_show_advanced", false)

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

        fun int(key: String): Int {
            val value = long(key)
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return value.toInt()
        }

        fun bytes(key: String): ByteArray = values[key] as? ByteArray
            ?: throw IllegalArgumentException("Missing bytes")
    }
}
