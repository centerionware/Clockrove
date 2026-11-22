package io.github.centerionware.backuptest

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.tracking.TimerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupPickerDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun documentPickerRestoresIntoAnIndependentDatabase() {
        val app = instrumentation.targetContext
        val repository = TimerRepository.get(app)
        assertTrue(repository.snapshot().jobs.isEmpty())
        val existingJob = repository.createJob("Synthetic destination job")
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        click("App settings and customers")
        click("Settings")
        click("Restore encrypted backup", scroll = true)
        openPicker()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitFor("picker cancellation") { findLabel("File selection cancelled.") }
        assertEquals(listOf(existingJob), repository.snapshot().jobs.map { it.id })

        openPicker()
        if (findLabel(FIXTURE) == null) {
            val roots = findLabel("Show roots") ?: findLabel("Open navigation drawer")
            if (roots != null) clickNode(roots)
            click("Downloads")
        }
        click(FIXTURE)
        waitFor("encrypted document staged") { findLabel("Selected encrypted file", prefix = true) }
        assertEquals(listOf(existingJob), repository.snapshot().jobs.map { it.id })

        val password = waitFor("backup password field") {
            findNode { it.isVisibleToUser && it.isEditable && it.isPassword }
        }
        assertTrue(password.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, PASSWORD)
        }))
        if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        click("Authenticate and preview", scroll = true)
        val apply = waitFor("authenticated restore preview") {
            findLabel("Backup authenticated", prefix = true)
        }
        assertTrue(apply.isVisibleToUser)
        assertEquals(listOf(existingJob), repository.snapshot().jobs.map { it.id })
        // Independent installs create different revision roots for app settings.
        click("Keep the version on this device", scroll = true)
        click("Apply reviewed merge", scroll = true)
        waitFor("restore confirmation") { findLabel("Apply this backup?") }
        click("Apply reviewed merge")

        val expectedJobs = setOf(existingJob, "ci-document-source")
        val result = waitFor("committed restored job") {
            repository.snapshot().takeIf { snapshot -> snapshot.jobs.map { it.id }.toSet() == expectedJobs }
        }
        assertEquals(2, result.jobs.size)
        assertEquals("Synthetic restore source", result.jobs.single { it.id == "ci-document-source" }.name)
        assertNull(result.running)
        val entry = repository.sessionHistory("ci-document-source").page.entries.single()
        assertEquals("ci-document-session", entry.id)
        assertEquals(1735689600000L, entry.start)
        assertEquals(1735693200000L, entry.end)
        assertEquals("Etc/UTC", entry.timezone)
    }

    private fun openPicker() {
        click("Choose encrypted backup")
        waitFor("Android document picker") {
            automation.rootInActiveWindow?.takeIf {
                it.packageName?.toString() in setOf("com.android.documentsui", "com.google.android.documentsui")
            }
        }
    }

    private fun findLabel(label: String, prefix: Boolean = false): AccessibilityNodeInfo? = findNode { node ->
        node.isVisibleToUser && listOf(node.text, node.contentDescription).any { value ->
            val text = value?.lineSequence()?.firstOrNull()?.trim()
            text == label || (prefix && text?.startsWith(label) == true)
        }
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun click(label: String, scroll: Boolean = false) {
        val node = waitFor(label) {
            findLabel(label)?.takeIf { it.isEnabled } ?: run {
                if (scroll) {
                    findNode { it.isVisibleToUser && it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(250)
                }
                null
            }
        }
        clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        throw AssertionError("Could not activate ${node.text ?: node.contentDescription}")
    }

    private fun <T : Any> waitFor(label: String, find: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            find()?.let { return it }
            SystemClock.sleep(100)
        }
        val activePackage = automation.rootInActiveWindow?.packageName
        try {
            val capture = File(instrumentation.targetContext.getExternalFilesDir(null), "backup-picker-failure.png")
            automation.takeScreenshot()?.let { bitmap ->
                try {
                    capture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
            // Keep the original failure even if the runner cannot save a screenshot.
        }
        throw AssertionError("Android did not expose $label (active package: $activePackage)")
    }

    companion object {
        private const val FIXTURE = "clockrove-picker-fixture.clockrove"
        private const val PASSWORD = "synthetic-document-fixture-password"
    }
}
