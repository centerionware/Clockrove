package io.github.centerionware.clockrove.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.util.concurrent.Executors

/** Existing provider remains the combined widget so installed widgets survive upgrades. */
open class TimerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)
    private fun update(context: Context) {
        val pending = goAsync()
        worker.execute { try { refreshAll(context) } finally { pending.finish() } }
    }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        // Launcher IDs are device-local. Restored widgets safely return to picker
        // instead of applying a stale binding or ever starting work on restore.
        val pending = goAsync()
        worker.execute { try { TimerRepository.get(context).removeWidgetConfigurations(oldIds.filter { it !in newIds }.distinct()); refreshAll(context) } catch (_: Exception) { refreshAll(context) } finally { pending.finish() } }
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        val pending = goAsync()
        worker.execute { try { TimerRepository.get(context).removeWidgetConfigurations(ids.toList()); refreshAll(context) } catch (_: Exception) { refreshAll(context) } finally { pending.finish() } }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED)) { update(context); return }
        if (intent.action != STOP) { super.onReceive(context, intent); return }
        val pending = goAsync()
        worker.execute {
            try {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val version = intent.getLongExtra("version", -1)
                if (owned(context, id) && version >= 0) TimerRepository.get(context).executeFromSurface(
                    "widget-stop-$id-$version", version, TimerRepository.Action.STOP, null, TimerRepository.Origin.WIDGET)
                refreshAll(context)
            } catch (_: Exception) { refreshAll(context) } finally { pending.finish() }
        }
    }
    companion object {
        private const val STOP = "io.github.centerionware.clockrove.WIDGET_STOP"
        private val worker = Executors.newSingleThreadExecutor()
        internal val providers = listOf(TimerWidget::class.java, TimerOnlyWidget::class.java, TaskListWidget::class.java)
        internal fun owned(context: Context, id: Int): Boolean = id > 0 && providers.any {
            id in AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it))
        }
        @Synchronized
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val repository = TimerRepository.get(context)
            providers.forEach { provider -> manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id ->
                try {
                val bound = repository.widgetSnapshot(id)
                val options = manager.getAppWidgetOptions(id)
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 56)
                val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
                val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
                val portrait = viewsForSize(context, bound, id, provider, minWidth, maxHeight)
                val landscape = viewsForSize(context, bound, id, provider, maxWidth, minHeight)
                val views = RemoteViews(landscape, portrait)
                manager.updateAppWidget(id, views)
                if (provider != TimerOnlyWidget::class.java) manager.notifyAppWidgetViewDataChanged(id, R.id.widget_tasks)
                } catch (_: Exception) {
                    // A failed refresh must not crash an already committed timer/settings command.
                    val unavailable = RemoteViews(context.packageName, R.layout.timer_widget).apply {
                        setTextViewText(R.id.widget_job, "Widget unavailable · open app")
                        setViewVisibility(R.id.widget_elapsed, View.GONE)
                        setImageViewResource(R.id.widget_action, R.drawable.ic_widget_open)
                        setContentDescription(R.id.widget_action, context.getString(R.string.widget_open))
                        setOnClickPendingIntent(R.id.widget_action, PendingIntent.getActivity(context, id,
                            Intent(context, WidgetPickerActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                    try { manager.updateAppWidget(id, unavailable) } catch (_: RuntimeException) { /* Host removed this widget. */ }
                }
            } }
        }
        internal fun viewsForSize(context: Context, bound: TimerRepository.WidgetSnapshot, id: Int, provider: Class<out TimerWidget>, width: Int, height: Int): RemoteViews {
                val tasks = provider == TaskListWidget::class.java || (provider == TimerWidget::class.java && hasTaskRoom(width, height, context.resources.configuration.fontScale))
                val views = if (!tasks) viewsFor(context, bound.state, id, bound) else RemoteViews(context.packageName, R.layout.tasks_widget).apply {
                    // Reapply must replace its one timer row, never append to the
                    // existing RemoteViews hierarchy on every committed update.
                    removeAllViews(R.id.widget_timer_container)
                    if (provider == TaskListWidget::class.java) setViewVisibility(R.id.widget_timer_container, View.GONE)
                    else addView(R.id.widget_timer_container, viewsFor(context, bound.state, id, bound))
                    val taskTitle = bound.defaultJob?.let { "Due soon · ${it.name}" } ?: if (bound.missingDefault) "Job unavailable · configure" else "Due soon · all jobs"
                    setTextViewText(R.id.widget_tasks_title, taskTitle)
                    setContentDescription(R.id.widget_tasks_title, "$taskTitle. Change widget settings")
                    setOnClickPendingIntent(R.id.widget_tasks_title, configureIntent(context, id))
                    setTextViewText(R.id.widget_tasks_empty, if (bound.missingDefault) "Choose an active job in widget settings" else "No tasks due in the next 7 days")
                    val adapter = Intent(context, WidgetTasksService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .setData(Uri.parse("clockrove://widget/$id/tasks/${bound.configuration.token ?: "picker"}"))
                    setRemoteAdapter(R.id.widget_tasks, adapter)
                    setEmptyView(R.id.widget_tasks, R.id.widget_tasks_empty)
                    val template = Intent(context, WidgetBoardActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .setData(Uri.parse("clockrove://widget/$id/board"))
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    setPendingIntentTemplate(R.id.widget_tasks, PendingIntent.getActivity(context, id, template, flags))
                }
                if (!tasks || provider != TaskListWidget::class.java) {
                    // The timer's title also provides configuration on launchers without a reconfigure menu.
                    if (!tasks) views.setOnClickPendingIntent(R.id.widget_job, configureIntent(context, id))
                }
                return views
        }
        internal fun hasTaskRoom(width: Int, height: Int, fontScale: Float): Boolean = width >= 180 && height >= 64 + (120 * fontScale.coerceAtLeast(1f)).toInt()
        internal fun configureIntent(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(context, id,
            Intent(context, WidgetConfigurationActivity::class.java).setData(Uri.parse("clockrove://widget/$id/configure"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        internal fun viewsFor(context: Context, snapshot: TimerRepository.Snapshot, id: Int, bound: TimerRepository.WidgetSnapshot? = null): RemoteViews {
            val running = snapshot.running
            val views = RemoteViews(context.packageName, R.layout.timer_widget)
            views.setTextViewText(R.id.widget_job, running?.jobName ?: bound?.defaultJob?.name ?: if (bound?.missingDefault == true) "Job unavailable · choose" else context.getString(R.string.widget_idle))
            val showElapsed = running != null && context.resources.configuration.fontScale <= 1.3f
            views.setViewVisibility(R.id.widget_elapsed, if (showElapsed) View.VISIBLE else View.GONE)
            if (running != null) {
                val elapsed = (System.currentTimeMillis() - running.startUtcMillis).coerceAtLeast(0)
                views.setChronometer(R.id.widget_elapsed, SystemClock.elapsedRealtime() - elapsed, null, showElapsed)
            }
            views.setContentDescription(R.id.widget_action, if (running != null) context.getString(R.string.widget_stop) else if (bound?.defaultJob != null) "Clock in to ${bound.defaultJob!!.name}" else context.getString(R.string.widget_open))
            views.setImageViewResource(R.id.widget_action, if (running != null) R.drawable.ic_widget_stop else if (bound?.defaultJob != null) R.drawable.ic_widget_start else R.drawable.ic_widget_open)
            val tap = if (running == null) {
                val intent = Intent(context, WidgetStartActivity::class.java).setData(Uri.parse("clockrove://widget/$id/start/${snapshot.version}/${bound?.configuration?.token}"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("version", snapshot.version)
                    .putExtra("token", bound?.configuration?.token).putExtra("jobId", bound?.defaultJob?.id)
                PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else PendingIntent.getBroadcast(context, id, Intent(context, TimerWidget::class.java).setAction(STOP)
                .setData(Uri.parse("clockrove://widget/$id/stop/${snapshot.version}"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("version", snapshot.version), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_action, tap)
            views.setOnClickPendingIntent(R.id.widget_job, configureIntent(context, id))
            return views
        }
    }
}
class TimerOnlyWidget : TimerWidget()
class TaskListWidget : TimerWidget()
