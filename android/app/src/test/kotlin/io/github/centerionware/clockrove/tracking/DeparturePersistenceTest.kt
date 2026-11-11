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
@Config(sdk = [24])
class DeparturePersistenceTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private lateinit var session: String
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("clockrove.db")
        repository = TimerRepository(context)
        job = repository.createJob("Synthetic departure job")
        repository.execute(command("start", TimerRepository.Action.START, 1000))
        session = repository.snapshot().running!!.id
    }
    @After fun teardown() { repository.close() }
    private fun command(id: String, action: TimerRepository.Action, at: Long) =
        TimerRepository.Command(id, repository.snapshot().version, action, job, at,
            "Etc/UTC", 0, TimerRepository.Origin.MANUAL)

    @Test fun deadlineSurvivesRecoveryAndFinalizesAtExitRatherThanDelayedWake() {
        repository.recordLocation(TimerRepository.LocationPoint("inside", session, 1500, 1600, 10.0, 20.0, 25.0))
        repository.recordLocation(TimerRepository.LocationPoint("travel", session, 3000, 3100, 10.1, 20.1, 25.0))
        assertEquals(TimerRepository.DepartureResult.RECORDED, repository.beginDeparture(session, 2000, 30))
        val expected = TimerRepository.Departure(session, 2000, 1_802_000)
        repository.close()
        repository = TimerRepository(RuntimeEnvironment.getApplication())
        assertEquals(expected, repository.pendingDeparture())
        assertEquals(TimerRepository.DepartureResult.NOT_DUE, repository.finalizeDeparture(session, 1_801_999))
        assertEquals(TimerRepository.DepartureResult.FINALIZED, repository.finalizeDeparture(session, 2_000_000))
        assertNull(repository.snapshot().running)
        assertEquals(listOf("inside"), repository.locationPoints(session).map { it.id })
        assertEquals(TimerRepository.DepartureResult.NO_PENDING, repository.finalizeDeparture(session, 2_000_001))
        repository.close()
        SQLiteDatabase.openOrCreateDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db"), null).use { db ->
            db.rawQuery("SELECT end_ms FROM sessions WHERE id=?", arrayOf(session)).use {
                assertTrue(it.moveToFirst()); assertEquals(2000L, it.getLong(0))
            }
        }
    }

    @Test fun duplicateExitDoesNotExtendGraceAndTimelyReentryCancels() {
        assertEquals(TimerRepository.DepartureResult.INVALID_BOUNDARY, repository.beginDeparture(session, 1000, 30))
        repository.beginDeparture(session, 2000, 30)
        assertEquals(TimerRepository.DepartureResult.ALREADY_PENDING, repository.beginDeparture(session, 5000, 1440))
        assertEquals(2000L, repository.pendingDeparture()!!.detectedUtcMillis)
        assertEquals(TimerRepository.DepartureResult.CANCELLED, repository.reenterBeforeDeadline(session, 1_801_999))
        assertEquals(TimerRepository.DepartureResult.NO_PENDING, repository.finalizeDeparture(session, 2_000_000))
        assertNotNull(repository.snapshot().running)
        repository.beginDeparture(session, 3000, 0)
        assertEquals(TimerRepository.DepartureResult.EXPIRED, repository.reenterBeforeDeadline(session, 3000))
        assertEquals(TimerRepository.DepartureResult.FINALIZED, repository.finalizeDeparture(session, 3000))
    }

    @Test fun manualStopClearsCandidateAndOldExpiryCannotCloseNewWork() {
        repository.beginDeparture(session, 2000, 30)
        repository.execute(command("manual-stop", TimerRepository.Action.STOP, 4000))
        repository.execute(command("manual-start", TimerRepository.Action.START, 5000))
        val manual = repository.snapshot()
        assertNull(repository.pendingDeparture())
        assertEquals(TimerRepository.DepartureResult.NO_PENDING, repository.finalizeDeparture(session, 2_000_000))
        assertEquals(TimerRepository.DepartureResult.STALE_SESSION, repository.beginDeparture(session, 6000, 30))
        assertEquals(manual, repository.snapshot())
    }

    @Test fun maximumGraceIsOneDayAndVersionTwoUpgradeKeepsMeasuredPoints() {
        repository.beginDeparture(session, 2000, 1440)
        assertEquals(86_402_000L, repository.pendingDeparture()!!.deadlineUtcMillis)
        try { repository.beginDeparture(session, 2000, 1441); fail("Invalid grace accepted") }
        catch (_: IllegalArgumentException) { }
        repository.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("clockrove.db")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("clockrove.db"), null).use { legacy ->
            javaClass.getResourceAsStream("/schema_v2.sql")!!.bufferedReader().use { input ->
                input.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { legacy.execSQL(it) }
            }
            legacy.execSQL("INSERT INTO jobs(id,name) VALUES ('old-job','Synthetic old')")
            legacy.execSQL("INSERT INTO sessions VALUES ('old-session','old-job',1000,NULL,'Etc/UTC',0,'MANUAL')")
            legacy.execSQL("INSERT INTO location_points VALUES ('old-point','old-session',1500,1600,10.0,20.0,25.0)")
            legacy.version = 2
        }
        repository = TimerRepository(context)
        assertEquals(listOf("old-point"), repository.locationPoints("old-session").map { it.id })
        assertEquals(TimerRepository.DepartureResult.RECORDED, repository.beginDeparture("old-session", 2000, 30))
    }
}
