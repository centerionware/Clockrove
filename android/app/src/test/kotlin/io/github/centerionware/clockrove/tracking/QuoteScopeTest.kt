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
    @Test fun wideUtf8PlanningCommandKeepsBoundedReceiptAndExactReplay() {
        val lines = (0 until 2000).map { QuoteStore.Line("line-$it", "Tile $it", "", "ITEM", "10000", "1250") }
        quote(lines = lines)
        val value = scope().copy(items = lines.mapIndexed { index, line -> QuoteScopeStore.Item(line.id, "task-$index", "界".repeat(240)) })
        val before = version
        val result = repository.applyQuoteScope("wide-scope", before, value)
        assertNull(result.conflict)
        assertEquals(result, repository.applyQuoteScope("wide-scope", before, value))
        assertEquals("COMMAND_ID_REUSED", repository.applyQuoteScope("wide-scope", before, value.copy(note = "Different reviewed scope note")).conflict)
        inspect { db -> db.rawQuery("SELECT length(fingerprint) FROM commands WHERE id='wide-scope'", null).use { it.moveToFirst(); assertTrue(it.getInt(0) < 256) } }
        assertEquals(2000, count("kanban_cards"))
        assertEquals(0, count("invoice_task_claims")); assertEquals(0, count("sessions"))
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
    private fun actualRequest() = QuoteInvoiceReviewStore.Request("quote", 1, ReportStore.Selection("2026-01-01", "2026-12-31", "UTC", listOf("scope-job")))
    private fun actualChoices(tasks: List<String> = emptyList(), times: List<QuoteInvoiceReviewStore.Time> = emptyList()) =
        QuoteInvoiceReviewStore.Choices(tasks, times, note = "Explicit review of actual performed scope", confirmed = true)
    @Test fun quoteInvoiceReviewUsesActualCompletedQuantityAndAcceptedPriceWithoutMutatingWork() {
        quote(); assertNull(repository.applyQuoteScope("scope", version, scope()).conflict)
        val request = actualRequest()
        assertTrue(repository.quoteActualReview(request).result.context!!.tasks.isEmpty())
        assertEquals("NO_BILLABLE_ITEMS", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices()).problem })
        val board = repository.boardSnapshot("scope-job").board
        val done = board.columns.single { it.completed }.id
        assertNull(repository.saveCard("complete", version, BoardStore.Draft("scope-task", "scope-job", done, "Actually installed tile", priceMinor = BigInteger("999"), currency = "USD", quantityUnits = 300000)).conflict)
        assertNull(repository.saveCard("unrelated", version, BoardStore.Draft("unmapped-task", "scope-job", done, "Unmapped extra work", priceMinor = BigInteger("999999"), currency = "USD")).conflict)
        assertNull(repository.saveJobSettings("tax", version, repository.jobDetail("scope-job").job.settings.copy(taxUnits = 100000)).conflict)
        val before = repository.boardSnapshot("scope-job"); val beforeVersion = version
        val available = repository.quoteActualReview(request).result.context!!
        assertEquals(listOf("scope-task"), available.tasks.map { it.actual.id })
        assertEquals(BigInteger("29970"), available.coverage.report.totals.single().amountMinor)
        val accepted = inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices(listOf("scope-task"))) }
        assertNull(accepted.problem)
        assertEquals(BigInteger("37500"), accepted.value!!.report.totals.single().amountMinor)
        assertEquals(75000, accepted.value!!.taxUnits)
        assertEquals("260000", accepted.value!!.context.tasks.single().accepted.quantity)
        val current = inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices(listOf("scope-task")).copy(currentPrices = true, currentTax = true, currentParty = true)) }
        assertNull(current.problem); assertEquals(BigInteger("29970"), current.value!!.report.totals.single().amountMinor)
        assertEquals(100000, current.value!!.taxUnits); assertFalse(current.value!!.acceptedParty)
        assertEquals("QUOTE_INVOICE_SOURCES_CHANGED", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices(listOf("unmapped-task"))).problem })
        assertEquals(before, repository.boardSnapshot("scope-job")); assertEquals(beforeVersion, version)
        assertEquals(0, count("invoices")); assertEquals(0, count("invoice_task_claims"))
        assertNull(repository.prepareInvoice("ordinary-bill", version, "ordinary", request.selection).conflict)
        assertEquals("QUOTE_INVOICE_SOURCES_CHANGED", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices(listOf("scope-task"))).problem })
    }
    @Test fun quoteInvoiceHourlyReviewRequiresRealExactUnclaimedIntervalsAndKeepsWideAgreedRate() {
        val wide = "9".repeat(1000)
        quote(lines = listOf(QuoteStore.Line("hours", "Estimated work", "", "HOURS", "15000", wide)))
        assertNull(repository.applyQuoteScope("scope", version, scope().copy(items = listOf(QuoteScopeStore.Item("hours", "scope-task", "Planning checklist")))).conflict)
        val request = actualRequest()
        assertEquals("NO_BILLABLE_ITEMS", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices()).problem })
        assertNull(repository.createManualSession("actual-work", version, SessionHistoryStore.Draft("session", "scope-job", at - 3600000, at, "UTC", 0)).conflict)
        val available = repository.quoteActualReview(request).result.context!!
        val times = available.coverage.report.intervals.map { QuoteInvoiceReviewStore.Time(it.sessionId, it.start, it.end) }
        val review = inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices(times = times)) }
        assertNull(review.problem); assertEquals(BigInteger(wide), review.value!!.report.totals.single().amountMinor)
        assertEquals(BigInteger("3600000"), review.value!!.report.totals.single().durationMillis)
        val forged = times.toMutableList(); forged[0] = forged[0].copy(end = forged[0].end + 1)
        assertEquals("QUOTE_INVOICE_SOURCES_CHANGED", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices(times = forged)).problem })
        assertEquals("INVALID_QUOTE_INVOICE", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices(times = times + times)).problem })
        assertEquals(1, count("sessions")); assertNull(repository.boardSnapshot("scope-job").board.cards.single().completedMillis)
        assertEquals(0, count("invoices")); assertEquals(0, count("invoice_time_claims"))
        assertNull(repository.prepareInvoice("ordinary-bill", version, "ordinary", request.selection).conflict)
        assertEquals("QUOTE_INVOICE_SOURCES_CHANGED", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices(times = times)).problem })
    }
    @Test fun quoteInvoiceReviewRequiresRevisionLinksAndExplicitHistoricalReviewAndRetainsArchivedRead() {
        quote(); assertNull(repository.applyQuoteScope("scope", version, scope()).conflict)
        val request = actualRequest()
        val revised = QuoteStore.Draft("quote", null, null, "Revised agreement", "USD", 100000,
            listOf(QuoteStore.Line("tile", "Tile", "", "ITEM", "280000", "1500")))
        assertNull(repository.saveQuote("revise", version, revised).conflict)
        assertNull(repository.issueQuote("issue-two", version, "quote", true).conflict)
        assertEquals("QUOTE_ACCEPTANCE_REQUIRED", repository.quoteActualReview(request.copy(revision = 2)).result.problem)
        assertNull(repository.decideQuote("accept-two", version, "quote", 2, "ACCEPTED", "Explicit synthetic revised acceptance", true).conflict)
        assertEquals("QUOTE_INVOICE_MAPPING_REQUIRED", repository.quoteActualReview(request.copy(revision = 2)).result.problem)
        assertEquals("QUOTE_HISTORICAL_REVIEW_REQUIRED", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices()).problem })
        assertEquals("NO_BILLABLE_ITEMS", inspect { QuoteInvoiceReviewStore.review(it, request, actualChoices().copy(historicalReviewed = true)).problem })
        assertNull(repository.applyQuoteScope("link-two", version, scope().copy(revision = 2, newJobName = null, jobDifferencesReviewed = true)).conflict)
        assertNull(repository.quoteActualReview(request.copy(revision = 2)).result.problem)
        assertNull(repository.archiveJob("archive", version, "scope-job").conflict)
        assertTrue(repository.quoteActualReview(request.copy(revision = 2)).result.context!!.job.archived)
        at += 31L * 86400000
        assertNull(repository.deleteJob("purge", version, "scope-job").conflict)
        assertEquals("JOB_UNAVAILABLE", repository.quoteActualReview(request.copy(revision = 2)).result.problem)
        assertEquals(0, count("invoices")); assertEquals(0, count("invoice_task_claims"))
    }
    private fun completedQuoteWork() {
        quote(); assertNull(repository.applyQuoteScope("scope", version, scope()).conflict)
        val done = repository.boardSnapshot("scope-job").board.columns.single { it.completed }.id
        assertNull(repository.saveCard("complete", version, BoardStore.Draft("scope-task", "scope-job", done, "Actual tile installation", priceMinor = BigInteger("999"), currency = "USD", quantityUnits = 260000)).conflict)
    }
    @Test fun quoteInvoiceSharesExclusiveClaimsAndPreservesOriginAcrossPaidReplacementAndJobPurge() {
        completedQuoteWork()
        val draft = QuoteInvoiceStore.Draft("quoted", actualRequest(), actualChoices(listOf("scope-task")))
        val before = version
        val outcome = repository.prepareQuoteInvoice("prepare-quote", before, draft)
        assertNull(outcome.conflict); assertEquals(outcome, repository.prepareQuoteInvoice("prepare-quote", before, draft))
        assertEquals("COMMAND_ID_REUSED", repository.prepareQuoteInvoice("prepare-quote", before, draft.copy(invoiceId = "different")).conflict)
        val original = repository.invoice("quoted"); val document = InvoiceStore.Snapshot.decode(original.payload)
        assertEquals("349.38", document.total); assertEquals("325.00", document.subtotal)
        assertEquals("1250", document.lines.single().unitPriceMinor); assertEquals("260000", document.lines.single().quantityUnits)
        assertTrue(document.basis.contains("Q-1000")); assertTrue(document.lines.single().detail.contains("Revision 1"))
        assertFalse(original.payload.contains(draft.choices.note))
        val origin = inspect { QuoteInvoiceStore.origin(it, "quoted")!! }
        assertEquals("quoted", origin.originalInvoiceId)
        assertTrue(origin.payload.contains("scope-task")); assertTrue(origin.payload.contains("ORIGINAL_ISSUE_PROVENANCE"))
        assertEquals("NO_BILLABLE_ITEMS", repository.prepareInvoice("ordinary", version, "ordinary", draft.request.selection).conflict)
        assertEquals("QUOTE_INVOICE_SOURCES_CHANGED", repository.prepareQuoteInvoice("again", version, draft.copy(invoiceId = "another")).conflict)
        assertNull(repository.recordInvoicePayment("payment", version, InvoicePaymentStore.Draft("payment", "quoted", "10000", "USD", at, "UTC")).conflict)
        val line = document.lines.single()
        val replacement = InvoiceLifecycleStore.ReplacementDraft("quoted", "replacement", listOf(InvoiceLifecycleStore.LineEdit(0, line.title, line.detail, "39000", "260000", "1500")), 75000, "Explicit correction of agreed unit price", "UTC", true)
        assertNull(repository.replaceInvoice("replace", version, replacement).conflict)
        val nextOrigin = inspect { QuoteInvoiceStore.origin(it, "replacement")!! }
        assertEquals(origin.payload, nextOrigin.payload); assertEquals("quoted", nextOrigin.originalInvoiceId)
        val balance = repository.invoicePayments("replacement").second.balance
        assertEquals(BigInteger("10000"), balance.paid); assertEquals(BigInteger("31925"), balance.due)
        assertEquals(setOf("quoted", "replacement"), repository.quoteInvoices("quote").map { it.invoiceId }.toSet())
        assertNull(repository.recordInvoicePayment("final", version, InvoicePaymentStore.Draft("final", "replacement", "31925", "USD", at, "UTC")).conflict)
        assertEquals("PAID_INVOICE_CANNOT_VOID", repository.voidInvoice("void-paid", version, InvoiceLifecycleStore.VoidDraft("replacement", "Attempted paid cancellation", "UTC", true)).conflict)
        assertEquals("QUOTE_INVOICE_SOURCES_CHANGED", repository.prepareQuoteInvoice("paid-again", version, draft.copy(invoiceId = "third")).conflict)
        assertNull(repository.archiveJob("archive", version, "scope-job").conflict)
        at += 31L * 86400000
        assertNull(repository.deleteJob("purge", version, "scope-job").conflict)
        assertEquals(origin.payload, inspect { QuoteInvoiceStore.origin(it, "replacement")!!.payload })
        assertEquals(2, repository.quoteInvoices("quote").size)
        assertEquals(original.payload, repository.invoice("quoted").payload)
        inspect { db -> db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) } }
    }
    @Test fun quoteInvoiceLateProvenanceFailureRollsBackInvoiceNumberClaimsRevisionAndReceipt() {
        completedQuoteWork()
        val draft = QuoteInvoiceStore.Draft("quoted", actualRequest(), actualChoices(listOf("scope-task")))
        val before = version; val revisions = count("entity_revisions")
        inspect { it.execSQL("CREATE TRIGGER reject_quote_invoice BEFORE INSERT ON quote_invoice_links BEGIN SELECT RAISE(ABORT,'Synthetic provenance failure'); END") }
        try { repository.prepareQuoteInvoice("prepare-quote", before, draft); fail("Expected full rollback") } catch (_: android.database.SQLException) { }
        assertEquals(before, version); assertEquals(revisions, count("entity_revisions"))
        assertEquals(0, count("invoices")); assertEquals(0, count("invoice_task_claims")); assertEquals(0, count("quote_invoice_links"))
        inspect { db ->
            db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1000L, it.getLong(0)) }
            db.rawQuery("SELECT id FROM commands WHERE id='prepare-quote'", null).use { assertFalse(it.moveToFirst()) }
            db.execSQL("DROP TRIGGER reject_quote_invoice")
        }
        assertNull(repository.prepareQuoteInvoice("prepare-quote", before, draft).conflict)
        assertEquals("INV-1000", repository.invoice("quoted").number)
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
