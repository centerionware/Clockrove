package io.github.centerionware.clockrove.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.centerionware.clockrove.tracking.TimerRepository
import io.github.centerionware.clockrove.widget.TimerWidget
import java.util.concurrent.Executors

/** A process-lifetime observer. Every callback re-reads committed native state. */
internal object DepartureRuntime {
    internal val executor = Executors.newSingleThreadExecutor()
    private var subscription: AutoCloseable? = null

    fun attach(context: Context, repository: TimerRepository) {
        val controller = DepartureAlarmController(context, repository)
        subscription = repository.subscribe { executor.execute { controller.reconcile() } }
    }

    fun receive(context: Context, intent: Intent, recover: Boolean, finish: () -> Unit) {
        executor.execute {
            try {
                val controller = DepartureAlarmController(context, TimerRepository.get(context))
                if (recover) {
                    controller.recover()
                    GeofenceRuntime.resume(context, recovery = true)
                } else controller.deliver(intent)
                TimerWidget.refreshAll(context)
            } finally { finish() }
        }
    }
}

class DepartureAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DepartureAlarmController.ACTION) return
        val pending = goAsync()
        DepartureRuntime.receive(context.applicationContext, intent, false) { pending.finish() }
    }
}

class DepartureRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_TIME_CHANGED) return
        val pending = goAsync()
        DepartureRuntime.receive(context.applicationContext, intent, true) { pending.finish() }
    }
}
