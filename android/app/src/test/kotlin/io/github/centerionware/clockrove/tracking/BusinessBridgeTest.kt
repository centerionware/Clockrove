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
    @Test fun receivablesSettingRoundTripsWhileMalformedAddressesRetainExistingSettings() {
        val business = dispatch.call(MethodCall("business", null)) as Map<*, *>
        val settings = (business["settings"] as Map<*, *>).entries.associate { it.key as String to it.value }
        val saved = dispatch.call(MethodCall("saveSettings", mapOf("id" to "accounting", "version" to repository.snapshot().version,
            "value" to settings + ("receivablesEmail" to "accounts@example.test")))) as TimerRepository.Outcome
        assertNull(saved.conflict)
        val before = repository.businessSnapshot()
        assertEquals("accounts@example.test", before.settings.receivablesEmail)
        val roundTrip = dispatch.call(MethodCall("business", null)) as Map<*, *>
        assertEquals("accounts@example.test", (roundTrip["settings"] as Map<*, *>)["receivablesEmail"])
        listOf("bad", "a@example.test,b@example.test", "a@example.test\r\nBcc:b@example.test").forEachIndexed { index, address ->
            val outcome = dispatch.call(MethodCall("saveSettings", mapOf("id" to "bad-accounting-$index", "version" to before.version,
                "value" to settings + mapOf("companyName" to "Must not save", "receivablesEmail" to address)))) as TimerRepository.Outcome
            assertEquals("INVALID_ACCOUNTS_RECEIVABLE_EMAIL", outcome.conflict)
        }
        assertEquals(before, repository.businessSnapshot())
        val cleared = dispatch.call(MethodCall("saveSettings", mapOf("id" to "clear-accounting", "version" to before.version,
            "value" to settings + ("receivablesEmail" to "")))) as TimerRepository.Outcome
        assertNull(cleared.conflict); assertEquals("", repository.businessSnapshot().settings.receivablesEmail)
    }
    @Test fun boardAndPriceRoundTripThroughActualDispatchWithoutFloatingPoint() {
        val outcome = change("saveCard", card("9007199254740993")) as TimerRepository.Outcome
        assertNull(outcome.conflict)
        val snapshot = dispatch.call(MethodCall("board", mapOf("jobId" to job))) as Map<*, *>
        val task = (snapshot["cards"] as List<*>).single() as Map<*, *>
        assertEquals("9007199254740993", task["priceMinor"]); assertEquals(outcome.version, snapshot["version"])
        assertEquals(3, (snapshot["columns"] as List<*>).size)
    }
    @Test fun exactQuantitiesRoundTripAndMalformedInputsCannotReachWriter() {
        val before = repository.boardSnapshot(job)
        for (bad in listOf<Any?>(null, 260000L, 26.0, "0", "-1", "1.5", "0260000", "9223372036854775808")) {
            try { change("saveCard", card("500") + ("quantityUnits" to bad)); fail("Malformed quantity accepted") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(before, repository.boardSnapshot(job))
        assertNull((change("saveCard", card("500") + ("quantityUnits" to "260000")) as TimerRepository.Outcome).conflict)
        val board = dispatch.call(MethodCall("board", mapOf("jobId" to job))) as Map<*, *>
        assertEquals("260000", ((board["cards"] as List<*>).single() as Map<*, *>)["quantityUnits"])
        val templates = dispatch.call(MethodCall("templates", mapOf("search" to ""))) as List<*>
        assertEquals("260000", (templates.single() as Map<*, *>)["quantityUnits"])
    }
    @Test fun widePricesAndDefaultRatesRoundTripAsStringsBeyondFixedWidth() {
        val exact = "1234567890123456789012345678901234567890"
        assertNull((change("saveCard", card(exact)) as TimerRepository.Outcome).conflict)
        val board = dispatch.call(MethodCall("board", mapOf("jobId" to job))) as Map<*, *>
        assertEquals(exact, ((board["cards"] as List<*>).single() as Map<*, *>)["priceMinor"])
        val business = dispatch.call(MethodCall("business", null)) as Map<*, *>
        val settings = (business["settings"] as Map<*, *>).entries.associate { it.key as String to it.value }
        assertNull((dispatch.call(MethodCall("saveSettings", mapOf("id" to "wide-default", "version" to repository.snapshot().version,
            "value" to settings + ("defaultHourlyMinor" to exact)))) as TimerRepository.Outcome).conflict)
        val result = dispatch.call(MethodCall("business", null)) as Map<*, *>
        assertEquals(exact, (result["settings"] as Map<*, *>)["defaultHourlyMinor"])
        assertEquals(java.math.BigInteger(exact), repository.jobDetail(job).job.effectiveHourlyMinor)
    }
    @Test fun floatingVersionsAndNoncanonicalMoneyCannotReachTheWriter() {
        for (price in listOf<Any>(12.0, 12L, "1.20", "-1", "01", "1".repeat(1025))) {
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
    @Test fun exactTaxUnitsRoundTripWithExplicitZeroJobOverride() {
        val business = dispatch.call(MethodCall("business", null)) as Map<*, *>
        val settings = (business["settings"] as Map<*, *>).entries.associate { it.key as String to it.value }
        assertNull((change("saveSettings", settings + ("defaultTaxUnits" to 75000)) as TimerRepository.Outcome).conflict)
        val detail = dispatch.call(MethodCall("job", mapOf("jobId" to job))) as Map<*, *>
        assertEquals(75000, detail["effectiveTaxUnits"])
        val jobSettings = (detail["settings"] as Map<*, *>).entries.associate { it.key as String to it.value }
        assertNull((dispatch.call(MethodCall("saveJob", mapOf("id" to "job-tax", "version" to repository.snapshot().version,
            "value" to jobSettings + ("taxUnits" to 0)))) as TimerRepository.Outcome).conflict)
        val result = dispatch.call(MethodCall("job", mapOf("jobId" to job))) as Map<*, *>
        assertEquals(0, result["effectiveTaxUnits"]); assertEquals(0, (result["settings"] as Map<*, *>)["taxUnits"])
        assertEquals(75000, repository.businessSnapshot().settings.defaultTaxUnits)
    }
    @Test fun floatingStringAndBooleanTaxUnitsCannotReachTheNativeWriter() {
        val business = dispatch.call(MethodCall("business", null)) as Map<*, *>
        val settings = (business["settings"] as Map<*, *>).entries.associate { it.key as String to it.value }
        val before = repository.businessSnapshot()
        for (bad in listOf<Any>(75000.0, "75000", true, Long.MAX_VALUE)) {
            try { change("saveSettings", settings + ("defaultTaxUnits" to bad)); fail("Malformed units accepted") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(before, repository.businessSnapshot())
    }
}
