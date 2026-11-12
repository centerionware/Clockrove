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
        assertNull(repository.saveBusinessSettings("rate", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultHourlyMinor = 10000, defaultTaxUnits = 75000)).conflict)
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
}
