package io.github.centerionware.billingtest

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.invoice.InvoiceDocuments
import io.github.centerionware.clockrove.tracking.BoardStore
import io.github.centerionware.clockrove.tracking.BusinessStore
import io.github.centerionware.clockrove.tracking.InvoicePaymentStore
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.ReportStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.io.File
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh real-app process: settings -> partial/full payments -> correction -> retained receipt. */
@RunWith(AndroidJUnit4::class)
class InvoicePaymentDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val captures get() = File(app.getExternalFilesDir(null), "payment-captures").apply { mkdirs() }
    private lateinit var job: String
    private val jobName = "Synthetic payment customer job"
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
        val fieldId = when (label) {
            "Payment amount (USD)" -> "invoice-payment-amount"
            "Internal note (optional)", "Reason for correction" -> "invoice-payment-note"
            "Accounts receivable email (optional)" -> "accounts-receivable-email"
            "Unit price (USD)" -> "invoice-correction-price-0"
            "Replacement tax (%)" -> "invoice-correction-tax"
            "Internal correction reason" -> "invoice-correction-reason"
            else -> null
        }
        fun editableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.className?.toString() == "android.widget.EditText") return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { editableNode(it)?.let { found -> return found } }
            return null
        }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (editable && node.isVisibleToUser && fieldId != null && node.viewIdResourceName == fieldId) return editableNode(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            fun matches(value: CharSequence?) = value?.toString()?.let {
                if (editable) it.contains(label) else it.lineSequence().any { line ->
                    val spoken = line.trim()
                    spoken == label || spoken == "$label, Collapsed" || spoken == "$label, Expanded"
                }
            } == true
            val hintMatches = editable && android.os.Build.VERSION.SDK_INT >= 26 && matches(node.hintText)
            if (node.isVisibleToUser && (!editable || node.isEditable) && (matches(node.text) || matches(node.contentDescription) || hintMatches)) return node
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(label: String, editable: Boolean = false): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        until("Actual app never exposed $label") { found = find(label, editable); found != null }
        return requireNotNull(found)
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
        until("Actual app never exposed $label") {
            if (find(label, editable) != null) true else {
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                    return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                val scrolled = automation.rootInActiveWindow?.let(::scroll) == true
                if (!scrolled) swipeContent(up = true)
                SystemClock.sleep(150)
                false
            }
        }
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
        var touchFallbackUsed = false
        repeat(12) {
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                return node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            if (automation.rootInActiveWindow?.let(::scroll) != true) {
                if (!touchFallbackUsed) {
                    repeat(8) {
                        swipeContent(up = false)
                        SystemClock.sleep(120)
                    }
                    touchFallbackUsed = true
                }
                return
            }
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
                val scrolled = automation.rootInActiveWindow?.let(::scroll) == true
                if (!scrolled) swipeContent(up = false)
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
        if (find(label, true) == null) scrollToStart()
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
    private fun receipt(repository: TimerRepository, copyId: String, name: String, title: String = "Saved payment copy") {
        waitFor(title)
        until("Actual app did not generate its saved payment PDF") { repository.invoiceCopy(copyId).pdf != null }
        val record = repository.invoiceCopy(copyId)
        val pages = InvoiceDocuments.preview(app, record, 0)["pages"] as Int
        waitFor("Page 1 of $pages")
        capture(name)
    }
    @Test fun actualSettingsPartialPaymentSettlementCorrectionAndDeletedJobReceipt() {
        val repository = TimerRepository.get(app)
        fun version() = repository.snapshot().version
        val settings = repository.businessSnapshot().settings.copy(currency = "USD", companyName = "Riverside Studio",
            companyEmail = "company@example.test", receivablesEmail = "", defaultTaxUnits = 75000)
        assertNull(repository.saveBusinessSettings("payment-fixture-settings", version(), settings).conflict)
        assertNull(repository.saveCustomer("payment-fixture-customer", version(), BusinessStore.Customer("payment-customer", "Synthetic customer", "customer@example.test")).conflict)
        job = repository.createJob(jobName)
        assertNull(repository.saveJobSettings("payment-fixture-job", version(), repository.jobDetail(job).job.settings.copy(billingMode = "TASK", customerId = "payment-customer")).conflict)
        val column = repository.boardSnapshot(job).board.columns.first { it.completed }.id
        assertNull(repository.saveCard("payment-fixture-task", version(), BoardStore.Draft("payment-task", job, column,
            "Synthetic completed tile", priceMinor = BigInteger.valueOf(10000), currency = "USD")).conflict)
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertNull(repository.prepareInvoice("payment-fixture-invoice", version(), "payment-invoice",
            ReportStore.Selection(date.format(Date(now - 86400000)), date.format(Date(now + 86400000)), "UTC", listOf(job))).conflict)
        val original = InvoiceDocuments.ensure(repository, "payment-invoice")
        assertEquals("INV-1000", original.number)
        try {
            launch(); click("App settings and customers"); click("Settings")
            waitFor("Billing defaults")
            enter("Accounts receivable email (optional)", "accounts@example.test"); capture("receivables-settings")
            reveal("Save settings"); click("Save settings")
            until("Actual AR address did not persist") { repository.businessSnapshot().settings.receivablesEmail == "accounts@example.test" }
            waitFor("App settings and customers"); click("App settings and customers"); click("Invoices"); click("INV-1000")
            waitFor("Unpaid"); capture("unpaid")
            reveal("Record payment"); click("Record payment")
            // The route can be visible before its first editable semantics node.
            // Do not send forward-scroll actions during that transition: they
            // move the incoming form past the amount before it can be found.
            waitFor("Refresh invoice balance")
            waitFor("Payment amount (USD)", true)
            enter("Payment amount (USD)", "30.001")
            reveal("Confirm payment"); click("Confirm payment")
            reveal("This currency allows 2 decimal places.")
            assertTrue(repository.invoicePayments("payment-invoice").second.payments.isEmpty())
            scrollToStart(); reveal("Payment amount (USD)", true)
            assertEquals("30.001", waitFor("Payment amount (USD)", true).text.toString())
            capture("invalid-payment")
            enter("Payment amount (USD)", ".01")
            enter("Internal note (optional)", "PRIVATE UI PAYMENT NOTE")
            capture("leading-dot-cent-payment")
            reveal("Confirm payment"); click("Confirm payment")
            until("Actual one-cent payment was not recorded") { repository.invoicePayments("payment-invoice").second.balance.paid == BigInteger.ONE }
            val first = repository.invoicePayments("payment-invoice").second.payments.single()
            assertEquals(BigInteger.ONE, first.amount)
            receipt(repository, "receipt-${first.id}", "partial-receipt")
            val partial = repository.invoiceCopy("receipt-${first.id}")
            assertEquals("107.49", InvoiceStore.Snapshot.decode(partial.payload).statement!!.due)
            assertFalse(partial.payload.contains("PRIVATE UI PAYMENT NOTE"))
            val mail = InvoiceDocuments.email(app, partial, repository.businessSnapshot().settings)
            assertArrayEquals(arrayOf("customer@example.test"), mail.getStringArrayExtra(Intent.EXTRA_EMAIL))
            assertArrayEquals(arrayOf("accounts@example.test"), mail.getStringArrayExtra(Intent.EXTRA_CC))
            app.contentResolver.openInputStream(InvoiceDocuments.uri(app, partial))!!.use { assertArrayEquals(partial.pdf, it.readBytes()) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            revealDetailStatus("Partially paid"); capture("partial-balance")
            reveal("Mark paid"); click("Mark paid")
            assertEquals("107.49", waitFor("Payment amount (USD)", true).text.toString())
            reveal("Confirm payment"); click("Confirm payment")
            until("Actual remaining payment did not settle the invoice") { repository.invoicePayments("payment-invoice").second.balance.due == BigInteger.ZERO }
            val second = repository.invoicePayments("payment-invoice").second.payments.single { it.id != first.id }
            receipt(repository, "receipt-${second.id}", "paid-receipt")
            val paid = repository.invoiceCopy("receipt-${second.id}")
            assertEquals("PAID", InvoiceStore.Snapshot.decode(paid.payload).statement!!.status)
            assertEquals("0.00", InvoiceStore.Snapshot.decode(paid.payload).statement!!.due)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            revealDetailStatus("Paid"); reveal("Correct payment"); click("Correct payment")
            waitFor("Reverse the recorded payment of USD 0.01.")
            enter("Reason for correction", "Corrected duplicate transfer entry")
            reveal("Confirm reversal"); click("Confirm reversal")
            until("Actual correction did not reverse its payment") { repository.invoicePayments("payment-invoice").second.payments.first { it.id == first.id }.reversalId != null }
            val reversal = repository.invoicePayments("payment-invoice").second.payments.first { it.id == first.id }.reversalId!!
            receipt(repository, "correction-$reversal", "correction-receipt")
            assertEquals(BigInteger.valueOf(10749), repository.invoicePayments("payment-invoice").second.balance.paid)
            assertEquals(BigInteger.ONE, repository.invoicePayments("payment-invoice").second.balance.due)
            assertArrayEquals(paid.pdf, repository.invoiceCopy("receipt-${second.id}").pdf)
            assertEquals(original.payload, repository.invoice("payment-invoice").payload)
            assertArrayEquals(original.pdf, repository.invoice("payment-invoice").pdf)
            assertNull(repository.archiveJob("payment-ui-archive", version(), job).conflict)
            inspect { it.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf(job)) }
            assertNull(repository.deleteJob("payment-ui-purge", version(), job).conflict)
            closeActivities(); launch(); click("App settings and customers"); click("Invoices"); click("INV-1000")
            waitFor("Job deleted · financial records retained"); waitFor("Partially paid"); capture("retained-balance")
            reveal("Mark paid"); click("Mark paid")
            assertEquals("0.01", waitFor("Payment amount (USD)", true).text.toString())
            reveal("Confirm payment"); click("Confirm payment")
            until("Deleted-job invoice could not be settled") { repository.invoicePayments("payment-invoice").second.balance.due == BigInteger.ZERO }
            val final = repository.invoicePayments("payment-invoice").second.payments.single { it.id != first.id && it.id != second.id }
            receipt(repository, "receipt-${final.id}", "retained-paid-receipt")
            assertEquals(BigInteger.valueOf(750), repository.invoicePayments("payment-invoice").second.collectedTax)
            assertEquals(4, repository.invoiceCopies("payment-invoice").size)
            inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1001L, it.getLong(0)) } }
            // New actual UI journey: partially paid correction -> immutable replacement ->
            // historic receipt owner -> VOID copy -> explicit unpaid cancellation.
            val correctionJob = repository.createJob("Synthetic correction job")
            assertNull(repository.saveJobSettings("correction-fixture-job", version(), repository.jobDetail(correctionJob).job.settings.copy(billingMode = "TASK", customerId = "payment-customer")).conflict)
            val correctionColumn = repository.boardSnapshot(correctionJob).board.columns.first { it.completed }.id
            assertNull(repository.saveCard("correction-fixture-card", version(), BoardStore.Draft("correction-task", correctionJob, correctionColumn,
                "Synthetic correction tile", priceMinor = BigInteger.valueOf(10000), currency = "USD")).conflict)
            val correctionSelection = ReportStore.Selection(date.format(Date(now - 86400000)), date.format(Date(now + 86400000)), "UTC", listOf(correctionJob))
            assertNull(repository.prepareInvoice("correction-fixture-invoice", version(), "correction-invoice", correctionSelection).conflict)
            val correctionOriginal = InvoiceDocuments.ensure(repository, "correction-invoice")
            assertEquals("INV-1001", correctionOriginal.number)
            assertNull(repository.recordInvoicePayment("correction-fixture-credit", version(), InvoicePaymentStore.Draft("correction-credit", "correction-invoice", "3000", "USD", System.currentTimeMillis() - 1000, "UTC")).conflict)
            val originalReceipt = InvoiceDocuments.ensure(repository, "correction-invoice", "receipt-correction-credit")
            closeActivities(); launch(); click("App settings and customers"); click("Invoices"); click("INV-1001")
            waitFor("Partially paid"); reveal("Correct this invoice"); click("Correct this invoice")
            reveal("Void & replace"); click("Void & replace")
            enter("Unit price (USD)", "120.00"); enter("Replacement tax (%)", "10")
            enter("Internal correction reason", "Synthetic corrected tile unit price")
            scrollToStart(); reveal("Unit price (USD)", true); capture("replacement-editor")
            reveal("Review correction"); click("Review correction")
            waitFor("Void and replace INV-1001?"); waitFor("Remaining balance: USD 102.00"); capture("replacement-review")
            click("Keep editing")
            assertEquals("PARTIALLY_PAID", repository.invoicePayments("correction-invoice").second.balance.status)
            assertEquals(1, repository.invoices(correctionJob).invoices.size)
            reveal("Review correction"); click("Review correction"); click("Confirm review")
            reveal("Issue replacement invoice"); click("Issue replacement invoice")
            until("Actual editor did not atomically replace the invoice") { repository.invoicePayments("correction-invoice").second.balance.status == "VOID" }
            val replacementId = requireNotNull(repository.invoiceVoidContext("correction-invoice")!!.info.replacementId)
            waitFor("INV-1002"); waitFor("Partially paid"); capture("replacement-balance")
            val replacementState = repository.invoicePayments(replacementId).second
            assertEquals(BigInteger.valueOf(3000), replacementState.balance.paid)
            assertEquals(BigInteger.valueOf(10200), replacementState.balance.due)
            assertEquals(BigInteger.valueOf(273), replacementState.collectedTax)
            assertEquals("correction-credit", replacementState.payments.single().id)
            assertEquals("correction-invoice", replacementState.payments.single().originalInvoiceId)
            assertEquals(replacementId, replacementState.payments.single().currentInvoiceId)
            assertEquals("12000", replacementState.document.lines.single().unitPriceMinor)
            assertFalse(replacementState.document.encode().contains("Synthetic corrected tile unit price"))
            assertEquals(correctionOriginal.payload, repository.invoice("correction-invoice").payload)
            assertArrayEquals(correctionOriginal.pdf, repository.invoice("correction-invoice").pdf)
            reveal("Open receipt"); click("Open receipt")
            receipt(repository, "receipt-correction-credit", "replacement-original-receipt")
            reveal("Email historical copy")
            assertArrayEquals(originalReceipt.pdf, repository.invoiceCopy("receipt-correction-credit").pdf)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            revealDetailStatus("Partially paid"); reveal("Open original invoice"); click("Open original invoice")
            waitFor("Voided"); capture("void-balance")
            assertEquals(BigInteger.ZERO, repository.invoicePayments("correction-invoice").second.balance.due)
            reveal("Cancellation copy"); click("Cancellation copy")
            receipt(repository, "void-correction-invoice", "cancellation-copy", "Cancellation copy")
            val cancellation = repository.invoiceCopy("void-correction-invoice")
            val cancellationMail = InvoiceDocuments.email(app, cancellation, repository.businessSnapshot().settings)
            assertArrayEquals(arrayOf("accounts@example.test"), cancellationMail.getStringArrayExtra(Intent.EXTRA_CC))
            app.contentResolver.openInputStream(InvoiceDocuments.uri(app, cancellation))!!.use { assertArrayEquals(cancellation.pdf, it.readBytes()) }
            assertNull(repository.saveCard("unpaid-void-fixture-card", version(), BoardStore.Draft("unpaid-void-task", correctionJob, correctionColumn,
                "Synthetic cancelled work", priceMinor = BigInteger.valueOf(5000), currency = "USD")).conflict)
            assertNull(repository.prepareInvoice("unpaid-void-fixture-invoice", version(), "unpaid-void-invoice", correctionSelection).conflict)
            val unpaidOriginal = InvoiceDocuments.ensure(repository, "unpaid-void-invoice")
            assertEquals("INV-1003", unpaidOriginal.number)
            closeActivities(); launch(); click("App settings and customers"); click("Invoices"); click("INV-1003")
            waitFor("Unpaid"); reveal("Correct this invoice"); click("Correct this invoice")
            reveal("Void unpaid invoice"); click("Void unpaid invoice")
            enter("Internal correction reason", "Synthetic cancelled duplicate invoice")
            reveal("Review correction"); click("Review correction"); waitFor("Void INV-1003?"); click("Confirm review")
            reveal("Confirm void"); click("Confirm void")
            until("Actual unpaid cancellation did not persist") { repository.invoicePayments("unpaid-void-invoice").second.balance.status == "VOID" }
            receipt(repository, "void-unpaid-void-invoice", "unpaid-void", "Cancellation copy")
            assertEquals(unpaidOriginal.payload, repository.invoice("unpaid-void-invoice").payload)
            assertArrayEquals(unpaidOriginal.pdf, repository.invoice("unpaid-void-invoice").pdf)
            assertEquals(BigInteger.valueOf(3000), repository.invoicePayments(replacementId).second.balance.paid)
            assertEquals(listOf("unpaid-void-task"), repository.invoiceBillingPreview(correctionSelection).preview.report.tasks.map { it.id })

        } catch (failure: Throwable) {
            try { diagnostic() } catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        } finally { closeActivities() }
    }
}
