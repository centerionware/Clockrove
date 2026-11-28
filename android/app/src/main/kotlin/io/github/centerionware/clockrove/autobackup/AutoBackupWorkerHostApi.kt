package io.github.centerionware.clockrove.autobackup

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import io.github.centerionware.clockrove.backup.BackupDocumentStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Writer used only by the headless daily worker; no Activity or path leaves the app. */
internal class AutoBackupWorkerHostApi(
    context: Context,
    messenger: BinaryMessenger,
    private val bridge: AutoBackupWorkerBridge,
    private val store: AutoBackupSettingsStore = AutoBackupSettingsStore(context),
) {
    private val app = context.applicationContext
    private val channel = MethodChannel(
        messenger,
        "clockrove/auto-backup/v1",
        StandardMethodCodec.INSTANCE,
        messenger.makeBackgroundTaskQueue(),
    )
    private val lock = Any()
    private var active: Run? = null

    private data class Run(
        val id: String,
        val folder: String,
        val document: android.net.Uri,
        val stream: OutputStream,
        val expectedBytes: Long,
        val name: String,
        var writtenBytes: Long = 0L,
    )

    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "beginSession" -> result.success(beginSession())
                    "startDocument" -> result.success(start(call))
                    "writeChunk" -> result.success(write(call))
                    "finishDocument" -> result.success(finish(call))
                    "cancelDocument" -> {
                        cancel(Input(call.arguments).string("runId"))
                        result.success(true)
                    }
                    "autoBackupFinished" -> {
                        val input = Input(call.arguments)
                        val ok = input.bool("ok")
                        val retry = input.bool("retry")
                        if (!ok) {
                            store.recordResult(
                                success = false,
                                code = input.string("code").take(32),
                                fileName = null,
                            )
                        }
                        bridge.complete(ok, retry)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            } catch (failure: AutoBackupSettingsStore.Failure) {
                result.error(failure.code, failure.message, null)
            } catch (failure: AutoBackupFolder.Failure) {
                result.error(failure.code, failure.message, null)
            } catch (_: IllegalArgumentException) {
                result.error("INVALID_ARGUMENT", "Invalid automatic backup request", null)
            } catch (_: ClassCastException) {
                result.error("INVALID_ARGUMENT", "Invalid automatic backup request", null)
            } catch (_: Exception) {
                result.error("AUTO_BACKUP_UNAVAILABLE", "Automatic backup is unavailable", null)
            }
        }
    }

    fun detach() {
        channel.setMethodCallHandler(null)
        val run = synchronized(lock) { active.also { active = null } }
        run?.let { closeAndDelete(it) }
        bridge.complete(false, false)
    }

    private fun beginSession(): Map<String, Any?> {
        val settings = store.read()
        if (!settings.enabled) {
            throw AutoBackupFolder.Failure("DISABLED", "Automatic backups are turned off")
        }
        val folder = settings.folderUri
            ?: throw AutoBackupFolder.Failure("FOLDER_REQUIRED", "Choose a backup folder")
        if (!AutoBackupFolder.hasWritePermission(app, folder)) {
            throw AutoBackupFolder.Failure(
                "FOLDER_UNAVAILABLE",
                "Choose the backup folder again",
            )
        }
        return mapOf(
            "encrypt" to settings.encrypt,
            "password" to settings.password,
        )
    }

    private fun start(call: MethodCall): Map<String, Any?> {
        val input = Input(call.arguments)
        val totalBytes = input.long("totalBytes")
        if (totalBytes !in 1L..MAX_AUTO_BACKUP_BYTES) {
            throw IllegalArgumentException("The automatic backup size is unsupported")
        }
        val encrypted = input.bool("encrypted")
        val capturedAt = input.long("capturedAtUtcMillis")
        val settings = store.read()
        if (!settings.enabled) {
            throw AutoBackupFolder.Failure("DISABLED", "Automatic backups are turned off")
        }
        if (settings.encrypt != encrypted) {
            throw AutoBackupFolder.Failure(
                "SETTINGS_CHANGED",
                "Automatic backup settings changed",
            )
        }
        val folder = settings.folderUri
            ?: throw AutoBackupFolder.Failure("FOLDER_REQUIRED", "Choose a backup folder")
        if (!AutoBackupFolder.hasWritePermission(app, folder)) {
            throw AutoBackupFolder.Failure(
                "FOLDER_UNAVAILABLE",
                "Choose the backup folder again",
            )
        }
        synchronized(lock) {
            if (active != null) {
                throw AutoBackupFolder.Failure("BUSY", "An automatic backup is already running")
            }
        }
        val name = fileName(capturedAt)
        val document = AutoBackupFolder.create(
            app,
            folder,
            name + AutoBackupRetention.FILE_PARTIAL_SUFFIX,
        )
        val stream = try {
            AutoBackupFolder.openOutput(app, document)
        } catch (failure: Exception) {
            AutoBackupFolder.delete(app, folder, document)
            throw failure
        }
        val run = Run(
            id = UUID.randomUUID().toString(),
            folder = folder,
            document = document,
            stream = stream,
            expectedBytes = totalBytes,
            name = name,
        )
        synchronized(lock) { active = run }
        return mapOf("runId" to run.id, "name" to run.name)
    }

    private fun write(call: MethodCall): Long {
        val input = Input(call.arguments)
        val id = input.string("runId")
        val offset = input.long("offset")
        val bytes = input.bytes("bytes")
        if (bytes.isEmpty() || bytes.size > MAX_CHUNK_BYTES) {
            throw IllegalArgumentException("The automatic backup chunk is invalid")
        }
        val run = synchronized(lock) { active }
            ?: throw AutoBackupFolder.Failure("NO_RUN", "No automatic backup is running")
        if (run.id != id || offset != run.writtenBytes) {
            throw AutoBackupFolder.Failure("OFFSET_MISMATCH", "The backup write is out of order")
        }
        if (bytes.size.toLong() > run.expectedBytes - run.writtenBytes) {
            throw AutoBackupFolder.Failure("SIZE_MISMATCH", "The backup exceeded its size")
        }
        run.stream.write(bytes)
        run.writtenBytes += bytes.size
        return run.writtenBytes
    }

    private fun finish(call: MethodCall): Map<String, Any?> {
        val id = Input(call.arguments).string("runId")
        val run = synchronized(lock) {
            active?.takeIf { it.id == id }?.also { active = null }
        } ?: throw AutoBackupFolder.Failure("NO_RUN", "No automatic backup is running")
        try {
            run.stream.flush()
            run.stream.close()
            if (run.writtenBytes != run.expectedBytes) {
                AutoBackupFolder.delete(app, run.folder, run.document)
                throw AutoBackupFolder.Failure("SIZE_MISMATCH", "The backup ended early")
            }
            AutoBackupFolder.rename(app, run.document, run.name)
            val pruned = try {
                AutoBackupFolder.prune(app, run.folder, System.currentTimeMillis())
            } catch (_: Exception) {
                // The completed backup stays; the next daily run retries cleanup.
                0
            }
            store.recordResult(success = true, code = "SUCCESS", fileName = run.name)
            return mapOf("name" to run.name, "pruned" to pruned)
        } catch (failure: Exception) {
            try { run.stream.close() } catch (_: Exception) { }
            AutoBackupFolder.delete(app, run.folder, run.document)
            throw failure
        }
    }

    private fun cancel(id: String) {
        val run = synchronized(lock) {
            active?.takeIf { it.id == id }?.also { active = null }
        } ?: return
        closeAndDelete(run)
    }

    private fun closeAndDelete(run: Run) {
        try { run.stream.close() } catch (_: Exception) { }
        AutoBackupFolder.delete(app, run.folder, run.document)
    }

    private fun fileName(capturedAtUtcMillis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(
            Date(capturedAtUtcMillis),
        )
        return "${AutoBackupRetention.FILE_PREFIX}$stamp${AutoBackupRetention.FILE_SUFFIX}"
    }

    private class Input(arguments: Any?) {
        private val values: Map<*, *> = when (arguments) {
            is Map<*, *> -> arguments
            else -> throw IllegalArgumentException("Expected a map")
        }

        fun string(key: String): String = (values[key] as? String)
            ?.also { require(it.isNotBlank() && it.length <= 128) }
            ?: throw IllegalArgumentException("Missing string")

        fun long(key: String): Long {
            val raw = values[key]
            require(raw is Int || raw is Long)
            return (raw as Number).toLong()
        }

        fun bool(key: String): Boolean = values[key] as? Boolean
            ?: throw IllegalArgumentException("Missing boolean")

        fun bytes(key: String): ByteArray = values[key] as? ByteArray
            ?: throw IllegalArgumentException("Missing bytes")
    }

    private companion object {
        const val MAX_AUTO_BACKUP_BYTES = BackupDocumentStore.MAX_ENCRYPTED_BYTES
        const val MAX_CHUNK_BYTES = 192 * 1024
    }
}
