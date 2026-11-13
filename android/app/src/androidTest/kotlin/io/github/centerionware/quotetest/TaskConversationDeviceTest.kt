package io.github.centerionware.quotetest

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
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
            if (editable && node.viewIdResourceName == "task-comment-body") return input(node)
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

    private fun reveal(label: String) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(label) != null) return
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
                return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            automation.rootInActiveWindow?.let(::scroll)
            SystemClock.sleep(100)
        }
        waitFor(label)
    }

    private fun keyboardVisible() = automation.windows.any {
        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }

    private fun enterComment(value: String) {
        reveal("Add a comment")
        val field = waitFor("Add a comment", editable = true)
        assertTrue("Comment field would not focus", field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val keyboardDeadline = SystemClock.uptimeMillis() + 10000
        while (!keyboardVisible() && SystemClock.uptimeMillis() < keyboardDeadline) SystemClock.sleep(100)
        assertTrue("Keyboard did not open for the comment", keyboardVisible())
        val editable = waitFor("Add a comment", editable = true)
        assertTrue("Comment field does not expose set text", editable.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        })
        assertTrue(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        if (keyboardVisible()) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
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
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
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
            waitFor("Task photo attachment")
            capture("task-gallery-attached")

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
            assertCameraTemporaryFilesWereRemoved()
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
            assertCameraTemporaryFilesWereRemoved()
            val attachedToComment = SQLiteDatabase.openDatabase(
                app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY,
            ).use { db -> db.rawQuery("SELECT COUNT(*) FROM task_attachments WHERE comment_id IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) } }
            assertEquals(1, attachedToComment)
            waitFor("Comment photo attachment")
            capture("comment-camera-attached")

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
        }
    }
}
