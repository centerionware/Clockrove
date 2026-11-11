package io.github.centerionware.clockrove.tracking

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
@Config(sdk = [24, 35])
class SessionHistoryBridgeTest {
    private lateinit var repository: TimerRepository
    private lateinit var dispatch: BusinessDispatch
    private lateinit var job: String
    private val version get() = repository.snapshot().version
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app); dispatch = BusinessDispatch(repository); job = repository.createJob("Synthetic history bridge")
    }
    @After fun close() { repository.close() }
    private fun value() = mapOf<String, Any?>("sessionId" to "entry", "jobId" to job, "start" to 1000L, "end" to 2000L, "timezone" to "UTC", "offsetSeconds" to 0)
    private fun change(method: String, fields: Map<String, Any?>, id: String = method, v: Any = version) =
        dispatch.call(MethodCall(method, mapOf("id" to id, "version" to v, "value" to fields))) as TimerRepository.Outcome
    @Test fun actualCodecCreatesCorrectsQueriesAndDeletesWithNativeManualOrigin() {
        assertNull(change("createManualSession", value() + ("origin" to "GEOFENCE")).conflict)
        var result = dispatch.call(MethodCall("sessionHistory", mapOf("jobId" to job, "limit" to 1))) as Map<*, *>
        var entry = (result["entries"] as List<*>).single() as Map<*, *>
        assertEquals("MANUAL", entry["origin"]); assertEquals(1000L, entry["start"]); assertNull(result["next"])
        assertNull(change("correctSession", value() + ("end" to 2500L)).conflict)
        result = dispatch.call(MethodCall("sessionHistory", mapOf("jobId" to job))) as Map<*, *>
        entry = (result["entries"] as List<*>).single() as Map<*, *>
        assertEquals(2500L, entry["end"]); assertEquals(version, result["version"])
        val revisions = dispatch.call(MethodCall("sessionRevisions", mapOf("jobId" to job, "sessionId" to "entry"))) as List<*>
        assertEquals(2, revisions.size); assertEquals("MANUAL_CORRECTION", (revisions.first() as Map<*, *>)["origin"])
        assertNull(change("deleteSession", mapOf("sessionId" to "entry")).conflict)
        assertTrue(repository.sessionHistory(job).page.entries.isEmpty())
    }
    @Test fun floatingBoundariesOffsetsAndVersionsNeverReachTheWriter() {
        val before = version
        for ((key, bad) in listOf("start" to 1000.0, "end" to "2000", "offsetSeconds" to 0.5)) {
            try { change("createManualSession", value() + (key to bad)); fail("Malformed boundary accepted") } catch (_: IllegalArgumentException) {}
        }
        try { change("createManualSession", value(), v = 1.5); fail("Floating version accepted") } catch (_: IllegalArgumentException) {}
        assertEquals(before, version); assertTrue(repository.sessionHistory(job).page.entries.isEmpty())
    }
    @Test fun malformedCursorAndUnboundedHistoryRequestsAreRejected() {
        for (query in listOf(mapOf("jobId" to job, "limit" to 501), mapOf("jobId" to job, "before" to "bad"),
            mapOf("jobId" to job, "before" to mapOf("start" to 1000.5, "id" to "entry")))) {
            try { dispatch.call(MethodCall("sessionHistory", query)); fail("Malformed query accepted") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(repository.sessionHistory(job).page.entries.isEmpty())
    }
    @Test fun responseLossRetainsOriginalIntentAndStaleDraftDoesNotOverwrite() {
        val captured = version; val original = change("createManualSession", value(), "same", captured)
        assertEquals(original, change("createManualSession", value(), "same", captured))
        assertEquals("COMMAND_ID_REUSED", change("createManualSession", value() + ("end" to 3000L), "same", captured).conflict)
        assertEquals("STALE_STATE", change("correctSession", value() + ("end" to 3000L), v = captured).conflict)
        assertEquals(2000L, repository.sessionHistory(job).page.entries.single().end)
    }
    @Test fun detailAndDeletedAuditUseStrictBoundedReadTransport() {
        assertNull(change("createManualSession", value()).conflict)
        val detail = dispatch.call(MethodCall("sessionDetail", mapOf("jobId" to job, "sessionId" to "entry"))) as Map<*, *>
        assertEquals(version, detail["version"])
        assertEquals(1000L, (detail["entry"] as Map<*, *>)["start"])
        assertNull(change("deleteSession", mapOf("sessionId" to "entry")).conflict)
        val result = dispatch.call(MethodCall("sessionAudit", mapOf("jobId" to job, "limit" to 1))) as Map<*, *>
        val event = (result["events"] as List<*>).single() as Map<*, *>
        assertEquals("entry", event["sessionId"])
        assertEquals(true, (event["revision"] as Map<*, *>)["deleted"])
        assertNotNull(result["next"])
        for (query in listOf(mapOf("jobId" to job, "before" to 1.5), mapOf("jobId" to job, "before" to 0), mapOf("jobId" to job, "limit" to 501))) {
            try { dispatch.call(MethodCall("sessionAudit", query)); fail("Invalid audit query accepted") } catch (_: IllegalArgumentException) {}
        }
        try { dispatch.call(MethodCall("formatSessionTime", mapOf("instant" to 1000.5, "timezone" to "UTC"))); fail("Fractional instant accepted") } catch (_: IllegalArgumentException) {}
    }

}
