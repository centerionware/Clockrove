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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class GpsDeletionBridgeTest {
    private lateinit var repository: TimerRepository
    private lateinit var dispatch: BusinessDispatch
    private lateinit var jobId: String
    private val version get() = repository.snapshot().version

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app)
        dispatch = BusinessDispatch(repository)
        jobId = repository.createJob("Synthetic GPS bridge job")
    }

    @After fun close() { repository.close() }

    private fun range(job: String? = jobId, start: Any = 1_000L, end: Any = 2_000L) =
        mapOf<String, Any?>("jobId" to job, "start" to start, "end" to end, "timezone" to "Etc/UTC")

    private fun delete(id: String, capturedVersion: Any, value: Map<String, Any?>) =
        dispatch.call(MethodCall("deleteLocationRange", mapOf(
            "id" to id,
            "version" to capturedVersion,
            "value" to value,
        ))) as TimerRepository.Outcome

    private fun number(sql: String): Long = SQLiteDatabase.openDatabase(
        RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { db -> db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) } }

    @Test fun previewAndCommitUseStrictBoundsAndKeepWorkEntriesUntouched() {
        val preview = dispatch.call(MethodCall("previewLocationRangeDeletion", range())) as Map<*, *>
        assertEquals(version, preview["version"])
        assertEquals(0L, preview["pointCount"])

        val before = version
        val outcome = delete("bridge-range", before, range())
        assertNull(outcome.conflict)
        assertEquals(before + 1, outcome.version)
        assertEquals(outcome, delete("bridge-range", before, range()))
        assertTrue(repository.sessionHistory(jobId).page.entries.isEmpty())
        val committedPreview = dispatch.call(MethodCall("previewLocationRangeDeletion", range())) as Map<*, *>
        assertEquals(0L, committedPreview["pointCount"])
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
    }

    @Test fun malformedNumbersAndTimezonesDoNotReachDeletionWriter() {
        for (bad in listOf(range(start = 1000.5), range(end = "2000"))) {
            try { dispatch.call(MethodCall("previewLocationRangeDeletion", bad)); fail("Malformed range preview was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try {
            dispatch.call(MethodCall("previewLocationRangeDeletion", range() + ("timezone" to "Invented/Zone")))
            fail("Unknown timezone was accepted")
        } catch (_: IllegalArgumentException) { }
        val before = version
        assertEquals("INVALID_GPS_RANGE", delete("invalid-bounds", before, range(start = 2000L, end = 1000L)).conflict)
        assertEquals(before, version)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
    }
}
