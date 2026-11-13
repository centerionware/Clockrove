package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.flutter.plugin.common.MethodCall
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class InvoicePreviewTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private val version get() = repository.snapshot().version
    private val selection get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { 1793494800000 })
        assertNull(repository.saveBusinessSettings("rate", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultHourlyMinor = java.math.BigInteger.valueOf(10000), defaultTaxUnits = 75000)).conflict)
        job = repository.createJob("Synthetic invoice preview")
    }
    @After fun close() { repository.close() }
    private fun preview(): Map<*, *> = BusinessDispatch(repository).call(MethodCall("invoiceBillingPreview", mapOf(
        "firstDate" to selection.firstDate, "lastDate" to selection.lastDate, "timezone" to selection.timezone, "jobIds" to selection.jobIds))) as Map<*, *>
    private fun counter(): Long = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); it.getLong(0) }
    }
    @Test fun nativePreviewDoesNotReserveWorkAndMatchesTheCommittedTaxedSnapshot() {
        assertNull(repository.createManualSession("work", version, SessionHistoryStore.Draft("work", job, 1793491200000, 1793494800000, "UTC", 0)).conflict)
        val before = version; val draft = preview()
        assertEquals(before, version); assertEquals(1000L, counter())
        assertNull(draft["problem"]); assertEquals("100.00", draft["subtotal"]); assertEquals("7.50", draft["tax"]); assertEquals("107.50", draft["total"])
        assertNull(repository.prepareInvoice("prepare", before, "invoice", selection).conflict)
        val issued = InvoiceStore.Snapshot.decode(repository.invoice("invoice").payload)
        assertEquals(draft["total"], issued.total); assertEquals(draft["subtotal"], issued.subtotal); assertEquals(draft["tax"], issued.taxAmount)
        val remaining = preview()
        assertEquals("NO_BILLABLE_ITEMS", remaining["problem"])
        val excluded = (remaining["excluded"] as List<*>).single() as Map<*, *>
        assertEquals("invoice", excluded["invoiceId"]); assertEquals("INV-1000", excluded["number"])
        assertEquals(1001L, counter())
    }
    @Test fun unpricedTaskPreviewIsActionableAndDoesNotAllocateOrThrow() {
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        val board = repository.boardSnapshot(job).board
        assertNull(repository.saveCard("task", version, BoardStore.Draft("task", job, board.columns.last().id, "Synthetic unpriced task", currency = "USD")).conflict)
        val result = preview()
        assertEquals("TASK_PRICES_REQUIRED", result["problem"]); assertNull(result["subtotal"]); assertNull(result["total"])
        assertEquals("", ((result["lines"] as List<*>).single() as Map<*, *>)["amount"])
        assertEquals(1000L, counter())
    }

    @Test fun hourlyMinimumIsInvoiceOnlyAndItsPolicyIsFrozenInTheIssuedDocument() {
        val settings = repository.businessSnapshot().settings
        assertNull(repository.saveBusinessSettings("minimum", version,
            settings.copy(minimumBillableHours = "1")).conflict)
        val start = 1793491200000L
        assertNull(repository.createManualSession("five-minute-session", version,
            SessionHistoryStore.Draft("five-minute-session", job, start, start + 300_000, "UTC", 0)).conflict)

        val actual = repository.report(selection).report.totals.single()
        assertEquals(java.math.BigInteger.valueOf(300_000), actual.durationMillis)
        assertEquals("100.00", preview()["subtotal"])
        val lines = preview()["lines"] as List<*>
        val workLine = lines.first() as Map<*, *>
        assertTrue((workLine["detail"] as String).contains("Billed hours: 1"))

        assertNull(repository.prepareInvoice("prepare-minimum", version, "minimum-invoice", selection).conflict)
        val issued = InvoiceStore.Snapshot.decode(repository.invoice("minimum-invoice").payload)
        assertEquals("100.00", issued.subtotal)
        assertEquals("1", org.json.JSONObject(requireNotNull(issued.billingPolicy)).getString("minimumHours"))
        assertEquals("107.50", issued.total)
    }

    @Test fun taskMinimumFeeIsVisibleAndJobZeroOverrideSuppressesTheGlobalFee() {
        val taskJob = repository.jobDetail(job).job.settings.copy(billingMode = "TASK")
        assertNull(repository.saveJobSettings("task-mode", version, taskJob).conflict)
        val board = repository.boardSnapshot(job).board
        assertNull(repository.saveCard("task", version, BoardStore.Draft("minimum-task", job,
            board.columns.last().id, "Synthetic task", priceMinor = java.math.BigInteger.valueOf(1_000), currency = "USD")).conflict)
        val settings = repository.businessSnapshot().settings
        assertNull(repository.saveBusinessSettings("minimum-fee", version,
            settings.copy(minimumTaskFeeMinor = java.math.BigInteger.valueOf(2_500))).conflict)
        val selection = this.selection.copy(lastDate = "2026-11-02")
        val raised = BusinessDispatch(repository).call(MethodCall("invoiceBillingPreview", mapOf(
            "firstDate" to selection.firstDate, "lastDate" to selection.lastDate, "timezone" to selection.timezone, "jobIds" to selection.jobIds))) as Map<*, *>
        assertEquals("25.00", raised["subtotal"])
        val raisedLines = raised["lines"] as List<*>
        assertTrue(raisedLines.any { (it as Map<*, *>)["title"] == "Task-rate minimum fee" })

        val jobSettings = repository.jobDetail(job).job.settings.copy(minimumTaskFeeMinor = java.math.BigInteger.ZERO)
        assertNull(repository.saveJobSettings("disable-minimum-fee", version, jobSettings).conflict)
        val overridden = BusinessDispatch(repository).call(MethodCall("invoiceBillingPreview", mapOf(
            "firstDate" to selection.firstDate, "lastDate" to selection.lastDate, "timezone" to selection.timezone, "jobIds" to selection.jobIds))) as Map<*, *>
        assertEquals("10.00", overridden["subtotal"])
        val overriddenLines = overridden["lines"] as List<*>
        assertFalse(overriddenLines.any { (it as Map<*, *>)["title"] == "Task-rate minimum fee" })

        assertNull(repository.saveJobSettings("inherit-task-minimum", version,
            repository.jobDetail(job).job.settings.copy(minimumTaskFeeMinor = null)).conflict)
        val changedCurrency = repository.businessSnapshot().settings.copy(
            currency = "EUR", defaultHourlyMinor = null, minimumTaskFeeMinor = java.math.BigInteger.valueOf(5_000))
        assertNull(repository.saveBusinessSettings("minimum-fee-eur", version, changedCurrency).conflict)
        val otherCurrency = BusinessDispatch(repository).call(MethodCall("invoiceBillingPreview", mapOf(
            "firstDate" to selection.firstDate, "lastDate" to selection.lastDate, "timezone" to selection.timezone, "jobIds" to selection.jobIds))) as Map<*, *>
        assertEquals("10.00", otherCurrency["subtotal"])
        val otherCurrencyLines = otherCurrency["lines"] as List<*>
        assertFalse(otherCurrencyLines.any { (it as Map<*, *>)["title"] == "Task-rate minimum fee" })
    }

    @Test fun invalidGlobalAndJobBillingPoliciesAreRejectedWithoutChangingSettings() {
        val original = repository.businessSnapshot()
        assertEquals("INVALID_MINIMUM_HOURS", repository.saveBusinessSettings("bad-global-hours", version,
            original.settings.copy(minimumBillableHours = "1e2")).conflict)
        assertEquals("INVALID_HOURLY_BILLING_MODE", repository.saveBusinessSettings("bad-global-mode", version,
            original.settings.copy(hourlyBillingMode = "ROUND_DOWN")).conflict)
        assertEquals(original, repository.businessSnapshot())

        val jobBefore = repository.jobDetail(job).job.settings
        assertEquals("INVALID_MINIMUM_HOURS", repository.saveJobSettings("bad-job-hours", version,
            jobBefore.copy(minimumBillableHours = "-1")).conflict)
        assertEquals("INVALID_HOURLY_BILLING_MODE", repository.saveJobSettings("bad-job-mode", version,
            jobBefore.copy(hourlyBillingMode = "ROUND_DOWN")).conflict)
        assertEquals(jobBefore, repository.jobDetail(job).job.settings)
    }
}
