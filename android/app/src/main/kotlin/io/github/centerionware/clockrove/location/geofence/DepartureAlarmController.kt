package io.github.centerionware.clockrove.location.geofence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.centerionware.clockrove.tracking.TimerRepository

/** Inexact deadline wake-up only. Never a location sampler or a second timer writer. */
internal class DepartureAlarmController(
    private val context: Context,
    private val repository: TimerRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun reconcile() {
        val candidate = repository.schedulableDeparture()
        if (candidate == null) {
            cancel()
            return
        }
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                maxOf(candidate.deadlineUtcMillis, now()), operation(candidate))
        } catch (_: RuntimeException) {
            // Preserve work when Android cannot arrange expiry; manual commands still work.
            repository.geofenceProblem(TimerRepository.GeofenceProblem.RECOVERY_UNCERTAIN)
            cancel()
        }
    }

    fun deliver(intent: Intent): TimerRepository.DepartureResult? {
        if (intent.action != ACTION) return null
        val session = intent.getStringExtra(SESSION) ?: return null
        if (!intent.hasExtra(DEADLINE)) return null
        val deadline = intent.getLongExtra(DEADLINE, -1)
        if (deadline < 0) return null
        // DB checks session, exact candidate generation, due time and review atomically.
        val result = repository.finalizeDeparture(session, now(), deadline)
        reconcile()
        return result
    }

    fun recover() {
        // A reboot or wall-clock edit is not proof that the worker stayed outside.
        repository.geofenceProblem(TimerRepository.GeofenceProblem.RECOVERY_UNCERTAIN)
        reconcile()
    }

    private fun operation(candidate: TimerRepository.Departure): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST, baseIntent()
            .putExtra(SESSION, candidate.sessionId).putExtra(DEADLINE, candidate.deadlineUtcMillis),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancel() {
        val pending = PendingIntent.getBroadcast(context, REQUEST, baseIntent(),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarms.cancel(pending)
        // Do not cancel the token: a previously delivered Intent must remain guarded by SQL.
    }

    private fun baseIntent() = Intent(context, DepartureAlarmReceiver::class.java)
        .setAction(ACTION).setData(Uri.parse("clockrove://automation/departure"))

    companion object {
        const val ACTION = "io.github.centerionware.clockrove.DEPARTURE_DUE"
        const val SESSION = "session"
        const val DEADLINE = "deadline"
        private const val REQUEST = 207
    }
}
