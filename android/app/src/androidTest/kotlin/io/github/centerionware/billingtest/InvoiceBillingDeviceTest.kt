package io.github.centerionware.billingtest

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.invoice.InvoicePdf
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Separate package/run after emulator app-data reset: preserves INV-1000 regression isolation. */
@RunWith(AndroidJUnit4::class)
class InvoiceBillingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val captures get() = File(app.getExternalFilesDir(null), "billing-captures").apply { mkdirs() }
    private val job = "financial-job"
    private val jobName = "Synthetic tile renovation"
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(
        app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun until(description: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        val failure = AssertionError(description)
        try { diagnostic() } catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
        throw failure
    }
    private fun find(label: String, editable: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            fun matches(value: CharSequence?) = value?.toString()?.let {
                if (editable) it.contains(label) else it.lineSequence().any { line -> line.trim() == label }
            } == true
            val hintMatches = editable && android.os.Build.VERSION.SDK_INT >= 26 && matches(node.hintText)
            if (node.isVisibleToUser && (!editable || node.isEditable) && (matches(node.text) || matches(node.contentDescription) || hintMatches)) return node
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(label: String, editable: Boolean = false): AccessibilityNodeInfo {
        until("Actual app never exposed $label") { find(label, editable) != null }
        return requireNotNull(find(label, editable))
    }
    private fun click(label: String) {
        var node = waitFor(label)
        while (true) {
            // Flutter can acknowledge a click on a node without a TAP action.
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent ?: throw AssertionError("Could not click $label")
        }
    }
    private fun reveal(label: String, editable: Boolean = false) {
        repeat(35) {
            if (find(label, editable) != null) return
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            automation.rootInActiveWindow?.let(::scroll)
            SystemClock.sleep(150)
        }
        waitFor(label, editable)
    }
    private fun scrollToStart() {
        repeat(12) {
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            if (automation.rootInActiveWindow?.let(::scroll) != true) return
            SystemClock.sleep(120)
        }
    }
    private fun enter(label: String, value: String) {
        reveal(label, true)
        val node = waitFor(label, true)
        assertTrue("Could not enter $label", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
    }
    private fun capture(name: String) {
        automation.waitForIdle(200, 3000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(captures, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun diagnostic() {
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo, depth: Int) {
            if (dump.length > 200000 || depth > 50) return
            dump.append(" ".repeat(depth)).append(node).append('\n')
            for (i in 0 until node.childCount) node.getChild(i)?.let { describe(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { describe(it, 0) }
        File(captures, "accessibility.txt").writeText(dump.toString())
        capture("failure")
    }
    private fun closeActivities() {
        instrumentation.runOnMainSync {
            listOf(Stage.RESUMED, Stage.STARTED, Stage.CREATED).flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).toList()
            }.distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun launch() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("App settings and customers")
    }
    private fun sequence() = inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); it.getLong(0) } }
    @Test fun realLegacyReviewQuantityTaxBillingExclusionAndRetainedPdfJourney() {
        // Seed the frozen released DDL before the production singleton is opened.
        // This test process follows pm clear on the ephemeral CI emulator only.
        assertFalse("Financial suite requires fresh app data", app.getDatabasePath("clockrove.db").exists())
        val at = System.currentTimeMillis()
        val original = InvoiceStore.Snapshot("INV-1000", at, "MODERN", "USD", 2,
            "Synthetic Tile Company", "company@example.test", "Synthetic customer", "customer@example.test",
            jobName, "Original released invoice", "Completed billable tasks · fixed prices", "", "", "100.00",
            "customer@example.test", "Invoice INV-1000", "Synthetic original invoice", listOf(InvoiceStore.Line("Previously installed tile", "Completed work", "100.00")))
        val originalPdf = InvoicePdf.generate(original)
        val originalPayload = JSONObject(original.encode()).apply {
            remove("subtotal"); remove("taxRateUnits"); remove("taxAmount")
            getJSONArray("lines").getJSONObject(0).apply { remove("quantityUnits"); remove("unitPriceMinor") }
        }.toString()
        app.getDatabasePath("clockrove.db").parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("clockrove.db"), null).use { db ->
            instrumentation.context.assets.open("schema_v6.sql").bufferedReader().use { source ->
                source.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
            }
            db.execSQL("INSERT INTO jobs(id,name,billing_mode,currency) VALUES(?,?,'TASK','USD')", arrayOf(job, jobName))
            // Keep all three columns; put Done first so both completed sources are visible on a phone.
            db.execSQL("INSERT INTO kanban_columns VALUES('done',?,'Done',0,1)", arrayOf(job))
            db.execSQL("INSERT INTO kanban_columns VALUES('todo',?,'To do',1,0)", arrayOf(job))
            db.execSQL("INSERT INTO kanban_columns VALUES('doing',?,'Doing',2,0)", arrayOf(job))
            for ((id, title, price) in listOf(Triple("old-task", "Previously installed tile", 10000), Triple("new-task", "Install 1 sq ft tile", 500))) {
                db.execSQL("INSERT INTO kanban_cards(id,job_id,column_id,title,position,completed_ms,price_minor,currency) VALUES(?,?,'done',?,0,?,?,'USD')", arrayOf<Any?>(id, job, title, at, price))
            }
            db.execSQL("INSERT INTO invoices(id,job_id,number,created_ms,payload,pdf) VALUES('legacy-invoice',?,'INV-1000',?,?,?)", arrayOf<Any?>(job, at, originalPayload, originalPdf))
            db.execSQL("UPDATE app_settings SET next_invoice_number=1001,currency='USD'")
            db.version = 6
        }
        val repository = TimerRepository.get(app)
        assertTrue(repository.legacyInvoiceContext("legacy-invoice").context.legacy)
        assertEquals(10000L, repository.boardSnapshot(job).board.cards.first().quantityUnits)
        try {
            launch(); click("App settings and customers"); click("Settings"); waitFor("Billing defaults")
            enter("Default tax (%)", "7.5"); capture("global-tax-settings")
            reveal("Save settings"); click("Save settings")
            until("Actual main Settings did not save tax") { repository.businessSnapshot().settings.defaultTaxUnits == 75000 }
            waitFor("App settings and customers"); click("App settings and customers"); click("Invoices"); click("INV-1000")
            waitFor("Review billed work")
            reveal("Previously installed tile"); click("Previously installed tile")
            until("Covered task checkbox did not become checked") { find("Previously installed tile")?.isChecked == true }
            enter("Why this work belongs to the invoice", "Checked original invoice: only the previously installed tile was billed.")
            reveal("I checked this against the original invoice"); click("I checked this against the original invoice")
            until("Original-document confirmation was not checked") { find("I checked this against the original invoice")?.isChecked == true }
            capture("legacy-selection"); click("Save coverage review")
            until("Actual Flutter review never committed") { !repository.legacyInvoiceContext("legacy-invoice").context.legacy }
            waitFor("Invoices")
            assertEquals(originalPayload, repository.invoice("legacy-invoice").payload)
            assertArrayEquals(originalPdf, repository.invoice("legacy-invoice").pdf)
            assertEquals(1001L, sequence())

            closeActivities(); launch(); reveal(jobName); click(jobName)
            waitFor("Task board"); reveal("Install 1 sq ft tile"); click("Install 1 sq ft tile")
            waitFor("Edit task"); enter("Unit price (USD)", "5.00"); enter("Quantity", "26")
            reveal("Save task"); capture("quantity-editor"); click("Save task")
            until("Actual task editor did not commit quantity and unit price") {
                repository.boardSnapshot(job).board.cards.single { it.id == "new-task" }.let { it.quantityUnits == 260000L && it.priceMinor == 500L }
            }
            waitFor("Task board"); capture("quantity-board")
            click("Job report"); reveal("PDF invoice"); click("PDF invoice"); waitFor("Review invoice")
            waitFor("USD 130.00"); waitFor("USD 9.75"); waitFor("USD 139.75")
            capture("unbilled-tax-review")
            reveal("Create invoice"); click("Create invoice"); waitFor("Prepared invoice")
            until("Actual invoice allocation did not persist") { repository.invoices(job).invoices.size == 2 }
            val issued = repository.invoices(job).invoices.single { it.number == "INV-1001" }
            val frozen = InvoiceStore.Snapshot.decode(repository.invoice(issued.id).payload)
            assertEquals("130.00", frozen.subtotal); assertEquals("9.75", frozen.taxAmount); assertEquals("139.75", frozen.total)
            assertEquals("Install 1 sq ft tile", frozen.lines.single().title)
            assertEquals("260000", frozen.lines.single().quantityUnits); assertEquals("500", frozen.lines.single().unitPriceMinor)
            waitFor("Page 1 of 1"); capture("issued-invoice")
            assertTrue(requireNotNull(repository.invoice(issued.id).pdf).size > 1000)
            assertEquals(1002L, sequence())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Work report"); reveal("PDF invoice"); click("PDF invoice"); waitFor("Review invoice")
            reveal("No unbilled work remains in these dates. Previously invoiced work is excluded.")
            capture("already-billed")
            assertEquals(1002L, sequence()); assertEquals(2, repository.invoices(job).invoices.size)

            closeActivities(); launch(); reveal(jobName); click(jobName); waitFor("Task board")
            click("Job settings"); waitFor("Job settings"); reveal("Use default tax rate"); click("Use default tax rate")
            enter("Job tax (%)", "101")
            val beforeInvalidTax = repository.jobDetail(job)
            reveal("Save job settings"); click("Save job settings")
            scrollToStart(); reveal("Tax percentage must be between 0 and 100.")
            assertEquals(beforeInvalidTax, repository.jobDetail(job))
            scrollToStart(); reveal("Job tax (%)", true)
            assertTrue("Invalid tax draft was lost", waitFor("Job tax (%)", true).text?.toString()?.contains("101") == true)
            enter("Job tax (%)", "0"); capture("job-tax-override")
            reveal("Save job settings"); click("Save job settings")
            until("Explicit zero tax override did not persist") { repository.jobDetail(job).job.settings.taxUnits == 0 }
            assertEquals(0, repository.jobDetail(job).job.effectiveTaxUnits)
            assertEquals(75000, repository.businessSnapshot().settings.defaultTaxUnits)
            waitFor("Task board"); click("Job settings"); waitFor("Job settings")
            reveal("Use default tax rate"); click("Use default tax rate")
            reveal("Save job settings"); click("Save job settings")
            until("Default tax inheritance was not restored") { repository.jobDetail(job).job.settings.taxUnits == null }
            assertEquals(75000, repository.jobDetail(job).job.effectiveTaxUnits)
            assertEquals(frozen, InvoiceStore.Snapshot.decode(repository.invoice(issued.id).payload))
            closeActivities()
            assertNull(repository.archiveJob("financial-archive", repository.snapshot().version, job).conflict)
            // Synthetic archive age only; production retention is still enforced by deleteJob.
            inspect { it.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf(job)) }
            assertNull(repository.deleteJob("financial-delete", repository.snapshot().version, job).conflict)
            assertTrue(repository.invoices().invoices.all { it.jobDeleted })
            assertArrayEquals(originalPdf, repository.invoice("legacy-invoice").pdf)
            launch(); click("App settings and customers"); click("Invoices")
            waitFor("$jobName · job deleted"); click("INV-1001"); waitFor("Prepared invoice"); waitFor("Page 1 of 1")
            capture("retained-invoice")
            assertEquals(frozen, InvoiceStore.Snapshot.decode(repository.invoice(issued.id).payload))
        } catch (failure: Throwable) {
            try { diagnostic() } catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        } finally { closeActivities() }
    }
}
