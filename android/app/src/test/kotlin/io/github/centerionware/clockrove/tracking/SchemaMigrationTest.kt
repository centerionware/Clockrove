package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
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
@Config(sdk = [24, 35])
class SchemaMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var repository: TimerRepository? = null
    private val at = 1793494800000L

    @Before fun setup() { app.deleteDatabase("clockrove.db") }

    @After fun close() {
        repository?.close()
        repository = null
        app.deleteDatabase("clockrove.db")
    }

    @Test fun schemaSixteenUpgradeKeepsExistingGpsDeletionTableAndBackfillsPointRevisions() {
        val created = TimerRepository(app, wallClock = { at }).also { repository = it }
        val jobId = created.createJob("Synthetic migration job")
        created.close()
        repository = null

        SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            // A schema-16 database already owns the GPS deletion table. The
            // point predates coordinate-free revision identities.
            assertEquals(
                1,
                db.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='location_deletion_ranges'",
                    null,
                ).use { it.moveToFirst(); it.getInt(0) },
            )
            db.execSQL(
                "INSERT INTO sessions(id,job_id,start_ms,end_ms,timezone,offset_seconds,origin) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>("migration-session", jobId, at, at + 60_000, "UTC", 0, "MANUAL"),
            )
            db.execSQL(
                "INSERT INTO location_points(id,session_id,measured_ms,received_ms,latitude,longitude,accuracy) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>("migration-point", "migration-session", at + 1_000, at + 1_100, 1.0, 2.0, 3.0),
            )
            db.version = 16
        }

        val upgraded = TimerRepository(app, wallClock = { at }).also { repository = it }
        assertEquals(listOf("migration-point"), upgraded.locationPoints("migration-session").map { it.id })

        SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(17, db.version)
            assertEquals(
                1,
                db.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='location_deletion_ranges'",
                    null,
                ).use { it.moveToFirst(); it.getInt(0) },
            )
            db.rawQuery(
                "SELECT r.payload FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                    "WHERE h.kind='location' AND h.entity_id='migration-point'",
                null,
            ).use {
                assertTrue(it.moveToFirst())
                val payload = JSONObject(it.getString(0))
                assertEquals(setOf("id", "session_id"), payload.keys().asSequence().toSet())
                assertFalse(payload.has("latitude"))
                assertFalse(payload.has("longitude"))
                assertFalse(it.moveToNext())
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
}
