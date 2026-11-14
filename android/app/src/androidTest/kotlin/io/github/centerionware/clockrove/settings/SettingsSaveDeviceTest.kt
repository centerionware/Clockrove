package io.github.centerionware.clockrove.settings

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
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
    private fun matchesLabel(value: CharSequence?, label: String): Boolean {
        val text = value?.toString()?.trim() ?: return false
        return text == label || text.lineSequence().firstOrNull()?.trim() == label
    }
    private fun find(label: String, editable: Boolean = false, checkable: Boolean = false): AccessibilityNodeInfo? {
        val fieldId = when (label) { "Default tax (%)" -> "default-tax-percent"; else -> null }
        fun editableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.className?.toString() == "android.widget.EditText") return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { editableNode(it)?.let { found -> return found } }
            return null
        }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (editable && fieldId != null && node.isVisibleToUser && node.viewIdResourceName == fieldId) {
                editableNode(node)?.let { return it }
            }
            if (checkable && node.isVisibleToUser && node.isCheckable &&
                (matchesLabel(node.text, label) || matchesLabel(node.contentDescription, label) || node.contentDescription?.toString()?.contains(label) == true)) return node
            val matches = matchesLabel(node.text, label) || matchesLabel(node.contentDescription, label) ||
                (editable && android.os.Build.VERSION.SDK_INT >= 26 && node.hintText?.toString()?.contains(label) == true)
            if (node.isVisibleToUser && matches && (!editable || (node.isEditable &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }))) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { result -> return result } }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(label: String, editable: Boolean = false, checkable: Boolean = false): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < until) {
            find(label, editable, checkable)?.let { return it }
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
    private fun enterText(label: String, value: String) {
        reveal(label, editable = true)
        val field = waitFor(label, editable = true)
        assertTrue("Could not focus $label", field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val keyboardDeadline = SystemClock.uptimeMillis() + 10000
        while (automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } &&
            SystemClock.uptimeMillis() < keyboardDeadline) SystemClock.sleep(100)
        assertTrue("Keyboard did not open for $label", automation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        })
        reveal(label, editable = true)
        val editable = waitFor(label, editable = true)
        assertTrue("Could not enter $label", editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        val enteredDeadline = SystemClock.uptimeMillis() + 5000
        while (find(label, editable = true)?.text?.toString() != value && SystemClock.uptimeMillis() < enteredDeadline) {
            SystemClock.sleep(100)
        }
        assertEquals("Entered value was not shown for $label", value, find(label, editable = true)?.text?.toString())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        automation.waitForIdle(200, 3000)
    }
    private fun setSwitch(label: String, checked: Boolean) {
        reveal(label, checkable = true)
        val current = waitFor(label, checkable = true)
        if (current.isChecked != checked) {
            assertTrue("Could not change $label", current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            automation.waitForIdle(100, 1000)
            if (find(label, checkable = true)?.isChecked == checked) return
            SystemClock.sleep(100)
        }
        dumpAccessibility("switch-state")
        assertEquals("$label did not change to the requested state", checked, find(label, checkable = true)?.isChecked)
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) {
            if ((node.isCheckable || node.isClickable) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
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
    private fun reveal(label: String, editable: Boolean = false, checkable: Boolean = false) {
        for (forward in listOf(true, false)) {
            var attempts = 0
            while (attempts++ < 60 && find(label, editable, checkable) == null) {
                val scrollAction = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
                    return node.isScrollable && node.performAction(scrollAction)
                }
                val moved = automation.rootInActiveWindow?.let(::scroll) == true
                SystemClock.sleep(150)
                if (find(label, editable, checkable) != null) return
                if (!moved) {
                    swipeContent(up = forward)
                    SystemClock.sleep(150)
                }
            }
            if (find(label, editable, checkable) != null) return
        }
        waitFor(label, editable, checkable)
    }
    private fun dumpAccessibility(name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-diagnostics").apply { mkdirs() }
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo, depth: Int) {
            if (dump.length > 200000 || depth > 50) return
            dump.append(" ".repeat(depth)).append(node.toString()).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { describe(it, 0) }
        File(directory, "$name-accessibility.txt").writeText(dump.toString())
        automation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name-screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
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
        val savedTaxUnits = if (before.settings.defaultTaxUnits == 12500) 15000 else 12500
        val savedTaxPercent = if (savedTaxUnits == 12500) "1.25" else "1.5"
        // Enable semantics before Flutter creates its accessibility bridge.
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click("App settings and customers")
            click("Settings")
            waitFor("Billing defaults")
            enterText("Default tax (%)", savedTaxPercent)
            reveal("Save settings")
            click("Save settings")
            val until = SystemClock.uptimeMillis() + 30000
            while (repository.businessSnapshot().settings.defaultTaxUnits != savedTaxUnits && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            val committed = repository.businessSnapshot()
            assertTrue("Changed settings were never committed by the actual app", committed.version > before.version)
            assertEquals("The edited tax default was not persisted", savedTaxUnits, committed.settings.defaultTaxUnits)
            waitFor("App settings and customers")
            click("App settings and customers"); click("Settings")
            waitFor("Billing defaults")
            assertEquals("The edited setting did not survive reopening Settings", savedTaxUnits, repository.businessSnapshot().settings.defaultTaxUnits)
            capture("main-settings-save-reopened")
        } finally {
            instrumentation.runOnMainSync {
                for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.CREATED)) {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().forEach { it.finish() }
                }
            }
            instrumentation.waitForIdleSync()
            val current = repository.businessSnapshot()
            if (current.settings != before.settings) {
                val cleanupId = "settings-device-cleanup-${java.util.UUID.randomUUID()}"
                assertNull("Could not restore synthetic Settings test data", repository.saveBusinessSettings(cleanupId, current.version, before.settings).conflict)
            }
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
            reveal("Record work locations")
            setSwitch("Record work locations", checked = false)
            capture("location-recording-global-off")
            reveal("Save settings")
            click("Save settings")
            val disabledDeadline = SystemClock.uptimeMillis() + 30000
            while (repository.businessSnapshot().settings.defaultLocationRecording && SystemClock.uptimeMillis() < disabledDeadline) SystemClock.sleep(100)
            assertFalse("Global location recording did not save as off", repository.businessSnapshot().settings.defaultLocationRecording)

            waitFor("App settings and customers"); click("App settings and customers"); click("Settings")
            reveal("Record work locations")
            setSwitch("Record work locations", checked = true)
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
