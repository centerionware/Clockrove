package io.github.centerionware.clockrove.autobackup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec

/** Activity-bound settings channel. The folder URI never crosses into Flutter. */
internal class AutoBackupSettingsHostApi(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val store: AutoBackupSettingsStore = AutoBackupSettingsStore(activity),
    private val documentActionPending: () -> Boolean = { false },
) {
    private val app = activity.applicationContext
    private val channel = MethodChannel(
        messenger,
        "clockrove/auto-backup-settings/v1",
        StandardMethodCodec.INSTANCE,
        messenger.makeBackgroundTaskQueue(),
    )
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pendingFolder: MethodChannel.Result? = null
    @Volatile private var attached = true

    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "read" -> result.success(publicSettings())
                    "save" -> result.success(save(call))
                    "chooseFolder" -> chooseFolder(result)
                    "clearFolder" -> result.success(clearFolder())
                    "runNow" -> result.success(runNow())
                    else -> result.notImplemented()
                }
            } catch (failure: AutoBackupSettingsStore.Failure) {
                result.error(failure.code, failure.message, null)
            } catch (failure: AutoBackupFolder.Failure) {
                result.error(failure.code, failure.message, null)
            } catch (_: IllegalArgumentException) {
                result.error("INVALID_ARGUMENT", "Invalid automatic backup setting", null)
            } catch (_: ClassCastException) {
                result.error("INVALID_ARGUMENT", "Invalid automatic backup setting", null)
            } catch (_: Exception) {
                result.error("SETTINGS_UNAVAILABLE", "Automatic backup settings are unavailable", null)
            }
        }
    }

    fun detach() {
        channel.setMethodCallHandler(null)
        attached = false
        val pending = synchronized(lock) {
            pendingFolder.also { pendingFolder = null }
        }
        pending?.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to choose a folder", null)
    }

    fun activityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_FOLDER) return false
        val pending = synchronized(lock) { pendingFolder } ?: return true
        synchronized(lock) { if (pendingFolder === pending) pendingFolder = null }
        if (resultCode != Activity.RESULT_OK) {
            pending.success(null)
            return true
        }
        val uri = data?.data
        if (uri == null || !attached) {
            pending.error("FOLDER_UNAVAILABLE", "The selected folder is unavailable", null)
            return true
        }
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val settings = store.setFolder(uri.toString())
            if (settings.enabled) AutoBackupScheduler.schedule(app)
            pending.success(publicSettings())
        } catch (_: SecurityException) {
            pending.error("FOLDER_PERMISSION", "The folder permission could not be kept", null)
        } catch (_: Exception) {
            pending.error("FOLDER_UNAVAILABLE", "The selected folder could not be saved", null)
        }
        return true
    }

    private fun save(call: MethodCall): Map<String, Any?> {
        val input = Input(call.arguments)
        val enabled = input.bool("enabled")
        val encrypt = input.bool("encrypt")
        val password = input.nullableString("password")
        val clearPassword = input.bool("clearPassword", default = false)
        val existing = store.read()
        if (enabled) {
            val folder = existing.folderUri
                ?: throw AutoBackupFolder.Failure("FOLDER_REQUIRED", "Choose a backup folder")
            if (!AutoBackupFolder.hasWritePermission(app, folder)) {
                throw AutoBackupFolder.Failure(
                    "FOLDER_UNAVAILABLE",
                    "Choose the backup folder again",
                )
            }
        }
        if (encrypt && password.isNullOrEmpty() &&
            (clearPassword || existing.password.isEmpty())
        ) {
            throw AutoBackupFolder.Failure(
                "PASSWORD_REQUIRED",
                "Enter a backup password",
            )
        }
        val saved = store.save(
            enabled = enabled,
            folderUri = existing.folderUri,
            encrypt = encrypt,
            suppliedPassword = password,
            clearPassword = clearPassword,
        )
        if (saved.enabled) AutoBackupScheduler.schedule(app) else AutoBackupScheduler.cancel(app)
        return publicSettings(saved)
    }

    private fun clearFolder(): Map<String, Any?> {
        val saved = store.setFolder(null)
        AutoBackupScheduler.cancel(app)
        return publicSettings(saved)
    }

    private fun runNow(): Boolean {
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
        AutoBackupScheduler.runNow(app)
        return true
    }

    private fun chooseFolder(result: MethodChannel.Result) {
        synchronized(lock) {
            if (!attached) {
                throw AutoBackupFolder.Failure(
                    "ACTIVITY_UNAVAILABLE",
                    "Reopen Clockrove to choose a folder",
                )
            }
            if (pendingFolder != null) {
                throw AutoBackupFolder.Failure(
                    "DOCUMENT_PICKER_OPEN",
                    "Finish choosing a folder first",
                )
            }
            pendingFolder = result
        }
        val posted = main.post {
            val stillPending = synchronized(lock) { attached && pendingFolder === result }
            if (!stillPending) return@post
            if (activity.isFinishing) {
                synchronized(lock) { if (pendingFolder === result) pendingFolder = null }
                result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to choose a folder", null)
            } else if (documentActionPending()) {
                synchronized(lock) { if (pendingFolder === result) pendingFolder = null }
                result.error("DOCUMENT_PICKER_OPEN", "Finish the open document action first", null)
            } else {
                try {
                    activity.startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                            )
                        },
                        REQUEST_FOLDER,
                    )
                } catch (_: ActivityNotFoundException) {
                    synchronized(lock) { if (pendingFolder === result) pendingFolder = null }
                    result.error("NO_DOCUMENT_PICKER", "No folder picker is installed", null)
                } catch (_: SecurityException) {
                    synchronized(lock) { if (pendingFolder === result) pendingFolder = null }
                    result.error("FOLDER_UNAVAILABLE", "The folder picker could not be opened", null)
                } catch (_: RuntimeException) {
                    synchronized(lock) { if (pendingFolder === result) pendingFolder = null }
                    result.error("FOLDER_UNAVAILABLE", "The folder picker could not be opened", null)
                }
            }
        }
        if (!posted) {
            synchronized(lock) { if (pendingFolder === result) pendingFolder = null }
            result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to choose a folder", null)
        }
    }

    private fun publicSettings(
        settings: AutoBackupSettingsStore.Settings = store.read(),
    ): Map<String, Any?> {
        val folder = settings.folderUri
        val available = folder != null && AutoBackupFolder.hasWritePermission(app, folder)
        return settings.publicMap(
            folderLabel = if (available) AutoBackupFolder.displayName(app, folder) else null,
        ) + mapOf("folderAvailable" to available)
    }

    private class Input(arguments: Any?) {
        private val values: Map<*, *> = when (arguments) {
            is Map<*, *> -> arguments
            else -> throw IllegalArgumentException("Expected a map")
        }

        fun bool(key: String, default: Boolean? = null): Boolean {
            val raw = values[key]
            if (raw == null && default != null) return default
            return raw as? Boolean ?: throw IllegalArgumentException("Missing boolean")
        }

        fun nullableString(key: String): String? {
            val raw = values[key] ?: return null
            return (raw as? String)?.also { require(it.length <= 1024) }
                ?: throw IllegalArgumentException("Invalid string")
        }
    }

    private companion object {
        const val REQUEST_FOLDER = 0xcc48
    }
}
