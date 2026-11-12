package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.flutter.plugin.common.MethodCall
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
class LegacyInvoiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var sequence = 0
    private val midnight = 1793491200000L
    private val hour = 3600000L
    private var now = midnight + 2 * hour
    private val version get() = repository.snapshot().version
    private val selected get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    private val note = "Checked against the original invoice and linked only its billed work."
    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now })
        assertNull(repository.saveBusinessSettings("settings", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultHourlyMinor = java.math.BigInteger.valueOf(10000))).conflict)
        job = repository.createJob("Synthetic legacy review")
        val payload = InvoiceStore.Snapshot("INV-1000", midnight + hour, "MODERN", "USD", 2, "Synthetic company", "", "Synthetic customer", "", "Synthetic legacy review",
            "2026-11-01 · UTC", "Legacy source identity unavailable", "", "", "100.00", "", "Invoice INV-1000", "Synthetic legacy invoice",
            listOf(InvoiceStore.Line("Original work", "", "100.00"))).encode()
        inspect { db ->
            db.execSQL("INSERT INTO invoices(id,job_id,number,created_ms,payload,pdf) VALUES('legacy',?,'INV-1000',?,?,?)", arrayOf<Any?>(job, midnight + hour, payload, "%PDF-preservation-fixture".toByteArray()))
            db.execSQL("INSERT INTO invoice_coverage(invoice_id,state) VALUES('legacy','LEGACY')")
            db.execSQL("UPDATE app_settings SET next_invoice_number=1001")
        }
    }
    @After fun close() { repository.close() }
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun draft(tasks: List<String> = emptyList(), spans: List<LegacyInvoiceStore.Span> = emptyList()) =
        LegacyInvoiceStore.Draft("legacy", tasks, spans, note, true, false)
    private fun reconcile(value: LegacyInvoiceStore.Draft, id: String = "review-${sequence++}", expected: Long = version) = repository.reconcileInvoiceCoverage(id, expected, value)
    private fun work(id: String = "work", start: Long = midnight, end: Long = start + 2 * hour) {
        assertNull(repository.createManualSession("create-$id", version, SessionHistoryStore.Draft(id, job, start, end, "UTC", 0)).conflict)
    }
    private fun task(id: String, done: Boolean = false) {
        val board = repository.boardSnapshot(job).board
        assertNull(repository.saveCard("save-$id-${sequence++}", version, BoardStore.Draft(id, job,
            if (done) board.columns.last().id else board.columns.first().id, "Synthetic $id", priceMinor = java.math.BigInteger.valueOf(10000), currency = "USD")).conflict)
    }
    @Test fun reviewedHoursReserveOnlyCoveredTimeWithoutChangingOriginalOrAllocatingNumber() {
        work()
        val original = repository.invoice("legacy")
        val before = version
        assertNull(reconcile(draft(spans = listOf(LegacyInvoiceStore.Span(midnight, midnight + hour, "UTC")))).conflict)
        assertEquals(before + 1, version)
        val saved = repository.invoice("legacy")
        assertEquals(original.payload, saved.payload); assertArrayEquals(original.pdf, saved.pdf)
        val context = repository.legacyInvoiceContext("legacy").context
        assertFalse(context.legacy); assertEquals(note, context.note); assertEquals(midnight + 2 * hour, context.reviewed)
        assertEquals("UTC", context.reservedTimes.single().timezone)
        assertNull(repository.prepareInvoice("next", version, "next", selected).conflict)
        assertEquals("INV-1001", repository.invoice("next").number)
        assertEquals("100.00", InvoiceStore.Snapshot.decode(repository.invoice("next").payload).total)
    }
    @Test fun existingIncompleteTaskCanBeLinkedWithoutFabricatingCompletionAndNeverBilledAgain() {
        task("old-task")
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        assertNull(reconcile(draft(tasks = listOf("old-task"))).conflict)
        assertNull(repository.boardSnapshot(job).board.cards.single().completedMillis)
        task("old-task", true)
        assertEquals("NO_BILLABLE_ITEMS", repository.prepareInvoice("again", version, "again", selected).conflict)
        assertEquals("legacy", repository.legacyInvoiceContext("legacy").context.tasks.single().invoiceId)
    }
    @Test fun confirmationNoteAndEmptySourceAcknowledgmentAreRequiredWithoutSideEffects() {
        val before = version
        assertEquals("LEGACY_CONFIRMATION_REQUIRED", reconcile(draft().copy(confirmed = false)).conflict)
        assertEquals("LEGACY_REVIEW_NOTE_REQUIRED", reconcile(draft().copy(note = "short", noSources = true)).conflict)
        assertEquals("LEGACY_SOURCE_CHOICE_REQUIRED", reconcile(draft()).conflict)
        assertEquals(before, version); assertTrue(repository.legacyInvoiceContext("legacy").context.legacy)
        assertNull(reconcile(draft().copy(noSources = true, note = "Original source records were removed before migration; none can be linked.")).conflict)
        assertFalse(repository.legacyInvoiceContext("legacy").context.legacy)
        inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1001L, it.getLong(0)) } }
    }
    @Test fun malformedAndOverlappingRangesAreRejectedBeforeAnyClaims() {
        val invalid = listOf(
            listOf(LegacyInvoiceStore.Span(midnight, midnight, "UTC")),
            listOf(LegacyInvoiceStore.Span(midnight + 1, midnight, "UTC")),
            listOf(LegacyInvoiceStore.Span(Long.MIN_VALUE, Long.MAX_VALUE, "UTC")),
            listOf(LegacyInvoiceStore.Span(midnight, midnight + hour, "Not/AZone")),
            listOf(LegacyInvoiceStore.Span(midnight, midnight + hour, "UTC"), LegacyInvoiceStore.Span(midnight + 1, midnight + 2 * hour, "UTC")),
        )
        val before = version
        for (spans in invalid) assertEquals("INVALID_COVERED_TIME", reconcile(draft(spans = spans)).conflict)
        assertEquals(before, version)
        assertTrue(repository.legacyInvoiceContext("legacy").context.reservedTimes.isEmpty())
    }
    @Test fun wrongJobMissingAndRepeatedTaskIdentitiesCannotBeClaimed() {
        val other = repository.createJob("Synthetic other job")
        val board = repository.boardSnapshot(other).board
        repository.saveCard("other-task", version, BoardStore.Draft("other-task", other, board.columns.first().id, "Synthetic unrelated task", currency = "USD"))
        task("here")
        val before = version
        assertEquals("INVOICE_SOURCE_UNAVAILABLE", reconcile(draft(tasks = listOf("other-task"))).conflict)
        assertEquals("INVOICE_SOURCE_UNAVAILABLE", reconcile(draft(tasks = listOf("missing"))).conflict)
        assertEquals("INVALID_INVOICE_SOURCES", reconcile(draft(tasks = listOf("here", "here"))).conflict)
        assertEquals("LEGACY_SOURCE_CHOICE_REQUIRED", reconcile(draft(tasks = listOf("here")).copy(noSources = true)).conflict)
        assertEquals(before, version)
    }
    @Test fun alreadyReservedWorkConflictsWithoutPartiallyClaimingOtherValidTasks() {
        work(); task("not-yet-claimed")
        inspect { db ->
            db.execSQL("INSERT INTO invoices(id,job_id,number,created_ms,payload) SELECT 'other',job_id,'INV-999',created_ms,payload FROM invoices WHERE id='legacy'")
            db.execSQL("INSERT INTO invoice_coverage(invoice_id,state) VALUES('other','COMPLETE')")
            InvoiceCoverageStore.reserveTime(db, "other", job, "work", midnight, midnight + hour, "UTC", midnight)
        }
        val before = version
        assertEquals("INVOICE_SOURCE_ALREADY_CLAIMED", reconcile(draft(tasks = listOf("not-yet-claimed"), spans = listOf(LegacyInvoiceStore.Span(midnight, midnight + hour, "UTC")))).conflict)
        assertEquals(before, version)
        inspect { db -> db.rawQuery("SELECT COUNT(*) FROM invoice_task_claims", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) } }
    }
    @Test fun lostReplyReplaysOriginalReviewAndDifferentRequestCannotReconcileTwice() {
        task("task")
        val value = draft(tasks = listOf("task")); val captured = version
        val first = reconcile(value, "same", captured)
        assertNull(first.conflict); assertEquals(first, reconcile(value, "same", captured))
        assertEquals("COMMAND_ID_REUSED", reconcile(value.copy(note = "Different review note for the same command."), "same", captured).conflict)
        assertEquals("INVOICE_ALREADY_RECONCILED", reconcile(value).conflict)
        inspect { db -> db.rawQuery("SELECT COUNT(*) FROM invoice_task_claims", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) } }
        assertNull(repository.archiveJob("archive-reviewed", version, job).conflict)
        now += JobLifecycleStore.retentionMillis
        assertNull(repository.deleteJob("purge-reviewed", version, job).conflict)
        assertEquals(first, reconcile(value, "same", captured))
        assertFalse(repository.legacyInvoiceContext("legacy").context.legacy)
        assertTrue(repository.legacyInvoiceContext("legacy").context.jobDeleted)
    }
    @Test fun failedSecondReservationRollsBackClaimsReviewVersionAndReceipt() {
        task("task-a"); task("task-b")
        inspect { it.execSQL("CREATE TRIGGER fail_review BEFORE INSERT ON invoice_task_claims WHEN NEW.task_id='task-b' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        val before = version
        try { reconcile(draft(tasks = listOf("task-a", "task-b")), "failed"); fail("Partial reconciliation committed") } catch (_: android.database.SQLException) {}
        assertEquals(before, version); assertTrue(repository.legacyInvoiceContext("legacy").context.legacy)
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM invoice_task_claims", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT id FROM commands WHERE id='failed'", null).use { assertFalse(it.moveToFirst()) }
            db.rawQuery("SELECT id FROM entity_revisions WHERE kind='invoice_task_claim'", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun concurrentReviewsCommitOneOutcomeAndOneReservation() {
        task("task"); val captured = version; val value = draft(tasks = listOf("task"))
        val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1)
        try {
            val futures = (1..2).map { n -> pool.submit<TimerRepository.Outcome> { gate.await(); reconcile(value, "race-$n", captured) } }
            gate.countDown(); val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.conflict == null }); assertEquals(1, results.count { it.conflict == "STALE_STATE" })
            inspect { db -> db.rawQuery("SELECT COUNT(*) FROM invoice_task_claims", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) } }
        } finally { pool.shutdownNow() }
    }
    @Test fun legacyTimeReservationAlsoFencesEditsOfMatchingLiveSessions() {
        work(end = midnight + hour)
        assertNull(reconcile(draft(spans = listOf(LegacyInvoiceStore.Span(midnight, midnight + hour, "UTC")))).conflict)
        assertEquals("INVOICED_SESSION_BOUNDARY", repository.correctSession("move", version,
            SessionHistoryStore.Draft("work", job, midnight + 2 * hour, midnight + 3 * hour, "UTC", 0)).conflict)
        assertNull(repository.correctSession("extend", version,
            SessionHistoryStore.Draft("work", job, midnight, midnight + 2 * hour, "UTC", 0)).conflict)
        assertNull(repository.prepareInvoice("new", version, "new", selected).conflict)
        assertEquals("100.00", InvoiceStore.Snapshot.decode(repository.invoice("new").payload).total)
    }
    @Test fun reviewTransportListsIncompleteAndCompletedTasksWithoutMutatingThem() {
        task("unfinished"); task("finished", true)
        val before = version
        val result = BusinessDispatch(repository).call(MethodCall("legacyInvoiceContext", mapOf("invoiceId" to "legacy"))) as Map<*, *>
        assertEquals(before, result["version"]); assertEquals(true, result["legacy"]); assertEquals("INV-1000", result["number"])
        val tasks = (result["tasks"] as List<*>).map { it as Map<*, *> }
        assertEquals(2, tasks.size)
        assertNull(tasks.single { it["id"] == "unfinished" }["completedLabel"])
        assertNotNull(tasks.single { it["id"] == "finished" }["completedLabel"])
        assertEquals(before, version)
    }
    @Test fun malformedTransportCannotCoerceFloatingTimesOrNumericTaskIds() {
        val dispatch = BusinessDispatch(repository); val before = version
        val base = mapOf<String, Any?>("invoiceId" to "legacy", "taskIds" to emptyList<String>(), "note" to note, "confirmed" to true, "noSources" to false)
        val invalid = listOf(
            base + ("spans" to listOf(mapOf("start" to midnight.toDouble(), "end" to midnight + hour, "timezone" to "UTC"))),
            base + mapOf("taskIds" to listOf(123), "spans" to emptyList<Any>()),
            base + mapOf("confirmed" to "true", "spans" to emptyList<Any>()),
        )
        for (value in invalid) {
            try { dispatch.call(MethodCall("reconcileInvoiceCoverage", mapOf("id" to "bad", "version" to before, "value" to value))); fail("Malformed source accepted") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(before, version); assertTrue(repository.legacyInvoiceContext("legacy").context.legacy)
    }
}
