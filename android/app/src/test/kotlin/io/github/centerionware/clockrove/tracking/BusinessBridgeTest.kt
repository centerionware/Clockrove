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
class BusinessBridgeTest {
    private lateinit var repository: TimerRepository
    private lateinit var dispatch: BusinessDispatch
    private lateinit var job: String
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app); dispatch = BusinessDispatch(repository); job = repository.createJob("Synthetic bridge job")
    }
    @After fun close() { repository.close() }
    private fun change(method: String, value: Map<String, Any?>, version: Any = repository.snapshot().version): Any? = dispatch.call(MethodCall(method, mapOf("id" to "same", "version" to version, "value" to value)))
    private fun card(price: Any?) = mapOf("cardId" to "task", "jobId" to job, "columnId" to repository.boardSnapshot(job).board.columns.first().id,
        "title" to "Synthetic task", "description" to "", "dueDate" to null, "billable" to true, "priceMinor" to price, "currency" to repository.jobDetail(job).job.settings.currency)
    @Test fun boardAndPriceRoundTripThroughActualDispatchWithoutFloatingPoint() {
        val outcome = change("saveCard", card("9007199254740993")) as TimerRepository.Outcome
        assertNull(outcome.conflict)
        val snapshot = dispatch.call(MethodCall("board", mapOf("jobId" to job))) as Map<*, *>
        val task = (snapshot["cards"] as List<*>).single() as Map<*, *>
        assertEquals("9007199254740993", task["priceMinor"]); assertEquals(outcome.version, snapshot["version"])
        assertEquals(3, (snapshot["columns"] as List<*>).size)
    }
    @Test fun floatingVersionsAndNoncanonicalMoneyCannotReachTheWriter() {
        for (price in listOf<Any>(12.0, 12L, "1.20", "-1", "01", "9223372036854775808")) {
            try { change("saveCard", card(price)); fail("Invalid money accepted") } catch (_: IllegalArgumentException) {}
        }
        try { change("saveCard", card("12"), 1.5); fail("Floating version accepted") } catch (_: IllegalArgumentException) {}
        assertTrue(repository.boardSnapshot(job).board.cards.isEmpty())
    }
    @Test fun malformedListsAndBooleansAreRejectedAndUnknownMethodsDoNotMutate() {
        assertNull(dispatch.call(MethodCall("unknown", null)))
        try { change("saveCard", card("12") + ("billable" to "true")); fail("String boolean accepted") } catch (_: IllegalArgumentException) {}
        try { change("reorderColumns", mapOf("jobId" to job, "columnIds" to listOf(123))); fail("Numeric ID accepted") } catch (_: IllegalArgumentException) {}
        assertTrue(repository.boardSnapshot(job).board.cards.isEmpty())
    }
}
