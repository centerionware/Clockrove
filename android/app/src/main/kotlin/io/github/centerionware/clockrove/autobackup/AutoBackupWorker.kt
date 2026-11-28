package io.github.centerionware.clockrove.autobackup

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.github.centerionware.clockrove.tracking.BackupHostApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

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
        // FlutterEngine's platform task runner is the Android main thread.
        // Build and destroy it there, then wait on WorkManager's worker thread.
        val session = onMainThread { createHeadlessSession() }
        return try {
            session.bridge.await(DART_TIMEOUT_MILLIS)
        } finally {
            onMainThread { session.close() }
        }
    }

    private fun createHeadlessSession(): HeadlessSession {
        val bridge = AutoBackupWorkerBridge()
        val engine = FlutterEngine(applicationContext)
        try {
            val backupHost = BackupHostApi(
                applicationContext,
                engine.dartExecutor.binaryMessenger,
            )
            val autoHost = AutoBackupWorkerHostApi(
                applicationContext,
                engine.dartExecutor.binaryMessenger,
                bridge,
            )
            // The first DartEntrypoint argument is the Flutter asset bundle,
            // not the Dart library. Put the package URI in the library slot so
            // the headless release engine can find autoBackupMain.
            engine.dartExecutor.executeDartEntrypoint(
                AutoBackupEntrypoint.create(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                ),
            )
            return HeadlessSession(engine, backupHost, autoHost, bridge)
        } catch (failure: Throwable) {
            engine.destroy()
            throw failure
        }
    }

    private fun <T> onMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val future = CompletableFuture<T>()
        val posted = Handler(Looper.getMainLooper()).post {
            try {
                future.complete(block())
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        if (!posted) throw IllegalStateException("The main thread is unavailable")
        return try {
            future.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private class HeadlessSession(
        private val engine: FlutterEngine,
        private val backupHost: BackupHostApi,
        private val autoHost: AutoBackupWorkerHostApi,
        val bridge: AutoBackupWorkerBridge,
    ) {
        fun close() {
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
        const val MAIN_THREAD_TIMEOUT_SECONDS = 10L
        const val MAX_ATTEMPTS = 2
    }
}
