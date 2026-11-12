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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class InvoiceCoverageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var now = 1793577600000L
    private val midnight = 1793491200000L
    private val hour = 3600000L
    private val version get() = repository.snapshot().version
    private val selection get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now })
        job = repository.createJob("Synthetic covered work")
        assertNull(repository.saveBusinessSettings("rate", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultHourlyMinor = 10000)).conflict)
        // Existing jobs retain creation currency, so set their explicit rate.
        assertNull(repository.saveJobSettings("job-rate", version, repository.jobDetail(job).job.settings.copy(hourlyRateMinor = 10000)).conflict)
    }
    @After fun close() { repository.close() }
    private fun work(id: String, start: Long = midnight, end: Long = start + hour) {
        assertNull(repository.createManualSession("create-$id", version, SessionHistoryStore.Draft(id, job, start, end, "UTC", 0)).conflict)
    }
    private fun prepare(id: String, selected: ReportStore.Selection = selection) = repository.prepareInvoice("prepare-$id", version, id, selected)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun preview() = inspect { InvoiceCoverageStore.preview(it, ReportStore.read(it, selection)) }
    private fun number() = inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); it.getLong(0) } }

    @Test fun repeatedHourlySelectionExcludesBilledTimeWithoutConsumingNumber() {
        work("one"); assertNull(prepare("first").conflict)
        val before = version
        assertEquals("NO_BILLABLE_ITEMS", prepare("repeat").conflict)
        assertEquals(before, version); assertEquals(1001L, number())
        val remaining = preview()
        assertTrue(remaining.report.intervals.isEmpty())
        assertEquals("INV-1000", remaining.excluded.single().number)
        assertEquals("one", remaining.excluded.single().sessionId)
    }
    @Test fun overlappingDateAndTimezoneSelectionsBillOnlyExactUtcRemainder() {
        work("cross-day", midnight - hour, midnight + hour)
        assertNull(prepare("today").conflict)
        val yesterday = ReportStore.Selection("2026-10-31", "2026-11-01", "UTC", listOf(job))
        assertNull(prepare("remainder", yesterday).conflict)
        val value = InvoiceStore.Snapshot.decode(repository.invoice("remainder").payload)
        assertEquals("100.00", value.total)
        assertEquals("NO_BILLABLE_ITEMS", prepare("zone", selection.copy(timezone = "America/New_York")).conflict)
        inspect { db -> db.rawQuery("SELECT start_ms,end_ms FROM invoice_time_claims ORDER BY start_ms", null).use {
            assertTrue(it.moveToFirst()); assertEquals(midnight - hour, it.getLong(0)); assertEquals(midnight, it.getLong(1))
            assertTrue(it.moveToNext()); assertEquals(midnight, it.getLong(0)); assertEquals(midnight + hour, it.getLong(1)); assertFalse(it.moveToNext())
        } }
    }
    @Test fun billedTasksStayExcludedAfterPriceCompletionAndTitleEdits() {
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        val board = repository.boardSnapshot(job).board
        val task = BoardStore.Draft("task", job, board.columns.last().id, "Synthetic task", priceMinor = 2500, currency = board.currency)
        assertNull(repository.saveCard("task", version, task).conflict)
        // Completion time comes from the injected native clock, within selection.
        val selected = selection.copy(lastDate = "2026-11-02")
        assertNull(prepare("task-first", selected).conflict)
        assertNull(repository.saveCard("task-edit", version, task.copy(title = "Changed", priceMinor = 9999)).conflict)
        assertEquals("NO_BILLABLE_ITEMS", prepare("task-again", selected).conflict)
        assertEquals(1001L, number())
        val template = repository.taskTemplates().single()
        assertNull(repository.copyTaskTemplate("copy", version, template.id, "new-task", job, board.columns.last().id, true).conflict)
        assertNull(prepare("independent-copy", selected).conflict)
    }
    @Test fun changedRatesDoNotRebillOldHoursAndNewWorkUsesTheNewRate() {
        work("old"); assertNull(prepare("first").conflict)
        assertNull(repository.saveJobSettings("changed-rate", version, repository.jobDetail(job).job.settings.copy(hourlyRateMinor = 20000)).conflict)
        work("new", midnight + hour)
        assertNull(prepare("second").conflict)
        assertEquals("200.00", InvoiceStore.Snapshot.decode(repository.invoice("second").payload).total)
        assertEquals("100.00", InvoiceStore.Snapshot.decode(repository.invoice("first").payload).total)
    }
    @Test fun boundaryEditsCannotMoveBilledHoursButCanAddUnbilledWork() {
        work("one"); assertNull(prepare("first").conflict)
        val moved = SessionHistoryStore.Draft("one", job, midnight + 2 * hour, midnight + 3 * hour, "UTC", 0)
        assertEquals("INVOICED_SESSION_BOUNDARY", repository.correctSession("move", version, moved).conflict)
        assertNull(repository.correctSession("extend", version, moved.copy(start = midnight, end = midnight + 2 * hour)).conflict)
        assertNull(prepare("extended").conflict)
        assertEquals("100.00", InvoiceStore.Snapshot.decode(repository.invoice("extended").payload).total)
    }
    @Test fun deletingAndRecreatingWorkDoesNotReleaseItsUtcReservation() {
        work("one"); assertNull(prepare("first").conflict)
        assertNull(repository.deleteSession("delete-work", version, "one").conflict)
        work("replacement")
        assertEquals("NO_BILLABLE_ITEMS", prepare("again").conflict)
        assertEquals(1001L, number())
    }
    @Test fun unbilledPiecesOnBothSidesOfAClaimAreKeptAndRoundedTogether() {
        work("middle", midnight + hour, midnight + 2 * hour)
        assertNull(prepare("first").conflict)
        assertNull(repository.correctSession("expand-both", version, SessionHistoryStore.Draft("middle", job, midnight, midnight + 3 * hour, "UTC", 0)).conflict)
        val remaining = preview().report.intervals
        assertEquals(listOf(midnight to midnight + hour, midnight + 2 * hour to midnight + 3 * hour), remaining.map { it.start to it.end })
        assertNull(prepare("both-sides").conflict)
        assertEquals("200.00", InvoiceStore.Snapshot.decode(repository.invoice("both-sides").payload).total)
    }
    @Test fun databaseRejectsOverlappingOrCrossJobClaimsAndAllowsTouchingIntervals() {
        work("one"); assertNull(prepare("first").conflict)
        inspect { db ->
            db.execSQL("INSERT INTO invoices(id,job_id,number,created_ms,payload) SELECT 'other',job_id,'INV-1001',created_ms,payload FROM invoices WHERE id='first'")
            try {
                db.execSQL("INSERT INTO invoice_time_claims(id,invoice_id,job_id,session_id,start_ms,end_ms,timezone) VALUES('overlap','other',?,'one',?,?,'UTC')", arrayOf<Any?>(job, midnight + 1, midnight + hour + 1))
                fail("Overlapping time claim accepted")
            } catch (_: android.database.SQLException) {}
            db.execSQL("INSERT INTO invoice_time_claims(id,invoice_id,job_id,session_id,start_ms,end_ms,timezone) VALUES('touching','other',?,'later',?,?,'UTC')", arrayOf<Any?>(job, midnight + hour, midnight + 2 * hour))
            try {
                db.execSQL("INSERT INTO invoice_task_claims(id,invoice_id,job_id,task_id) VALUES('wrong-job','first','other-job','task')")
                fail("Cross-job task claim accepted")
            } catch (_: android.database.SQLException) {}
            db.execSQL("INSERT INTO invoice_task_claims(id,invoice_id,job_id,task_id) VALUES('task-first','first',?,'task')", arrayOf(job))
            try {
                db.execSQL("INSERT INTO invoice_task_claims(id,invoice_id,job_id,task_id) VALUES('task-twice','other',?,'task')", arrayOf(job))
                fail("Duplicate task claim accepted")
            } catch (_: android.database.SQLException) {}
        }
    }
    @Test fun concurrentDifferentRequestsCannotReserveTheSameWorkTwice() {
        work("one"); val captured = version
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val requests = (1..2).map { n -> pool.submit<TimerRepository.Outcome> { start.await(); repository.prepareInvoice("race-$n", captured, "invoice-$n", selection) } }
            start.countDown(); val outcomes = requests.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.conflict == null }); assertEquals(1, outcomes.count { it.conflict == "STALE_STATE" })
            assertEquals(1001L, number())
            assertEquals("NO_BILLABLE_ITEMS", prepare("fresh-after-race").conflict)
        } finally { pool.shutdownNow() }
    }
    @Test fun lostReplyReplaysOriginalReservationWithoutAllocatingAgain() {
        work("one"); val captured = version
        val result = repository.prepareInvoice("same", captured, "first", selection)
        assertNull(result.conflict)
        assertEquals(result, repository.prepareInvoice("same", captured, "first", selection))
        assertEquals(1001L, number())
        inspect { db -> db.rawQuery("SELECT COUNT(*) FROM invoice_time_claims", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) } }
    }
    @Test fun reservationFailureRollsBackInvoiceSequenceClaimsAndReceipt() {
        work("one")
        inspect { it.execSQL("CREATE TRIGGER fail_claim BEFORE INSERT ON invoice_time_claims BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        val before = version
        try { prepare("failed"); fail("Claim failure committed") } catch (_: android.database.SQLException) {}
        assertEquals(before, version); assertEquals(1000L, number())
        inspect { db ->
            for (table in listOf("invoices", "invoice_coverage", "invoice_time_claims")) db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT id FROM commands WHERE id='prepare-failed'", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun jobPurgeRetainsImmutableInvoiceCoverageLogoAndFinancialRevisions() {
        work("one"); assertNull(prepare("first").conflict)
        val original = repository.invoice("first")
        assertNull(repository.archiveJob("archive", version, job).conflict)
        now += JobLifecycleStore.retentionMillis
        assertNull(repository.deleteJob("purge", version, job).conflict)
        assertEquals(original, repository.invoice("first"))
        val retained = repository.invoices().invoices.single()
        assertEquals("first", retained.id); assertEquals(job, retained.jobId)
        assertTrue(retained.jobDeleted); assertFalse(retained.legacy)
        assertEquals("Synthetic covered work", retained.jobName)
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM invoice_time_claims WHERE job_id=?", arrayOf(job)).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.rawQuery("SELECT payload,deleted FROM entity_revisions WHERE kind='invoice' AND entity_id='first'", null).use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("Synthetic covered work")); assertEquals(0, it.getInt(1))
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
}
