package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
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
class InvoiceCopyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private val at = 1793494800000L
    private val version get() = repository.snapshot().version
    private fun n(value: Long) = BigInteger.valueOf(value)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun pay(id: String, amount: String) = repository.recordInvoicePayment("record-$id", version,
        InvoicePaymentStore.Draft(id, "invoice", amount, "USD", at - 1000, "UTC", "Transfer", "Synthetic reference", "PRIVATE PAYMENT NOTE"))
    private fun statement(id: String) = requireNotNull(InvoiceStore.Snapshot.decode(repository.invoiceCopy(id).payload).statement)
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); repository = TimerRepository(app, wallClock = { at })
        assertNull(repository.saveBusinessSettings("currency", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultTaxUnits = 75000)).conflict)
        job = repository.createJob("Synthetic receipt job")
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        assertNull(repository.saveCard("task", version, BoardStore.Draft("tile", job, repository.boardSnapshot(job).board.columns.last().id, "Synthetic tile", priceMinor = n(10000), currency = "USD")).conflict)
        assertNull(repository.prepareInvoice("prepare", version, "invoice", ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))).conflict)
    }
    @After fun close() { repository.close() }
    @Test fun eachPaymentRetainsItsOwnImmutableReceiptAndOriginalInvoiceNeverChanges() {
        val original = repository.invoice("invoice").payload
        assertNull(pay("first", "3000").conflict)
        val partial = repository.invoiceCopy("receipt-first")
        val partialState = statement("receipt-first")
        assertEquals("PARTIALLY_PAID", partialState.status); assertEquals("30.00", partialState.paid); assertEquals("77.50", partialState.due)
        assertEquals("receipt-first", partialState.reference)
        assertEquals("30.00", partialState.events.single().amount)
        assertTrue(partialState.events.single().detail.contains("Synthetic reference"))
        assertFalse(partial.payload.contains("PRIVATE PAYMENT NOTE"))
        assertNull(pay("final", "7750").conflict)
        assertEquals("PAID", statement("receipt-final").status); assertEquals("0.00", statement("receipt-final").due)
        assertEquals(partial.payload, repository.invoiceCopy("receipt-first").payload)
        assertEquals(original, repository.invoice("invoice").payload)
        assertEquals(listOf("RECEIPT", "RECEIPT"), repository.invoiceCopies("invoice").map { it.kind })
        inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1001L, it.getLong(0)) } }
    }
    @Test fun balanceCopyFreezesVersionHistoryAndRetryDoesNotConsumeInvoiceNumberOrMoney() {
        assertNull(pay("first", "3000").conflict)
        val initial = version
        val result = repository.prepareInvoiceBalanceCopy("copy-command", initial, "copy", "invoice")
        assertNull(result.conflict)
        val before = repository.invoiceCopy("balance-copy").payload
        assertEquals("30.00", statement("balance-copy").paid)
        assertNull(pay("second", "2000").conflict)
        assertEquals(result, repository.prepareInvoiceBalanceCopy("copy-command", initial, "copy", "invoice"))
        assertEquals(before, repository.invoiceCopy("balance-copy").payload)
        assertEquals(1, statement("balance-copy").events.size)
        assertNull(repository.prepareInvoiceBalanceCopy("new-copy", version, "new", "invoice").conflict)
        val current = statement("balance-new")
        assertEquals(2, current.events.size); assertEquals("50.00", current.paid); assertEquals("57.50", current.due)
        assertEquals(n(5000), repository.invoicePayments("invoice").second.balance.paid)
    }
    @Test fun correctionKeepsOldReceiptAndShowsReversalWithoutLeakingInternalReason() {
        assertNull(pay("first", "3000").conflict)
        val receipt = repository.invoiceCopy("receipt-first").payload
        assertNull(repository.reverseInvoicePayment("reverse", version, InvoicePaymentStore.Reversal("reversal", "invoice", "first", at, "UTC", "PRIVATE CORRECTION REASON")).conflict)
        val correction = statement("correction-reversal")
        assertEquals("UNPAID", correction.status); assertEquals("0.00", correction.paid); assertEquals("107.50", correction.due)
        assertEquals("Payment reversed", correction.events.single().title); assertEquals("-30.00", correction.events.single().amount)
        assertFalse(repository.invoiceCopy("correction-reversal").payload.contains("PRIVATE CORRECTION REASON"))
        assertEquals(receipt, repository.invoiceCopy("receipt-first").payload)
        assertNull(repository.prepareInvoiceBalanceCopy("balance", version, "after", "invoice").conflict)
        assertEquals(listOf("30.00", "-30.00"), statement("balance-after").events.map { it.amount })
    }
    @Test fun receiptFailureRollsBackPaymentEventsAndCommandThenSameIntentCanRetry() {
        val initial = version
        inspect { it.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON invoice_copies BEGIN SELECT RAISE(ABORT,'Synthetic copy failure'); END") }
        try { pay("first", "3000"); fail("Receipt intent must be atomic with its payment") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(initial, version); assertTrue(repository.invoiceCopies("invoice").isEmpty())
        assertEquals(n(0), repository.invoicePayments("invoice").second.balance.paid)
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM invoice_payment_events", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM commands WHERE id='record-first'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.execSQL("DROP TRIGGER fail_receipt")
        }
        assertNull(pay("first", "3000").conflict)
        assertEquals(1, repository.invoiceCopies("invoice").size)
    }
    @Test fun metadataIsImmutableButFirstDerivedPdfCanBeCachedWithoutLedgerMutation() {
        assertNull(pay("first", "3000").conflict)
        val original = repository.invoiceCopy("receipt-first")
        val initial = version
        val bytes = "%PDF-synthetic-copy-provider-fixture".toByteArray()
        assertFalse(repository.cacheInvoiceCopyPdf("receipt-first", "wrong-payload", bytes))
        assertTrue(repository.cacheInvoiceCopyPdf("receipt-first", original.payload, bytes))
        assertFalse(repository.cacheInvoiceCopyPdf("receipt-first", original.payload, bytes))
        assertEquals(initial, version); assertEquals(n(3000), repository.invoicePayments("invoice").second.balance.paid)
        assertArrayEquals(bytes, repository.invoiceCopy("receipt-first").pdf)
        inspect { db ->
            for (sql in listOf("UPDATE invoice_copies SET metadata='{}' WHERE id='receipt-first'", "DELETE FROM invoice_copies WHERE id='receipt-first'")) {
                try { db.execSQL(sql); fail("Immutable financial copy changed") } catch (_: android.database.sqlite.SQLiteException) { }
            }
        }
    }
    @Test fun receivedTimezoneOffsetAndPrintedLabelAreRetainedInPaymentAndEvent() {
        assertNull(repository.recordInvoicePayment("dated", version, InvoicePaymentStore.Draft("dated", "invoice", "3000", "USD", at - 1000, "Asia/Kathmandu")).conflict)
        val payment = repository.invoicePayments("invoice").second.payments.single()
        assertEquals(20700, payment.offsetSeconds)
        assertTrue(payment.receivedLabel.endsWith("+05:45 · Asia/Kathmandu"))
        assertTrue(statement("receipt-dated").events.single().detail.contains(payment.receivedLabel))
        inspect { db ->
            db.rawQuery("SELECT payload FROM invoice_payment_events WHERE id='PAYMENT-dated'", null).use {
                assertTrue(it.moveToFirst()); val event = org.json.JSONObject(it.getString(0))
                assertEquals(20700, event.getInt("offset_seconds")); assertEquals(payment.receivedLabel, event.getString("time_label"))
            }
        }
    }
    @Test fun receiptsAndTheirFinancialRevisionsRemainAfterJobPurge() {
        assertNull(pay("first", "3000").conflict)
        val original = repository.invoiceCopy("receipt-first").payload
        assertNull(repository.archiveJob("archive", version, job).conflict)
        inspect { it.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf(job)) }
        assertNull(repository.deleteJob("purge", version, job).conflict)
        assertEquals(original, repository.invoiceCopy("receipt-first").payload)
        assertNull(repository.prepareInvoiceBalanceCopy("balance", version, "after-purge", "invoice").conflict)
        assertEquals("77.50", statement("balance-after-purge").due)
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM entity_revisions WHERE kind='invoice_copy' AND payload IS NOT NULL", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
}
