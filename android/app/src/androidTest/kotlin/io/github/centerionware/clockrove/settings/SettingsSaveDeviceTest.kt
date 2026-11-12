package io.github.centerionware.clockrove.settings

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.tracking.TimerRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Flutter + native channel + SQLite journey, not a settings API fixture. */
@RunWith(AndroidJUnit4::class)
class SettingsSaveDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { result -> return result } }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < until) {
            find(label)?.let { return it }
            SystemClock.sleep(100)
        }
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-accessibility.txt")
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo, depth: Int) {
            if (dump.length > 200000 || depth > 50) return
            dump.append(" ".repeat(depth)).append(node.toString()).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { describe(it, 0) }
        file.writeText(dump.toString())
        automation.takeScreenshot()?.let { bitmap ->
            File(file.parentFile, "settings-screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        throw AssertionError("Actual app never exposed $label")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("Actual app could not click $label")
    }
    private fun reveal(label: String) {
        repeat(60) {
            if (find(label) != null) return
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
                return false
            }
            automation.rootInActiveWindow?.let(::scroll)
            SystemClock.sleep(120)
        }
        waitFor(label)
    }
    @Test fun mainSettingsSaveSurvivesNativeCommitAndCanBeOpenedAgain() {
        val app = instrumentation.targetContext
        val repository = TimerRepository.get(app)
        val before = repository.businessSnapshot()
        // Enable semantics before Flutter creates its accessibility bridge.
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click("App settings and customers")
            click("Settings")
            waitFor("Billing defaults")
            reveal("Save settings")
            click("Save settings")
            val until = SystemClock.uptimeMillis() + 30000
            while (repository.businessSnapshot().version == before.version && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            assertTrue("Settings were never committed by the actual app", repository.businessSnapshot().version > before.version)
            waitFor("App settings and customers")
            click("App settings and customers"); click("Settings")
            waitFor("Billing defaults")
            assertEquals(before.settings, repository.businessSnapshot().settings)
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
