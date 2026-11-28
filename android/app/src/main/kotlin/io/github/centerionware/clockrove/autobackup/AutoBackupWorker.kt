package io.github.centerionware.clockrove.autobackup

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.github.centerionware.clockrove.tracking.BackupHostApi

/** Runs even when Clockrove has no Activity, using a short-lived headless engine. */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val store = AutoBackupSettingsStore(applicationContext)
        val settings = try {
            store.read()
        } catch (_: Exception) {
            return Result.failure()
        }
        if (!settings.enabled) return Result.success()
        val folder = settings.folderUri
        if (folder == null) {
            recordFailure(store, "FOLDER_REQUIRED")
            return Result.failure()
        }
        if (!AutoBackupFolder.hasWritePermission(applicationContext, folder)) {
            recordFailure(store, "FOLDER_UNAVAILABLE")
            return Result.failure()
        }
        val outcome = try {
            runHeadlessBackup()
        } catch (_: Exception) {
            recordFailure(store, "WORKER_FAILED")
            return retryOrFail()
        }
        return when {
            outcome == null -> {
                recordFailure(store, "TIMEOUT")
                retryOrFail()
            }
            outcome.ok -> Result.success()
            outcome.retry -> Result.retry()
            else -> Result.failure()
        }
    }

    private fun runHeadlessBackup(): AutoBackupWorkerBridge.Outcome? {
        val bridge = AutoBackupWorkerBridge()
        val engine = FlutterEngine(applicationContext)
        val backupHost = BackupHostApi(
            applicationContext,
            engine.dartExecutor.binaryMessenger,
        )
        val autoHost = AutoBackupWorkerHostApi(
            applicationContext,
            engine.dartExecutor.binaryMessenger,
            bridge,
        )
        return try {
            engine.dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint(
                    "package:clockrove/auto_backup_worker.dart",
                    "autoBackupMain",
                ),
            )
            bridge.await(DART_TIMEOUT_MILLIS)
        } finally {
            autoHost.detach()
            backupHost.detach()
            engine.destroy()
        }
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    private fun recordFailure(store: AutoBackupSettingsStore, code: String) {
        try {
            store.recordResult(success = false, code = code, fileName = null)
        } catch (_: Exception) {
            // The next run reports the same failure from the settings channel.
        }
    }

    private companion object {
        const val DART_TIMEOUT_MILLIS = 9L * 60L * 1000L
        const val MAX_ATTEMPTS = 2
    }
}
