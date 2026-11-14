package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
class BusinessReportStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var clock = at("2026-06-15T12:00:00.000Z")
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private lateinit var customer: BusinessStore.Customer
    private val version get() = repository.snapshot().version

    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { clock })
        job = repository.createJob("Synthetic financial job")
        customer = BusinessStore.Customer("financial-customer", "Synthetic customer")
        assertNull(repository.saveCustomer("financial-customer-save", version, customer).conflict)
        val current = repository.jobDetail(job).job.settings
        assertNull(repository.saveJobSettings("financial-job-settings", version,
            current.copy(billingMode = "TASK", customerId = customer.id, taxUnits = 75000)).conflict)
        val board = repository.boardSnapshot(job).board
        assertNull(repository.saveCard("financial-supply-task", version, BoardStore.Draft(
            "financial-supply", job, board.columns.last().id, "Install supplied tile",
            priceMinor = BigInteger("10000"), currency = board.currency,
            chargeType = TaskClassification.SUPPLIES,
        )).conflict)
        assertNull(repository.saveCard("financial-labor-task", version, BoardStore.Draft(
            "financial-labor", job, board.columns.last().id, "Install tile labor",
            priceMinor = BigInteger("5000"), currency = board.currency,
            chargeType = TaskClassification.LABOR,
        )).conflict)
    }

    @After fun close() { repository.close() }

    private fun at(value: String) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)!!.time

    private fun selection(
        date: String = "2026-06-15",
        jobs: List<String> = emptyList(),
        customers: List<String> = emptyList(),
    ) = BusinessReportStore.Selection(date, date, "UTC", jobs, customers)

    private fun readReport(value: BusinessReportStore.Selection) =
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            BusinessReportStore.read(db, value)
        }

    private fun issueInvoice(id: String = "financial-invoice") {
        val work = ReportStore.Selection("2026-06-15", "2026-06-15", "UTC", listOf(job))
        val result = repository.prepareInvoice("issue-$id", version, id, work)
        assertNull(result.conflict)
    }

    @Test fun exactTaxSalesClassificationCashCostsAndProfitReconcileWithoutMixing() {
        issueInvoice()
        assertNull(repository.recordInvoicePayment("partial-payment", version,
            InvoicePaymentStore.Draft("financial-payment", "financial-invoice", "5000", "USD", clock, "UTC", reference = "Synthetic deposit")).conflict)
        assertNull(repository.saveActualCost("direct-cost", version, ActualCostStore.Draft(
            "materials-cost", job, "financial-supply", "2000", "USD", ActualCostStore.MATERIALS,
            clock, "UTC", "Synthetic supplier", "M-1", "=1+2",
        )).conflict)
        assertNull(repository.saveActualCost("overhead-cost", version, ActualCostStore.Draft(
            "overhead-cost-row", null, null, "1000", "USD", ActualCostStore.OVERHEAD,
            clock, "UTC", "Synthetic utility", "O-1", "Shop overhead",
        )).conflict)

        val report = readReport(selection())
        val total = report.summaries.single()
        assertEquals(BigInteger("15000"), total.invoicedSales)
        assertEquals(BigInteger("5000"), total.cashReceived)
        assertEquals(BigInteger("1125"), total.taxBilled)
        assertEquals(BigInteger("349"), total.taxCollected)
        assertEquals(BigInteger("11125"), total.outstanding)
        assertEquals(BigInteger("5000"), total.labor)
        assertEquals(BigInteger("10000"), total.supplies)
        assertEquals(BigInteger.ZERO, total.unclassified)
        assertEquals(BigInteger("2000"), total.directCosts)
        assertEquals(BigInteger("1000"), total.overhead)
        assertEquals(BigInteger("13000"), total.grossProfit)
        assertEquals(BigInteger("12000"), total.netProfit)
        assertEquals("86.6667", total.grossMarginPercent)
        assertEquals("80", total.netMarginPercent)
        assertEquals(BigInteger("2000"), total.cashSurplus)
        assertEquals(2, total.costCount)
        assertEquals(1, total.unattributedCostCount)
        assertEquals("ISSUED", report.invoices.single().kind)
        assertEquals("PAYMENT", report.cash.single().kind)
        assertEquals(2, report.costs.size)
        val customerReport = readReport(selection(customers = listOf(customer.id)))
        assertEquals(1, customerReport.costs.size)
        assertEquals(1, customerReport.excludedUnknownCustomerCostCount)
        assertEquals(BigInteger.ZERO, customerReport.summaries.single().overhead)
        val csv = ReportCsv.business(report)
        assertTrue(csv.contains("sales_minor_text"))
        assertTrue(csv.contains("'15000"))
        assertTrue(csv.contains("'=1+2"))
    }

    @Test fun voidEventReversesPriorIssueAndBalanceWithoutCountingAQuoteAsRevenue() {
        issueInvoice()
        assertNull(repository.voidInvoice("void-original", version,
            InvoiceLifecycleStore.VoidDraft("financial-invoice", "Customer cancelled this project", "UTC", true)).conflict)
        val report = readReport(selection())
        val total = report.summaries.single()
        assertEquals(BigInteger.ZERO, total.invoicedSales)
        assertEquals(BigInteger.ZERO, total.taxBilled)
        assertEquals(BigInteger.ZERO, total.outstanding)
        assertEquals(listOf("ISSUED", "VOID_ADJUSTMENT"), report.invoices.map { it.kind })
        assertTrue(report.cash.isEmpty())
    }

    @Test fun asOfOutstandingUsesEventDatesAndKeepsCurrenciesInSeparateGroups() {
        issueInvoice()
        clock = at("2026-06-16T12:00:00.000Z")
        assertNull(repository.recordInvoicePayment("late-payment", version,
            InvoicePaymentStore.Draft("late-financial-payment", "financial-invoice", "5000", "USD", clock, "UTC")).conflict)
        val june15 = readReport(selection("2026-06-15"))
        assertEquals(BigInteger("16125"), june15.summaries.single().outstanding)
        assertEquals(BigInteger.ZERO, june15.summaries.single().cashReceived)
        val june16 = readReport(BusinessReportStore.Selection(
            "2026-06-15", "2026-06-16", "UTC", listOf(job), listOf(customer.id),
        ))
        assertEquals(BigInteger("5000"), june16.summaries.single().cashReceived)
        assertEquals(BigInteger("11125"), june16.summaries.single().outstanding)

        val jpy = repository.createJob("Synthetic JPY job")
        val jpySettings = repository.jobDetail(jpy).job.settings
        assertNull(repository.saveJobSettings("jpy-job-settings", version, jpySettings.copy(currency = "JPY")).conflict)
        assertNull(repository.saveActualCost("jpy-expense", version, ActualCostStore.Draft(
            "jpy-cost", jpy, null, "7", "JPY", ActualCostStore.OTHER_DIRECT, clock, "UTC", "", "", "",
        )).conflict)
        val currencies = readReport(BusinessReportStore.Selection(
            "2026-06-15", "2026-06-16", "UTC",
        )).summaries.associateBy { it.currency }
        assertEquals(setOf("USD", "JPY"), currencies.keys)
        assertEquals(BigInteger("7"), currencies.getValue("JPY").directCosts)
        assertEquals(BigInteger("15000"), currencies.getValue("USD").invoicedSales)
    }

    @Test fun dateAndFilterValidationRejectMalformedOrOversizedSelections() {
        listOf(
            selection("2026-02-30"),
            BusinessReportStore.Selection("2026-06-16", "2026-06-15", "UTC"),
            BusinessReportStore.Selection("2026-06-15", "2026-06-15", "not/a-zone"),
            BusinessReportStore.Selection("2026-06-15", "2026-06-15", "UTC", listOf("bad/id")),
        ).forEach { value ->
            try {
                readReport(value)
                fail("Invalid business report selection accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
