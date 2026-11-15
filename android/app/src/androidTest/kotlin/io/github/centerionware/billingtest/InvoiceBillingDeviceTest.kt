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
    private fun find(label: String, editable: Boolean = false, checkable: Boolean = false): AccessibilityNodeInfo? {
        val fieldId = when (label) {
            "Why this work belongs to the invoice" -> "legacy-invoice-review-note"
            "Default tax (%)" -> "default-tax-percent"
            "Job tax (%)" -> "job-tax-percent"
            "Unit price (USD)" -> "task-unit-price"
            "Quantity" -> "task-quantity"
            else -> null
        }
        fun editableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.className?.toString() == "android.widget.EditText") return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { editableNode(it)?.let { found -> return found } }
            return null
        }
        fun matches(value: CharSequence?) = value?.toString()?.let {
            if (editable) it.contains(label) else it.lineSequence().any { line -> line.trim() == label }
        } == true
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (editable && node.isVisibleToUser && fieldId != null && node.viewIdResourceName == fieldId) return editableNode(node)
            if (checkable && node.isVisibleToUser && node.isCheckable &&
                (matches(node.text) || node.contentDescription?.toString()?.contains(label) == true)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            val hintMatches = editable && android.os.Build.VERSION.SDK_INT >= 26 && matches(node.hintText)
            val errorMatches = matches(node.error)
            if (!checkable && node.isVisibleToUser && (!editable || node.isEditable) &&
                (matches(node.text) || matches(node.contentDescription) || hintMatches || errorMatches)) return node
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    private fun safeTapBounds(node: AccessibilityNodeInfo): android.graphics.Rect? {
        val root = automation.rootInActiveWindow ?: return null
        if (node.packageName != root.packageName || !node.isVisibleToUser) return null
        val viewport = android.graphics.Rect().also(root::getBoundsInScreen)
        if (viewport.isEmpty) return null
        val systemBounds = automation.windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }
            .map { android.graphics.Rect().also(it::getBoundsInScreen) }
        val topInset = systemBounds
            .filter { it.top <= viewport.top + 8 && it.bottom > viewport.top }
            .map { it.bottom }
            .maxOrNull()
        val navTop = systemBounds
            .filter { it.top > viewport.top + 50 && it.bottom >= viewport.bottom - 8 }
            .map { it.top }
            .minOrNull()
        val imeTop = automation.windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { android.graphics.Rect().also(it::getBoundsInScreen).top }
            .minOrNull()
        if (topInset != null) viewport.top = maxOf(viewport.top, topInset)
        viewport.bottom = minOf(viewport.bottom, navTop ?: viewport.bottom, imeTop ?: viewport.bottom)
        val target = android.graphics.Rect().also(node::getBoundsInScreen)
        if (!target.intersect(viewport) || target.width() < 8 || target.height() < 12) return null
        return target
    }
    private fun waitFor(label: String, editable: Boolean = false, checkable: Boolean = false): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        until("Actual app never exposed $label") { found = find(label, editable, checkable); found != null }
        return requireNotNull(found)
    }
    private fun waitForSwitch(label: String, checked: Boolean) {
        until("$label did not become ${if (checked) "enabled" else "disabled"}") {
            find(label, checkable = true)?.isChecked == checked
        }
    }
    private fun click(label: String) {
        // A semantic ACTION_CLICK can report success without delivering
        // Flutter's pointer event; API 24 also exposes some active buttons as
        // non-clickable. Find a point inside both the target and the app's
        // unobscured viewport before tapping so a Flutter node extending
        // beneath Android's navigation bar can never hit Home.
        reveal(label)
        check(tapNode(waitFor(label))) { "Tap was obscured before reaching $label" }
        automation.waitForIdle(250, 3000)
    }
    private fun reveal(label: String, editable: Boolean = false) {
        until("Actual app never exposed $label") {
            if (find(label, editable)?.let(::safeTapBounds) != null) true else {
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    // Prefer the screen's vertical container to editable
                    // fields' own scroll actions, then use a real swipe if the
                    // platform does not expose a scroll action.
                    if (node.isVisibleToUser && node.isScrollable &&
                        node.className?.toString() == "android.widget.ScrollView" &&
                        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                    return false
                }
                val scrolled = automation.rootInActiveWindow?.let(::scroll) == true
                if (scrolled) {
                    // Accessibility scrolls complete asynchronously. Observe their
                    // settled result before considering another movement; an
                    // immediate fallback swipe can skip the target entirely.
                    automation.waitForIdle(250, 3000)
                }
                if (find(label, editable)?.let(::safeTapBounds) != null) true else {
                    swipeContent(up = true)
                    automation.waitForIdle(250, 3000)
                    find(label, editable)?.let(::safeTapBounds) != null
                }
            }
        }
    }
    private fun scrollToStart(topLabel: String = "Billing basis") {
        hideKeyboard()
        // On API 24 the Flutter ScrollView reports ACTION_SCROLL_BACKWARD as
        // successful while remaining at the same offset. An accessibility
        // acknowledgement is not proof that the contents actually moved, so
        // use real downward-content swipes and stop only once the top section
        // is safely exposed. Forms may reopen at a restored scroll position,
        // so every new screen can name its own top anchor.
        repeat(40) {
            if (find(topLabel)?.let(::safeTapBounds) != null) return
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isVisibleToUser && node.isScrollable &&
                    node.className?.toString() == "android.widget.ScrollView" &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                return false
            }
            // Some API-24 ScrollViews acknowledge the action without moving.
            // Check for the target again; retain the touch gesture as a fallback.
            if (automation.rootInActiveWindow?.let(::scroll) == true) {
                automation.waitForIdle(250, 3000)
                if (find(topLabel)?.let(::safeTapBounds) != null) return
            }
            swipeContent(up = false)
            SystemClock.sleep(120)
        }
        until("Could not restore screen to safe top anchor $topLabel") {
            find(topLabel)?.let(::safeTapBounds) != null
        }
    }
    private fun swipeContent(up: Boolean) {
        val root = automation.rootInActiveWindow ?: return
        val bounds = android.graphics.Rect().also(root::getBoundsInScreen)
        data class Target(val bounds: android.graphics.Rect, val firstChild: android.graphics.Rect?)
        fun scrollTarget(node: AccessibilityNodeInfo): Target? {
            if (node.isVisibleToUser && node.isScrollable &&
                node.className?.toString() == "android.widget.ScrollView") {
                val child = node.getChild(0)?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
                return Target(android.graphics.Rect().also(node::getBoundsInScreen), child)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { scrollTarget(it)?.let { found -> return found } }
            }
            return null
        }
        val target = scrollTarget(root)
        val content = target?.bounds?.takeUnless { it.isEmpty } ?: bounds
        val imeTop = automation.windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { android.graphics.Rect().also(it::getBoundsInScreen).top }
            .minOrNull()
        // On API 35 the app's Flutter root extends underneath Android's
        // navigation bar. Starting a swipe at the root's bottom edge lets the
        // system bar consume it instead of scrolling the page.
        val systemBottomTop = automation.windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }
            .map { android.graphics.Rect().also(it::getBoundsInScreen) }
            .filter { it.top > bounds.top + 50 && it.bottom >= bounds.bottom - 8 }
            .map { it.top }
            .minOrNull()
        val bottom = minOf(
            bounds.bottom,
            imeTop ?: bounds.bottom,
            systemBottomTop ?: bounds.bottom,
            content.bottom,
        ) - 20
        val top = maxOf(bounds.top + 220, content.top + 10)
        if (bottom - top < 300) return
        // Start just inside the scroll view's first child. Page margins miss
        // Flutter's hit-test tree; tapping a control's center can consume a drag.
        val child = target?.firstChild?.takeUnless { it.isEmpty }
        val x = (child?.let { (it.left + 1).coerceAtMost(it.right - 1) } ?: content.centerX()).toFloat()
        val startY = if (up) bottom - 80 else top + 80
        val endY = if (up) top + (bottom - top) * 2 / 5 else bottom - 160
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, eventTime: Long, y: Float) {
            val event = android.view.MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        inject(android.view.MotionEvent.ACTION_DOWN, downTime, startY.toFloat())
        for (step in 1..8) {
            val eventTime = SystemClock.uptimeMillis() + 16
            val y = startY + (endY - startY) * step / 8
            inject(android.view.MotionEvent.ACTION_MOVE, eventTime, y.toFloat())
            SystemClock.sleep(16)
        }
        inject(android.view.MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endY.toFloat())
    }
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val safeBounds = safeTapBounds(node)
        // A focused EditText can reveal the IME between the caller's check and
        // this tap. In that case its ACTION_CLICK already opened text entry;
        // skip the now-obscured pointer event instead of tapping system UI.
        if (safeBounds == null && keyboardVisible() &&
            node.packageName == automation.rootInActiveWindow?.packageName) return false
        check(safeBounds != null) {
            "Refusing tap outside the app's unobscured viewport: target=$node bounds=$bounds"
        }
        val x = requireNotNull(safeBounds).exactCenterX()
        val y = safeBounds.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, eventTime: Long) {
            val event = android.view.MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        inject(android.view.MotionEvent.ACTION_DOWN, downTime)
        SystemClock.sleep(80)
        inject(android.view.MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
        return true
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
        var field = waitFor(label, true)
        // Try the editable node's text action before opening the IME. Accept
        // it only after reading back the exact value; some APIs acknowledge
        // this action without changing the Flutter controller.
        if (field.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } &&
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) {
            val directSetDeadline = SystemClock.uptimeMillis() + 2000
            while (SystemClock.uptimeMillis() < directSetDeadline &&
                find(label, true)?.text?.toString() != value) {
                SystemClock.sleep(100)
            }
            if (find(label, true)?.text?.toString() == value) {
                hideKeyboard()
                return
            }
        }
        field = waitFor(label, true)
        assertTrue("Could not focus $label", field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        // Some Flutter EditableText nodes accept ACTION_CLICK without focusing
        // the underlying field on older Android releases. Use a screen touch
        // when that semantic action does not open the IME.
        val focusDeadline = SystemClock.uptimeMillis() + 2000
        while (SystemClock.uptimeMillis() < focusDeadline &&
            find(label, true)?.isFocused != true) {
            SystemClock.sleep(100)
        }
        if (find(label, true)?.isFocused != true) {
            assertTrue("Could not touch-focus $label", tapNode(waitFor(label, true)))
        }
        // Wait for the actual IME/inset transition before writing. A fast SET_TEXT
        // acknowledgement can otherwise precede the keyboard opening and hiding.
        until("Editable field did not focus with keyboard for $label") {
            find(label, true)?.isFocused == true && keyboardVisible()
        }
        val node = waitFor(label, true)
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } &&
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) {
            val setTextDeadline = SystemClock.uptimeMillis() + 2000
            while (SystemClock.uptimeMillis() < setTextDeadline && find(label, true)?.text?.toString() != value) {
                SystemClock.sleep(100)
            }
        }
        // Some API-24 Flutter fields report ACTION_SET_TEXT success without
        // replacing the current value. Select the old text and type through
        // the active IME before treating the field as failed.
        if (find(label, true)?.text?.toString() != value) {
            val current = waitFor(label, true)
            val length = current.text?.length ?: 0
            current.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                },
            )
            // API 24 and 35 can report a successful selected range while only
            // moving the caret. For populated fields, use the editor's normal
            // Ctrl+A shortcut before replacing text. Keep empty fields on the
            // plain IME typing path; Ctrl+A can interfere with a blank field.
            if (length > 0) {
                val downTime = SystemClock.uptimeMillis()
                instrumentation.sendKeySync(
                    KeyEvent(
                        downTime,
                        downTime,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_A,
                        0,
                        KeyEvent.META_CTRL_ON,
                    ),
                )
                instrumentation.sendKeySync(
                    KeyEvent(
                        downTime,
                        downTime + 1,
                        KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_A,
                        0,
                        KeyEvent.META_CTRL_ON,
                    ),
                )
            }
            instrumentation.sendStringSync(value)
        }
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
            scrollToStart("Billing defaults")
            enter("Default tax (%)", "7.5"); capture("global-tax-settings")
            reveal("Save settings"); click("Save settings")
            until("Actual main Settings did not save tax") { repository.businessSnapshot().settings.defaultTaxUnits == 75000 }
            waitFor("App settings and customers"); click("App settings and customers"); click("Invoices"); click("INV-1000")
            reveal("Review billed work"); click("Review billed work"); waitFor("Review billed work")
            reveal("Previously installed tile"); click("Previously installed tile")
            until("Covered task checkbox did not become checked") { find("Previously installed tile")?.isChecked == true }
            enter("Why this work belongs to the invoice", "Checked original invoice: only the previously installed tile was billed.")
            reveal("I checked this against the original invoice"); click("I checked this against the original invoice")
            until("Original-document confirmation was not checked") { find("I checked this against the original invoice")?.isChecked == true }
            capture("legacy-selection"); click("Save coverage review")
            until("Actual Flutter review never committed") { !repository.legacyInvoiceContext("legacy-invoice").context.legacy }
            waitFor("INV-1000"); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitFor("Invoices")
            assertEquals(originalPayload, repository.invoice("legacy-invoice").payload)
            assertArrayEquals(originalPdf, repository.invoice("legacy-invoice").pdf)
            assertEquals(1001L, sequence())

            closeActivities(); launch(); reveal(jobName); click(jobName)
            waitFor("Task board"); reveal("Install 1 sq ft tile"); click("Install 1 sq ft tile")
            waitFor("Edit task")
            enter("Unit price (USD)", "1000000000000000000.00"); enter("Quantity", "26")
            reveal("Supplies"); click("Supplies"); capture("supplies-classification")
            reveal("Save task"); click("Save task")
            until("Wide unit price was narrowed during actual UI save") {
                repository.boardSnapshot(job).board.cards.single { it.id == "new-task" }.priceMinor == java.math.BigInteger("100000000000000000000")
            }
            waitFor("Task board"); waitFor("USD 26000000000000000000.00"); capture("wide-money-board")
            click("Install 1 sq ft tile"); waitFor("Edit task"); reveal("Unit price (USD)", true)
            assertTrue("Wide unit price did not reopen exactly", waitFor("Unit price (USD)", true).text?.toString()?.contains("1000000000000000000.00") == true)
            enter("Unit price (USD)", "5.00")
            reveal("Save task"); capture("quantity-editor"); click("Save task")
            until("Actual task editor did not commit quantity and unit price") {
                repository.boardSnapshot(job).board.cards.single { it.id == "new-task" }.let {
                    it.quantityUnits == 260000L && it.priceMinor == java.math.BigInteger.valueOf(500) && it.chargeType == "SUPPLIES"
                }
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
            assertEquals("SUPPLIES", frozen.lines.single().chargeType)
            waitFor("Page 1 of 1"); capture("issued-invoice")
            assertTrue(requireNotNull(repository.invoice(issued.id).pdf).size > 1000)
            assertEquals(1002L, sequence())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Work report"); reveal("PDF invoice"); click("PDF invoice"); waitFor("Review invoice")
            reveal("No unbilled work remains in these dates. Previously invoiced work is excluded.")
            capture("already-billed")
            assertEquals(1002L, sequence()); assertEquals(2, repository.invoices(job).invoices.size)

            closeActivities(); launch(); reveal(jobName); click(jobName); waitFor("Task board")
            click("Job settings"); waitFor("Job settings"); reveal("Use default tax rate")
            assertTrue("New job should inherit the global tax default", waitFor("Use default tax rate", checkable = true).isChecked)
            click("Use default tax rate"); waitForSwitch("Use default tax rate", false)
            // Enabling the override inserts a field above the current viewport.
            // Start from a known position before looking for that newly built field.
            scrollToStart()
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
            scrollToStart(); reveal("Use default tax rate")
            assertFalse("Saved zero tax override should disable default inheritance", waitFor("Use default tax rate", checkable = true).isChecked)
            click("Use default tax rate"); waitForSwitch("Use default tax rate", true)
            capture("job-tax-default-inheritance")
            reveal("Save job settings"); click("Save job settings")
            until("Saving inherited tax did not close Job settings") { find("Task board") != null }
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
            waitFor("$jobName · job deleted"); click("INV-1001"); reveal("View original PDF"); click("View original PDF"); waitFor("Prepared invoice"); waitFor("Page 1 of 1")
            capture("retained-invoice")
            assertEquals(frozen, InvoiceStore.Snapshot.decode(repository.invoice(issued.id).payload))
        } catch (failure: Throwable) {
            try { diagnostic() } catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        } finally { closeActivities() }
    }
}
