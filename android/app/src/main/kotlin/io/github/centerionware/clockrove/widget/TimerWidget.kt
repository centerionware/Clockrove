package io.github.centerionware.clockrove.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.RemoteViews
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.util.concurrent.Executors

/** Operates without starting a Flutter engine. No location permission is needed to stop. */
class TimerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        worker.execute { try { refresh(context, manager, ids) } finally { pending.finish() } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != STOP) { super.onReceive(context, intent); return }
        val pending = goAsync()
        worker.execute {
            try {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val version = intent.getLongExtra("version", -1)
                val manager = AppWidgetManager.getInstance(context)
                val owned = manager.getAppWidgetIds(ComponentName(context, TimerWidget::class.java))
                if (widgetId in owned && version >= 0) {
                    TimerRepository.get(context).executeFromSurface(
                        "widget-stop-$widgetId-$version", version, TimerRepository.Action.STOP,
                        null, TimerRepository.Origin.WIDGET,
                    )
                }
                refreshAll(context)
            } finally { pending.finish() }
        }
    }

    companion object {
        private const val STOP = "io.github.centerionware.clockrove.WIDGET_STOP"
        private val worker = Executors.newSingleThreadExecutor()

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            refresh(context, manager, manager.getAppWidgetIds(ComponentName(context, TimerWidget::class.java)))
        }

        private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snapshot = TimerRepository.get(context).snapshot()
            ids.forEach { id -> manager.updateAppWidget(id, viewsFor(context, snapshot, id)) }
        }

        internal fun viewsFor(context: Context, snapshot: TimerRepository.Snapshot, id: Int): RemoteViews {
            val running = snapshot.running
            val views = RemoteViews(context.packageName, R.layout.timer_widget)
            views.setTextViewText(R.id.widget_job, running?.jobName ?: context.getString(R.string.widget_idle))
            val showElapsed = running != null && context.resources.configuration.fontScale <= 1.3f
            views.setViewVisibility(R.id.widget_elapsed, if (showElapsed) android.view.View.VISIBLE else android.view.View.GONE)
            if (running != null) {
                // RemoteViews chronometer handles display ticks; no persistent counter writer.
                val elapsed = (System.currentTimeMillis() - running.startUtcMillis).coerceAtLeast(0)
                views.setChronometer(R.id.widget_elapsed, SystemClock.elapsedRealtime() - elapsed, null, showElapsed)
            }
            views.setContentDescription(R.id.widget_action, context.getString(if (running == null) R.string.widget_open else R.string.widget_stop))
            views.setImageViewResource(R.id.widget_action, if (running == null) R.drawable.ic_widget_open else R.drawable.ic_widget_stop)
            val tap = if (running == null) {
                PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else {
                val intent = Intent(context, TimerWidget::class.java).setAction(STOP)
                    .setData(Uri.parse("clockrove://widget/$id/stop/${snapshot.version}"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("version", snapshot.version)
                PendingIntent.getBroadcast(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            views.setOnClickPendingIntent(R.id.widget_action, tap)
            return views
        }
    }
}
