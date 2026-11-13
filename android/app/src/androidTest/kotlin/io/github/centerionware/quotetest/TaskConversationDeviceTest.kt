package io.github.centerionware.quotetest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.tracking.BoardStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Flutter/native comments and reviewed camera-image attachment journey. */
@RunWith(AndroidJUnit4::class)
class TaskConversationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val captures get() = File(app.getExternalFilesDir(null), "task-media-captures").apply { mkdirs() }

    private fun find(label: String, editable: Boolean = false, actionable: Boolean = false): AccessibilityNodeInfo? {
        fun input(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.toString() == "android.widget.EditText" && node.isEditable) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { input(it)?.let { value -> return value } }
            return null
        }
        fun clickable(node: AccessibilityNodeInfo): Boolean {
            var current: AccessibilityNodeInfo? = node
            repeat(50) {
                val value = current ?: return false
                if (value.isClickable) return true
                current = value.parent
            }
            return false
        }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (editable && node.isVisibleToUser && node.viewIdResourceName == "task-comment-body") return input(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { value -> return value } }
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .flatMap { it.lineSequence().map(String::trim).toList() }
            if (node.isVisibleToUser && labels.any { it == label } &&
                (!actionable || clickable(node)) && (!editable || node.isEditable)) return node
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun waitFor(label: String, editable: Boolean = false, actionable: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            find(label, editable, actionable)?.let { return it }
            SystemClock.sleep(100)
        }
        val suffix = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(50)
        val root = automation.rootInActiveWindow
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo, depth: Int) {
            if (dump.length > 200000 || depth > 50) return
            dump.append(" ".repeat(depth)).append(node.toString()).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
        }
        if (root != null) describe(root, 0)
        File(captures, "accessibility-failure-$suffix.txt").writeText(dump.toString())
        automation.takeScreenshot()?.let { bitmap ->
            File(captures, "accessibility-failure-$suffix.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        throw AssertionError("Actual app did not expose '$label'")
    }

    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label, actionable = true)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("Actual app could not click '$label'")
    }

    private fun reveal(label: String, editable: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(label, editable) != null) return
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
                return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            val scrolled = automation.rootInActiveWindow?.let(::scroll) == true
            if (!scrolled) swipeContent(up = true)
            SystemClock.sleep(100)
        }
        waitFor(label, editable)
    }

    private fun swipeContent(up: Boolean) {
        val root = automation.rootInActiveWindow ?: return
        val bounds = android.graphics.Rect().also(root::getBoundsInScreen)
        val imeTop = automation.windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { android.graphics.Rect().also(it::getBoundsInScreen).top }
            .minOrNull()
        val bottom = minOf(bounds.bottom, imeTop ?: bounds.bottom) - 80
        val top = bounds.top + 220
        if (bottom - top < 300) return
        val x = bounds.centerX().toFloat()
        val startY = if (up) bottom - 80 else top + 80
        val endY = if (up) top + (bottom - top) * 2 / 5 else bottom - 160
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, eventTime: Long, y: Float) {
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, downTime, startY.toFloat())
        for (step in 1..8) {
            val eventTime = SystemClock.uptimeMillis() + 16
            val y = startY + (endY - startY) * step / 8
            inject(MotionEvent.ACTION_MOVE, eventTime, y.toFloat())
            SystemClock.sleep(16)
        }
        inject(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endY.toFloat())
    }

    private fun scrollToStart() {
        val deadline = SystemClock.uptimeMillis() + 15000
        var stationaryPolls = 0
        while (SystemClock.uptimeMillis() < deadline && stationaryPolls < 5) {
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { if (scroll(it)) return true }
                }
                return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            if (automation.rootInActiveWindow?.let(::scroll) == true) {
                stationaryPolls = 0
            } else {
                if (stationaryPolls == 0) {
                    repeat(8) {
                        swipeContent(up = false)
                        SystemClock.sleep(120)
                    }
                }
                stationaryPolls++
            }
            SystemClock.sleep(150)
        }
    }

    private fun keyboardVisible() = automation.windows.any {
        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }

    private fun hideKeyboard() {
        if (!keyboardVisible()) return
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val deadline = SystemClock.uptimeMillis() + 10000
        while (keyboardVisible() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertFalse("Keyboard did not close", keyboardVisible())
    }

    private fun keyboardWindows(): String = automation.windows.joinToString("\n") { window ->
        val bounds = android.graphics.Rect().also(window::getBoundsInScreen)
        "type=${window.type} active=${window.isActive} focused=${window.isFocused} bounds=$bounds"
    }

    private fun enterComment(value: String) {
        reveal("Add a comment", editable = true)
        val field = waitFor("Add a comment", editable = true)
        assertTrue("Comment field would not focus", field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val keyboardDeadline = SystemClock.uptimeMillis() + 10000
        while (!keyboardVisible() && SystemClock.uptimeMillis() < keyboardDeadline) SystemClock.sleep(100)
        if (!keyboardVisible()) {
            File(captures, "comment-keyboard-missing-windows.txt").writeText(keyboardWindows())
            capture("comment-keyboard-missing")
            throw AssertionError("Keyboard did not open for the comment; windows:\n${keyboardWindows()}")
        }
        val editable = waitFor("Add a comment", editable = true)
        assertTrue("Comment field does not expose set text", editable.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        })
        assertTrue(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        hideKeyboard()
    }

    private fun capture(name: String) {
        automation.waitForIdle(200, 3000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(captures, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun attachmentCount(): Int = SQLiteDatabase.openDatabase(
        app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY,
    ).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM task_attachments", null).use { it.moveToFirst(); it.getInt(0) }
    }

    private fun assertCameraTemporaryFilesWereRemoved() {
        val directory = File(app.cacheDir, "task-captures")
        assertTrue("Reviewed camera temporary file was retained", directory.listFiles().isNullOrEmpty())
    }

    @Test fun actualTaskCommentAndReviewedCameraPhotosPersistInPrivateStorage() {
        val repository = TimerRepository.get(app)
        val job = repository.createJob("Synthetic task photo job")
        val board = repository.boardSnapshot(job).board
        val taskId = "synthetic-task-photo-card"
        assertNull(repository.saveCard("synthetic-task-photo-create", repository.snapshot().version,
            BoardStore.Draft(taskId, job, board.columns.first().id, "Synthetic tile installation", currency = board.currency)).conflict)

        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val cameraFixturePreferences = app.getSharedPreferences("clockrove_ci_test_flags", Context.MODE_PRIVATE)
        assertTrue("CI camera fixture preference must be saved", cameraFixturePreferences.edit()
            .putBoolean("camera_fixture", true).commit())
        assertTrue("Task media journey requires the debug APK",
            (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        val cameraFixture = ComponentName(
            app.packageName,
            "io.github.centerionware.clockrove.testsupport.TaskPhotoCameraActivity",
        )
        assertTrue("Debug camera fixture activity must be installed", app.packageManager
            .getActivityInfo(cameraFixture, 0).enabled)
        try {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click("Synthetic task photo job")
            waitFor("Task board")
            click("Job settings")
            waitFor("Work location recording")
            click("Use global default (on)")
            click("Off for this job")
            waitFor("Off for this job")
            capture("job-location-recording-off")
            reveal("Save job settings")
            click("Save job settings")
            waitFor("Task board")
            assertEquals(false, repository.jobDetail(job).job.settings.locationRecordingOverride)
            assertFalse(repository.locationRecordingEnabled(job))

            click("Task notes and photos")
            waitFor("Task notes & photos")
            enterComment("Synthetic site progress note")
            reveal("Add comment")
            click("Add comment")
            waitFor("Synthetic site progress note")
            hideKeyboard()
            capture("task-comment")

            reveal("Choose photo")
            click("Choose photo")
            waitFor("Review photo")
            assertEquals("Gallery image must wait for explicit review", 0, attachmentCount())
            capture("task-gallery-review")
            click("Attach photo")
            val galleryDeadline = SystemClock.uptimeMillis() + 30000
            while (attachmentCount() != 1 && SystemClock.uptimeMillis() < galleryDeadline) SystemClock.sleep(100)
            assertEquals(1, attachmentCount())
            hideKeyboard()
            scrollToStart()
            waitFor("Task photo attachment")
            capture("task-gallery-attached")

            hideKeyboard()
            scrollToStart()
            reveal("Take photo")
            click("Take photo")
            waitFor("Review photo")
            capture("task-camera-review")
            assertEquals("Reviewing a camera photo must not save it yet", 1, attachmentCount())
            assertCameraTemporaryFilesWereRemoved()
            click("Attach photo")
            val attachDeadline = SystemClock.uptimeMillis() + 30000
            while (attachmentCount() != 2 && SystemClock.uptimeMillis() < attachDeadline) SystemClock.sleep(100)
            assertEquals(2, attachmentCount())
            waitFor("Photo attached to this task.")
            assertCameraTemporaryFilesWereRemoved()
            scrollToStart()
            waitFor("Task photo attachment")
            capture("task-camera-attached")

            // Comment-photo controls have their own label so users can attach
            // the image to the right progress update without guessing.
            reveal("Take photo for comment")
            click("Take photo for comment")
            waitFor("Review photo")
            assertEquals(2, attachmentCount())
            assertCameraTemporaryFilesWereRemoved()
            click("Attach photo")
            val commentPhotoDeadline = SystemClock.uptimeMillis() + 30000
            while (attachmentCount() != 3 && SystemClock.uptimeMillis() < commentPhotoDeadline) SystemClock.sleep(100)
            assertEquals(3, attachmentCount())
            waitFor("Photo attached to this comment.")
            assertCameraTemporaryFilesWereRemoved()
            val attachedToComment = SQLiteDatabase.openDatabase(
                app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY,
            ).use { db -> db.rawQuery("SELECT COUNT(*) FROM task_attachments WHERE comment_id IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) } }
            assertEquals(1, attachedToComment)
            waitFor("Task notes & photos")
            reveal("Comment photo attachment")
            capture("comment-camera-attached")

            scrollToStart()
            reveal("Task photo attachment")
            click("Task photo attachment")
            waitFor("Task photo")
            capture("task-photo-viewer")
        } finally {
            instrumentation.runOnMainSync {
                for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.CREATED)) {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().forEach { it.finish() }
                }
            }
            instrumentation.waitForIdleSync()
            cameraFixturePreferences.edit().remove("camera_fixture").commit()
        }
    }
}
