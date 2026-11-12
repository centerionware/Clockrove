package io.github.centerionware.clockrove.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.tracking.TimerRepository
import io.github.centerionware.clockrove.tracking.WidgetTasksStore

/** Launcher collections scroll natively without keeping Flutter or a timer service alive. */
class WidgetTasksService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    private data class Rows(val items: List<WidgetTasksStore.Task> = emptyList(), val today: String = "", val failed: Boolean = false, val filtered: Boolean = false)
    private inner class Factory(private val id: Int) : RemoteViewsFactory {
        // Framework adapters may call the same factory through different Binder
        // connections during a resize/reapply. Publish one complete snapshot.
        @Volatile private var rows = Rows()
        private fun trace(event: String) {
            android.util.Log.d("ClockroveWidgetFactory", "$event widget=$id factory=${System.identityHashCode(this)} count=${rows.items.size} failed=${rows.failed}")
        }
        override fun onCreate() { trace("create"); onDataSetChanged() }
        override fun onDestroy() {
            // No owned resources need closing. In-flight callbacks on an old
            // adapter must not see its cache cleared by another connection.
            // The framework releases the factory and its immutable rows together.
            trace("destroy")
        }
        @Synchronized
        override fun onDataSetChanged() {
            rows = try {
                if (!TimerWidget.owned(this@WidgetTasksService, id)) {
                    trace("unowned")
                    Rows()
                } else {
                    val data = TimerRepository.get(this@WidgetTasksService).widgetTasks(id)
                    Rows(data.items.toList(), data.today, filtered = data.jobId != null)
                }
            } catch (failure: Exception) {
                android.util.Log.w("ClockroveWidgetFactory", "Load failed: ${failure.javaClass.simpleName}")
                Rows(failed = true)
            }
            trace("changed")
        }
        override fun getCount(): Int = rows.let { if (it.failed) 1 else it.items.size }
        override fun getViewAt(position: Int): RemoteViews? {
            val data = rows
            val views = RemoteViews(packageName, R.layout.widget_task_row)
            if (data.failed) { views.setTextViewText(R.id.widget_task_title, "Tasks unavailable. Open Clockrove to retry."); return views }
            val item = data.items.getOrNull(position) ?: return null
            val date = if (item.due < data.today) "Overdue · ${item.due}" else if (item.due == data.today) "Today" else item.due
            views.setTextViewText(R.id.widget_task_title, item.title)
            views.setTextViewText(R.id.widget_task_detail, if (data.filtered) date else "${item.jobName} · $date")
            views.setTextColor(R.id.widget_task_detail, if (item.due < data.today) 0xFFFFB4AB.toInt() else 0xFFCBD5E1.toInt())
            views.setContentDescription(R.id.widget_task_row, "${item.title}, ${item.jobName}, $date. Open job board")
            views.setOnClickFillInIntent(R.id.widget_task_row, Intent().putExtra("jobId", item.jobId).putExtra("taskId", item.id))
            return views
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds() = false
    }
}
