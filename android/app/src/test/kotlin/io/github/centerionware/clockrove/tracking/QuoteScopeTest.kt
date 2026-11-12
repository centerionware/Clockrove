package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
class QuoteScopeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private var at = 1793494800000L
    private val version get() = repository.snapshot().version
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun count(table: String) = inspect { db -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun quote(id: String = "quote", lines: List<QuoteStore.Line> = listOf(QuoteStore.Line("tile", "Synthetic tile", "Synthetic definition", "ITEM", "260000", "1250")), accepted: Boolean = true) {
        assertNull(repository.saveQuote("save-$id", version, QuoteStore.Draft(id, null, null, "Synthetic scope", "USD", 75000, lines)).conflict)
        assertNull(repository.issueQuote("issue-$id", version, id, true).conflict)
        if (accepted) assertNull(repository.decideQuote("accept-$id", version, id, 1, "ACCEPTED", "Explicit synthetic local acceptance", true).conflict)
    }
    private fun scope() = QuoteScopeStore.Draft("quote", 1, "scope-job", listOf(QuoteScopeStore.Item("tile", "scope-task", "Install tile")), "Reviewed actual planned scope", true, "Synthetic accepted job")
    @Before fun setup() { app.deleteDatabase("clockrove.db"); repository = TimerRepository(app, wallClock = { at }); repository.snapshot(); assertNull(repository.saveBusinessSettings("setup-currency", version, repository.businessSnapshot().settings.copy(currency = "USD")).conflict) }
    @After fun close() { repository.close() }
    @Test fun acceptedFixedScopeCreatesOnlyUncompletedPlanningWorkAndRepeatsReuseIt() {
        quote(); val before = version; val value = scope()
        val result = repository.applyQuoteScope("map", before, value)
        assertNull(result.conflict); assertEquals(result, repository.applyQuoteScope("map", before, value))
        assertNull(repository.applyQuoteScope("map-again", version, value).conflict)
        assertEquals(1, count("jobs")); assertEquals(1, count("kanban_cards")); assertEquals(1, count("quote_scope_lines"))
        val board = repository.boardSnapshot("scope-job").board; val task = board.cards.single()
        assertNull(task.completedMillis); assertFalse(board.columns.single { it.id == task.columnId }.completed)
        assertEquals(BigInteger("1250"), task.priceMinor); assertEquals(260000L, task.quantityUnits); assertTrue(task.billable)
        assertEquals("TASK", repository.jobDetail("scope-job").job.settings.billingMode)
        assertEquals(75000, repository.jobDetail("scope-job").job.effectiveTaxUnits)
        assertEquals("scope-task", repository.quoteScope("quote").second.single().taskId)
        assertFalse(repository.taskTemplates().single().description.contains("Q-1000"))
        for (table in listOf("sessions", "invoices", "invoice_payments", "invoice_task_claims", "invoice_time_claims")) assertEquals(table, 0, count(table))
        inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1000L, it.getLong(0)) } }
        assertEquals("QUOTE_SCOPE_ALREADY_MAPPED", repository.applyQuoteScope("duplicate", version, value.copy(items = listOf(value.items.single().copy(taskId = "different-task")))).conflict)
        assertEquals(1, count("kanban_cards"))
    }
    @Test fun estimatedHoursCreateNonbillableChecklistAndExactJobRateButNeverWorkedTime() {
        val wide = "9".repeat(1000)
        quote(lines = listOf(QuoteStore.Line("hours", "Estimated preparation", "", "HOURS", "15000", wide)))
        val value = scope().copy(items = listOf(QuoteScopeStore.Item("hours", "hours-task", "Preparation checklist")))
        assertNull(repository.applyQuoteScope("hours", version, value).conflict)
        val task = repository.boardSnapshot("scope-job").board.cards.single()
        assertFalse(task.billable); assertNull(task.priceMinor); assertNull(task.completedMillis)
        assertTrue(task.description.contains("Estimated 1.5 hours"))
        val job = repository.jobDetail("scope-job").job
        assertEquals("HOURLY", job.settings.billingMode); assertEquals(BigInteger(wide), job.effectiveHourlyMinor)
        assertEquals(0, count("sessions")); assertEquals(0, count("invoices"))
    }
    @Test fun unacceptedMixedOrUnconfirmedScopesRejectBeforeCreatingAnyWork() {
        quote(accepted = false)
        assertEquals("QUOTE_ACCEPTANCE_REQUIRED", repository.applyQuoteScope("unaccepted", version, scope()).conflict)
        assertEquals("INVALID_QUOTE_SCOPE", repository.applyQuoteScope("unconfirmed", version, scope().copy(confirmed = false)).conflict)
        quote("mixed", listOf(QuoteStore.Line("tile", "Tile", "", "ITEM", "10000", "100"), QuoteStore.Line("hours", "Hours", "", "HOURS", "10000", "200")))
        assertEquals("QUOTE_SCOPE_SPLIT_REQUIRED", repository.applyQuoteScope("mixed", version, scope().copy(quoteId = "mixed", items = listOf(
            QuoteScopeStore.Item("tile", "tile-task", "Tile"), QuoteScopeStore.Item("hours", "hours-task", "Hours")))).conflict)
        assertEquals(0, count("jobs")); assertEquals(0, count("kanban_cards")); assertEquals(0, count("quote_scope_lines"))
    }
    @Test fun linkingExistingCompletedTaskNeverChangesItsPriceCompletionOrSourceIdentity() {
        quote(); val job = repository.createJob("Existing job")
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        val column = repository.boardSnapshot(job).board.columns.single { it.completed }.id
        assertNull(repository.saveCard("existing", version, BoardStore.Draft("existing-task", job, column, "Actual completed work", priceMinor = BigInteger("9999"), currency = "USD")).conflict)
        val original = repository.boardSnapshot(job).board.cards.single()
        val value = scope().copy(jobId = job, newJobName = null, items = listOf(QuoteScopeStore.Item("tile", "existing-task", "Reviewed actual task", true)))
        assertEquals("QUOTE_JOB_REVIEW_REQUIRED", repository.applyQuoteScope("needs-review", version, value).conflict)
        assertNull(repository.applyQuoteScope("link", version, value.copy(jobDifferencesReviewed = true)).conflict)
        assertEquals(original, repository.boardSnapshot(job).board.cards.single())
        assertEquals(0, count("invoices")); assertEquals(0, count("invoice_task_claims"))
        assertEquals("existing-task", repository.quoteScope("quote").second.single().taskId)
    }
    @Test fun interruptedMappingRollsBackJobTasksTemplatesMoneyAncestryAndCommand() {
        quote(); val before = version
        val revisions = count("entity_revisions")
        inspect { it.execSQL("CREATE TRIGGER reject_scope BEFORE INSERT ON quote_scope_revisions BEGIN SELECT RAISE(ABORT,'Synthetic late interruption'); END") }
        try { repository.applyQuoteScope("interrupt", before, scope()); fail("Expected rollback") } catch (_: android.database.SQLException) { }
        assertEquals(before, version); assertEquals(revisions, count("entity_revisions"))
        for (table in listOf("jobs", "kanban_cards", "task_templates", "quote_scope_lines", "job_money", "job_tax_rates")) assertEquals(table, 0, count(table))
        inspect { db ->
            db.rawQuery("SELECT id FROM commands WHERE id='interrupt'", null).use { assertFalse(it.moveToFirst()) }
            db.execSQL("DROP TRIGGER reject_scope")
        }
        assertNull(repository.applyQuoteScope("interrupt", before, scope()).conflict)
    }
    @Test fun purgedWorkRetainsScopeProvenanceAndCannotBeResurrectedByConversion() {
        quote(); assertNull(repository.applyQuoteScope("map", version, scope()).conflict)
        val binding = repository.quoteScope("quote").second.single()
        assertNull(repository.archiveJob("archive", version, "scope-job").conflict)
        at += 31L * 86400000
        assertNull(repository.deleteJob("purge", version, "scope-job").conflict)
        assertEquals(binding, repository.quoteScope("quote").second.single())
        assertEquals("JOB_ID_EXISTS", repository.applyQuoteScope("resurrect", version, scope()).conflict)
        assertEquals(0, count("jobs")); assertEquals(0, count("kanban_cards"))
        inspect { db ->
            db.rawQuery("SELECT payload FROM entity_revisions WHERE kind='quote_scope_line'", null).use { assertTrue(it.moveToFirst()); assertFalse(it.isNull(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun acceptedRevisionReusesTheSameTaskAndHistoricalRevisionNeedsExplicitReview() {
        quote(); assertNull(repository.applyQuoteScope("scope-one", version, scope()).conflict)
        val original = repository.boardSnapshot("scope-job").board.cards.single()
        val revised = QuoteStore.Draft("quote", null, null, "Revised accepted scope", "USD", 100000,
            listOf(QuoteStore.Line("tile", "Revised tile", "", "ITEM", "280000", "1500")))
        assertNull(repository.saveQuote("revise", version, revised).conflict)
        assertNull(repository.issueQuote("issue-two", version, "quote", true).conflict)
        assertNull(repository.decideQuote("accept-two", version, "quote", 2, "ACCEPTED", "Explicit review of changed scope", true).conflict)
        assertEquals("QUOTE_HISTORICAL_REVIEW_REQUIRED", repository.applyQuoteScope("historical", version, scope().copy(newJobName = null)).conflict)
        val newer = scope().copy(revision = 2, newJobName = null, jobDifferencesReviewed = true)
        assertNull(repository.applyQuoteScope("scope-two", version, newer).conflict)
        assertEquals(original, repository.boardSnapshot("scope-job").board.cards.single())
        assertEquals(1, count("quote_scope_lines")); assertEquals(2, count("quote_scope_revisions"))
        assertEquals(1, repository.quoteScope("quote").second.single().originalRevision)
        assertNull(repository.applyQuoteScope("historical-reviewed", version, scope().copy(newJobName = null, historicalReviewed = true)).conflict)
        assertEquals(1, count("kanban_cards")); assertEquals(0, count("invoices"))
    }
    @Test fun concurrentScopeCommandsCreateOneJobAndOneTask() {
        quote(); val initial = version; val gate = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val pending = (1..2).map { index -> pool.submit<TimerRepository.Outcome> {
                gate.await(); repository.applyQuoteScope("race-$index", initial, scope())
            } }
            gate.countDown(); val outcomes = pending.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.conflict == null }); assertEquals(1, outcomes.count { it.conflict == "STALE_STATE" })
        } finally { pool.shutdownNow() }
        assertEquals(1, count("jobs")); assertEquals(1, count("kanban_cards")); assertEquals(1, count("quote_scope_lines"))
        assertEquals(0, count("sessions")); assertEquals(0, count("invoices"))
    }
}
