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
@Config(sdk = [24])
class GpsDeletionTest {
    private lateinit var repository: TimerRepository
    private lateinit var firstJob: String
    private lateinit var secondJob: String
    private var now = 10_000L
    private var sequence = 0
    private val version get() = repository.snapshot().version
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now })
        firstJob = repository.createJob("Synthetic first GPS job")
        secondJob = repository.createJob("Synthetic second GPS job")
    }

    @After fun close() { repository.close() }

    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(
        app.getDatabasePath("clockrove.db").path,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    ).use(block)

    private fun number(db: SQLiteDatabase, sql: String, vararg args: String): Long =
        db.rawQuery(sql, args.toList().toTypedArray()).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }

    private fun command(action: TimerRepository.Action, jobId: String?, at: Long) =
        TimerRepository.Command(
            "gps-timer-${sequence++}",
            version,
            action,
            jobId,
            at,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )

    private fun observed(
        jobId: String,
        prefix: String,
        start: Long,
        samples: List<Long>,
        stop: Boolean = true,
    ): String {
        assertNull(repository.execute(command(TimerRepository.Action.START, jobId, start)).conflict)
        val sessionId = repository.snapshot().running!!.id
        samples.forEachIndexed { index, measured ->
            assertEquals(
                TimerRepository.SampleResult.RECORDED,
                repository.recordLocation(
                    TimerRepository.LocationPoint(
                        "$prefix-$index",
                        sessionId,
                        measured,
                        measured + 1,
                        12.0 + index,
                        -34.0 - index,
                        5.0,
                    ),
                ),
            )
        }
        if (stop) {
            assertNull(repository.execute(command(TimerRepository.Action.STOP, null, samples.last() + 100)).conflict)
        }
        return sessionId
    }

    private fun range(jobId: String?, start: Long, end: Long) =
        GpsDeletionStore.Range(jobId, start, end, "Etc/UTC")

    @Test fun previewUsesHalfOpenMeasuredBoundsAndHonorsJobScope() {
        observed(firstJob, "first", 1_000_000, listOf(1_100_000, 1_400_000, 1_700_000))
        observed(secondJob, "second", 2_000_000, listOf(2_100_000, 2_400_000))

        assertEquals(1L, repository.previewLocationRangeDeletion(range(firstJob, 1_400_000, 1_700_000)).pointCount)
        assertEquals(3L, repository.previewLocationRangeDeletion(range(null, 1_400_000, 2_400_000)).pointCount)
        assertEquals(0L, repository.previewLocationRangeDeletion(range(secondJob, 1_400_000, 1_700_000)).pointCount)
    }

    @Test fun deletionKeepsTimesheetsWritesCoordinateFreeRangeAndPointTombstonesAndReplays() {
        val firstSession = observed(firstJob, "first", 1_000_000, listOf(1_100_000, 1_400_000, 1_700_000))
        val secondSession = observed(secondJob, "second", 2_000_000, listOf(2_100_000, 2_400_000))
        val firstEntry = repository.sessionDetail(firstJob, firstSession).entry!!
        val sessionRevisions = repository.sessionRevisions(firstJob, firstSession)
        val before = version
        val target = range(firstJob, 1_400_000, 1_700_000)
        val published = mutableListOf<Long>()
        val subscription = repository.subscribe { published.add(it) }

        val removed = repository.deleteLocationRange("delete-gps", before, target)
        assertNull(removed.conflict)
        assertEquals(before + 1, removed.version)
        assertEquals(removed, repository.deleteLocationRange("delete-gps", before, target))
        subscription.close()
        assertEquals(listOf(before, before + 1), published)
        assertEquals(listOf("first-0", "first-2"), repository.locationPoints(firstSession).map { it.id })
        assertEquals(listOf("second-0", "second-1"), repository.locationPoints(secondSession).map { it.id })
        assertEquals(firstEntry, repository.sessionDetail(firstJob, firstSession).entry)
        assertEquals(sessionRevisions, repository.sessionRevisions(firstJob, firstSession))
        assertEquals(before + 1, version)

        inspect { db ->
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM location_deletion_ranges"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM entity_revisions WHERE kind='location_deletion_range'"))
            val rangeRevision = db.rawQuery(
                "SELECT r.id,r.payload,r.deleted FROM location_deletion_ranges d JOIN entity_revisions r ON r.id=d.revision_id WHERE d.job_id=?",
                arrayOf(firstJob),
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(2))
                val payload = JSONObject(it.getString(1))
                assertEquals(1_400_000L, payload.getLong("start_ms"))
                assertEquals(1_700_000L, payload.getLong("end_ms"))
                assertFalse(payload.has("latitude")); assertFalse(payload.has("longitude"))
                it.getString(0)
            }
            db.rawQuery(
                "SELECT r.id,r.payload FROM entity_revisions r WHERE r.kind='location' AND r.entity_id='first-1' AND r.deleted=1",
                null,
            ).use {
                assertTrue(it.moveToFirst())
                val deletedRevision = it.getString(0)
                val payload = JSONObject(it.getString(1))
                assertEquals(setOf("id", "session_id"), payload.keys().asSequence().toSet())
                val parents = db.rawQuery("SELECT parent_id FROM revision_parents WHERE revision_id=?", arrayOf(deletedRevision)).use { rows ->
                    buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
                }
                assertEquals(2, parents.size)
                assertTrue(rangeRevision in parents)
            }
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM location_points WHERE id='first-1'"))
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun emptyDeletionStillStoresCoverageAndStalePreviewsCannotCommit() {
        val target = range(null, 5_000_000, 6_000_000)
        val preview = repository.previewLocationRangeDeletion(target)
        assertEquals(0L, preview.pointCount)
        assertNull(repository.deleteLocationRange("empty-range", preview.version, target).conflict)
        assertEquals("STALE_STATE", repository.deleteLocationRange("stale-range", preview.version, target).conflict)
        inspect { db ->
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM location_deletion_ranges"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM location_deletion_ranges WHERE job_id IS NULL"))
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM entity_revisions WHERE kind='location_deletion_range'"))
            db.rawQuery("PRAGMA foreign_key_list(location_deletion_ranges)", null).use { rows ->
                while (rows.moveToNext()) assertNotEquals("jobs", rows.getString(2))
            }
        }
    }

    @Test fun pointsRecordedAfterDeletionCarryTheCoveringRangeAsCausalParent() {
        val sessionId = observed(firstJob, "before", 1_000_000, listOf(1_100_000), stop = false)
        val range = range(firstJob, 1_100_000, 1_800_000)
        assertNull(repository.deleteLocationRange("cover-before", version, range).conflict)
        assertEquals(
            TimerRepository.SampleResult.RECORDED,
            repository.recordLocation(
                TimerRepository.LocationPoint("after", sessionId, 1_500_000, 1_900_000, 45.0, 67.0, 8.0),
            ),
        )
        inspect { db ->
            val coverageRevision = db.rawQuery("SELECT revision_id FROM location_deletion_ranges", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            val pointRevision = db.rawQuery("SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id='after'", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            val parents = db.rawQuery("SELECT parent_id FROM revision_parents WHERE revision_id=?", arrayOf(pointRevision)).use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            assertEquals(setOf(coverageRevision), parents)
            val payload = db.rawQuery("SELECT payload FROM entity_revisions WHERE id=?", arrayOf(pointRevision)).use {
                assertTrue(it.moveToFirst()); JSONObject(it.getString(0))
            }
            assertEquals(setOf("id", "session_id"), payload.keys().asSequence().toSet())
            assertFalse(payload.has("latitude")); assertFalse(payload.has("longitude"))
        }
    }

    @Test fun unexpectedDeleteFailureRollsBackPointsCoverageRevisionsVersionAndReceipt() {
        val sessionId = observed(firstJob, "first", 1_000_000, listOf(1_100_000, 1_400_000))
        val before = version
        inspect { db ->
            db.execSQL("CREATE TRIGGER fail_gps_range_delete BEFORE DELETE ON location_points BEGIN SELECT RAISE(ABORT,'synthetic delete failure'); END")
        }
        try {
            repository.deleteLocationRange("rollback-delete", before, range(firstJob, 1_100_000, 1_500_000))
            fail("Expected the injected delete failure")
        } catch (_: android.database.SQLException) { }
        assertEquals(before, version)
        assertEquals(listOf("first-0", "first-1"), repository.locationPoints(sessionId).map { it.id })
        inspect { db ->
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM location_deletion_ranges"))
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM entity_revisions WHERE origin='GPS_RANGE_DELETE'"))
            assertEquals(0L, number(db, "SELECT COUNT(*) FROM commands WHERE id='rollback-delete'"))
            db.execSQL("DROP TRIGGER fail_gps_range_delete")
        }
        assertNull(repository.deleteLocationRange("rollback-delete", before, range(firstJob, 1_100_000, 1_500_000)).conflict)
    }
}
