package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
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
class BusinessSchemaTest {
    private lateinit var repository: TimerRepository
    private var clock = 1_000_000L
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { app.deleteDatabase("clockrove.db"); open() }
    @After fun close() { repository.close() }
    private fun open() { repository = TimerRepository(app, wallClock = { clock }) }
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun number(db: SQLiteDatabase, sql: String, vararg args: String): Long = db.rawQuery(sql, args.toList().toTypedArray()).use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun command(id: String, job: String, action: TimerRepository.Action, at: Long) = TimerRepository.Command(
        id, repository.snapshot().version, action, job, at, "Etc/UTC", 0, TimerRepository.Origin.MANUAL)
    private fun legacy() {
        repository.close(); app.deleteDatabase("clockrove.db")
        inspectCreate { db ->
            javaClass.getResourceAsStream("/schema_v4.sql")!!.bufferedReader().use { input ->
                input.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
            }
            db.execSQL("INSERT INTO jobs VALUES('old-job','Synthetic active',0)")
            db.execSQL("INSERT INTO jobs VALUES('old-archive','Synthetic archived',1)")
            db.execSQL("INSERT INTO sessions VALUES('old-session','old-job',1000,NULL,'Etc/UTC',0,'MANUAL')")
            db.execSQL("INSERT INTO location_points VALUES('old-point','old-session',1500,1600,10.0,20.0,25.0)")
            db.execSQL("INSERT INTO pending_departure VALUES('old-session',2000,1802000)")
            db.execSQL("INSERT INTO automation_policies(job_id,enabled,auto_start,configured_ms,facts_since_ms) VALUES('old-job',1,1,1000,1000)")
            db.execSQL("INSERT INTO work_sites(id,job_id,name,latitude,longitude,radius,enabled,registration_key) VALUES('old-site','old-job','Synthetic place',10.0,20.0,150,1,'old-registration')")
            db.execSQL("UPDATE state SET version=12")
            db.execSQL("INSERT INTO commands VALUES('old-command',?,12,NULL)", arrayOf(JSONArray(listOf(11, "START", "old-job", 1000, "Etc/UTC", 0, "MANUAL")).toString()))
            db.version = 4
        }
    }
    private fun inspectCreate(block: (SQLiteDatabase) -> Unit) {
        app.getDatabasePath("clockrove.db").parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("clockrove.db"), null).use(block)
    }

    @Test fun newJobHasARealThreeColumnBoardAndDefaultPricingWithoutInventedRates() {
        val job = repository.createJob("Synthetic new board")
        inspect { db ->
            assertEquals(5, db.version)
            db.rawQuery("SELECT name,position,completed FROM kanban_columns WHERE job_id=? ORDER BY position", arrayOf(job)).use {
                val names = mutableListOf<String>(); var index = 0
                while (it.moveToNext()) { names.add(it.getString(0)); assertEquals(index, it.getInt(1)); assertEquals(if (index == 2) 1 else 0, it.getInt(2)); index++ }
                assertEquals(listOf("To do", "Doing", "Done"), names)
            }
            db.rawQuery("SELECT j.billing_mode,j.hourly_rate_minor,j.currency,s.currency,s.default_hourly_minor FROM jobs j CROSS JOIN app_settings s WHERE j.id=?", arrayOf(job)).use {
                assertTrue(it.moveToFirst()); assertEquals("HOURLY", it.getString(0)); assertTrue(it.isNull(1))
                assertEquals(it.getString(2), it.getString(3)); assertTrue(it.isNull(4))
            }
            assertEquals(3L, number(db, "SELECT COUNT(*) FROM entity_heads WHERE kind='column'"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM entity_heads WHERE kind='job' AND entity_id=?", job))
        }
    }

    @Test fun versionFourUpgradePreservesRealTrackingAndStartsUnknownArchiveAgeAtMigration() {
        legacy(); open()
        assertEquals(12L, repository.snapshot().version)
        assertEquals("old-session", repository.snapshot().running!!.id)
        assertEquals(listOf("old-point"), repository.locationPoints("old-session").map { it.id })
        assertEquals(TimerRepository.Departure("old-session", 2000, 1802000), repository.pendingDeparture())
        assertEquals("old-registration", repository.sites().single().registrationKey)
        assertNull(repository.executeFromSurface("old-command", 11, TimerRepository.Action.START, "old-job", TimerRepository.Origin.MANUAL).conflict)
        inspect { db ->
            assertEquals(clock, number(db, "SELECT archived_ms FROM jobs WHERE id='old-archive'"))
            assertEquals(6L, number(db, "SELECT COUNT(*) FROM kanban_columns"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM entity_heads WHERE kind='session' AND entity_id='old-session'"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM entity_heads WHERE kind='automation' AND entity_id='old-job'"))
            db.rawQuery("SELECT payload FROM entity_revisions WHERE kind='site'", null).use {
                assertTrue(it.moveToFirst()); val payload = JSONObject(it.getString(0))
                assertEquals(10.0, payload.getDouble("latitude"), 0.0); assertFalse(payload.has("registration_key")); assertFalse(payload.has("observed_ms"))
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun failedBoardSeedingRollsBackTheWholeJobAndItsRevisionMetadata() {
        val before = repository.snapshot()
        val revisions = inspect { number(it, "SELECT COUNT(*) FROM entity_revisions") }
        inspect { it.execSQL("CREATE TRIGGER fail_default_column BEFORE INSERT ON kanban_columns WHEN NEW.name='Doing' BEGIN SELECT RAISE(ABORT,'synthetic seed failure'); END") }
        try { repository.createJob("Must roll back"); fail("Partial job creation succeeded") } catch (_: android.database.SQLException) {}
        assertEquals(before, repository.snapshot())
        inspect { db ->
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM jobs")); assertEquals(0L, number(db, "SELECT COUNT(*) FROM kanban_columns"))
            assertEquals(revisions, number(db, "SELECT COUNT(*) FROM entity_revisions"))
        }
    }

    @Test fun sessionFinalizationAddsAncestryAndDuplicateCommandsDoNotAddRevisions() {
        val job = repository.createJob("Synthetic revised work")
        repository.execute(command("start", job, TimerRepository.Action.START, 1000))
        val session = repository.snapshot().running!!.id
        val stop = command("stop", job, TimerRepository.Action.STOP, 3000)
        val outcome = repository.execute(stop)
        assertEquals(outcome, repository.execute(stop))
        inspect { db ->
            assertEquals(2L, number(db, "SELECT COUNT(*) FROM entity_revisions WHERE kind='session' AND entity_id=?", session))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM revision_parents p JOIN entity_revisions r ON r.id=p.revision_id WHERE r.kind='session' AND r.entity_id=?", session))
            db.rawQuery("SELECT r.payload FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='session' AND h.entity_id=?", arrayOf(session)).use {
                assertTrue(it.moveToFirst()); assertEquals(3000L, JSONObject(it.getString(0)).getLong("end_ms"))
            }
        }
    }

    @Test fun automaticRetrospectiveEndRecordsTheActualExitInTheRevision() {
        val job = repository.createJob("Synthetic retrospective work")
        repository.execute(command("start", job, TimerRepository.Action.START, 1000))
        val session = repository.snapshot().running!!.id
        repository.beginDeparture(session, 2000, 0)
        assertEquals(TimerRepository.DepartureResult.FINALIZED, repository.finalizeDeparture(session, 9000))
        inspect { db -> db.rawQuery("SELECT r.payload FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='session' AND h.entity_id=?", arrayOf(session)).use {
            assertTrue(it.moveToFirst()); assertEquals(2000L, JSONObject(it.getString(0)).getLong("end_ms"))
        } }
    }

    @Test fun siteEditsAndDeletionHaveStableAncestryWithoutOperationalLocationFacts() {
        val job = repository.createJob("Synthetic site revisions")
        val site = GeofenceStore.Site("site", job, "Synthetic place", 10.0, 20.0)
        assertNull(repository.saveSite("create", repository.snapshot().version, site).conflict)
        assertNull(repository.saveSite("same", repository.snapshot().version, site).conflict)
        assertNull(repository.saveSite("rename", repository.snapshot().version, site.copy(name = "Synthetic renamed")).conflict)
        assertNull(repository.removeSite("remove", repository.snapshot().version, site.id).conflict)
        inspect { db ->
            assertEquals(3L, number(db, "SELECT COUNT(*) FROM entity_revisions WHERE kind='site' AND entity_id='site'"))
            assertEquals(1L, number(db, "SELECT r.deleted FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='site' AND h.entity_id='site'"))
        }
    }

    @Test fun schemaRejectsFloatingMoneyAndCrossJobCardPlacement() {
        val first = repository.createJob("Synthetic first")
        val second = repository.createJob("Synthetic second")
        inspect { db ->
            db.setForeignKeyConstraintsEnabled(true)
            val column = db.rawQuery("SELECT id FROM kanban_columns WHERE job_id=? LIMIT 1", arrayOf(first)).use { assertTrue(it.moveToFirst()); it.getString(0) }
            try {
                db.execSQL("INSERT INTO kanban_cards(id,job_id,column_id,title,position,currency) VALUES('bad',?,?, 'Wrong job',0,'USD')", arrayOf(second, column))
                fail("Cross-job card accepted")
            } catch (_: android.database.SQLException) {}
            try { db.execSQL("UPDATE app_settings SET default_hourly_minor=1.5"); fail("Floating money accepted") }
            catch (_: android.database.SQLException) {}
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM kanban_cards"))
        }
    }

    @Test fun failedVersionFiveMigrationLeavesVersionFourDataAndSchemaUntouched() {
        legacy()
        inspect { it.execSQL("CREATE TABLE customers(id TEXT PRIMARY KEY)") }
        try { open(); repository.snapshot(); fail("Invalid migration succeeded") } catch (_: android.database.SQLException) {}
        inspect { db ->
            assertEquals(4, db.version)
            assertEquals(2L, number(db, "SELECT COUNT(*) FROM jobs"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM location_points"))
            db.rawQuery("PRAGMA table_info(jobs)", null).use { rows ->
                val names = buildSet { while (rows.moveToNext()) add(rows.getString(1)) }
                assertFalse("archived_ms" in names)
            }
        }
    }
}
