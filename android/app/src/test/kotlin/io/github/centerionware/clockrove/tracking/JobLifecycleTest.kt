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
class JobLifecycleTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var now = 10000L
    private var sequence = 0
    private val version get() = repository.snapshot().version
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now }); job = repository.createJob("Synthetic private job")
    }
    @After fun close() { repository.close() }
    private fun archive() = repository.archiveJob("archive-${sequence++}", version, job)
    private fun restore() = repository.restoreJob("restore-${sequence++}", version, job)
    private fun remove() = repository.deleteJob("delete-${sequence++}", version, job)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun site() {
        assertNull(repository.saveSite("site", version, GeofenceStore.Site("site", job, "Synthetic private site", 1.123, 2.456, 150.0, true)).conflict)
        assertNull(repository.saveAutomation("policy", version, GeofenceStore.Settings(job, true, true, false, 47)).conflict)
    }
    @Test fun archiveRequiresClockOutAndPreservesHistory() {
        assertNull(repository.executeFromSurface("start", version, TimerRepository.Action.START, job, TimerRepository.Origin.MANUAL).conflict)
        assertEquals("STOP_JOB_BEFORE_ARCHIVE", archive().conflict)
        val start = repository.snapshot().running!!.startUtcMillis
        repository.execute(TimerRepository.Command("stop", version, TimerRepository.Action.STOP, null, start + 1000, "UTC", 0, TimerRepository.Origin.MANUAL))
        assertNull(archive().conflict); assertTrue(repository.snapshot().jobs.isEmpty())
        inspect { db -> db.rawQuery("SELECT COUNT(*) FROM sessions WHERE job_id=?", arrayOf(job)).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) } }
        assertEquals("JOB_UNAVAILABLE", repository.executeFromSurface("new-start", version, TimerRepository.Action.START, job, TimerRepository.Origin.MANUAL).conflict)
    }
    @Test fun archiveAndRestoreKeepPreferencesButRotateStaleRegistrationsAndRequireFreshEntry() {
        site()
        val key = repository.automationSnapshot(job).sites.single().registrationKey
        assertNull(archive().conflict)
        inspect { db ->
            assertTrue(GeofenceStore.sites(db, true).isEmpty()); assertTrue(GeofenceStore.policies(db).isEmpty())
            db.rawQuery("SELECT enabled,auto_start,automatic_departure,grace_minutes,armed FROM automation_policies WHERE job_id=?", arrayOf(job)).use {
                it.moveToFirst(); assertEquals(listOf(1, 1, 0, 47, 0), (0..4).map(it::getInt))
            }
        }
        now += 1000; assertNull(restore().conflict)
        val restored = repository.automationSnapshot(job)
        assertTrue(restored.settings.enabled); assertTrue(restored.settings.autoStart); assertFalse(restored.settings.automaticDeparture); assertEquals(47, restored.settings.graceMinutes)
        assertNotEquals(key, restored.sites.single().registrationKey); assertFalse(restored.armed); assertNull(repository.snapshot().running)
    }
    @Test fun deleteOnlyAtThirtyContinuousUtcDaysAndRestoreResetsTheClock() {
        assertEquals("ARCHIVE_RETENTION_REQUIRED", remove().conflict)
        assertNull(archive().conflict); val original = now
        now = original + JobLifecycleStore.retentionMillis - 1; assertEquals("ARCHIVE_RETENTION_REQUIRED", remove().conflict)
        assertFalse(repository.archivedJobs().single().canDelete)
        assertNull(restore().conflict); assertNull(archive().conflict); val second = now
        now = original + JobLifecycleStore.retentionMillis; assertEquals("ARCHIVE_RETENTION_REQUIRED", remove().conflict)
        now = second + JobLifecycleStore.retentionMillis; assertTrue(repository.archivedJobs().single().canDelete); assertNull(remove().conflict)
        assertTrue(repository.archivedJobs().isEmpty())
    }
    @Test fun clockRollbackAndUnknownArchiveDatesNeverGrantEarlyDeletion() {
        assertNull(archive().conflict); now = 9999
        assertEquals("ARCHIVE_RETENTION_REQUIRED", remove().conflict)
        inspect { it.execSQL("UPDATE jobs SET archived_ms=NULL WHERE id=?", arrayOf(job)) }
        now = Long.MAX_VALUE; assertEquals("ARCHIVE_RETENTION_REQUIRED", remove().conflict)
        assertFalse(JobLifecycleStore.eligible(true, null, now)); assertFalse(JobLifecycleStore.eligible(false, 0, now))
    }
    @Test fun templatesAndCustomersSurviveDeletionWithoutProvenanceOrWorkResurrection() {
        val board = repository.boardSnapshot(job).board
        repository.saveCustomer("customer", version, BusinessStore.Customer("customer", "Synthetic retained customer", "synthetic@example.test"))
        repository.saveJobSettings("assign", version, repository.jobDetail(job).job.settings.copy(customerId = "customer"))
        repository.saveCard("card", version, BoardStore.Draft("card", job, board.columns.last().id, "Synthetic reusable tile task", "Synthetic reusable details", "2026-11-30", true, 12500, board.currency))
        val template = repository.taskTemplates().single()
        inspect { it.execSQL("INSERT INTO invoices VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any?>("invoice", job, "customer", "1", now, "Synthetic private invoice", byteArrayOf(1,2,3), null)) }
        assertNull(archive().conflict); now += JobLifecycleStore.retentionMillis; assertNull(remove().conflict)
        assertEquals("customer", repository.businessSnapshot().customers.single().id)
        assertEquals(template.id, repository.taskTemplates().single().id); assertNull(repository.taskTemplates().single().sourceJobName)
        inspect { db ->
            for (table in listOf("jobs", "sessions", "location_points", "kanban_cards", "kanban_columns", "invoices", "work_sites", "automation_policies")) db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
            db.rawQuery("SELECT source_job_id,source_card_id FROM task_templates", null).use { it.moveToFirst(); assertTrue(it.isNull(0)); assertTrue(it.isNull(1)) }
            db.rawQuery("SELECT payload FROM entity_revisions WHERE job_id=? AND payload IS NOT NULL", arrayOf(job)).use { while (it.moveToNext()) assertFalse(it.getString(0).contains("Synthetic")) }
            db.rawQuery("SELECT payload FROM entity_revisions WHERE kind='template' AND entity_id=? AND payload IS NOT NULL", arrayOf(template.id)).use { while (it.moveToNext()) assertFalse(it.getString(0).contains(job)) }
            db.rawQuery("SELECT id FROM job_deletions", null).use { it.moveToFirst(); assertEquals(job, it.getString(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
        val other = repository.createJob("Synthetic new job"); val fresh = repository.boardSnapshot(other)
        assertNull(repository.copyTaskTemplate("copy", fresh.version, template.id, "fresh", other, fresh.board.columns.first().id, true).conflict)
        val copied = repository.boardSnapshot(other).board.cards.single()
        assertNull(copied.completedMillis); assertNull(copied.dueDate); assertEquals(12500L, copied.priceMinor); assertNull(repository.snapshot().running)
    }
    @Test fun deletingJobDoesNotRecreateAUserRemovedTemplate() {
        val board = repository.boardSnapshot(job).board
        repository.saveCard("card", version, BoardStore.Draft("card", job, board.columns.first().id, "Synthetic", currency = board.currency))
        repository.removeTaskTemplate("forget", version, repository.taskTemplates().single().id)
        archive(); now += JobLifecycleStore.retentionMillis; assertNull(remove().conflict)
        assertTrue(repository.taskTemplates().isEmpty())
        inspect { db -> db.rawQuery("SELECT payload FROM entity_revisions WHERE kind='template' AND payload IS NOT NULL", null).use { while (it.moveToNext()) assertFalse(it.getString(0).contains(job)) } }
    }
    @Test fun oldTimerAndConfigurationResponsesReplayAfterPrivatePayloadPurgeWithoutExecutingAgain() {
        site(); val siteVersion = inspect { db -> db.rawQuery("SELECT version FROM commands WHERE id='site'", null).use { it.moveToFirst(); it.getLong(0) - 1 } }
        val startVersion = version
        val start = repository.executeFromSurface("start", startVersion, TimerRepository.Action.START, job, TimerRepository.Origin.MANUAL)
        val running = repository.snapshot().running!!
        val stopCommand = TimerRepository.Command("stop", version, TimerRepository.Action.STOP, null, running.startUtcMillis + 1000, "UTC", 0, TimerRepository.Origin.MANUAL)
        val stop = repository.execute(stopCommand)
        archive(); now += JobLifecycleStore.retentionMillis; assertNull(remove().conflict)
        val after = version
        assertEquals(start, repository.executeFromSurface("start", startVersion, TimerRepository.Action.START, job, TimerRepository.Origin.MANUAL))
        assertEquals(stop, repository.execute(stopCommand))
        assertNull(repository.saveSite("site", siteVersion, GeofenceStore.Site("site", job, "Synthetic private site", 1.123, 2.456, 150.0, true)).conflict)
        assertEquals("COMMAND_ID_REUSED", repository.saveSite("site", siteVersion, GeofenceStore.Site("site", job, "Changed", 1.123, 2.456, 150.0, true)).conflict)
        assertEquals("COMMAND_ID_REUSED", repository.executeFromSurface("start", startVersion, TimerRepository.Action.START, "other", TimerRepository.Origin.MANUAL).conflict)
        assertEquals(after, version); assertNull(repository.snapshot().running)
        inspect { db -> db.rawQuery("SELECT fingerprint FROM commands", null).use { while (it.moveToNext()) { assertFalse(it.getString(0).contains("Synthetic")); assertFalse(it.getString(0).contains("1.123")); assertFalse(it.getString(0).contains("UTC")) } } }
    }
    @Test fun restoreCapacityFailureLeavesJobArchivedAndPreferencesUntouched() {
        site(); archive()
        val other = repository.createJob("Synthetic capacity job")
        inspect { db ->
            db.execSQL("INSERT INTO automation_policies(job_id,enabled,configured_ms,facts_since_ms) VALUES(?,1,0,0)", arrayOf(other))
            repeat(100) { index -> db.execSQL("INSERT INTO work_sites(id,job_id,name,latitude,longitude,radius,enabled,registration_key) VALUES(?,?,?,0,0,150,1,?)", arrayOf("capacity-$index", other, "Synthetic site", "key-$index")) }
        }
        assertEquals("SITE_CAPACITY_EXCEEDED", restore().conflict)
        assertTrue(repository.jobDetail(job).job.archived); assertEquals(1, repository.archivedJobs().size)
    }
    @Test fun deletionFailureRollsBackWorkRevisionsTemplatesAndReceipts() {
        site(); archive(); now += JobLifecycleStore.retentionMillis
        val before = version; val previous = repository.jobDetail(job)
        val fingerprints = inspect { db -> db.rawQuery("SELECT fingerprint FROM commands ORDER BY id", null).use { rows -> buildList { while(rows.moveToNext()) add(rows.getString(0)) } } }
        inspect { it.execSQL("CREATE TRIGGER fail_job_delete BEFORE DELETE ON jobs BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        try { repository.deleteJob("failed", version, job); fail("Delete succeeded") } catch (_: android.database.SQLException) {}
        assertEquals(before, version); assertEquals(previous, repository.jobDetail(job))
        inspect { db ->
            db.rawQuery("SELECT fingerprint FROM commands ORDER BY id", null).use { rows -> val actual = buildList { while(rows.moveToNext()) add(rows.getString(0)) }; assertEquals(fingerprints, actual) }
            db.rawQuery("SELECT COUNT(*) FROM job_deletions", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT id FROM commands WHERE id='failed'", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun concurrentDuplicateDeleteReturnsOneOutcomeAndOneDeletionFence() {
        archive(); now += JobLifecycleStore.retentionMillis; val expected = version
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val requests = (1..2).map { pool.submit<TimerRepository.Outcome> { start.await(); repository.deleteJob("same-delete", expected, job) } }
            start.countDown(); val outcomes = requests.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(outcomes[0], outcomes[1]); assertNull(outcomes[0].conflict)
            inspect { db -> db.rawQuery("SELECT COUNT(*) FROM job_deletions", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) } }
        } finally { pool.shutdownNow() }
    }
}
