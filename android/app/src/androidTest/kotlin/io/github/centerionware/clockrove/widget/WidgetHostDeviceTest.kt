package io.github.centerionware.clockrove.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.tracking.BoardStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual framework host, remote service, scrolling and PendingIntent handoff. Not a launcher mock. */
@RunWith(AndroidJUnit4::class)
class WidgetHostDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private fun until(description: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < end) {
            var success = false
            instrumentation.runOnMainSync { success = predicate() }
            if (success) return
            SystemClock.sleep(100)
        }
        throw AssertionError(description)
    }
    private fun options(width: Int, height: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
    }
    @Test fun combinedResizesScrollsActualCollectionAndTaskTapOpensMatchingBoard() {
        val repository = TimerRepository.get(app)
        val job = repository.createJob("Synthetic widget board")
        val board = repository.boardSnapshot(job).board
        val column = board.columns.first { !it.completed }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        repeat(30) { index ->
            val id = "widget-device-card-${index.toString().padStart(2, '0')}"
            assertNull(repository.saveCard("save-$id", repository.snapshot().version,
                BoardStore.Draft(id, job, column.id, "Synthetic due task $index", dueDate = date, currency = board.currency)).conflict)
        }
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 913)
        val id = host.allocateAppWidgetId()
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var view: AppWidgetHostView? = null
        try {
            assertTrue("CI must grant widget binding before instrumentation", manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, TimerWidget::class.java), options(132, 56)))
            assertNull(repository.saveWidgetConfiguration("widget-device-binding", repository.snapshot().version, id, job).conflict)
            instrumentation.runOnMainSync {
                host.startListening()
                view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                activity.setContentView(view, android.view.ViewGroup.LayoutParams(-1, -1))
            }
            TimerWidget.refreshAll(app)
            until("Compact Combined must omit task list") { view!!.findViewById<View>(R.id.widget_action) != null && view!!.findViewById<View>(R.id.widget_tasks) == null }
            manager.updateAppWidgetOptions(id, options(300, 340))
            TimerWidget.refreshAll(app)
            until("Expanded Combined must bind all 30 tasks through RemoteViewsService") { view!!.findViewById<ListView>(R.id.widget_tasks)?.count == 30 }
            instrumentation.runOnMainSync { view!!.findViewById<ListView>(R.id.widget_tasks).setSelection(29) }
            until("Task collection must scroll to its final row") { view!!.findViewById<ListView>(R.id.widget_tasks)?.lastVisiblePosition == 29 }
            val file = File(app.getExternalFilesDir(null), "widget-combined.png")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            val monitor = instrumentation.addMonitor(WidgetBoardActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync {
                    val list = view!!.findViewById<ListView>(R.id.widget_tasks)
                    val child = list.getChildAt(list.childCount - 1)
                    assertTrue(list.performItemClick(child, 29, list.adapter.getItemId(29)))
                }
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 30000)
                assertNotNull("Task PendingIntent must open actual board activity", opened)
                assertEquals(job, opened!!.intent.getStringExtra("jobId"))
                assertEquals("widget-device-card-29", opened.intent.getStringExtra("taskId"))
                assertTrue((opened as WidgetBoardActivity).getInitialRoute().contains(job))
                instrumentation.runOnMainSync { opened.finish() }
            } finally { instrumentation.removeMonitor(monitor) }
            assertNull("Task tap must never start a timer", repository.snapshot().running)
        } finally {
            repository.removeWidgetConfigurations(listOf(id))
            instrumentation.runOnMainSync { host.stopListening(); host.deleteHost(); activity.finish() }
        }
    }
    @Test fun threeProvidersAreDiscoverableAndOnlyTimerProviderRemainsCompactAtLargeSizes() {
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 914)
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val ids = mutableListOf<Int>()
        try {
            for (provider in TimerWidget.providers) {
                val id = host.allocateAppWidgetId(); ids.add(id)
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, provider), options(300, 340)))
                var view: AppWidgetHostView? = null
                instrumentation.runOnMainSync { host.startListening(); view = host.createView(activity, id, manager.getAppWidgetInfo(id)); activity.setContentView(view) }
                TimerWidget.refreshAll(app)
                until("Provider must render its intended surface: ${provider.simpleName}") {
                    if (provider == TimerOnlyWidget::class.java) view!!.findViewById<View>(R.id.widget_action) != null && view!!.findViewById<View>(R.id.widget_tasks) == null
                    else view!!.findViewById<View>(R.id.widget_tasks) != null
                }
            }
        } finally {
            TimerRepository.get(app).removeWidgetConfigurations(ids)
            instrumentation.runOnMainSync { host.stopListening(); host.deleteHost(); activity.finish() }
        }
    }
}
