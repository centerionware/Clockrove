package io.github.centerionware.clockrove.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.centerionware.clockrove.tracking.TimerRepository
import io.github.centerionware.clockrove.widget.TimerWidget
import java.util.concurrent.Executors

/** A process-lifetime observer. Every callback re-reads committed native state. */
internal object DepartureRuntime {
    private val worker = Executors.newSingleThreadExecutor()
    private var subscription: AutoCloseable? = null

    fun attach(context: Context, repository: TimerRepository) {
        val controller = DepartureAlarmController(context, repository)
        subscription = repository.subscribe { worker.execute { controller.reconcile() } }
    }

    fun receive(context: Context, intent: Intent, recover: Boolean, finish: () -> Unit) {
        worker.execute {
            try {
                val controller = DepartureAlarmController(context, TimerRepository.get(context))
                if (recover) controller.recover() else controller.deliver(intent)
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
