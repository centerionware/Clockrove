package io.github.centerionware.clockrove.tracking

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.centerionware.clockrove.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android report -> stored GPS -> interactive map journey. */
@RunWith(AndroidJUnit4::class)
class ReportMapDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val captures get() = File(app.getExternalFilesDir(null), "map-captures").apply { mkdirs() }
    private var seededJobId: String? = null

    private fun find(label: String): AccessibilityNodeInfo? {
        fun matches(value: CharSequence?) = value?.toString()?.lineSequence()
            ?.any { it.trim() == label } == true

        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            }
            return if (node.isVisibleToUser && (matches(node.text) || matches(node.contentDescription))) node else null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun waitFor(label: String, timeoutMillis: Long = 30000): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            find(label)?.let { return it }
            SystemClock.sleep(100)
        }
        diagnostic("missing-${label.replace(Regex("[^a-zA-Z0-9]+"), "-")}")
        throw AssertionError("Actual Android app never exposed '$label'")
    }

    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) {
            if ((node.isClickable || node.isCheckable) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("Actual Android app could not click '$label'")
    }

    private fun reveal(label: String) {
        repeat(24) {
            if (find(label) != null) return
            val root = automation.rootInActiveWindow
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isVisibleToUser && node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { if (scroll(it)) return true }
                }
                return false
            }
            root?.let(::scroll)
            swipeUp()
            SystemClock.sleep(180)
        }
        waitFor(label, 1000)
    }

    private fun swipeUp() {
        val root = automation.rootInActiveWindow ?: return
        val bounds = android.graphics.Rect().also(root::getBoundsInScreen)
        val x = bounds.centerX().toFloat()
        val startY = (bounds.bottom - 240).toFloat()
        val endY = (bounds.top + 320).toFloat()
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, eventTime: Long, y: Float) {
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, downTime, startY)
        for (step in 1..8) {
            val y = startY + (endY - startY) * step / 8
            inject(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis() + 16, y)
            SystemClock.sleep(16)
        }
        inject(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endY)
    }

    private fun seedGpsHistory() {
        val repository = TimerRepository.get(app)
        val jobId = repository.createJob("Synthetic GPS map journey")
        seededJobId = jobId
        val timezone = TimeZone.getDefault()
        val startCalendar = Calendar.getInstance(timezone).apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = startCalendar.timeInMillis
        val end = startCalendar.apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        val offset = timezone.getOffset(start) / 1000

        SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.beginTransaction()
            try {
                database.execSQL(
                    "INSERT INTO sessions(id,job_id,start_ms,end_ms,timezone,offset_seconds,origin) VALUES(?,?,?,?,?,?,?)",
                    arrayOf<Any?>("synthetic-map-session", jobId, start, end, timezone.id, offset, "MANUAL"),
                )
                repeat(49) { index ->
                    val measured = start + index * 300000L
                    database.execSQL(
                        "INSERT INTO location_points(id,session_id,measured_ms,received_ms,latitude,longitude,accuracy) VALUES(?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            "synthetic-map-point-$index",
                            "synthetic-map-session",
                            measured,
                            measured,
                            37.4219983 + index * 0.00003,
                            -122.084 + index * 0.00003,
                            8.0,
                        ),
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = timezone }.format(Date())
        val monthStart = Calendar.getInstance(timezone).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val first = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = timezone }.format(monthStart.time)
        assertEquals(
            49,
            repository.report(ReportStore.Selection(first, today, timezone.id, listOf(jobId))).report.points.size,
        )
    }

    private fun removeGpsHistory() {
        val jobId = seededJobId ?: return
        val repository = TimerRepository.get(app)
        val archived = repository.archiveJob(
            "map-test-archive-$jobId",
            repository.snapshot().version,
            jobId,
        )
        assertEquals("Synthetic map test job could not be archived", null, archived.conflict)
        SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                "UPDATE jobs SET archived_ms=? WHERE id=?",
                arrayOf<Any?>(
                    System.currentTimeMillis() - JobLifecycleStore.retentionMillis - 1000,
                    jobId,
                ),
            )
        }
        val deleted = repository.deleteJob(
            "map-test-delete-$jobId",
            archived.version,
            jobId,
        )
        assertEquals("Synthetic map test job could not be removed", null, deleted.conflict)
    }

    private fun capture(name: String) {
        automation.waitForIdle(250, 5000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(captures, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun diagnostic(name: String) {
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo, depth: Int) {
            if (dump.length > 150000 || depth > 50) return
            dump.append(" ".repeat(depth)).append(node).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { describe(it, 0) }
        File(captures, "$name-accessibility.txt").writeText(dump.toString())
        automation.takeScreenshot()?.let { bitmap ->
            File(captures, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun closeActivities() {
        instrumentation.runOnMainSync {
            listOf(Stage.RESUMED, Stage.STARTED, Stage.CREATED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).toList() }
                .distinct()
                .forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun opensMapForFortyNineRecordedSamplesAndShowsTileState() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            seedGpsHistory()
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            waitFor("App settings and customers")
            click("Synthetic GPS map journey")
            waitFor("Job report")
            click("Job report")
            waitFor("Work report")
            reveal("Map · 49 measured samples")
            click("Map · 49 measured samples")
            waitFor("Report location history")
            waitFor("Synthetic GPS map journey")
            waitFor("37.421998, -122.084000")

            val tileDeadline = SystemClock.uptimeMillis() + 30000
            val terminalStates = listOf(
                "Map tiles loaded",
                "Map tiles are unavailable. Your recorded location is still available.",
            )
            while (SystemClock.uptimeMillis() < tileDeadline && terminalStates.none { find(it) != null }) {
                SystemClock.sleep(150)
            }
            assertNotNull("Map remained blank/loading without a tile status", terminalStates.firstNotNullOfOrNull(::find))
            waitFor("Open map")
            waitFor("© OpenStreetMap contributors")
            capture("report-map-49-samples")
        } finally {
            closeActivities()
            removeGpsHistory()
        }
    }
}
