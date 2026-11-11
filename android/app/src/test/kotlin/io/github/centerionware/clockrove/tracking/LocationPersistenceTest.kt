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
class LocationPersistenceTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private fun command(id: String, action: TimerRepository.Action, at: Long, selected: String? = job) =
        TimerRepository.Command(id, repository.snapshot().version, action, selected,
            at, "Etc/UTC", 0, TimerRepository.Origin.MANUAL)
    private fun point(id: String, session: String, at: Long) =
        TimerRepository.LocationPoint(id, session, at, at + 100, 10.0, 20.0, 12.5)

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("clockrove.db")
        repository = TimerRepository(context)
        job = repository.createJob("Synthetic location job")
    }
    @After fun teardown() { repository.close() }

    @Test fun pointsRequireActiveBoundSessionAndRemainImmutable() {
        assertEquals(TimerRepository.SampleResult.OUTSIDE_SESSION, repository.recordLocation(point("idle", "absent", 1200)))
        repository.execute(command("start", TimerRepository.Action.START, 1000))
        val session = repository.snapshot().running!!.id
        assertEquals(TimerRepository.SampleResult.OUTSIDE_SESSION, repository.recordLocation(point("old", session, 999)))
        val observed = point("actual", session, 1200)
        assertEquals(TimerRepository.SampleResult.RECORDED, repository.recordLocation(observed))
        val committed = repository.snapshot().version
        assertEquals(TimerRepository.SampleResult.DUPLICATE, repository.recordLocation(observed.copy(receivedUtcMillis = 1400)))
        assertEquals(TimerRepository.SampleResult.DUPLICATE, repository.recordLocation(observed))
        assertEquals(committed, repository.snapshot().version)
        assertEquals(TimerRepository.SampleResult.ID_CONFLICT, repository.recordLocation(observed.copy(latitude = 11.0)))
        assertEquals(listOf(observed), repository.locationPoints(session))
        listOf(observed.copy(id = "nan", latitude = Double.NaN),
            observed.copy(id = "range", longitude = 181.0),
            observed.copy(id = "accuracy", accuracyMeters = -1.0),
            observed.copy(id = "time", receivedUtcMillis = 1199)).forEach {
            assertEquals(TimerRepository.SampleResult.INVALID_SAMPLE, repository.recordLocation(it))
        }
    }

    @Test fun clockRewindStopPrunesFuturePointsAndLateCallbacksCannotLeaveIdleTrail() {
        repository.execute(command("start", TimerRepository.Action.START, 1000))
        val session = repository.snapshot().running!!.id
        val kept = point("kept", session, 1500)
        repository.recordLocation(kept)
        repository.recordLocation(point("beyond-end", session, 3000))
        assertNull(repository.execute(command("stop", TimerRepository.Action.STOP, 2000)).conflict)
        assertEquals(listOf(kept), repository.locationPoints(session))
        assertEquals(TimerRepository.SampleResult.OUTSIDE_SESSION,
            repository.recordLocation(point("late", session, 1800)))
        val other = repository.createJob("Synthetic other job")
        repository.execute(command("new-start", TimerRepository.Action.START, 4000, other))
        assertEquals(TimerRepository.SampleResult.OUTSIDE_SESSION,
            repository.recordLocation(point("old-session-callback", session, 4500)))
        assertTrue(repository.locationPoints(repository.snapshot().running!!.id).isEmpty())
    }

    @Test fun versionOneUpgradePreservesSessionAndCommandOutcome() {
        repository.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("clockrove.db")
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("clockrove.db"), null)
        javaClass.getResourceAsStream("/schema_v1.sql")!!.bufferedReader().use { input ->
            input.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { legacy.execSQL(it) }
        }
        legacy.execSQL("INSERT INTO jobs(id,name) VALUES ('legacy-job','Synthetic legacy')")
        legacy.execSQL("INSERT INTO sessions(id,job_id,start_ms,timezone,offset_seconds,origin) VALUES ('legacy-session','legacy-job',1000,'Etc/UTC',0,'MANUAL')")
        legacy.execSQL("UPDATE state SET version=5 WHERE id=1")
        val start = TimerRepository.Command("legacy-command", 4, TimerRepository.Action.START,
            "legacy-job", 1000, "Etc/UTC", 0, TimerRepository.Origin.MANUAL)
        val fingerprint = org.json.JSONArray(listOf<Any?>(4L, "START", "legacy-job", 1000L, "Etc/UTC", 0, "MANUAL")).toString()
        legacy.execSQL("INSERT INTO commands VALUES (?,?,5,NULL)", arrayOf("legacy-command", fingerprint))
        legacy.version = 1
        legacy.close()
        repository = TimerRepository(context)
        assertEquals("legacy-session", repository.snapshot().running!!.id)
        assertEquals(5L, repository.snapshot().version)
        assertEquals(TimerRepository.Outcome(5, null), repository.execute(start))
        val sample = point("migrated-point", "legacy-session", 1500)
        assertEquals(TimerRepository.SampleResult.RECORDED, repository.recordLocation(sample))
        repository.close()
        repository = TimerRepository(context)
        assertEquals(listOf(sample), repository.locationPoints("legacy-session"))
    }
    @Test fun failedUpgradeRollsBackCreatedTableAndKeepsLegacyVersion() {
        repository.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("clockrove.db")
        val path = context.getDatabasePath("clockrove.db")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            javaClass.getResourceAsStream("/schema_v1.sql")!!.bufferedReader().use { input ->
                input.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { legacy.execSQL(it) }
            }
            legacy.execSQL("INSERT INTO jobs(id,name) VALUES ('kept-job','Synthetic retained')")
            // Force failure after CREATE TABLE succeeds, while CREATE INDEX runs.
            legacy.execSQL("CREATE INDEX location_session_date ON jobs(name)")
            legacy.version = 1
        }
        repository = TimerRepository(context)
        try {
            repository.snapshot()
            fail("Conflicting migration must fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            assertEquals(1, legacy.version)
            legacy.rawQuery("SELECT name FROM jobs WHERE id='kept-job'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic retained", it.getString(0))
            }
            legacy.rawQuery("SELECT name FROM sqlite_master WHERE name='location_points'", null).use {
                assertFalse(it.moveToFirst())
            }
        }
    }

}
