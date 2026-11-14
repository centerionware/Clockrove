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
class LocationRecordingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private var sequence = 0
    private val version get() = repository.snapshot().version

    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { 2_000_000 })
        repository.snapshot()
    }
    @After fun close() { repository.close() }

    @Test fun globalDefaultAndJobOverridesInheritAndPersist() {
        val first = repository.createJob("First synthetic location job")
        val second = repository.createJob("Second synthetic location job")
        assertTrue(repository.defaultLocationRecordingEnabled())
        assertTrue(repository.locationRecordingEnabled(first))
        assertTrue(repository.locationRecordingEnabled(second))

        val defaults = repository.businessSnapshot().settings.copy(defaultLocationRecording = false)
        assertNull(repository.saveBusinessSettings("gps-global-off", version, defaults).conflict)
        assertFalse(repository.defaultLocationRecordingEnabled())
        assertFalse(repository.locationRecordingEnabled(first))
        assertFalse(repository.locationRecordingEnabled(second))

        val firstSettings = repository.jobDetail(first).job.settings.copy(locationRecordingOverride = true)
        assertNull(repository.saveJobSettings("gps-job-on", version, firstSettings).conflict)
        assertTrue(repository.locationRecordingEnabled(first))
        assertFalse(repository.locationRecordingEnabled(second))

        val secondSettings = repository.jobDetail(second).job.settings.copy(locationRecordingOverride = false)
        assertNull(repository.saveJobSettings("gps-job-off", version, secondSettings).conflict)
        assertEquals(true, repository.jobDetail(first).job.settings.locationRecordingOverride)
        assertEquals(false, repository.jobDetail(second).job.settings.locationRecordingOverride)
        repository.close()
        repository = TimerRepository(app, wallClock = { 2_000_000 })
        repository.snapshot()
        assertFalse(repository.defaultLocationRecordingEnabled())
        assertTrue(repository.locationRecordingEnabled(first))
        assertFalse(repository.locationRecordingEnabled(second))
    }

    @Test fun disabledJobsRejectSamplesAndActiveSamplesHaveFiveMinuteFloor() {
        val job = repository.createJob("Synthetic throttled location job")
        assertNull(repository.execute(TimerRepository.Command("gps-start", version, TimerRepository.Action.START, job,
            1_000_000, "UTC", 0, TimerRepository.Origin.MANUAL)).conflict)
        val session = requireNotNull(repository.snapshot().running).id
        fun sample(id: String, measured: Long) = TimerRepository.LocationPoint(id, session, measured, measured + 100, 1.0, 2.0, 4.0)
        val inherited = repository.jobDetail(job).job.settings.copy(locationRecordingOverride = false)
        assertNull(repository.saveJobSettings("gps-job-disable", version, inherited).conflict)
        assertEquals(TimerRepository.SampleResult.RECORDING_DISABLED, repository.recordLocation(sample("job-disabled", 1_000_100)))
        assertNull(repository.saveJobSettings("gps-job-enable", version,
            repository.jobDetail(job).job.settings.copy(locationRecordingOverride = true)).conflict)
        assertEquals(TimerRepository.SampleResult.RECORDED, repository.recordLocation(sample("first", 1_100_100)))
        assertEquals(TimerRepository.SampleResult.THROTTLED, repository.recordLocation(sample("too-soon", 1_399_999)))
        assertEquals(TimerRepository.SampleResult.RECORDED, repository.recordLocation(sample("five-minutes", 1_400_100)))

        assertNull(repository.saveJobSettings("gps-job-inherit", version,
            repository.jobDetail(job).job.settings.copy(locationRecordingOverride = null)).conflict)
        val changed = repository.businessSnapshot().settings.copy(defaultLocationRecording = false)
        assertNull(repository.saveBusinessSettings("disable-gps", version, changed).conflict)
        assertEquals(TimerRepository.SampleResult.RECORDING_DISABLED, repository.recordLocation(sample("disabled", 1_700_200)))
        assertEquals(listOf("first", "five-minutes"), repository.locationPoints(session).map { it.id })
    }

    @Test fun v9ToV10UpgradeAddsPreferencesAndConversationWithoutLosingWork() {
        val job = repository.createJob("Migration location job")
        val board = repository.boardSnapshot(job).board
        assertNull(repository.saveCard("migration-card-save", version, BoardStore.Draft(
            "migration-card", job, board.columns.first().id, "Synthetic preserved task", currency = board.currency,
        )).conflict)
        repository.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE task_attachments")
            db.execSQL("DROP TABLE task_comment_events")
            db.execSQL("DROP TABLE task_comments")
            db.execSQL("DROP TABLE job_location_recording")
            db.execSQL("DROP TABLE location_recording_settings")
            db.execSQL("DROP TABLE job_invoice_billing_policy")
            db.execSQL("DROP TABLE invoice_billing_defaults")
            db.version = 9
        }
        repository = TimerRepository(app, wallClock = { 2_000_000 })
        assertTrue(repository.snapshot().jobs.any { it.id == job })
        assertEquals("Synthetic preserved task", repository.boardSnapshot(job).board.cards.single().title)
        assertTrue(repository.defaultLocationRecordingEnabled())
        assertTrue(repository.locationRecordingEnabled(job))
        assertTrue(repository.taskConversation(job, "migration-card").conversation.comments.isEmpty())
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            assertEquals(11, db.version)
        }
    }
}
