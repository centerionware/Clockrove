package io.github.centerionware.clockrove.tracking

import io.flutter.plugin.common.MethodCall
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
class InvoicePaymentBridgeTest {
    private lateinit var repository: TimerRepository
    private lateinit var dispatch: BusinessDispatch
    private val at = 1793494800000L
    private val version get() = repository.snapshot().version
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { at }); dispatch = BusinessDispatch(repository)
        assertNull(repository.saveBusinessSettings("settings", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultTaxUnits = 75000,
            companyEmail = "company@example.test", receivablesEmail = "accounts@example.test")).conflict)
        val job = repository.createJob("Synthetic bridge invoice")
        assertNull(repository.saveJobSettings("job", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK", customerEmailOverride = "customer@example.test")).conflict)
        assertNull(repository.saveCard("card", version, BoardStore.Draft("card", job, repository.boardSnapshot(job).board.columns.first { it.completed }.id,
            "Synthetic tile", priceMinor = BigInteger.valueOf(10000), currency = "USD")).conflict)
        assertNull(repository.prepareInvoice("invoice", version, "invoice", ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))).conflict)
    }
    @After fun close() { repository.close() }
    private fun payment(): Map<String, Any?> = mapOf("paymentId" to "payment", "invoiceId" to "invoice", "amountMinor" to "3000", "currency" to "USD",
        "received" to at - 1000, "timezone" to "UTC", "method" to "Transfer", "reference" to "Synthetic reference", "note" to "Private note")
    private fun change(method: String, id: String, values: Map<String, Any?>, expected: Any = version) =
        dispatch.call(MethodCall(method, mapOf("id" to id, "version" to expected, "value" to values))) as TimerRepository.Outcome
    private fun read(invoice: String = "invoice") = dispatch.call(MethodCall("invoicePayments", mapOf("invoiceId" to invoice))) as Map<*, *>
    @Test fun contextAndCommandRoundTripExactBalanceTimeAccountingAndReceiptIdentity() {
        assertEquals("10750", read()["dueMinor"])
        val initial = version; val result = change("recordInvoicePayment", "record", payment(), initial)
        assertNull(result.conflict); assertEquals(result, change("recordInvoicePayment", "record", payment(), initial))
        val value = read()
        assertEquals(result.version, value["version"]); assertEquals("3000", value["paidMinor"]); assertEquals("7750", value["dueMinor"])
        assertEquals("PARTIALLY_PAID", value["status"]); assertEquals("accounts@example.test", value["accountingEmail"])
        assertEquals("customer@example.test", value["customerEmail"]); assertEquals(at, value["now"])
        val entry = (value["payments"] as List<*>).single() as Map<*, *>
        assertEquals("3000", entry["amountMinor"]); assertEquals(at - 1000, entry["received"]); assertEquals(0, entry["offsetSeconds"])
        assertTrue((entry["receivedLabel"] as String).endsWith("Z · UTC"))
        val copy = (value["copies"] as List<*>).single() as Map<*, *>
        assertEquals("receipt-payment", copy["id"]); assertEquals("RECEIPT", copy["kind"])
        assertEquals("3000", copy["paidMinor"]); assertEquals("7750", copy["dueMinor"])
        val summary = repository.invoices().invoices.single()
        assertEquals("3000", summary.paidMinor); assertEquals("7750", summary.dueMinor); assertEquals("PARTIALLY_PAID", summary.status)
    }
    @Test fun floatingAmountTimestampAndVersionAreRejectedBeforeNativeWriter() {
        val before = repository.invoicePayments("invoice")
        for (values in listOf(payment() + ("amountMinor" to 3000.0), payment() + ("amountMinor" to 3000L), payment() + ("received" to at.toDouble()))) {
            try { change("recordInvoicePayment", "bad", values); fail("Invalid transport reached writer") } catch (_: IllegalArgumentException) { }
        }
        try { change("recordInvoicePayment", "bad-version", payment(), version.toDouble()); fail("Floating version reached writer") } catch (_: IllegalArgumentException) { }
        assertEquals(before.first, repository.invoicePayments("invoice").first)
        assertTrue(repository.invoicePayments("invoice").second.payments.isEmpty())
    }
    @Test fun reversalAndBalanceCopyDoNotCreateAnotherChargeAndOldReceiptRemains() {
        assertNull(change("recordInvoicePayment", "record", payment()).conflict)
        val originalReceipt = repository.invoiceCopy("receipt-payment").payload
        val correction = mapOf<String, Any?>("reversalId" to "correction", "paymentId" to "payment", "invoiceId" to "invoice", "effective" to at,
            "timezone" to "UTC", "note" to "Corrected wrong reference")
        assertNull(change("reverseInvoicePayment", "reverse", correction).conflict)
        assertEquals("0", read()["paidMinor"]); assertEquals("10750", read()["dueMinor"])
        assertNull(change("prepareInvoiceBalanceCopy", "balance", mapOf("invoiceId" to "invoice", "copyToken" to "copy")).conflict)
        assertEquals("0.00", InvoiceStore.Snapshot.decode(repository.invoiceCopy("balance-copy").payload).statement!!.paid)
        assertEquals(originalReceipt, repository.invoiceCopy("receipt-payment").payload)
        val summary = repository.invoices().invoices.single()
        assertEquals("UNPAID", summary.status); assertEquals("10750", summary.dueMinor)
    }
    @Test fun replacementBridgeRetainsCreditRelationshipsAndStrictStringAmounts() {
        assertNull(change("recordInvoicePayment", "record", payment()).conflict)
        val context = dispatch.call(MethodCall("invoiceLifecycleContext", mapOf("invoiceId" to "invoice"))) as Map<*, *>
        assertEquals(75000, context["taxUnits"])
        val original = (context["lines"] as List<*>).single() as Map<*, *>
        assertEquals("10000", original["amountMinor"])
        val line = original.entries.associate { it.key as String to it.value } + mapOf("amountMinor" to "12000", "unitPriceMinor" to "12000")
        val values = mapOf<String, Any?>("invoiceId" to "invoice", "replacementId" to "replacement", "lines" to listOf(line), "taxUnits" to 100000,
            "reason" to "Synthetic amount correction", "timezone" to "UTC", "confirmed" to true, "releaseClaimIds" to emptyList<String>(), "addedWork" to null)
        try { change("replaceInvoice", "float-line", values + ("lines" to listOf(line + ("amountMinor" to 12000.0)))); fail("Floating line amount reached writer") } catch (_: IllegalArgumentException) { }
        try { change("replaceInvoice", "float-tax", values + ("taxUnits" to 100000.0)); fail("Floating tax reached writer") } catch (_: IllegalArgumentException) { }
        val before = version; val outcome = change("replaceInvoice", "replace", values, before)
        assertNull(outcome.conflict); assertEquals(outcome, change("replaceInvoice", "replace", values, before))
        assertEquals("VOID", read()["status"]); assertEquals("0", read()["dueMinor"])
        assertEquals("replacement", (read()["void"] as Map<*, *>)["replacementId"])
        val current = read("replacement")
        assertEquals("invoice", current["replacesInvoiceId"]); assertEquals("3000", current["paidMinor"]); assertEquals("10200", current["dueMinor"])
        val paid = (current["payments"] as List<*>).single() as Map<*, *>
        assertEquals("invoice", paid["originalInvoiceId"]); assertEquals("replacement", paid["currentInvoiceId"])
        assertEquals("replacement", ((read()["payments"] as List<*>).single() as Map<*, *>)["currentInvoiceId"])
    }
    @Test fun voidBridgeRequiresConfirmationAndRetainsSeparateCancellationCopy() {
        val values = mapOf<String, Any?>("invoiceId" to "invoice", "reason" to "Synthetic invoice cancellation", "timezone" to "UTC", "confirmed" to false)
        assertEquals("INVOICE_VOID_CONFIRMATION_REQUIRED", change("voidInvoice", "unconfirmed", values).conflict)
        assertNull(change("voidInvoice", "void", values + ("confirmed" to true)).conflict)
        assertEquals("VOID", read()["status"])
        val info = read()["void"] as Map<*, *>
        assertNull(info["replacementId"]); assertEquals("0", info["creditedMinor"]); assertEquals("10750", info["cancelledMinor"])
        assertEquals("VOID", ((read()["copies"] as List<*>).single() as Map<*, *>)["kind"])
        assertEquals("INVOICE_VOID", change("recordInvoicePayment", "late-payment", payment()).conflict)
    }

}
