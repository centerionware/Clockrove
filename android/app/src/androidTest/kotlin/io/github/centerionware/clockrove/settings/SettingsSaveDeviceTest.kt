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
import io.github.centerionware.clockrove.tracking.InvoiceEmailTemplate
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.ReportStore
import io.github.centerionware.clockrove.tracking.SessionHistoryStore
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
            if (node.isVisibleToUser && (node.text?.toString()?.trim() == label || node.contentDescription?.toString()?.trim() == label)) return node
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
    private fun capture(name: String) {
        automation.waitForIdle(200, 3000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
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
    @Test fun templatesValidateAndExpandOnActualAndroidWithoutRegexInitialization() {
        try {
            java.util.regex.Pattern.compile("\\{([^{}]*)}")
            fail("Legacy unescaped brace unexpectedly accepted on Android")
        } catch (_: java.util.regex.PatternSyntaxException) { /* Android-only JVM incompatibility reproduced safely. */ }
        val body = "Hello {customer_name},\nInvoice {invoice_number}: {amount} {currency}\n{company_name}"
        assertTrue(InvoiceEmailTemplate.valid("Invoice {invoice_number}", true))
        assertTrue(InvoiceEmailTemplate.valid(body, false))
        for (invalid in listOf("{unknown}", "{", "}", "{company_name", "{{company_name}}", "Invoice {company_name}}")) {
            assertFalse(InvoiceEmailTemplate.valid(invalid, false))
            assertFalse(InvoiceEmailTemplate.valid(invalid, true))
        }
        assertFalse(InvoiceEmailTemplate.valid("Invoice\n{invoice_number}", true))
        val values = mapOf("customer_name" to "Synthetic \$1 \\ customer", "invoice_number" to "INV-1000",
            "amount" to "125.00", "currency" to "USD", "company_name" to "Synthetic company {customer_name}")
        assertEquals("Hello Synthetic \$1 \\ customer,\nInvoice INV-1000: 125.00 USD\nSynthetic company {customer_name}",
            InvoiceEmailTemplate.expand(body, values, false))
        val repository = TimerRepository.get(instrumentation.targetContext)
        val before = repository.businessSnapshot()
        for ((index, invalid) in listOf("{unknown}", "{", "}", "{customer_name", "{{customer_name}}", "{company_name}}").withIndex()) {
            assertEquals("INVALID_EMAIL_SUBJECT", repository.saveBusinessSettings("invalid-subject-$index", before.version, before.settings.copy(emailSubject = invalid)).conflict)
            assertEquals("INVALID_EMAIL_BODY", repository.saveBusinessSettings("invalid-body-$index", before.version, before.settings.copy(emailBody = invalid)).conflict)
            assertEquals(before, repository.businessSnapshot())
        }
        val settings = before.settings.copy(currency = "USD", defaultHourlyMinor = java.math.BigInteger.valueOf(12500), companyName = "Synthetic saved company", emailSubject = "Invoice {invoice_number}", emailBody = body)
        assertNull(repository.saveBusinessSettings("actual-android-template-save", before.version, settings).conflict)
        assertEquals(settings, repository.businessSnapshot().settings)
        val job = repository.createJob("Synthetic actual invoice job")
        assertNull(repository.createManualSession("actual-invoice-work", repository.snapshot().version,
            SessionHistoryStore.Draft("actual-invoice-session", job, 1793491200000, 1793494800000, "UTC", 0)).conflict)
        assertNull(repository.prepareInvoice("actual-invoice-prepare", repository.snapshot().version, "actual-invoice",
            ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))).conflict)
        val invoice = InvoiceStore.Snapshot.decode(repository.invoice("actual-invoice").payload)
        assertEquals("INV-1000", invoice.number)
        assertEquals("Invoice INV-1000", invoice.subject)
        assertTrue(invoice.body.contains("125.00 USD"))
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

    @Test fun invoiceStylePreviewOpensRealSampleWithoutSavingInvoiceOrSettings() {
        val app = instrumentation.targetContext
        val repository = TimerRepository.get(app)
        val before = repository.businessSnapshot()
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click("App settings and customers")
            click("Settings")
            waitFor("Billing defaults")
            reveal("Preview invoice")
            click("Preview invoice")
            val style = when (before.settings.invoiceStyle) {
                "CLASSIC" -> "Classic"
                "MODERN" -> "Modern"
                else -> "Minimal"
            }
            waitFor("Invoice style preview")
            waitFor("$style · sample invoice")
            waitFor("Page 1 of 1")
            assertEquals("Preview must not change saved settings or consume an invoice number", before, repository.businessSnapshot())
            val directory = File(app.getExternalFilesDir(null), "invoice-previews").apply { mkdirs() }
            automation.waitForIdle(200, 3000)
            val bitmap = requireNotNull(automation.takeScreenshot())
            File(directory, "clockrove-settings-preview-screen.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        } finally {
            instrumentation.runOnMainSync {
                for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.CREATED)) {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().forEach { it.finish() }
                }
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun locationRecordingDefaultCanBeTurnedOffAndBackOnInActualSettings() {
        val app = instrumentation.targetContext
        val repository = TimerRepository.get(app)
        var before = repository.businessSnapshot()
        if (!before.settings.defaultLocationRecording) {
            assertNull(repository.saveBusinessSettings("location-default-reset", before.version,
                before.settings.copy(defaultLocationRecording = true)).conflict)
            before = repository.businessSnapshot()
        }
        assertTrue(before.settings.defaultLocationRecording)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click("App settings and customers"); click("Settings")
            waitFor("Record work locations")
            click("Record work locations")
            capture("location-recording-global-off")
            reveal("Save settings")
            click("Save settings")
            val disabledDeadline = SystemClock.uptimeMillis() + 30000
            while (repository.businessSnapshot().settings.defaultLocationRecording && SystemClock.uptimeMillis() < disabledDeadline) SystemClock.sleep(100)
            assertFalse("Global location recording did not save as off", repository.businessSnapshot().settings.defaultLocationRecording)

            waitFor("App settings and customers"); click("App settings and customers"); click("Settings")
            waitFor("Record work locations")
            click("Record work locations")
            capture("location-recording-global-on")
            reveal("Save settings")
            click("Save settings")
            val enabledDeadline = SystemClock.uptimeMillis() + 30000
            while (!repository.businessSnapshot().settings.defaultLocationRecording && SystemClock.uptimeMillis() < enabledDeadline) SystemClock.sleep(100)
            assertTrue("Global location recording did not save as on", repository.businessSnapshot().settings.defaultLocationRecording)
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
