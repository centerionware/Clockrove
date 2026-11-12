package io.github.centerionware.quotetest

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.invoice.InvoiceDocuments
import io.github.centerionware.clockrove.tracking.*
import io.github.centerionware.billingtest.CopyPdfTextApi35
import java.io.File
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh Actions app: actual Flutter issue/accept/plan/complete/invoice, no mocked work. */
@RunWith(AndroidJUnit4::class)
class QuoteBillingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val captures get() = File(app.getExternalFilesDir(null), "quote-captures").apply { mkdirs() }
    private lateinit var job: String
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
    private fun find(label: String, editable: Boolean = false, actionable: Boolean = false): AccessibilityNodeInfo? {
        val slug = label.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-")
        val fieldIds = listOfNotNull("quote-editor-$slug", when (label) {
            "Private decision note" -> "quote-decision-note"
            "Private scope review note" -> "quote-scope-note"
            "Private invoice review note" -> "quote-invoice-note"
            "First date (YYYY-MM-DD)" -> "quote-invoice-first"
            "Last date (YYYY-MM-DD)" -> "quote-invoice-last"
            "Unit price (USD)" -> "task-unit-price"
            "Quantity" -> "task-quantity"
            else -> null
        })
        fun editableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.className?.toString() == "android.widget.EditText") return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { editableNode(it)?.let { found -> return found } }
            return null
        }
        fun canClick(node: AccessibilityNodeInfo): Boolean {
            var cursor: AccessibilityNodeInfo? = node
            repeat(50) { val current = cursor ?: return false; if (current.isClickable) return true; cursor = current.parent }
            return false
        }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (editable && node.isVisibleToUser && node.viewIdResourceName in fieldIds) return editableNode(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            fun matches(value: CharSequence?) = value?.toString()?.let {
                if (editable) it.contains(label) else it.lineSequence().any { line ->
                    val spoken = line.trim()
                    spoken == label || spoken == "$label, Collapsed" || spoken == "$label, Expanded"
                }
            } == true
            val hintMatches = editable && android.os.Build.VERSION.SDK_INT >= 26 && matches(node.hintText)
            if (node.isVisibleToUser && (!actionable || canClick(node)) && (!editable || node.isEditable) && (matches(node.text) || matches(node.contentDescription) || hintMatches)) return node
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(label: String, editable: Boolean = false, actionable: Boolean = false): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        until("Actual app never exposed $label") { found = find(label, editable, actionable); found != null }
        return requireNotNull(found)
    }
    private fun click(label: String) {
        var node = waitFor(label, actionable = true)
        while (true) {
            // Flutter can acknowledge a click on a node without a TAP action.
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent ?: throw AssertionError("Could not click $label")
        }
    }
    private fun reveal(label: String, editable: Boolean = false) {
        until("Actual app never exposed $label") {
            if (find(label, editable) != null) true else {
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    // Prefer the vertical form over editable text's own scroll
                    // actions. Route semantics can arrive after navigation.
                    if (node.isVisibleToUser && node.isScrollable && node.className?.toString() == "android.widget.ScrollView" &&
                        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                    return false
                }
                automation.rootInActiveWindow?.let(::scroll)
                SystemClock.sleep(150)
                false
            }
        }
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
    private fun revealDetailStatus(label: String) {
        waitFor("Refresh invoice details")
        until("Actual invoice detail never exposed $label after Back") {
            if (find(label) != null) true else {
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                    return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                // The incoming route can expose its app bar before scrolling is
                // enabled. Keep polling the real route rather than treating one
                // transient false action as proof that it is already at the top.
                automation.rootInActiveWindow?.let(::scroll)
                false
            }
        }
    }
    private fun keyboardVisible() = automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    private fun hideKeyboard() {
        if (keyboardVisible()) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            until("Keyboard did not close after Back") { !keyboardVisible() }
            automation.waitForIdle(250, 3000)
        }
    }
    private fun enter(label: String, value: String) {
        hideKeyboard()
        reveal(label, true)
        val field = waitFor(label, true)
        assertTrue("Could not focus $label", field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        until("Focused field did not expose text entry for $label") {
            find(label, true)?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } == true
        }
        // Wait for the actual IME/inset transition before writing. A fast SET_TEXT
        // acknowledgement can otherwise precede the keyboard opening and hiding.
        until("Keyboard did not open for $label") { keyboardVisible() }
        val node = waitFor(label, true)
        assertTrue("Could not enter $label", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        until("Entered text was not exposed for $label") { find(label, true)?.text?.toString() == value }
        hideKeyboard()
    }
    private fun capture(name: String) {
        automation.waitForIdle(200, 3000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(captures, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun diagnostic() {
        val dump = StringBuilder()
        automation.windows.forEach { dump.append("Window ").append(it).append('\n') }
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
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("App settings and customers")
    }
    private fun count(table: String) = inspect { db -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun back() { hideKeyboard(); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); automation.waitForIdle(200, 3000) }
    private fun noWorkOrInvoices() {
        assertEquals(0, count("sessions")); assertEquals(0, count("invoices")); assertEquals(0, count("invoice_task_claims")); assertEquals(0, count("invoice_payments"))
    }
    private fun document(repository: TimerRepository, id: String, name: String) {
        val record = InvoiceDocuments.ensure(repository, id)
        val bytes = requireNotNull(record.pdf)
        val file = File(captures, "$name.pdf").apply { writeBytes(bytes) }
        val extracted = StringBuilder()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { pdf ->
            assertEquals(1, pdf.pageCount)
            pdf.openPage(0).use { page ->
                val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                try {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(captures, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
                if (android.os.Build.VERSION.SDK_INT >= 35) extracted.append(CopyPdfTextApi35.read(page).replace(Regex("\\s+"), ""))
            }
        } }
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            File(captures, "$name-extracted.txt").writeText(extracted.toString())
            for (expected in listOf("375.00", "28.13", "403.13", "30×12.50USDeach", "Quote", "Revision1")) assertTrue("Missing exact quote invoice text $expected", extracted.contains(expected))
        }
        val email = InvoiceDocuments.email(app, record, repository.businessSnapshot().settings)
        assertArrayEquals(arrayOf("quote-customer@example.test"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertArrayEquals(arrayOf("accounts@example.test"), email.getStringArrayExtra(Intent.EXTRA_CC))
        val uri = InvoiceDocuments.uri(app, record)
        assertEquals(uri, email.clipData!!.getItemAt(0).uri)
        app.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        assertTrue(email.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, email.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    @Test fun actualQuoteIssueAcceptancePlanningCompletedQuantityInvoiceAndDuplicateExclusion() {
        val repository = TimerRepository.get(app)
        fun version() = repository.snapshot().version
        assertNull(repository.saveBusinessSettings("quote-fixture-settings", version(), repository.businessSnapshot().settings.copy(currency = "USD", companyName = "Riverside Studio", companyEmail = "company@example.test", receivablesEmail = "accounts@example.test", defaultTaxUnits = 75000, invoiceStyle = "MODERN")).conflict)
        assertNull(repository.saveCustomer("quote-fixture-customer", version(), BusinessStore.Customer("quote-customer", "Synthetic quote customer", "quote-customer@example.test")).conflict)
        try {
            launch(); click("App settings and customers"); click("Quotes"); click("New quote")
            waitFor("Quote draft"); enter("Quote title", "Synthetic tile work")
            scrollToStart(); reveal("No customer selected"); click("No customer selected"); click("Synthetic quote customer")
            enter("Description", "Install tile"); enter("Quantity", "26"); enter("Unit price (USD)", "12.50")
            enter("Tax percentage", "7.5"); reveal("Save quote draft"); click("Save quote draft")
            until("Actual quote draft was not saved") { count("quote_drafts") == 1 }
            val quoteId = inspect { db -> db.rawQuery("SELECT id FROM quotes", null).use { it.moveToFirst(); it.getString(0) } }
            noWorkOrInvoices(); assertEquals(0, count("kanban_cards"))
            scrollToStart(); reveal("Review quote for issue"); click("Review quote for issue"); click("Confirm review")
            reveal("Issue quote"); click("Issue quote")
            until("Actual quote issue was not committed") { repository.quote(quoteId).latest?.number == "Q-1000" }
            noWorkOrInvoices(); assertEquals(0, count("kanban_cards"))
            scrollToStart(); reveal("Open issued quote"); click("Open issued quote"); waitFor("Page 1 of 1"); capture("issued-quote")
            val quotePdf = requireNotNull(repository.quoteDocument(quoteId, 1).pdf)
            back(); scrollToStart(); enter("Private decision note", "Synthetic customer explicitly accepted this revision")
            reveal("Review customer decision"); click("Review customer decision"); click("Confirm review")
            reveal("Record reviewed decision"); click("Record reviewed decision")
            until("Actual accepted decision was not committed") { repository.quote(quoteId).latest?.decision == "ACCEPTED" }
            capture("accepted-quote"); noWorkOrInvoices()
            scrollToStart(); reveal("Plan accepted scope"); click("Plan accepted scope"); click("Review group plan")
            waitFor("Review scope plan"); enter("Private scope review note", "Reviewed planned task quantity and accepted unit price")
            reveal("Review selected plan"); click("Review selected plan"); click("Confirm review")
            reveal("Apply reviewed plan"); click("Apply reviewed plan")
            until("Actual scope mapping was not committed") { repository.quoteScope(quoteId).second.size == 1 }
            val binding = repository.quoteScope(quoteId).second.single()
            val planned = repository.boardSnapshot(binding.jobId).board.cards.single()
            assertNull(planned.completedMillis); assertEquals(260000L, planned.quantityUnits); assertEquals(BigInteger("1250"), planned.priceMinor)
            noWorkOrInvoices(); waitFor("Review group plan"); capture("planned-scope")
            click("Review group plan"); waitFor("Review scope plan"); reveal("Open existing job"); click("Open existing job")
            waitFor("Task board"); waitFor("Install tile"); click("Install tile"); waitFor("Edit task")
            enter("Unit price (USD)", "9.99"); enter("Quantity", "30"); reveal("Save task"); click("Save task")
            until("Actual task quantity edit was not saved") { repository.boardSnapshot(binding.jobId).board.cards.single().quantityUnits == 300000L }
            waitFor("Task board"); capture("actual-task-quantity")
            click("Task actions"); click("Move to Done"); click("Move task")
            until("Actual task completion was not committed") { repository.boardSnapshot(binding.jobId).board.cards.single().completedMillis != null }
            until("Task move confirmation did not close") { find("Move task") == null && find("Task board") != null }
            assertEquals(BigInteger("999"), repository.boardSnapshot(binding.jobId).board.cards.single().priceMinor)
            assertEquals(0, count("sessions")); assertEquals(0, count("invoices"))
            val completedDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getDefault() }.format(java.util.Date(requireNotNull(repository.boardSnapshot(binding.jobId).board.cards.single().completedMillis)))
            back(); waitFor("Review scope plan"); back(); waitFor("Plan accepted scope"); back()
            scrollToStart(); reveal("Invoice actual work"); click("Invoice actual work")
            waitFor("Load available work"); enter("First date (YYYY-MM-DD)", completedDate); enter("Last date (YYYY-MM-DD)", completedDate)
            reveal("Load available work"); click("Load available work"); reveal("Install tile"); click("Install tile"); capture("actual-source-selection")
            enter("Private invoice review note", "Reviewed actual thirty units at accepted quote price")
            reveal("Review actual-work invoice"); click("Review actual-work invoice"); waitFor("Invoice total USD 403.13"); capture("actual-invoice-review")
            click("Keep editing"); assertEquals(0, count("invoices"))
            reveal("Review actual-work invoice"); click("Review actual-work invoice"); click("Confirm invoice review")
            reveal("Prepare reviewed invoice"); click("Prepare reviewed invoice")
            until("Actual quote invoice was not committed") { repository.quoteInvoices(quoteId).size == 1 }
            val invoice = repository.quoteInvoices(quoteId).single().invoiceId
            val snapshot = InvoiceStore.Snapshot.decode(repository.invoice(invoice).payload)
            assertEquals("403.13", snapshot.total); assertEquals("375.00", snapshot.subtotal); assertEquals("28.13", snapshot.taxAmount)
            assertEquals("300000", snapshot.lines.single().quantityUnits); assertEquals("1250", snapshot.lines.single().unitPriceMinor)
            assertEquals(1, count("invoice_task_claims")); assertEquals(0, count("sessions")); assertEquals(0, count("invoice_payments"))
            assertArrayEquals(quotePdf, repository.quoteDocument(quoteId, 1).pdf)
            reveal("View original PDF"); click("View original PDF"); waitFor("Prepared invoice"); waitFor("Page 1 of 1"); capture("actual-invoice-viewer")
            document(repository, invoice, "actual-quote-invoice-modern")
            back(); back(); scrollToStart(); reveal("Related invoices"); click("Related invoices"); waitFor("INV-1000"); capture("related-invoices")
            back(); scrollToStart(); reveal("Invoice actual work"); click("Invoice actual work")
            reveal("Already billed · INV-1000"); capture("already-billed")
            assertEquals(1, count("invoices"))
            val date = completedDate
            assertEquals("NO_BILLABLE_ITEMS", repository.prepareInvoice("ordinary-again", version(), "duplicate", ReportStore.Selection(date, date, java.util.TimeZone.getDefault().id, listOf(binding.jobId))).conflict)
            // The other two styles exercise the same accepted-price preparation
            // boundary and actual Android renderer, using independent performed work.
            for (style in listOf("MINIMAL", "CLASSIC")) {
                val token = style.lowercase(java.util.Locale.ROOT); val id = "quote-$token"
                assertNull(repository.saveBusinessSettings("style-$token", version(), repository.businessSnapshot().settings.copy(invoiceStyle = style)).conflict)
                assertNull(repository.saveQuote("save-$token", version(), QuoteStore.Draft(id, "quote-customer", null, "Synthetic tile work", "USD", 75000, listOf(QuoteStore.Line("tile", "Install tile", "", "ITEM", "260000", "1250")))).conflict)
                assertNull(repository.issueQuote("issue-$token", version(), id, true).conflict)
                assertNull(repository.decideQuote("accept-$token", version(), id, 1, "ACCEPTED", "Explicit synthetic acceptance", true).conflict)
                val job = "job-$token"; val task = "task-$token"
                assertNull(repository.applyQuoteScope("scope-$token", version(), QuoteScopeStore.Draft(id, 1, job, listOf(QuoteScopeStore.Item("tile", task, "Install tile")), "Reviewed actual planned scope", true, "Synthetic performed work")).conflict)
                val done = repository.boardSnapshot(job).board.columns.single { it.completed }.id
                assertNull(repository.saveCard("performed-$token", version(), BoardStore.Draft(task, job, done, "Install tile", priceMinor = BigInteger("999"), currency = "USD", quantityUnits = 300000)).conflict)
                val styleDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getDefault() }.format(java.util.Date(requireNotNull(repository.boardSnapshot(job).board.cards.single().completedMillis)))
                val request = QuoteInvoiceReviewStore.Request(id, 1, ReportStore.Selection(styleDate, styleDate, java.util.TimeZone.getDefault().id, listOf(job)))
                assertNull(repository.prepareQuoteInvoice("prepare-$token", version(), QuoteInvoiceStore.Draft("invoice-$token", request, QuoteInvoiceReviewStore.Choices(listOf(task), emptyList(), note = "Reviewed actual units at accepted price", confirmed = true))).conflict)
                document(repository, "invoice-$token", "actual-quote-invoice-$token")
            }
            assertEquals(3, count("invoices")); assertEquals(3, count("invoice_task_claims")); assertEquals(0, count("invoice_payments"))
            inspect { db -> db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) } }
        } catch (failure: Throwable) { try { diagnostic() } catch (extra: Throwable) { failure.addSuppressed(extra) }; throw failure }
        finally { closeActivities() }
    }
}
