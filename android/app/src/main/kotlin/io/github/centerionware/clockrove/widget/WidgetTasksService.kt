package io.github.centerionware.clockrove.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.tracking.TimerRepository
import io.github.centerionware.clockrove.tracking.WidgetTasksStore

/** Launcher collections scroll natively without keeping Flutter or a timer service alive. */
class WidgetTasksService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    private inner class Factory(private val id: Int) : RemoteViewsFactory {
        private var items: List<WidgetTasksStore.Task> = emptyList()
        private var today = ""
        private var failed = false
        override fun onCreate() = Unit
        override fun onDestroy() { items = emptyList() }
        override fun onDataSetChanged() {
            try {
                if (!TimerWidget.owned(this@WidgetTasksService, id)) { items = emptyList(); failed = false; return }
                val data = TimerRepository.get(this@WidgetTasksService).widgetTasks(id)
                items = data.items; today = data.today; failed = false
            } catch (_: Exception) { items = emptyList(); failed = true }
        }
        override fun getCount(): Int = if (failed) 1 else items.size
        override fun getViewAt(position: Int): RemoteViews? {
            val views = RemoteViews(packageName, R.layout.widget_task_row)
            if (failed) { views.setTextViewText(R.id.widget_task_title, "Tasks unavailable. Open Clockrove to retry."); return views }
            val item = items.getOrNull(position) ?: return null
            val date = if (item.due < today) "Overdue · ${item.due}" else if (item.due == today) "Today" else item.due
            views.setTextViewText(R.id.widget_task_title, item.title)
            views.setTextViewText(R.id.widget_task_detail, "${item.jobName} · $date")
            views.setTextColor(R.id.widget_task_detail, if (item.due < today) 0xFFFFB4AB.toInt() else 0xFFCBD5E1.toInt())
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
