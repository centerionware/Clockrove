package io.github.centerionware.clockrove.autobackup

import io.flutter.embedding.engine.dart.DartExecutor

/** Points the headless engine at the worker function rather than the app entrypoint. */
internal object AutoBackupEntrypoint {
    const val DART_LIBRARY = "package:clockrove/auto_backup_worker.dart"
    const val DART_FUNCTION = "autoBackupMain"

    fun create(bundlePath: String) = DartExecutor.DartEntrypoint(
        bundlePath,
        DART_LIBRARY,
        DART_FUNCTION,
    )
}
