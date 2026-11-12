package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
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
class InvoiceTaxTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var serial = 0
    private val midnight = 1793491200000L
    private val version get() = repository.snapshot().version
    private val selection get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { midnight + 3600000 })
        // Fix the currency before job creation rather than relying on CI locale.
        assertNull(repository.saveBusinessSettings("currency", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultHourlyMinor = 10000)).conflict)
        job = repository.createJob("Synthetic taxable job")
    }
    @After fun close() { repository.close() }
    private fun defaultTax(units: Int) = repository.saveBusinessSettings("tax-${serial++}", version, repository.businessSnapshot().settings.copy(defaultTaxUnits = units))
    private fun overrideTax(units: Int?) = repository.saveJobSettings("job-tax-${serial++}", version, repository.jobDetail(job).job.settings.copy(taxUnits = units))
    private fun invoice(id: String, startHour: Long = 0): InvoiceStore.Snapshot {
        assertNull(repository.createManualSession("work-$id", version, SessionHistoryStore.Draft("work-$id", job, midnight + startHour * 3600000, midnight + (startHour + 1) * 3600000, "UTC", 0)).conflict)
        assertNull(repository.prepareInvoice("prepare-$id", version, id, selection).conflict)
        return InvoiceStore.Snapshot.decode(repository.invoice(id).payload)
    }
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    @Test fun inheritedPercentAddsTaxToSubtotalAndEmailUsesGrandTotal() {
        assertNull(defaultTax(75000).conflict)
        val result = invoice("first")
        assertEquals("100.00", result.subtotal); assertEquals(75000, result.taxRateUnits)
        assertEquals("7.50", result.taxAmount); assertEquals("107.50", result.total)
        assertEquals("100.00", result.lines.first().amount); assertTrue(result.body.contains("107.50 USD"))
        assertEquals(result, InvoiceStore.Snapshot.decode(result.encode()))
    }
    @Test fun explicitZeroOverridesNonzeroDefaultAndNullRestoresInheritance() {
        assertNull(defaultTax(75000).conflict)
        assertNull(overrideTax(0).conflict)
        val free = invoice("zero")
        assertEquals("0.00", free.taxAmount); assertEquals("100.00", free.total)
        assertEquals(0, repository.jobDetail(job).job.effectiveTaxUnits)
        assertNull(overrideTax(null).conflict)
        assertEquals(75000, repository.jobDetail(job).job.effectiveTaxUnits)
        assertEquals("107.50", invoice("inherited", 1).total)
        repository.close(); repository = TimerRepository(app)
        assertNull(repository.jobDetail(job).job.settings.taxUnits)
        assertEquals(75000, repository.businessSnapshot().settings.defaultTaxUnits)
    }
    @Test fun nonzeroOverrideAndIssuedSnapshotSurviveLaterDefaultChanges() {
        assertNull(defaultTax(75000).conflict); assertNull(overrideTax(50000).conflict)
        val first = invoice("first"); val original = repository.invoice("first").payload
        assertEquals("105.00", first.total)
        assertNull(defaultTax(100000).conflict); assertNull(overrideTax(null).conflict)
        assertEquals(original, repository.invoice("first").payload)
        assertEquals("110.00", invoice("next", 1).total)
    }
    @Test fun invalidDefaultsAndOverridesAreRejectedWithoutPartialWrites() {
        val before = repository.businessSnapshot()
        val jobBefore = repository.jobDetail(job)
        assertEquals("INVALID_TAX_RATE", repository.saveBusinessSettings("bad-default", version, before.settings.copy(companyName = "Must not save", defaultTaxUnits = 1000001)).conflict)
        assertEquals(before, repository.businessSnapshot())
        assertEquals("INVALID_TAX_RATE", repository.saveJobSettings("bad-job", version, jobBefore.job.settings.copy(name = "Must not save", taxUnits = -1)).conflict)
        assertEquals(jobBefore, repository.jobDetail(job))
        inspect { db ->
            try { db.execSQL("UPDATE billing_tax_settings SET default_tax_units=1.5"); fail("Fractional tax units accepted") } catch (_: android.database.SQLException) {}
            try { db.execSQL("INSERT INTO job_tax_rates VALUES(?,1000001)", arrayOf(job)); fail("Out-of-range job rate accepted") } catch (_: android.database.SQLException) {}
        }
    }
    @Test fun taskSubtotalIsRoundedOnceForTaxAndZeroPricesRemainExplicit() {
        assertNull(defaultTax(100000).conflict)
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        val done = repository.boardSnapshot(job).board.columns.last().id
        for (n in 1..2) assertNull(repository.saveCard("task-$n", version, BoardStore.Draft("task-$n", job, done, "Synthetic task $n", priceMinor = 3, currency = "USD")).conflict)
        assertNull(repository.prepareInvoice("prepare-tasks", version, "tasks", selection).conflict)
        val result = InvoiceStore.Snapshot.decode(repository.invoice("tasks").payload)
        assertEquals("0.06", result.subtotal); assertEquals("0.01", result.taxAmount); assertEquals("0.07", result.total)
    }
    @Test fun currencyChangeDoesNotReinterpretTaxOrIssuedMoney() {
        assertNull(defaultTax(75000).conflict)
        val original = invoice("first")
        assertNull(repository.saveBusinessSettings("new-currency", version, repository.businessSnapshot().settings.copy(currency = "JPY", defaultHourlyMinor = 500)).conflict)
        assertEquals("USD", repository.jobDetail(job).job.settings.currency)
        assertEquals(original, InvoiceStore.Snapshot.decode(repository.invoice("first").payload))
        assertEquals(75000, repository.jobDetail(job).job.effectiveTaxUnits)
    }
}
