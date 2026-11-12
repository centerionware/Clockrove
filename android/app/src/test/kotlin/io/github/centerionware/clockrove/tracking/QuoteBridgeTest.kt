package io.github.centerionware.clockrove.tracking

import io.flutter.plugin.common.MethodCall
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
class QuoteBridgeTest {
    private lateinit var repository: TimerRepository
    private lateinit var dispatch: BusinessDispatch
    private val version get() = repository.snapshot().version
    private fun line(): Map<String, Any?> = mapOf("lineId" to "line", "title" to "Synthetic tile", "detail" to "", "kind" to "ITEM", "quantityUnits" to "260000", "unitPriceMinor" to "1250")
    private fun draft(): Map<String, Any?> = mapOf("quoteId" to "quote", "customerId" to null, "jobId" to null, "title" to "Synthetic quote", "currency" to "USD", "taxUnits" to 75000, "lines" to listOf(line()), "validUntil" to "2026-12-01", "terms" to "", "notes" to "")
    private fun command(method: String, id: String, value: Map<String, Any?>, expected: Any = version) = dispatch.call(MethodCall(method, mapOf("id" to id, "version" to expected, "value" to value))) as TimerRepository.Outcome
    private fun context() = dispatch.call(MethodCall("quoteContext", mapOf("quoteId" to "quote"))) as Map<*, *>
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { 1793494800000L }); dispatch = BusinessDispatch(repository)
    }
    @After fun close() { repository.close() }
    @Test fun nativeChannelIssuesExactStringsAndRequiresExplicitRevisionDecision() {
        assertNull(command("saveQuote", "save", draft()).conflict)
        val saved = JSONObject(context()["draftJson"] as String)
        assertEquals("1250", saved.getJSONArray("lines").getJSONObject(0).getString("unitPrice"))
        assertNull(command("issueQuote", "issue", mapOf("quoteId" to "quote", "confirmed" to true)).conflict)
        val latest = context()["latest"] as Map<*, *>
        assertEquals("Q-1000", latest["number"]); assertEquals(1, latest["revision"])
        assertEquals("349.38", JSONObject(latest["payload"] as String).getString("total"))
        assertNull(command("decideQuote", "accept", mapOf("quoteId" to "quote", "revision" to 1, "decision" to "ACCEPTED", "note" to "Explicit local confirmation", "confirmed" to true)).conflict)
        assertEquals("ACCEPTED", (context()["latest"] as Map<*, *>)["decision"])
    }
    @Test fun numericMoneyQuantityFractionalVersionAndWrongBooleansNeverReachWrites() {
        val before = version
        val invalid = listOf(draft() + ("lines" to listOf(line() + ("unitPriceMinor" to 1250))),
            draft() + ("lines" to listOf(line() + ("quantityUnits" to 260000))), draft() + ("taxUnits" to 75000.0))
        for ((index, value) in invalid.withIndex()) {
            try { command("saveQuote", "bad-$index", value); fail("Strict types required") } catch (_: IllegalArgumentException) { }
        }
        try { command("saveQuote", "bad-version", draft(), 0.0); fail("Integer version required") } catch (_: IllegalArgumentException) { }
        assertEquals(before, version); assertNull(context()["draftJson"])
        assertNull(command("saveQuote", "save", draft()).conflict)
        try { command("issueQuote", "bad-confirm", mapOf("quoteId" to "quote", "confirmed" to 1)); fail("Boolean required") } catch (_: IllegalArgumentException) { }
        assertNull(context()["latest"])
    }
}
