package io.github.centerionware.clockrove.autobackup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object AutoBackupScheduler {
    private const val PERIODIC_WORK = "clockrove-auto-backup-daily"
    private const val NOW_WORK = "clockrove-auto-backup-now"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(PERIODIC_WORK)
        manager.cancelUniqueWork(NOW_WORK)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            NOW_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun constraints() = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .build()
}
