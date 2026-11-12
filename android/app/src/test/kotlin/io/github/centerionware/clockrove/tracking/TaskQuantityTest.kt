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
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class TaskQuantityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private val at = 1793494800000L
    private val version get() = repository.snapshot().version
    private val selection get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { at })
        assertNull(repository.saveBusinessSettings("settings", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultTaxUnits = 75000)).conflict)
        job = repository.createJob("Synthetic tile job")
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
    }
    @After fun close() { repository.close() }
    private fun task(id: String = "tile", units: Long = 260000, price: Long = 500) = BoardStore.Draft(id, job,
        repository.boardSnapshot(job).board.columns.last().id, "Install 1 sq ft tile", priceMinor = price, currency = "USD", quantityUnits = units)
    @Test fun twentySixUnitsFlowThroughReportsTaxAndFrozenInvoiceWithoutDuplicateBilling() {
        assertNull(repository.saveCard("save", version, task()).conflict)
        assertEquals(BigInteger.valueOf(13000), repository.report(selection).report.totals.single().amountMinor)
        assertNull(repository.prepareInvoice("prepare", version, "invoice", selection).conflict)
        val snapshot = InvoiceStore.Snapshot.decode(repository.invoice("invoice").payload)
        assertEquals("130.00", snapshot.subtotal); assertEquals("9.75", snapshot.taxAmount); assertEquals("139.75", snapshot.total)
        assertEquals("260000", snapshot.lines.single().quantityUnits); assertEquals("500", snapshot.lines.single().unitPriceMinor)
        assertTrue(snapshot.lines.single().detail.contains("26 × 5.00 USD each"))
        assertEquals(snapshot, InvoiceStore.Snapshot.decode(snapshot.encode()))
        assertNull(repository.saveCard("edit", version, task(units = 300000, price = 900)).conflict)
        assertEquals(snapshot, InvoiceStore.Snapshot.decode(repository.invoice("invoice").payload))
        assertEquals("NO_BILLABLE_ITEMS", repository.prepareInvoice("again", version, "another", selection).conflict)
    }
    @Test fun fractionalQuantitiesRoundEachLineBeforeTaxAndUseArbitraryPrecision() {
        assertNull(repository.saveCard("first", version, task("first", 5000, 1)).conflict)
        assertNull(repository.saveCard("second", version, task("second", 5000, 1)).conflict)
        assertEquals(BigInteger.valueOf(2), repository.report(selection).report.totals.single().amountMinor)
        assertEquals(BigInteger("8507059173023461584739690778423250"), TaskQuantityStore.amount(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals("0.0001", TaskQuantityStore.format(1)); assertEquals("26.125", TaskQuantityStore.format(261250))
    }
    @Test fun templatesKeepQuantityWithoutCopyingCompletionAndCanOmitPrice() {
        assertNull(repository.saveCard("save", version, task()).conflict)
        val template = repository.taskTemplates().single()
        assertEquals(260000L, template.quantityUnits)
        val todo = repository.boardSnapshot(job).board.columns.first().id
        assertNull(repository.copyTaskTemplate("copy", version, template.id, "copy", job, todo, false).conflict)
        val copy = repository.boardSnapshot(job).board.cards.single { it.id == "copy" }
        assertEquals(260000L, copy.quantityUnits); assertNull(copy.priceMinor); assertNull(copy.completedMillis)
        assertNull(repository.removeCard("remove", version, job, "tile").conflict)
        assertEquals(260000L, repository.taskTemplates().first { it.id == template.id }.quantityUnits)
        repository.close(); repository = TimerRepository(app, wallClock = { at })
        assertEquals(260000L, repository.boardSnapshot(job).board.cards.single().quantityUnits)
    }
    @Test fun quantityChangesArePartOfReceiptIdentityAndInvalidQuantityDoesNotWrite() {
        val before = repository.boardSnapshot(job)
        assertEquals("INVALID_TASK_QUANTITY", repository.saveCard("bad", version, task(units = 0)).conflict)
        assertEquals(before, repository.boardSnapshot(job))
        val originalVersion = version
        val saved = repository.saveCard("save", originalVersion, task())
        assertNull(saved.conflict)
        assertEquals(saved, repository.saveCard("save", originalVersion, task()))
        assertNotNull(repository.saveCard("save", originalVersion, task(units = 270000)).conflict)
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            try { db.execSQL("UPDATE task_quantities SET quantity_units=1.5"); fail("Fractional units accepted") } catch (_: android.database.SQLException) {}
            try { db.execSQL("UPDATE task_quantities SET quantity_units=0"); fail("Zero units accepted") } catch (_: android.database.SQLException) {}
            db.execSQL("DELETE FROM task_quantities")
        }
        // Released tasks have no quantity row: exactly one, never zero.
        assertEquals(10000L, repository.boardSnapshot(job).board.cards.single().quantityUnits)
        assertEquals(BigInteger.valueOf(500), repository.report(selection).report.totals.single().amountMinor)
    }
}
