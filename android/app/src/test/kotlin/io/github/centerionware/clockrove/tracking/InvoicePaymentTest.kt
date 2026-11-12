package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
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
class InvoicePaymentTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private val at = 1793494800000L
    private val version get() = repository.snapshot().version
    private val selection get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    private fun n(value: Long) = BigInteger.valueOf(value)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun draft(id: String = "payment", amount: String = "3000") = InvoicePaymentStore.Draft(id, "invoice", amount, "USD", at - 1000, "UTC", "Transfer", "Synthetic reference", "Synthetic payment note")
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); repository = TimerRepository(app, wallClock = { at })
        assertNull(repository.saveBusinessSettings("currency", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultTaxUnits = 75000)).conflict)
        job = repository.createJob("Synthetic payment job")
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        assertNull(repository.saveCard("task", version, BoardStore.Draft("tile", job, repository.boardSnapshot(job).board.columns.last().id, "Synthetic work", priceMinor = n(10000), currency = "USD")).conflict)
        assertNull(repository.prepareInvoice("prepare", version, "invoice", selection).conflict)
    }
    @After fun close() { repository.close() }
    private fun snapshot() = repository.invoicePayments("invoice").second
    @Test fun partialThenFullPaymentKeepsFrozenOriginalNumberCoverageAndDocument() {
        val original = repository.invoice("invoice").payload
        assertEquals("UNPAID", snapshot().balance.status)
        assertNull(repository.recordInvoicePayment("record", version, draft()).conflict)
        assertEquals(n(3000), snapshot().balance.paid)
        assertEquals(n(7750), snapshot().balance.due)
        assertEquals(n(209), snapshot().collectedTax)
        assertEquals("PARTIALLY_PAID", snapshot().balance.status)
        assertNull(repository.recordInvoicePayment("settle", version, draft("final", "7750")).conflict)
        assertEquals("PAID", snapshot().balance.status)
        assertEquals(n(750), snapshot().collectedTax)
        assertEquals(n(0), snapshot().balance.due)
        assertEquals(original, repository.invoice("invoice").payload)
        assertEquals("INV-1000", repository.invoice("invoice").number)
        assertEquals("NO_BILLABLE_ITEMS", repository.prepareInvoice("rebill", version, "another", selection).conflict)
        inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1001L, it.getLong(0)) } }
    }
    @Test fun responseLossAndOpaqueReceiptReplayNeverRecordsPaymentTwice() {
        val initial = version; val value = draft()
        val outcome = repository.recordInvoicePayment("record", initial, value)
        assertNull(outcome.conflict)
        repository.close(); repository = TimerRepository(app, wallClock = { at })
        assertEquals(outcome, repository.recordInvoicePayment("record", initial, value))
        inspect { OpaqueReceipt.purgePayloads(it) }
        assertEquals(outcome, repository.recordInvoicePayment("record", initial, value))
        assertEquals("COMMAND_ID_REUSED", repository.recordInvoicePayment("record", initial, value.copy(amountMinor = "3001")).conflict)
        assertEquals(1, snapshot().payments.size); assertEquals(n(3000), snapshot().balance.paid)
    }
    @Test fun concurrentRemainingBalancePaymentsHaveOneWinnerAndRetainStaleDraft() {
        val initial = version
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val futures = (1..2).map { i -> pool.submit<TimerRepository.Outcome> { start.await(); repository.recordInvoicePayment("record-$i", initial, draft("payment-$i", "10750")) } }
            start.countDown()
            val outcomes = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.conflict == null }); assertEquals(1, outcomes.count { it.conflict == "STALE_STATE" })
            assertEquals(n(10750), snapshot().balance.paid); assertEquals(1, snapshot().payments.size)
            assertEquals("PAYMENT_EXCEEDS_BALANCE", repository.recordInvoicePayment("reviewed-extra", version, draft("extra", "1")).conflict)
        } finally { pool.shutdownNow() }
    }
    @Test fun malformedCurrencyOverpaymentAndDuplicateIdentityNeverChangeBalance() {
        listOf("0", "-1", "01", "1.0", "1e3", " 1", "9".repeat(4097)).forEachIndexed { i, amount ->
            assertEquals("INVALID_PAYMENT_AMOUNT", repository.recordInvoicePayment("bad-$i", version, draft("bad-$i", amount)).conflict)
        }
        assertEquals("PAYMENT_CURRENCY_MISMATCH", repository.recordInvoicePayment("currency-bad", version, draft().copy(currency = "EUR")).conflict)
        assertEquals("INVALID_PAYMENT", repository.recordInvoicePayment("zone-bad", version, draft().copy(timezone = "Invalid/Zone")).conflict)
        assertEquals("INVALID_PAYMENT", repository.recordInvoicePayment("future", version, draft().copy(received = at + 1)).conflict)
        assertEquals("PAYMENT_EXCEEDS_BALANCE", repository.recordInvoicePayment("too-much", version, draft(amount = "10751")).conflict)
        assertEquals(n(0), snapshot().balance.paid)
        assertNull(repository.recordInvoicePayment("valid", version, draft()).conflict)
        assertEquals("PAYMENT_ID_EXISTS", repository.recordInvoicePayment("duplicate", version, draft()).conflict)
        assertEquals(1, snapshot().payments.size)
    }
    @Test fun auditedReversalRetainsPaymentAndConservesTaxWithoutMutatingOriginal() {
        assertNull(repository.recordInvoicePayment("first", version, draft()).conflict)
        assertNull(repository.recordInvoicePayment("second", version, draft("second", "2000")).conflict)
        val original = repository.invoice("invoice").payload
        val reversal = InvoicePaymentStore.Reversal("reverse", "invoice", "payment", at, "UTC", "Entered against wrong reference")
        val initial = version
        val outcome = repository.reverseInvoicePayment("reverse-command", initial, reversal)
        assertNull(outcome.conflict)
        assertEquals(outcome, repository.reverseInvoicePayment("reverse-command", initial, reversal))
        assertEquals(n(2000), snapshot().balance.paid); assertEquals(n(8750), snapshot().balance.due)
        assertEquals(n(140), snapshot().collectedTax)
        assertEquals(2, snapshot().payments.size)
        assertEquals("reverse", snapshot().payments.first { it.id == "payment" }.reversalId)
        assertEquals("PAYMENT_ALREADY_REVERSED", repository.reverseInvoicePayment("again", version, reversal.copy(id = "other")).conflict)
        assertEquals(original, repository.invoice("invoice").payload)
        inspect { db ->
            val events = db.rawQuery("SELECT payload FROM invoice_payment_events", null).use { rows -> buildList { while (rows.moveToNext()) add(JSONObject(rows.getString(0))) } }
            val cash = events.fold(n(0)) { sum, event -> sum.add(BigInteger(event.getString("cash_change_minor"))) }
            val tax = events.fold(n(0)) { sum, event -> sum.add(BigInteger(event.getString("tax_change_minor"))) }
            val sales = events.fold(n(0)) { sum, event -> sum.add(BigInteger(event.getString("sales_change_minor"))) }
            assertEquals(n(2000), cash); assertEquals(n(140), tax); assertEquals(cash, tax.add(sales))
        }
    }
    @Test fun ledgerAndFinancialRevisionsSurviveJobPurge() {
        assertNull(repository.recordInvoicePayment("record", version, draft()).conflict)
        assertNull(repository.archiveJob("archive", version, job).conflict)
        inspect { it.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf(job)) }
        assertNull(repository.deleteJob("purge", version, job).conflict)
        assertEquals(n(3000), snapshot().balance.paid)
        assertNull(repository.recordInvoicePayment("later", version, draft("later", "1000")).conflict)
        assertEquals(n(4000), snapshot().balance.paid)
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM entity_revisions WHERE kind='invoice_payment' AND payload IS NOT NULL", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun eventFailureRollsBackPaymentDestinationRevisionsAndCommandReceipt() {
        val initial = version
        inspect { it.execSQL("CREATE TRIGGER fail_payment_event BEFORE INSERT ON invoice_payment_events BEGIN SELECT RAISE(ABORT,'Synthetic event failure'); END") }
        try { repository.recordInvoicePayment("record", initial, draft()); fail("Expected injected rollback") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(initial, version); assertEquals(n(0), snapshot().balance.paid)
        inspect { db ->
            listOf("invoice_payments", "payment_destinations", "invoice_payment_events").forEach { table -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) } }
            db.rawQuery("SELECT COUNT(*) FROM commands WHERE id='record'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM entity_revisions WHERE kind='invoice_payment'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.execSQL("DROP TRIGGER fail_payment_event")
        }
        assertNull(repository.recordInvoicePayment("record", initial, draft()).conflict)
    }
    @Test fun wideIssuedTotalPaymentsAndReversalsStayTextBeyondSourcePriceLimit() {
        val total = "9".repeat(1050)
        inspect { db ->
            val record = repository.invoice("invoice")
            val document = InvoiceStore.Snapshot.decode(record.payload).copy(total = total + ".00", subtotal = null, taxRateUnits = null, taxAmount = null)
            db.execSQL("UPDATE invoices SET payload=? WHERE id='invoice'", arrayOf(document.encode()))
        }
        val amount = BigInteger(total).multiply(n(100))
        val first = amount.divide(n(3))
        assertNull(repository.recordInvoicePayment("wide", version, draft(amount = first.toString())).conflict)
        assertEquals(first, snapshot().balance.paid); assertNull(snapshot().collectedTax)
        assertNull(repository.recordInvoicePayment("wide-settle", version, draft("settle", amount.subtract(first).toString())).conflict)
        assertEquals("PAID", snapshot().balance.status)
        assertNull(repository.reverseInvoicePayment("wide-reverse", version, InvoicePaymentStore.Reversal("reverse", "invoice", "payment", at, "UTC", "Synthetic correction of payment")).conflict)
        assertEquals(amount.subtract(first), snapshot().balance.paid)
        inspect { db -> db.rawQuery("SELECT typeof(amount_minor),amount_minor FROM invoice_payments WHERE id='payment'", null).use {
            it.moveToFirst(); assertEquals("text", it.getString(0)); assertEquals(first.toString(), it.getString(1))
        } }
    }
}
