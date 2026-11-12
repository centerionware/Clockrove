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
    @get:org.junit.Rule val caseName = org.junit.rules.TestName()
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
        captureSetup("failure")
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
        val activity = instrumentation.startActivitySync(Intent(app, WidgetTestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WidgetTestHostActivity
        var view: AppWidgetHostView? = null
        try {
            assertTrue("CI must grant widget binding before instrumentation", manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, TimerWidget::class.java), options(132, 56)))
            assertNull(repository.saveWidgetConfiguration("widget-device-binding", repository.snapshot().version, id, job).conflict)
            instrumentation.runOnMainSync {
                host.startListening()
                view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                val container = FrameLayout(activity)
                val density = activity.resources.displayMetrics.density
                container.addView(view, FrameLayout.LayoutParams((132 * density).toInt(), (56 * density).toInt()))
                activity.setContentView(container)
            }
            TimerWidget.refreshAll(app)
            until("Compact Combined must omit task list") { view!!.findViewById<View>(R.id.widget_action) != null && view!!.findViewById<View>(R.id.widget_tasks) == null }
            instrumentation.runOnMainSync {
                val density = activity.resources.displayMetrics.density
                view!!.layoutParams = FrameLayout.LayoutParams((300 * density).toInt(), (340 * density).toInt())
            }
            manager.updateAppWidgetOptions(id, options(300, 340))
            TimerWidget.refreshAll(app)
            until("Expanded Combined must bind all 30 tasks through RemoteViewsService") { view!!.findViewById<ListView>(R.id.widget_tasks)?.count == 30 }
            instrumentation.runOnMainSync { view!!.findViewById<ListView>(R.id.widget_tasks).setSelection(29) }
            until("Task collection must load and scroll to its final row") {
                val list = view!!.findViewById<ListView>(R.id.widget_tasks)
                list.lastVisiblePosition == 29 && list.getChildAt(list.childCount - 1)?.findViewById<android.widget.TextView>(R.id.widget_task_title)?.text == "Synthetic due task 29"
            }
            val file = File(app.getExternalFilesDir(null), "widget-combined.png")
            instrumentation.runOnMainSync {
                val bitmap = android.graphics.Bitmap.createBitmap(view!!.width, view!!.height, android.graphics.Bitmap.Config.ARGB_8888)
                view!!.draw(android.graphics.Canvas(bitmap))
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val monitor = instrumentation.addMonitor(WidgetBoardActivity::class.java.name, null, false)
            var opened: android.app.Activity? = null
            try {
                instrumentation.runOnMainSync {
                    val list = view!!.findViewById<ListView>(R.id.widget_tasks)
                    val child = list.getChildAt(list.childCount - 1)
                    assertTrue(list.performItemClick(child, 29, list.adapter.getItemId(29)))
                }
                opened = instrumentation.waitForMonitorWithTimeout(monitor, 30000)
                assertNotNull("Task PendingIntent must open actual board activity", opened)
                val boardActivity = opened as WidgetBoardActivity
                assertEquals(job, boardActivity.intent.getStringExtra("jobId"))
                assertEquals("widget-device-card-29", boardActivity.intent.getStringExtra("taskId"))
                assertTrue(boardActivity.getInitialRoute().contains(job))
                until("Task tap must focus the actual board window") { boardActivity.hasWindowFocus() }
                clickText("Synthetic widget board", false)
                clickText("Task board", false)
                clickText("0 of 30 tasks complete", false)
                clickText("To do", false)
                instrumentation.uiAutomation.waitForIdle(300, 3000)
                SystemClock.sleep(300)
                captureSetup("board")
            } finally { instrumentation.runOnMainSync { opened?.finish() }; instrumentation.removeMonitor(monitor) }
            assertNull("Task tap must never start a timer", repository.snapshot().running)
        } finally {
            repository.removeWidgetConfigurations(listOf(id))
            instrumentation.runOnMainSync { host.stopListening(); host.deleteHost(); activity.finish() }
        }
    }
    @Test fun threeProvidersAreDiscoverableAndOnlyTimerProviderRemainsCompactAtLargeSizes() {
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 914)
        val activity = instrumentation.startActivitySync(Intent(app, WidgetTestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WidgetTestHostActivity
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
    private fun captureSetup(kind: String = "setup") {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val directory = File(app.getExternalFilesDir(null), "widget-captures").apply { mkdirs() }
            File(directory, "${caseName.methodName}-$kind.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun clickText(label: String, click: Boolean = true, checked: Boolean = false) {
        var found: android.view.accessibility.AccessibilityNodeInfo? = null
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            // Refresh cached virtual nodes after an asynchronous Flutter data load.
            if (!node.refresh()) return null
            // Flutter may merge a ListTile's title and subtitle into one label.
            fun matches(value: CharSequence?) = value?.toString()?.lineSequence()?.any { it.trim() == label } == true
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { match -> return match } }
            if (node.isVisibleToUser && (!checked || node.isChecked) && (matches(node.text) || matches(node.contentDescription))) return node
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 30000
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            found = instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
            if (found == null) SystemClock.sleep(100)
        }
        if (found == null) {
            val dump = StringBuilder()
            fun describe(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                if (dump.length > 200000 || depth > 50) return
                dump.append(" ".repeat(depth)).append(node.toString()).append('\n')
                for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let { describe(it, 0) }
            File(app.getExternalFilesDir(null), "widget-accessibility.txt").writeText(dump.toString())
            captureSetup("failure")
        }
        var node = found ?: throw AssertionError("Actual widget setup did not show $label")
        if (!click) return
        while (true) {
            // Flutter's bridge acknowledges ACTION_CLICK even without a TAP
            // semantic action; acknowledgement alone does not prove a control tap.
            if (node.isClickable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent ?: throw AssertionError("Could not click $label")
        }
    }
    @Test fun actualFlutterConfigurationSavesBindingAndCancelKeepsIt() {
        val repository = TimerRepository.get(app)
        val job = repository.createJob("Widget setup choice")
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 915)
        val id = host.allocateAppWidgetId()
        var activity: WidgetConfigurationActivity? = null
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, TimerOnlyWidget::class.java), options(132, 56)))
            val intent = Intent(app, WidgetConfigurationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            activity = instrumentation.startActivitySync(intent) as WidgetConfigurationActivity
            clickText("Widget setup choice")
            clickText("Widget setup choice", click = false, checked = true)
            captureSetup()
            clickText("Save widget")
            until("Actual widget setup must commit and close") { activity!!.isFinishing && repository.widgetSnapshot(id).configuration.jobId == job }
            val binding = repository.widgetSnapshot(id).configuration
            activity = instrumentation.startActivitySync(intent) as WidgetConfigurationActivity
            clickText("Choose each time")
            clickText("Choose each time", click = false, checked = true)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            until("Cancel must close widget setup") { activity!!.isFinishing }
            assertEquals(binding, repository.widgetSnapshot(id).configuration)
            assertNull(repository.snapshot().running)
        } finally {
            repository.removeWidgetConfigurations(listOf(id))
            instrumentation.runOnMainSync { activity?.finish(); host.deleteHost() }
        }
    }

    @Test fun actualTimerActionsWorkWithoutFlutterOrLocationPermission() {
        val repository = TimerRepository.get(app)
        assertNull(repository.snapshot().running)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION))
        val job = repository.createJob("Synthetic native timer")
        val taskJob = repository.createJob("Synthetic task scope")
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 916)
        val id = host.allocateAppWidgetId()
        val taskWidgetId = host.allocateAppWidgetId()
        val activity = instrumentation.startActivitySync(Intent(app, WidgetTestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WidgetTestHostActivity
        val picker = instrumentation.addMonitor(WidgetPickerActivity::class.java.name, null, false)
        val start = instrumentation.addMonitor(WidgetStartActivity::class.java.name, null, false)
        var view: AppWidgetHostView? = null
        var taskView: AppWidgetHostView? = null
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, TimerOnlyWidget::class.java), options(132, 56)))
            assertNull(repository.saveWidgetConfiguration("native-timer-binding", repository.snapshot().version, id, job).conflict)
            assertTrue(manager.bindAppWidgetIdIfAllowed(taskWidgetId, ComponentName(app, TimerWidget::class.java), options(300, 340)))
            assertNull(repository.saveWidgetConfiguration("native-task-scope-binding", repository.snapshot().version, taskWidgetId, taskJob).conflict)
            // Publish the final configuration version before creating host views;
            // a cached earlier binding must not be the action this test clicks.
            TimerWidget.refreshAll(app)
            instrumentation.runOnMainSync {
                host.startListening()
                view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                val container = FrameLayout(activity)
                val density = activity.resources.displayMetrics.density
                container.addView(view, FrameLayout.LayoutParams((132 * density).toInt(), (56 * density).toInt()))
                taskView = host.createView(activity, taskWidgetId, manager.getAppWidgetInfo(taskWidgetId))
                container.addView(taskView, FrameLayout.LayoutParams((300 * density).toInt(), (340 * density).toInt()).apply { topMargin = (72 * density).toInt() })
                activity.setContentView(container)
            }
            repeat(3) { TimerWidget.refreshAll(app) }
            until("Configured clock-in control must be rendered") {
                view!!.findViewById<android.widget.ImageButton>(R.id.widget_action)?.contentDescription == "Clock in to Synthetic native timer"
            }
            instrumentation.runOnMainSync { assertTrue(view!!.findViewById<View>(R.id.widget_action).performClick()) }
            val started = instrumentation.waitForMonitorWithTimeout(start, 30000)
            assertTrue("PendingIntent must launch the native start activity", started is WidgetStartActivity)
            until("Actual widget PendingIntent must start its configured native job") { repository.snapshot().running?.jobId == job }
            assertNull(picker.waitForActivityWithTimeout(100))
            instrumentation.runOnMainSync {
                val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                assertTrue("Native widget action must not open a Flutter screen", resumed.none { it is io.github.centerionware.clockrove.MainActivity })
            }
            until("Combined must distinguish its task filter from another running job") {
                taskView!!.findViewById<android.widget.TextView>(R.id.widget_job)?.text == "Synthetic native timer" &&
                    taskView!!.findViewById<android.widget.TextView>(R.id.widget_tasks_title)?.text == "Due soon · Synthetic task scope"
            }
            instrumentation.runOnMainSync {
                assertEquals("Repeated updates must keep exactly one timer row", 1,
                    taskView!!.findViewById<android.widget.LinearLayout>(R.id.widget_timer_container).childCount)
            }
            until("Clock-in must refresh the widget to clock-out") {
                view!!.findViewById<android.widget.ImageButton>(R.id.widget_action)?.contentDescription == app.getString(R.string.widget_stop)
            }
            instrumentation.runOnMainSync {
                val bitmap = android.graphics.Bitmap.createBitmap(view!!.width, view!!.height, android.graphics.Bitmap.Config.ARGB_8888)
                view!!.draw(android.graphics.Canvas(bitmap))
                File(app.getExternalFilesDir(null), "widget-timer-running.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            SystemClock.sleep(10)
            instrumentation.runOnMainSync { assertTrue(view!!.findViewById<View>(R.id.widget_action).performClick()) }
            until("Actual widget broadcast must clock out without Flutter or GPS") { repository.snapshot().running == null }
            until("Clock-out must refresh both widget defaults without appending rows") {
                taskView!!.findViewById<android.widget.TextView>(R.id.widget_job)?.text == "Synthetic task scope" &&
                    taskView!!.findViewById<android.widget.LinearLayout>(R.id.widget_timer_container)?.childCount == 1
            }
            val entry = repository.sessionHistory(job).page.entries.single()
            assertEquals("WIDGET", entry.origin)
            assertTrue((entry.end ?: entry.start) > entry.start)
            assertTrue(repository.locationPoints(entry.id).isEmpty())
            assertNull(picker.waitForActivityWithTimeout(100))
        } finally {
            val current = repository.snapshot()
            if (current.running?.jobId == job) repository.executeFromSurface("native-widget-test-cleanup-$job", current.version, TimerRepository.Action.STOP, null, TimerRepository.Origin.MANUAL)
            repository.removeWidgetConfigurations(listOf(id, taskWidgetId))
            instrumentation.runOnMainSync { host.stopListening(); host.deleteHost(); activity.finish() }
            instrumentation.removeMonitor(picker); instrumentation.removeMonitor(start)
        }
    }

}
