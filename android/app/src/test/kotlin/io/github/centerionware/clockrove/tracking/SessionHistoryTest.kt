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
@Config(sdk = [24])
class SessionHistoryTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var now = 10000L
    private var sequence = 0
    private val version get() = repository.snapshot().version
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now }); job = repository.createJob("Synthetic history job")
    }
    @After fun close() { repository.close() }
    private fun draft(id: String = "entry", start: Long = 1000, end: Long = 2000, target: String = job) = SessionHistoryStore.Draft(id, target, start, end, "UTC", 0)
    private fun create(value: SessionHistoryStore.Draft = draft()) = repository.createManualSession("create-${sequence++}", version, value)
    private fun correct(value: SessionHistoryStore.Draft) = repository.correctSession("correct-${sequence++}", version, value)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun timer(action: TimerRepository.Action, at: Long, origin: TimerRepository.Origin = TimerRepository.Origin.MANUAL) =
        repository.execute(TimerRepository.Command("timer-${sequence++}", version, action, if (action == TimerRepository.Action.STOP) null else job, at, "UTC", 0, origin))
    private fun recordPoint(id: String, sessionId: String, at: Long) = repository.recordLocation(TimerRepository.LocationPoint(id, sessionId, at, at, 1.0, 2.0, 3.0))
    private fun observed(): String {
        assertNull(timer(TimerRepository.Action.START, 1000, TimerRepository.Origin.WIDGET).conflict)
        val session = repository.snapshot().running!!.id
        for (at in listOf(1100L, 1200L, 1800L, 1900L)) assertEquals(TimerRepository.SampleResult.RECORDED, recordPoint("point-$at", session, at))
        assertNull(timer(TimerRepository.Action.STOP, 2000).conflict)
        return session
    }
    @Test fun manualEntriesStoreActualContextAndAncestryWithoutStartingWorkOrInventingGps() {
        assertNull(create().conflict); assertNull(repository.snapshot().running)
        val entry = repository.sessionHistory(job).page.entries.single()
        assertEquals("MANUAL", entry.origin); assertEquals(1000L, entry.start); assertEquals(2000L, entry.end)
        assertEquals("UTC", entry.timezone); assertEquals(0, entry.offsetSeconds); assertFalse(entry.needsReview)
        assertTrue(repository.locationPoints(entry.id).isEmpty())
        val original = repository.sessionRevisions(job, entry.id).single()
        assertEquals("MANUAL_ENTRY", original.origin); assertTrue(original.parents.isEmpty())
        now = 1 // Revision ancestry, not the wall-clock sort order, establishes history.
        assertNull(correct(draft(end = 2200)).conflict)
        val revisions = repository.sessionRevisions(job, entry.id)
        assertEquals(2, revisions.size); assertEquals(listOf(original.id), revisions.first().parents)
        assertEquals("MANUAL_CORRECTION", revisions.first().origin); assertEquals(1L, revisions.first().changed)
    }
    @Test fun zeroNegativeAndOverflowDurationsAndInvalidTimezoneOffsetsAreRejected() {
        for (value in listOf(draft(end = 1000), draft(end = 999), draft(start = Long.MIN_VALUE, end = Long.MAX_VALUE))) assertEquals("INVALID_BOUNDARY", create(value).conflict)
        assertEquals("INVALID_TIMEZONE", create(draft().copy(timezone = "Invented/Zone")).conflict)
        assertEquals("INVALID_TIMEZONE", create(draft().copy(offsetSeconds = 3600)).conflict)
        assertTrue(repository.sessionHistory(job).page.entries.isEmpty())
        assertNull(create(draft(start = -2000, end = -1000)).conflict)
    }
    @Test fun globalHalfOpenOverlapChecksAllowAdjacentEntriesAcrossJobs() {
        assertNull(create().conflict)
        val other = repository.createJob("Synthetic second job")
        assertEquals("OVERLAP", create(draft("overlap", 1999, 3000, other)).conflict)
        assertNull(create(draft("adjacent", 2000, 3000, other)).conflict)
        assertEquals("OVERLAP", correct(draft(end = 2001)).conflict)
        assertEquals(2000L, repository.sessionHistory(job).page.entries.single().end)
    }
    @Test fun openSessionIsNeverEditedDeletedOrOverlapped() {
        assertNull(timer(TimerRepository.Action.START, 2000).conflict)
        val running = repository.snapshot().running!!
        assertEquals("STOP_SESSION_BEFORE_EDIT", correct(draft(running.id, 2000, 3000)).conflict)
        assertEquals("STOP_SESSION_BEFORE_EDIT", repository.deleteSession("remove", version, running.id).conflict)
        assertEquals("OVERLAP", create(draft(end = 2001)).conflict)
        assertNull(create().conflict); assertEquals(running, repository.snapshot().running)
    }
    @Test fun shorteningPrunesBothEndsWithDeletionRevisionsAndExtensionAddsNoPoints() {
        val id = observed()
        assertNull(correct(draft(id, 1200, 1800)).conflict)
        assertEquals(listOf(1200L, 1800L), repository.locationPoints(id).map { it.measuredUtcMillis })
        inspect { db -> db.rawQuery("SELECT entity_id,deleted FROM entity_revisions WHERE kind='location' ORDER BY entity_id", null).use {
            assertTrue(it.moveToNext()); assertEquals("point-1100", it.getString(0)); assertEquals(1, it.getInt(1))
            assertTrue(it.moveToNext()); assertEquals("point-1900", it.getString(0)); assertFalse(it.moveToNext())
        } }
        assertNull(correct(draft(id, 900, 2200)).conflict)
        assertEquals(2, repository.locationPoints(id).size)
        val revisions = repository.sessionRevisions(job, id)
        assertTrue(revisions.any { it.payload?.contains("WIDGET") == true })
        assertEquals("MANUAL", repository.sessionHistory(job).page.entries.single().origin)
    }
    @Test fun deletionRetainsRevisionChainAndFenceAndOriginalCommandReplay() {
        val id = observed(); val previous = repository.sessionRevisions(job, id).first()
        val v = version; val removed = repository.deleteSession("remove", v, id)
        assertNull(removed.conflict); assertEquals(removed, repository.deleteSession("remove", v, id))
        assertTrue(repository.sessionHistory(job).page.entries.isEmpty()); assertTrue(repository.locationPoints(id).isEmpty())
        val deletion = repository.sessionRevisions(job, id).first()
        assertTrue(deletion.deleted); assertEquals("MANUAL_DELETE", deletion.origin); assertEquals(listOf(previous.id), deletion.parents)
        assertEquals("SESSION_ID_EXISTS", create(draft(id)).conflict)
        assertEquals("COMMAND_ID_REUSED", repository.deleteSession("remove", v, "different").conflict)
        inspect { db -> db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) } }
    }
    @Test fun correctingOldWorkPreservesCurrentSameJobDepartureAndRegistration() {
        assertNull(create().conflict)
        assertNull(repository.saveSite("site", version, GeofenceStore.Site("site", job, "Synthetic site", 1.0, 2.0, 150.0, true)).conflict)
        assertNull(repository.saveAutomation("policy", version, GeofenceStore.Settings(job, true, false, true, 30)).conflict)
        val key = repository.automationSnapshot(job).sites.single().registrationKey
        assertNull(timer(TimerRepository.Action.START, 3000).conflict)
        val running = repository.snapshot().running!!
        repository.beginDeparture(running.id, 4000, 30)
        val pending = repository.pendingDeparture()
        assertNull(correct(draft(start = 1100, end = 1900)).conflict)
        assertEquals(running, repository.snapshot().running); assertEquals(pending, repository.pendingDeparture())
        assertEquals(key, repository.automationSnapshot(job).sites.single().registrationKey)
    }
    @Test fun manualCorrectionResolvesOnlyItsOwnHistoricalAutomationConflict() {
        create()
        inspect { db ->
            db.execSQL("INSERT INTO finalized_departures(session_id,detected_ms,deadline_ms) VALUES('entry',1800,2000)")
            GeofenceStore.conflict(db, "entry", 1900, "REENTRY_AFTER_FINALIZATION", true)
        }
        assertTrue(repository.sessionHistory(job).page.entries.single().needsReview)
        assertNull(correct(draft()).conflict)
        assertFalse(repository.sessionHistory(job).page.entries.single().needsReview)
        inspect { db -> for (table in listOf("finalized_departures", "geofence_conflicts")) db.rawQuery("SELECT session_id FROM $table WHERE session_id='entry'", null).use { assertFalse(it.moveToFirst()) } }
    }
    @Test fun unexpectedFailureRollsBackPruningRevisionsVersionAndReceipt() {
        val id = observed(); val v = version; val revisions = repository.sessionRevisions(job, id)
        inspect { db -> db.execSQL("CREATE TRIGGER fail_correction BEFORE UPDATE ON sessions BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        try { repository.correctSession("failed", v, draft(id, 1200, 1800)); fail("Unexpected success") } catch (_: android.database.SQLException) {}
        assertEquals(v, version); assertEquals(4, repository.locationPoints(id).size); assertEquals(revisions, repository.sessionRevisions(job, id))
        inspect { db -> db.rawQuery("SELECT id FROM commands WHERE id='failed'", null).use { assertFalse(it.moveToFirst()) }; db.execSQL("DROP TRIGGER fail_correction") }
        assertNull(repository.correctSession("failed", v, draft(id, 1200, 1800)).conflict)
    }
    @Test fun concurrentOverlappingCommandsSerializeAndDuplicateCorrectionCommitsOnce() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            var gate = CountDownLatch(1); val createVersion = version
            val creates = (1..2).map { n -> pool.submit<TimerRepository.Outcome> { gate.await(); repository.createManualSession("parallel-$n", createVersion, draft("entry-$n")) } }
            gate.countDown(); val outcomes = creates.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.conflict == null }); assertEquals(1, outcomes.count { it.conflict == "STALE_STATE" })
            val entry = repository.sessionHistory(job).page.entries.single(); val v = version
            gate = CountDownLatch(1)
            val changes = (1..2).map { pool.submit<TimerRepository.Outcome> { gate.await(); repository.correctSession("same-correction", v, draft(entry.id, 1100, 1900)) } }
            gate.countDown(); val results = changes.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1]); assertNull(results[0].conflict)
            assertEquals(2, repository.sessionRevisions(job, entry.id).size); assertEquals(v + 1, version)
        } finally { pool.shutdownNow() }
    }
    @Test fun historyPaginationUsesStableCursorAndIncludesArchivedJobs() {
        for (n in 1..3) assertNull(create(draft("entry-$n", n * 1000L, n * 1000L + 500)).conflict)
        repository.archiveJob("archive", version, job)
        val first = repository.sessionHistory(job, limit = 2).page
        assertEquals(listOf("entry-3", "entry-2"), first.entries.map { it.id }); assertNotNull(first.next)
        val second = repository.sessionHistory(job, first.next, 2).page
        assertEquals(listOf("entry-1"), second.entries.map { it.id }); assertNull(second.next)
        assertNull(correct(draft("entry-1", 1000, 1400)).conflict)
    }
    @Test fun editsRejectCrossJobIdentityAndMissingSessionWithoutWritingAnything() {
        create(); val other = repository.createJob("Synthetic other job")
        assertEquals("SESSION_JOB_IMMUTABLE", correct(draft(target = other)).conflict)
        assertEquals("SESSION_UNAVAILABLE", correct(draft("missing")).conflict)
        assertEquals("JOB_UNAVAILABLE", create(draft("unknown", target = "missing-job")).conflict)
        assertEquals(1, repository.sessionHistory(job).page.entries.size)
    }
    @Test fun dstRepeatedHourKeepsExplicitOffsetContextAndUtcDuration() {
        val first = 1793511000000L // 2026-11-01 05:30Z, first 01:30 in New York.
        val second = first + 3600000L
        assertNull(create(draft(start = first, end = second).copy(timezone = "America/New_York", offsetSeconds = -14400)).conflict)
        assertEquals("INVALID_TIMEZONE", correct(draft(start = second, end = second + 3600000).copy(timezone = "America/New_York", offsetSeconds = -14400)).conflict)
        assertNull(correct(draft(start = second, end = second + 3600000).copy(timezone = "America/New_York", offsetSeconds = -18000)).conflict)
        assertEquals(3600000L, repository.sessionHistory(job).page.entries.single().let { it.end!! - it.start })
    }
    @Test fun preparedInvoiceRemainsImmutableWhenSourceSessionIsCorrectedOrDeleted() {
        val start = 1793491200000L
        repository.saveBusinessSettings("rate", version, repository.businessSnapshot().settings.copy(currency = "USD", defaultHourlyMinor = 10000))
        repository.saveJobSettings("currency", version, repository.jobDetail(job).job.settings.copy(currency = "USD", hourlyRateMinor = 10000))
        assertNull(create(draft(start = start, end = start + 3600000)).conflict)
        val selection = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
        assertNull(repository.prepareInvoice("prepare", version, "invoice", selection).conflict)
        val payload = repository.invoice("invoice").payload
        assertNull(correct(draft(start = start, end = start + 7200000)).conflict)
        assertEquals("100.00", InvoiceStore.Snapshot.decode(payload).total)
        assertEquals(java.math.BigInteger.valueOf(20000), repository.report(selection).report.totals.single().amountMinor)
        assertNull(repository.deleteSession("remove", version, "entry").conflict)
        assertEquals(payload, repository.invoice("invoice").payload)
    }
}
