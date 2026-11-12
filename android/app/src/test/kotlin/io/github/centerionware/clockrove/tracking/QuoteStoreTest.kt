package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
class QuoteStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private var at = 1793494800000L
    private val version get() = repository.snapshot().version
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun draft(id: String = "quote") = QuoteStore.Draft(id, null, null, "Synthetic tiled floor", "USD", 75000,
        listOf(QuoteStore.Line("tile", "Install tile", "Synthetic estimate", "ITEM", "260000", "1250")), "2026-12-01", "Synthetic terms", "Synthetic note")
    private fun count(table: String) = inspect { db -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
    private fun sequence() = inspect { db -> db.rawQuery("SELECT next_number FROM quote_counter", null).use { it.moveToFirst(); it.getLong(0) } }
    private fun issue(value: QuoteStore.Draft = draft()) {
        assertNull(repository.saveQuote("save-${value.id}", version, value).conflict)
        assertNull(repository.issueQuote("issue-${value.id}", version, value.id, true).conflict)
    }
    @Before fun setup() { app.deleteDatabase("clockrove.db"); repository = TimerRepository(app, wallClock = { at }); repository.snapshot() }
    @After fun close() { repository.close() }
    @Test fun savingDoesNotAllocateAndIssuingFreezesExactEstimateWithoutWorkOrCharges() {
        val value = draft(); val before = version
        val saved = repository.saveQuote("save", before, value)
        assertNull(saved.conflict); assertEquals(saved, repository.saveQuote("save", before, value))
        assertEquals("COMMAND_ID_REUSED", repository.saveQuote("save", before, value.copy(title = "Changed retry")).conflict)
        assertEquals(value, repository.quote(value.id).draft); assertNull(repository.quote(value.id).latest)
        assertEquals(1000L, sequence())
        val initial = version
        assertEquals("QUOTE_CONFIRMATION_REQUIRED", repository.issueQuote("no-confirm", initial, value.id, false).conflict)
        val issued = repository.issueQuote("issue", initial, value.id, true)
        assertNull(issued.conflict); assertEquals(issued, repository.issueQuote("issue", initial, value.id, true))
        val revision = repository.quote(value.id).latest!!; val payload = JSONObject(revision.payload)
        assertEquals("Q-1000", revision.number); assertEquals(1, revision.revision)
        assertEquals("325.00", payload.getString("subtotal")); assertEquals("24.38", payload.getString("tax")); assertEquals("349.38", payload.getString("total"))
        assertNull(repository.quote(value.id).draft); assertEquals(1001L, sequence())
        for (table in listOf("sessions", "kanban_cards", "invoices", "invoice_task_claims", "invoice_time_claims", "invoice_payments")) assertEquals(table, 0, count(table))
        inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1000L, it.getLong(0)) } }
    }
    @Test fun revisionsKeepNumberAndPriorAcceptanceButRequireNewExplicitDecision() {
        issue(); val first = repository.quote("quote").latest!!
        val acceptedAt = version
        val accepted = repository.decideQuote("accept", acceptedAt, "quote", 1, "ACCEPTED", "Confirmed locally by owner", true)
        assertNull(accepted.conflict); assertEquals(accepted, repository.decideQuote("accept", acceptedAt, "quote", 1, "ACCEPTED", "Confirmed locally by owner", true))
        assertEquals("ACCEPTED", repository.quote("quote").latest!!.decision)
        assertEquals("QUOTE_ALREADY_DECIDED", repository.decideQuote("decline-again", version, "quote", 1, "DECLINED", "Changed decision after acceptance", true).conflict)
        val next = draft().copy(title = "Revised synthetic estimate")
        assertNull(repository.saveQuote("revise", version, next).conflict)
        assertEquals(first.payload, repository.quote("quote").latest!!.payload)
        assertNull(repository.issueQuote("issue-revision", version, "quote", true).conflict)
        val second = repository.quote("quote").latest!!
        assertEquals("Q-1000", second.number); assertEquals(2, second.revision); assertNull(second.decision); assertEquals(1001L, sequence())
        assertEquals("QUOTE_REVISION_CHANGED", repository.decideQuote("old-accept", version, "quote", 1, "ACCEPTED", "An obsolete revision accepted", true).conflict)
        inspect { db ->
            db.rawQuery("SELECT payload FROM quote_revisions WHERE revision=1", null).use { it.moveToFirst(); assertEquals(first.payload, it.getString(0)) }
            db.rawQuery("SELECT decision FROM quote_decisions WHERE revision=1", null).use { it.moveToFirst(); assertEquals("ACCEPTED", it.getString(0)) }
            try { db.execSQL("UPDATE quote_revisions SET payload='changed'"); fail("immutable") } catch (_: android.database.SQLException) { }
            try { db.execSQL("DELETE FROM quote_decisions"); fail("immutable") } catch (_: android.database.SQLException) { }
            try { db.execSQL("UPDATE quotes SET number='Q-9999'"); fail("immutable number") } catch (_: android.database.SQLException) { }
        }
    }
    @Test fun quantitiesEstimatedHoursWideTotalsAndHalfCentBoundariesStayExact() {
        val wide = "9".repeat(1000)
        val value = draft().copy(lines = listOf(QuoteStore.Line("hours", "Estimated hours", "No worked hours created", "HOURS", "9223372036854775807", wide)), taxUnits = 1000000)
        val total = QuoteStore.totals(value)
        val subtotal = BigInteger(wide).multiply(BigInteger("9223372036854775807")).add(BigInteger("5000")).divide(BigInteger("10000"))
        assertEquals(subtotal, total.subtotal); assertEquals(subtotal.multiply(BigInteger.valueOf(2)), total.total)
        for ((quantity, expected) in listOf("4999" to "0", "5000" to "1", "5001" to "1")) {
            assertEquals(BigInteger(expected), QuoteStore.totals(draft().copy(lines = listOf(QuoteStore.Line("fraction", "Fraction", "", "ITEM", quantity, "1")), taxUnits = 0)).total)
        }
        issue(value)
        assertEquals(InvoiceStore.amount(total.total, 2), JSONObject(repository.quote("quote").latest!!.payload).getString("total"))
    }
    @Test fun invalidInputNeverCreatesDraftOrConsumesNumber() {
        val original = draft(); val line = original.lines.single()
        val invalid = listOf(original.copy(validUntil = "2026-02-30"), original.copy(validUntil = "0000-01-01"),
            original.copy(lines = listOf(line, line)), original.copy(lines = emptyList()), original.copy(taxUnits = 1000001),
            original.copy(lines = listOf(line.copy(quantity = "9223372036854775808"))), original.copy(lines = listOf(line.copy(quantity = "01"))),
            original.copy(lines = listOf(line.copy(unitPrice = "1.25"))), original.copy(lines = listOf(line.copy(unitPrice = "9".repeat(1025)))))
        val before = version
        for ((index, value) in invalid.withIndex()) {
            assertNotNull(repository.saveQuote("invalid-$index", before, value).conflict)
            assertEquals(before, version); assertEquals(0, count("quotes")); assertEquals(1000L, sequence())
        }
    }
    @Test fun issuedCustomerCompanyLogoAndProvenanceSurviveEditsAndJobPurge() {
        val job = repository.createJob("Synthetic quoted job")
        assertNull(repository.saveCustomer("customer", version, BusinessStore.Customer("customer", "Original customer", "customer@example.test")).conflict)
        assertNull(repository.saveBusinessSettings("company", version, repository.businessSnapshot().settings.copy(companyName = "Original company")).conflict)
        // Opaque asset tests retention only; actual image decoding/rendering has separate gates.
        inspect { db ->
            db.execSQL("INSERT INTO logo_assets VALUES('logo','image/png',1,1,'synthetic',?)", arrayOf(byteArrayOf(1, 2, 3)))
            db.execSQL("UPDATE app_settings SET logo_id='logo'")
        }
        issue(draft().copy(customerId = "customer", jobId = job))
        val original = repository.quote("quote").latest!!
        assertEquals("CUSTOMER_IN_USE", repository.removeCustomer("remove-customer", version, "customer").conflict)
        assertNull(repository.saveCustomer("change-customer", version, BusinessStore.Customer("customer", "Changed customer", "changed@example.test")).conflict)
        assertNull(repository.saveBusinessSettings("change-company", version, repository.businessSnapshot().settings.copy(companyName = "Changed company")).conflict)
        assertNull(repository.removeLogo("remove-logo", version).conflict)
        assertNull(repository.archiveJob("archive", version, job).conflict)
        at += 31L * 86400000
        assertNull(repository.deleteJob("delete-job", version, job).conflict)
        assertEquals(original, repository.quote("quote").latest)
        val frozen = JSONObject(original.payload)
        assertEquals("Original company", frozen.getString("company")); assertEquals("Original customer", frozen.getString("customer"))
        assertEquals("Synthetic quoted job", frozen.getString("job"))
        inspect { db ->
            db.rawQuery("SELECT bytes FROM logo_assets WHERE id='logo'", null).use { assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(0)) }
            db.rawQuery("SELECT payload FROM entity_revisions WHERE kind='quote_revision'", null).use { assertTrue(it.moveToFirst()); assertFalse(it.isNull(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun concurrentIssueAndLateFailureLeaveOneAtomicNumberedOutcome() {
        assertNull(repository.saveQuote("save", version, draft()).conflict)
        val initial = version; val gate = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { i -> pool.submit<TimerRepository.Outcome> { gate.await(); repository.issueQuote("concurrent-$i", initial, "quote", true) } }
            gate.countDown(); val outcomes = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.conflict == null }); assertEquals(1, outcomes.count { it.conflict == "STALE_STATE" })
        } finally { pool.shutdownNow() }
        assertEquals(1, count("quote_revisions")); assertEquals(1001L, sequence())
        assertNull(repository.saveQuote("save-other", version, draft("other")).conflict)
        inspect { it.execSQL("CREATE TRIGGER reject_quote_revision BEFORE INSERT ON quote_revisions WHEN NEW.quote_id='other' BEGIN SELECT RAISE(ABORT,'Synthetic interruption'); END") }
        val before = version
        try { repository.issueQuote("interrupted", before, "other", true); fail("Expected rollback") } catch (_: android.database.SQLException) { }
        assertEquals(before, version); assertEquals(1001L, sequence()); assertNotNull(repository.quote("other").draft)
        inspect { db ->
            db.rawQuery("SELECT number FROM quotes WHERE id='other'", null).use { it.moveToFirst(); assertTrue(it.isNull(0)) }
            db.execSQL("DROP TRIGGER reject_quote_revision")
        }
        assertNull(repository.issueQuote("interrupted", before, "other", true).conflict)
        assertEquals("Q-1001", repository.quote("other").latest!!.number)
    }
    @Test fun quotePagesUseStableKeysAndExpiryIsDerivedWithoutWriting() {
        for (id in listOf("alpha", "bravo", "charlie")) issue(draft(id).copy(validUntil = "2026-11-01"))
        val initial = version
        val first = repository.quotes(limit = 2)
        assertEquals(listOf("charlie", "bravo"), first.page.entries.map { it.id }); assertNotNull(first.page.next)
        val second = repository.quotes(first.page.next, 2)
        assertEquals(listOf("alpha"), second.page.entries.map { it.id }); assertNull(second.page.next)
        inspect { db ->
            val onDate = QuoteStore.index(db, "2026-11-01")
            assertTrue(onDate.entries.all { it.status == "ISSUED" })
            val expired = QuoteStore.index(db, "2026-11-02")
            assertTrue(expired.entries.all { it.status == "EXPIRED" })
        }
        assertEquals(initial, version)
        assertNull(repository.decideQuote("accept", version, "alpha", 1, "ACCEPTED", "Explicit local acceptance", true).conflict)
        inspect { db -> assertEquals("ACCEPTED", QuoteStore.index(db, "2026-11-02").entries.single { it.id == "alpha" }.status) }
        assertNull(repository.saveQuote("edit", version, draft("alpha")).conflict)
        val editing = repository.quotes().page.entries.single { it.id == "alpha" }
        assertTrue(editing.hasDraft); assertEquals("DRAFT", editing.status); assertEquals("Q-1000", editing.number)
        assertEquals("ACCEPTED", repository.quoteRevision("alpha", 1)!!.decision)
    }
    @Test fun revisionHistoryReadsOldDocumentsAndPaginatesWithoutReplacingThem() {
        issue()
        val first = repository.quoteRevision("quote", 1)!!
        for (revision in 2..3) {
            assertNull(repository.saveQuote("save-$revision", version, draft().copy(title = "Revision $revision")).conflict)
            assertNull(repository.issueQuote("issue-$revision", version, "quote", true).conflict)
        }
        assertEquals(listOf(3, 2), repository.quoteHistory("quote", limit = 2).map { it.revision })
        assertEquals(listOf(1), repository.quoteHistory("quote", before = 2, limit = 2).map { it.revision })
        assertEquals(first, repository.quoteRevision("quote", 1)); assertNull(repository.quoteRevision("quote", 4))
        try { repository.quotes(limit = 51); fail("Page limit required") } catch (_: IllegalArgumentException) { }
        try { repository.quoteHistory("quote", before = 0); fail("Positive revision cursor required") } catch (_: IllegalArgumentException) { }
    }
    @Test fun exhaustedNumberSequenceRejectsNewIssueWithoutPreventingExistingRevisions() {
        issue()
        inspect { it.execSQL("UPDATE quote_counter SET next_number=?", arrayOf(Long.MAX_VALUE)) }
        assertNull(repository.saveQuote("new", version, draft("new")).conflict)
        val before = version
        assertEquals("QUOTE_SEQUENCE_EXHAUSTED", repository.issueQuote("new-issue", before, "new", true).conflict)
        assertEquals(before, version); assertNotNull(repository.quote("new").draft); assertNull(repository.quote("new").latest)
        assertEquals(Long.MAX_VALUE, sequence())
        assertNull(repository.saveQuote("revise", version, draft()).conflict)
        assertNull(repository.issueQuote("reissue", version, "quote", true).conflict)
        assertEquals(2, repository.quote("quote").latest!!.revision); assertEquals("Q-1000", repository.quote("quote").latest!!.number)
        assertEquals(Long.MAX_VALUE, sequence())
    }
    @Test fun revisionsKeepFrozenCurrencyPrecisionRatherThanReinterpretingMinorUnits() {
        issue()
        // Synthetic historical precision, deliberately different from today's
        // platform USD default, proves the frozen contract rather than ISO policy.
        val historical = JSONObject(repository.quote("quote").latest!!.payload)
            .put("draft", JSONObject(draft("frozen").encode())).put("number", "Q-9000")
            .put("digits", 3).put("subtotal", "32.500").put("tax", "2.438").put("total", "34.938")
            .put("subject", "Synthetic historical quote").put("body", "Synthetic historical quote")
        inspect { db ->
            db.execSQL("INSERT INTO quotes VALUES('frozen','Q-9000',?)", arrayOf(at))
            db.execSQL("INSERT INTO quote_revisions VALUES('frozen',1,NULL,NULL,?,NULL,?)", arrayOf<Any?>(historical.toString(), at))
        }
        assertEquals(3, repository.quoteCurrencyDigits("frozen", "USD"))
        assertEquals(0, repository.quoteCurrencyDigits("frozen", "JPY"))
        assertNull(repository.saveQuote("save-frozen", version, draft("frozen")).conflict)
        assertEquals("34.938", repository.quotes().page.entries.single { it.id == "frozen" }.total)
        assertNull(repository.issueQuote("issue-frozen", version, "frozen", true).conflict)
        val revision = JSONObject(repository.quote("frozen").latest!!.payload)
        assertEquals(3, revision.getInt("digits")); assertEquals("34.938", revision.getString("total"))
        assertEquals("1.250", InvoiceStore.Snapshot.decode(repository.quoteDocument("frozen", 2).payload).lines.single().detail.substringAfter("× ").substringBefore(" USD"))
    }
}
