package io.github.centerionware.clockrove.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.centerionware.clockrove.tracking.TimerRepository
import io.github.centerionware.clockrove.widget.TimerWidget
import java.util.concurrent.Executors

class NotificationStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getLongExtra("version", -1)
        if (version < 0) return
        val pending = goAsync()
        worker.execute {
            try {
                TimerRepository.get(context).executeFromSurface("notification-stop-$version", version,
                    TimerRepository.Action.STOP, null, TimerRepository.Origin.NOTIFICATION)
                TimerWidget.refreshAll(context)
            } finally { pending.finish() }
        }
    }
    companion object { private val worker = Executors.newSingleThreadExecutor() }
}
