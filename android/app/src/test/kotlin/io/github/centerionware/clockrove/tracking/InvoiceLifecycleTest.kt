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
class InvoiceLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private val at = 1793494800000L
    private val version get() = repository.snapshot().version
    private val selection get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    private fun n(value: Long) = BigInteger.valueOf(value)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun ledger(id: String = "invoice") = repository.invoicePayments(id).second
    private fun count(table: String) = inspect { db -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun nextNumber() = inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) } }
    private fun payment(id: String = "payment", amount: String = "3000", invoice: String = "invoice") {
        assertNull(repository.recordInvoicePayment("record-$id", version, InvoicePaymentStore.Draft(id, invoice, amount, "USD", at - 1000, "UTC", "Transfer", "Synthetic reference")).conflict)
    }
    private fun voidDraft(invoice: String = "invoice") = InvoiceLifecycleStore.VoidDraft(invoice, "Synthetic invoice correction", "UTC", true)
    private fun replacement(id: String = "replacement", original: String = "invoice", price: String = "12000", tax: Int = 100000): InvoiceLifecycleStore.ReplacementDraft {
        val source = ledger(original).document
        val lines = source.lines.mapIndexed { index, line ->
            InvoiceLifecycleStore.LineEdit(index, line.title, line.detail, if (line.amount.isBlank()) null else price, line.quantityUnits, if (line.quantityUnits == null) null else price)
        }
        return InvoiceLifecycleStore.ReplacementDraft(original, id, lines, tax, "Synthetic price correction", "UTC", true)
    }
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); repository = TimerRepository(app, wallClock = { at })
        assertNull(repository.saveBusinessSettings("currency", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultTaxUnits = 75000)).conflict)
        job = repository.createJob("Synthetic replacement job")
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        assertNull(repository.saveCard("task", version, BoardStore.Draft("tile", job, repository.boardSnapshot(job).board.columns.last().id, "Synthetic work", priceMinor = n(10000), currency = "USD")).conflict)
        assertNull(repository.prepareInvoice("prepare", version, "invoice", selection).conflict)
    }
    @After fun close() { repository.close() }
    @Test fun unpaidVoidReleasesWorkButRetainsOriginalAndFrozenCancellationCopy() {
        val original = repository.invoice("invoice").payload
        val pdf = "%PDF-original".toByteArray()
        assertTrue(repository.cacheInvoicePdf("invoice", original, pdf))
        val initial = version; val draft = voidDraft()
        val result = repository.voidInvoice("void", initial, draft)
        assertNull(result.conflict)
        assertEquals(result, repository.voidInvoice("void", initial, draft))
        assertEquals("COMMAND_ID_REUSED", repository.voidInvoice("void", initial, draft.copy(reason = "Changed reason for cancellation")).conflict)
        assertEquals(original, repository.invoice("invoice").payload)
        assertArrayEquals(pdf, repository.invoice("invoice").pdf)
        assertEquals("VOID", ledger().balance.status)
        assertEquals(n(0), ledger().balance.due)
        assertEquals("0", repository.invoices().invoices.single().dueMinor)
        assertEquals("VOID", repository.invoices().invoices.single().status)
        assertEquals("INVOICE_VOID", repository.recordInvoicePayment("late", version, InvoicePaymentStore.Draft("late", "invoice", "1", "USD", at, "UTC")).conflict)
        assertEquals("INVOICE_VOID", repository.prepareInvoiceBalanceCopy("balance", version, "late", "invoice").conflict)
        val copy = InvoiceStore.Snapshot.decode(repository.invoiceCopy("void-invoice").payload)
        assertEquals("VOID", copy.statement!!.status); assertEquals("0.00", copy.statement.due)
        assertTrue(copy.body.contains("No payment is due")); assertFalse(copy.body.contains(draft.reason))
        assertNull(repository.prepareInvoice("rebill", version, "rebilled", selection).conflict)
        assertEquals("INV-1001", repository.invoice("rebilled").number)
        inspect { db -> db.rawQuery("SELECT released_ms FROM invoice_task_claims WHERE invoice_id='invoice'", null).use { assertTrue(it.moveToFirst()); assertEquals(at, it.getLong(0)) } }
    }
    @Test fun eligibilityAndConfirmationRejectBeforeAnyFinancialMutation() {
        assertEquals("INVOICE_VOID_CONFIRMATION_REQUIRED", repository.voidInvoice("unconfirmed", version, voidDraft().copy(confirmed = false)).conflict)
        assertEquals("INVOICE_VOID_REASON_REQUIRED", repository.voidInvoice("reason", version, voidDraft().copy(reason = "short")).conflict)
        payment()
        assertEquals("PARTIAL_INVOICE_REQUIRES_REPLACEMENT", repository.voidInvoice("partial", version, voidDraft()).conflict)
        assertEquals("REPLACEMENT_BELOW_CREDIT", repository.replaceInvoice("below", version, replacement(price = "2999", tax = 0)).conflict)
        assertEquals(0, count("invoice_voids")); assertEquals(1, count("invoices")); assertEquals(1001L, nextNumber())
        payment("rest", "7750")
        assertEquals("PAID_INVOICE_CANNOT_VOID", repository.voidInvoice("paid-void", version, voidDraft()).conflict)
        assertEquals("PAID_INVOICE_CANNOT_VOID", repository.replaceInvoice("paid-replace", version, replacement()).conflict)
        assertEquals(0, count("invoice_voids")); assertEquals(n(10750), ledger().balance.paid)
    }
    @Test fun replacementTransfersIdentityAndTaxWithoutCreatingCashOrChangingOldReceipt() {
        payment()
        val original = repository.invoice("invoice").payload
        val receipt = repository.invoiceCopy("receipt-payment").payload
        val initial = version; val draft = replacement()
        val result = repository.replaceInvoice("replace", initial, draft)
        assertNull(result.conflict)
        assertEquals(result, repository.replaceInvoice("replace", initial, draft))
        assertEquals(2, count("invoices")); assertEquals(1, count("invoice_payments")); assertEquals(1002L, nextNumber())
        assertEquals("VOID", ledger().balance.status); assertEquals(n(0), ledger().balance.paid)
        val current = ledger("replacement")
        assertEquals("INV-1001", current.invoice.number); assertEquals(n(13200), current.balance.total)
        assertEquals(n(3000), current.balance.paid); assertEquals(n(10200), current.balance.due); assertEquals(n(273), current.collectedTax)
        assertEquals("PARTIALLY_PAID", current.balance.status)
        assertEquals("invoice", current.payments.single().originalInvoiceId)
        assertEquals("replacement", ledger().payments.single().currentInvoiceId)
        assertEquals(at - 1000, current.payments.single().received)
        assertEquals(original, repository.invoice("invoice").payload)
        assertEquals(receipt, repository.invoiceCopy("receipt-payment").payload)
        assertEquals("30.00", current.document.statement!!.paid)
        assertEquals("102.00", current.document.statement.due)
        val cancellation = InvoiceStore.Snapshot.decode(repository.invoiceCopy("void-invoice").payload)
        assertTrue(cancellation.body.contains("INV-1001")); assertEquals("30.00", cancellation.statement!!.paid)
        assertEquals("NO_BILLABLE_ITEMS", repository.prepareInvoice("duplicate", version, "duplicate", selection).conflict)
        inspect { db ->
            val events = db.rawQuery("SELECT payload FROM invoice_payment_events ORDER BY command_version,ordinal", null).use { rows -> buildList { while (rows.moveToNext()) add(JSONObject(rows.getString(0))) } }
            assertEquals(3, events.size)
            fun sum(key: String) = events.fold(n(0)) { value, event -> value.add(BigInteger(event.getString(key))) }
            assertEquals(n(3000), sum("cash_change_minor")); assertEquals(n(0), sum("credit_change_minor"))
            assertEquals(n(273), sum("tax_change_minor")); assertEquals(n(3000), sum("tax_change_minor").add(sum("sales_change_minor")))
            assertEquals(listOf(0, 1), events.drop(1).map { it.getInt("ordinal") })
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun replacementChainsAndCorrectionsKeepOriginalReceiptAndCurrentCredit() {
        payment()
        val receipt = repository.invoiceCopy("receipt-payment").payload
        assertNull(repository.replaceInvoice("replace", version, replacement()).conflict)
        assertNull(repository.replaceInvoice("replace-again", version, replacement("third", "replacement", "15000", 0)).conflict)
        assertEquals(n(3000), ledger("third").balance.paid); assertEquals(n(12000), ledger("third").balance.due)
        assertEquals("third", ledger("replacement").payments.single().currentInvoiceId)
        val correction = InvoicePaymentStore.Reversal("reverse", "third", "payment", at, "UTC", "Correct synthetic allocation")
        assertEquals("INVOICE_VOID", repository.reverseInvoicePayment("old-correction", version, correction.copy(invoiceId = "invoice")).conflict)
        assertNull(repository.reverseInvoicePayment("current-correction", version, correction).conflict)
        assertEquals(n(0), ledger("third").balance.paid); assertEquals(n(15000), ledger("third").balance.due)
        assertEquals("reverse", ledger().payments.single().reversalId)
        assertEquals(receipt, repository.invoiceCopy("receipt-payment").payload)
        assertEquals("third", repository.invoiceCopy("correction-reverse").id)
        inspect { db ->
            var cash = n(0); var credit = n(0); var tax = n(0); var sales = n(0)
            db.rawQuery("SELECT payload FROM invoice_payment_events ORDER BY command_version,ordinal", null).use { rows -> while (rows.moveToNext()) {
                val event = JSONObject(rows.getString(0)); cash = cash.add(BigInteger(event.getString("cash_change_minor")))
                credit = credit.add(BigInteger(event.getString("credit_change_minor"))); tax = tax.add(BigInteger(event.getString("tax_change_minor"))); sales = sales.add(BigInteger(event.getString("sales_change_minor")))
            } }
            assertEquals(n(0), cash); assertEquals(n(0), credit); assertEquals(n(0), tax); assertEquals(n(0), sales)
        }
    }
    @Test fun replacementEqualToCreditIsPaidWithoutSyntheticPayment() {
        payment()
        assertNull(repository.replaceInvoice("replace", version, replacement(price = "3000", tax = 0)).conflict)
        assertEquals("PAID", ledger("replacement").balance.status); assertEquals(n(0), ledger("replacement").balance.due)
        assertEquals(1, count("invoice_payments")); assertEquals(1, ledger("replacement").payments.size)
        assertEquals("PAID_INVOICE_CANNOT_VOID", repository.voidInvoice("paid", version, voidDraft("replacement")).conflict)
    }
    @Test fun sourceReleaseMustUseExplicitOwnedClaimIdsAndAdditionUsesActualUnbilledWork() {
        assertNull(repository.saveCard("second-task", version, BoardStore.Draft("paint", job, repository.boardSnapshot(job).board.columns.last().id, "Synthetic added work", priceMinor = n(5000), currency = "USD")).conflict)
        val claim = inspect { InvoiceLifecycleStore.claims(it, "invoice").single() }
        assertEquals("INVALID_RELEASED_CLAIMS", repository.replaceInvoice("foreign", version, replacement().copy(releaseClaimIds = listOf("not-owned"))).conflict)
        assertNull(repository.replaceInvoice("replace", version, replacement(tax = 0).copy(addedWork = selection)).conflict)
        assertEquals(n(17000), ledger("replacement").balance.total)
        assertEquals(setOf("tile", "paint"), inspect { InvoiceLifecycleStore.claims(it, "replacement").map { c -> c.taskId }.toSet() })
        assertEquals(0, repository.invoiceBillingPreview(selection).preview.report.tasks.size)
        val nextClaim = inspect { InvoiceLifecycleStore.claims(it, "replacement").first { c -> c.taskId == claim.taskId } }
        assertNull(repository.replaceInvoice("release", version, replacement("third", "replacement", "12000", 0).copy(releaseClaimIds = listOf(nextClaim.id))).conflict)
        assertEquals(listOf("tile"), repository.invoiceBillingPreview(selection).preview.report.tasks.map { it.id })
    }
    @Test fun duplicateOrInventedRowsAndMismatchedQuantityProductsAreRejected() {
        val base = replacement()
        val line = base.lines.single()
        assertEquals("INVALID_REPLACEMENT_LINES", repository.replaceInvoice("duplicate", version, base.copy(lines = listOf(line, line))).conflict)
        assertEquals("INVALID_REPLACEMENT_LINES", repository.replaceInvoice("invented", version, base.copy(lines = listOf(line.copy(sourceIndex = 99)))).conflict)
        assertEquals("INVALID_REPLACEMENT_AMOUNT", repository.replaceInvoice("float", version, base.copy(lines = listOf(line.copy(amountMinor = "1.5")))).conflict)
        assertEquals("INVALID_REPLACEMENT_AMOUNT", repository.replaceInvoice("product", version, base.copy(lines = listOf(line.copy(quantityUnits = "260000", unitPriceMinor = "500")))).conflict)
        assertEquals(1, count("invoices")); assertEquals(0, count("invoice_voids"))
        assertNull(repository.replaceInvoice("exact-product", version, base.copy(lines = listOf(line.copy(quantityUnits = "240000", unitPriceMinor = "500")))).conflict)
        assertEquals("240000", ledger("replacement").document.lines.single().quantityUnits)
        assertEquals("500", ledger("replacement").document.lines.single().unitPriceMinor)
    }
    @Test fun eventFailureRollsBackNumberCoverageCreditCopyRevisionAndCommand() {
        payment(); val initial = version
        val original = repository.invoice("invoice").payload
        inspect { it.execSQL("CREATE TRIGGER fail_transfer BEFORE INSERT ON invoice_payment_events WHEN NEW.kind='TRANSFER' BEGIN SELECT RAISE(ABORT,'Synthetic transfer failure'); END") }
        try { repository.replaceInvoice("replace", initial, replacement()); fail("Expected injected rollback") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(initial, version); assertEquals(1001L, nextNumber()); assertEquals(1, count("invoices")); assertEquals(0, count("invoice_voids"))
        assertEquals(n(3000), ledger().balance.paid); assertEquals("invoice", ledger().payments.single().currentInvoiceId)
        assertEquals(original, repository.invoice("invoice").payload); assertEquals(1, count("invoice_copies"))
        assertEquals(1, inspect { InvoiceLifecycleStore.claims(it, "invoice").size })
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM commands WHERE id='replace'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM entity_revisions WHERE kind='invoice' AND entity_id='replacement'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.execSQL("DROP TRIGGER fail_transfer")
        }
        assertNull(repository.replaceInvoice("replace", initial, replacement()).conflict)
    }
    @Test fun lateCancellationCopyFailureRollsBackAlreadyTransferredCreditAndAllHistory() {
        payment(); val initial = version
        val oldReceipt = repository.invoiceCopy("receipt-payment").payload
        val revisions = count("entity_revisions")
        inspect { it.execSQL("CREATE TRIGGER fail_void_copy BEFORE INSERT ON invoice_copies WHEN NEW.kind='VOID' BEGIN SELECT RAISE(ABORT,'Synthetic late copy failure'); END") }
        try { repository.replaceInvoice("replace", initial, replacement()); fail("Expected late rollback") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(initial, version); assertEquals(1001L, nextNumber()); assertEquals(revisions, count("entity_revisions"))
        assertEquals(1, count("invoices")); assertEquals(1, count("invoice_payment_events")); assertEquals(0, count("invoice_voids"))
        assertEquals("PARTIALLY_PAID", ledger().balance.status); assertEquals(n(3000), ledger().balance.paid)
        assertEquals("invoice", ledger().payments.single().currentInvoiceId)
        assertEquals(oldReceipt, repository.invoiceCopy("receipt-payment").payload)
        inspect { db ->
            assertEquals(1, InvoiceLifecycleStore.claims(db, "invoice").size)
            db.rawQuery("SELECT COUNT(*) FROM commands WHERE id='replace'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.execSQL("DROP TRIGGER fail_void_copy")
        }
        assertNull(repository.replaceInvoice("replace", initial, replacement()).conflict)
    }
    @Test fun cancellationRecordAndFinancialRevisionRemainImmutableThroughJobPurge() {
        payment()
        assertNull(repository.replaceInvoice("replace", version, replacement()).conflict)
        val cancellation = repository.invoiceCopy("void-invoice").payload
        inspect { db ->
            for (sql in listOf("UPDATE invoice_voids SET reason='rewritten'", "DELETE FROM invoice_voids")) {
                try { db.execSQL(sql); fail("Cancellation history must be immutable") } catch (_: android.database.sqlite.SQLiteException) { }
            }
        }
        assertNull(repository.archiveJob("archive", version, job).conflict)
        inspect { it.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf(job)) }
        assertNull(repository.deleteJob("delete", version, job).conflict)
        assertEquals(cancellation, repository.invoiceCopy("void-invoice").payload)
        assertEquals("VOID", ledger().balance.status); assertEquals(n(3000), ledger("replacement").balance.paid)
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM entity_revisions WHERE kind='invoice_void' AND payload IS NOT NULL", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun concurrentReplacementCommandsProduceOneSuccessorAndOneCreditAllocation() {
        payment(); val initial = version
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val futures = (1..2).map { i -> pool.submit<TimerRepository.Outcome> { start.await(); repository.replaceInvoice("replace-$i", initial, replacement("replacement-$i")) } }
            start.countDown(); val outcomes = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.conflict == null }); assertEquals(1, outcomes.count { it.conflict == "STALE_STATE" })
            assertEquals(2, count("invoices")); assertEquals(1, count("invoice_voids")); assertEquals(1, count("payment_destinations"))
            assertEquals(n(3000), repository.invoices().invoices.filter { it.status != "VOID" }.fold(n(0)) { sum, invoice -> sum.add(BigInteger(invoice.paidMinor)) })
        } finally { pool.shutdownNow() }
    }
    @Test fun deletedJobReplacementRetainsFinancialHistoryAndOpaqueSourceReservations() {
        payment()
        assertNull(repository.archiveJob("archive", version, job).conflict)
        inspect { it.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf(job)) }
        assertNull(repository.deleteJob("delete", version, job).conflict)
        assertNull(repository.replaceInvoice("replace", version, replacement()).conflict)
        assertEquals(n(3000), ledger("replacement").balance.paid)
        assertEquals(1, inspect { InvoiceLifecycleStore.claims(it, "replacement").size })
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM entity_revisions WHERE kind='invoice_void' AND payload IS NOT NULL", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun splitPaymentsAndTaxReclassificationConserveEveryMinorUnit() {
        payment("first", "11"); payment("second", "17")
        assertNull(repository.replaceInvoice("replace", version, replacement()).conflict)
        val current = ledger("replacement")
        assertEquals(n(28), current.balance.paid); assertEquals(n(3), current.collectedTax)
        inspect { db ->
            val events = db.rawQuery("SELECT payload FROM invoice_payment_events ORDER BY command_version,ordinal", null).use { rows -> buildList { while (rows.moveToNext()) add(JSONObject(rows.getString(0))) } }
            assertEquals(6, events.size)
            fun sum(key: String) = events.fold(n(0)) { total, row -> total.add(BigInteger(row.getString(key))) }
            assertEquals(n(28), sum("cash_change_minor")); assertEquals(n(0), sum("credit_change_minor"))
            assertEquals(n(3), sum("tax_change_minor")); assertEquals(n(25), sum("sales_change_minor"))
            assertEquals(listOf(0, 1, 2, 3), events.takeLast(4).map { it.getInt("ordinal") })
        }
    }
    @Test fun wideReplacementAndCreditStayTextWithoutNarrowingToMachineIntegers() {
        val price = "9".repeat(1024)
        val paid = BigInteger(price).divide(n(3))
        inspect { db ->
            val source = ledger().document
            val document = source.copy(total = InvoiceStore.amount(BigInteger(price), 2), subtotal = InvoiceStore.amount(BigInteger(price), 2), taxRateUnits = 0, taxAmount = "0.00")
            db.execSQL("UPDATE invoices SET payload=? WHERE id='invoice'", arrayOf(document.encode()))
        }
        payment(amount = paid.toString())
        assertNull(repository.replaceInvoice("replace", version, replacement(price = price, tax = 100000)).conflict)
        val current = ledger("replacement")
        assertEquals(paid, current.balance.paid)
        val total = BigInteger(price).add(TaxRate.amount(BigInteger(price), 100000))
        assertEquals(total, current.balance.total); assertEquals(total.subtract(paid), current.balance.due)
        assertEquals(paid.toString(), inspect { InvoiceLifecycleStore.voidInfo(it, "invoice")!!.credited })
        assertEquals(paid, PaymentMath.parseMajor(current.document.statement!!.paid, 2))
    }
    @Test fun hourlyClaimsTransferExactIntervalsAndInformationalRowsCannotBecomeCharges() {
        val hourly = repository.createJob("Synthetic hourly work")
        assertNull(repository.saveJobSettings("hourly-rate", version, repository.jobDetail(hourly).job.settings.copy(hourlyRateMinor = n(3600))).conflict)
        assertNull(repository.createManualSession("time", version, SessionHistoryStore.Draft("session", hourly, at - 3600000, at, "UTC", 0)).conflict)
        val report = selection.copy(jobIds = listOf(hourly))
        assertNull(repository.prepareInvoice("prepare-hourly", version, "hourly", report).conflict)
        val draft = replacement("new-hourly", "hourly", "7200", 0)
        assertEquals(2, draft.lines.size)
        assertEquals("INVALID_REPLACEMENT_LINES", repository.replaceInvoice("fabricated-time", version, draft.copy(lines = draft.lines.map { if (it.sourceIndex == 1) it.copy(amountMinor = "100") else it })).conflict)
        assertNull(repository.replaceInvoice("replace-hourly", version, draft).conflict)
        val claims = inspect { InvoiceLifecycleStore.claims(it, "new-hourly") }
        assertEquals(1, claims.size); assertEquals(at - 3600000, claims.single().start); assertEquals(at, claims.single().end)
        assertTrue(repository.invoiceBillingPreview(report).preview.report.intervals.isEmpty())
    }
    @Test fun legacyBarrierMovesToReplacementAndExplicitUnpaidCancellationRemovesIt() {
        inspect { it.execSQL("UPDATE invoice_coverage SET state='LEGACY' WHERE invoice_id='invoice'") }
        assertNull(repository.replaceInvoice("replace", version, replacement()).conflict)
        assertEquals(listOf("replacement"), repository.invoiceBillingPreview(selection).preview.legacy.map { it.first })
        assertEquals("INVOICE_VOID", repository.reconcileInvoiceCoverage("old-review", version, LegacyInvoiceStore.Draft("invoice", emptyList(), emptyList(), "Synthetic old source review", true, true)).conflict)
        assertNull(repository.voidInvoice("void", version, voidDraft("replacement")).conflict)
        assertTrue(repository.invoiceBillingPreview(selection).preview.legacy.isEmpty())
        assertNull(repository.prepareInvoice("new", version, "new", selection).conflict)
    }
}
