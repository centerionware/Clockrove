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
class InvoiceSnapshotTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private val version get() = repository.snapshot().version
    private val selection get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { 1793534400000 }); job = repository.createJob("Synthetic invoice job")
        repository.saveBusinessSettings("settings", version, repository.businessSnapshot().settings.copy(defaultHourlyMinor = java.math.BigInteger.valueOf(12345), companyName = "Synthetic Co", companyEmail = "billing@example.test"))
        repository.saveCustomer("customer", version, BusinessStore.Customer("customer", "Synthetic Customer", "recipient@example.test", address = "Synthetic address"))
        repository.saveJobSettings("job", version, repository.jobDetail(job).job.settings.copy(customerId = "customer"))
        repository.execute(TimerRepository.Command("start", version, TimerRepository.Action.START, job, 1793491200000, "UTC", 0, TimerRepository.Origin.MANUAL))
        repository.execute(TimerRepository.Command("stop", version, TimerRepository.Action.STOP, null, 1793494800000, "UTC", 0, TimerRepository.Origin.MANUAL))
    }
    @After fun close() { repository.close() }
    private fun prepare(command: String = "prepare", id: String = "invoice") = repository.prepareInvoice(command, version, id, selection)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    @Test fun snapshotContainsActualSelectionPriceContactsAndSafeEmailDraft() {
        assertNull(prepare().conflict)
        val record = repository.invoice("invoice"); val data = InvoiceStore.Snapshot.decode(record.payload)
        assertEquals("INV-1000", data.number); assertEquals("123.45", data.total); assertEquals("Synthetic Co", data.company)
        assertEquals("Synthetic Customer", data.customer); assertEquals("recipient@example.test", data.email)
        assertTrue(data.body.contains("123.45")); assertTrue(data.period.contains("UTC")); assertTrue(data.basis.contains("billed"))
        assertEquals("123.45", data.lines.first().amount); assertEquals(TaskClassification.LABOR, data.lines.first().chargeType)
        assertEquals(2, data.lines.size); assertNull(record.pdf)
        assertEquals(data, InvoiceStore.Snapshot.decode(data.encode()))
        val olderPayload = JSONObject(data.encode()).apply {
            val lines = getJSONArray("lines")
            for (index in 0 until lines.length()) lines.getJSONObject(index).remove("chargeType")
        }.toString()
        assertTrue(InvoiceStore.Snapshot.decode(olderPayload).lines.all { it.chargeType == TaskClassification.UNCLASSIFIED })
    }
    @Test fun taskInvoiceFreezesSuppliesClassificationForLaterReports() {
        repository.saveJobSettings("task-rate", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK"))
        val board = repository.boardSnapshot(job).board
        assertNull(repository.saveCard("supplies-task", version, BoardStore.Draft(
            id = "tile-materials", jobId = job, columnId = board.columns.last().id, title = "Synthetic tile materials",
            priceMinor = java.math.BigInteger.valueOf(2000), currency = board.currency,
            quantityUnits = 260000, chargeType = TaskClassification.SUPPLIES)).conflict)

        assertNull(prepare("prepare-supplies", "supplies-invoice").conflict)
        val frozen = InvoiceStore.Snapshot.decode(repository.invoice("supplies-invoice").payload)
        assertEquals("INV-1000", frozen.number)
        assertEquals("520.00", frozen.subtotal)
        assertEquals(TaskClassification.SUPPLIES, frozen.lines.single().chargeType)
        assertEquals(frozen, InvoiceStore.Snapshot.decode(frozen.encode()))
    }
    @Test fun editsNeverRewritePreparedSnapshotAndRetryDoesNotAllocateAnotherNumber() {
        val originalVersion = version; val first = repository.prepareInvoice("same", originalVersion, "invoice", selection)
        val original = repository.invoice("invoice").payload
        repository.saveBusinessSettings("changed", version, repository.businessSnapshot().settings.copy(defaultHourlyMinor = java.math.BigInteger.valueOf(999), companyName = "Changed", invoiceStyle = "CLASSIC"))
        repository.saveCustomer("contact-changed", version, BusinessStore.Customer("customer", "Changed Customer", "changed@example.test"))
        repository.saveJobSettings("job-changed", version, repository.jobDetail(job).job.settings.copy(name = "Changed job"))
        assertEquals(first, repository.prepareInvoice("same", originalVersion, "invoice", selection))
        assertEquals(original, repository.invoice("invoice").payload)
        assertNull(repository.createManualSession("new-work", version, SessionHistoryStore.Draft("new-work", job, 1793494800000, 1793498400000, "UTC", 0)).conflict)
        assertNull(prepare("second", "invoice-2").conflict); assertEquals("INV-1001", repository.invoice("invoice-2").number)
        val next = InvoiceStore.Snapshot.decode(repository.invoice("invoice-2").payload)
        assertEquals("9.99", next.total); assertEquals("Changed Customer", next.customer); assertEquals("CLASSIC", next.style)
    }
    @Test fun missingRateOrPricePreventsNumberAllocationAndExplicitFreeTaskIsValid() {
        repository.saveBusinessSettings("unset", version, repository.businessSnapshot().settings.copy(defaultHourlyMinor = null))
        assertEquals("HOURLY_RATE_REQUIRED", prepare().conflict)
        repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK"))
        assertEquals("NO_BILLABLE_ITEMS", prepare("empty").conflict)
        val board = repository.boardSnapshot(job).board
        repository.saveCard("task", version, BoardStore.Draft("task", job, board.columns.last().id, "Synthetic free task", priceMinor = null, currency = board.currency))
        assertEquals("TASK_PRICES_REQUIRED", prepare("missing-price").conflict)
        repository.saveCard("price", version, BoardStore.Draft("task", job, board.columns.last().id, "Synthetic free task", priceMinor = java.math.BigInteger.valueOf(0), currency = board.currency))
        assertNull(prepare("free").conflict)
        val data = InvoiceStore.Snapshot.decode(repository.invoice("invoice").payload)
        assertEquals("INV-1000", data.number); assertEquals(InvoiceStore.amount(java.math.BigInteger.ZERO, data.digits), data.total)
        assertEquals(1, data.lines.size); assertTrue(data.basis.contains("quantity × unit price"))
    }
    @Test fun invoiceIdReuseIsRejectedAndConflictingGenerationIsRollbackSafe() {
        assertNull(prepare().conflict); assertEquals("INVOICE_ID_EXISTS", prepare("reused").conflict)
        inspect { it.execSQL("CREATE TRIGGER fail_invoice BEFORE INSERT ON invoices BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        assertNull(repository.createManualSession("rollback-work", version, SessionHistoryStore.Draft("rollback-work", job, 1793494800000, 1793498400000, "UTC", 0)).conflict)
        val before = version
        try { prepare("failed", "second"); fail("Unexpected success") } catch (_: android.database.SQLException) {}
        assertEquals(before, version)
        inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1001, it.getInt(0)) }; db.rawQuery("SELECT id FROM commands WHERE id='failed'", null).use { assertFalse(it.moveToFirst()) } }
    }
    @Test fun cachedPdfCannotOverwriteAnExistingDocumentOrAttachToChangedSnapshot() {
        prepare(); val original = repository.invoice("invoice"); val pdf = "%PDF-synthetic-cache-boundary".toByteArray()
        assertFalse(repository.cacheInvoicePdf("invoice", "different", pdf))
        assertTrue(repository.cacheInvoicePdf("invoice", original.payload, pdf))
        assertFalse(repository.cacheInvoicePdf("invoice", original.payload, "%PDF-other".toByteArray()))
        assertArrayEquals(pdf, repository.invoice("invoice").pdf)
    }
    @Test fun malformedStoredTemplatesReturnFieldErrorsWithoutAllocatingInvoiceNumbers() {
        val original = repository.businessSnapshot().settings
        val before = version
        for (subject in listOf(true, false)) {
            inspect { db -> db.execSQL("UPDATE app_settings SET email_subject=?,email_body=? WHERE id=1",
                arrayOf<Any?>(if (subject) "{unknown}" else original.emailSubject, if (subject) original.emailBody else "{customer_name")) }
            assertEquals(if (subject) "INVALID_EMAIL_SUBJECT" else "INVALID_EMAIL_BODY", prepare("invalid-$subject").conflict)
            assertEquals(before, version)
            inspect { db ->
                db.rawQuery("SELECT next_invoice_number FROM app_settings WHERE id=1", null).use { assertTrue(it.moveToFirst()); assertEquals(1000L, it.getLong(0)) }
                db.rawQuery("SELECT COUNT(*) FROM invoices", null).use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
            }
        }
        assertNull(repository.saveBusinessSettings("correct-templates", version, original).conflict)
        assertNull(prepare("corrected-invoice").conflict)
        assertEquals("INV-1000", repository.invoice("invoice").number)
    }

    @Test fun firstNumberIs1000AndLegacyOrHigherCountersNeverRewritePreparedInvoices() {
        inspect { it.execSQL("UPDATE app_settings SET next_invoice_number=1 WHERE id=1") }
        assertNull(prepare("legacy-sequence", "legacy-invoice").conflict)
        val first = repository.invoice("legacy-invoice")
        assertEquals("INV-1000", first.number)
        inspect { db ->
            db.rawQuery("SELECT next_invoice_number FROM app_settings WHERE id=1", null).use { assertTrue(it.moveToFirst()); assertEquals(1001L, it.getLong(0)) }
            db.execSQL("UPDATE app_settings SET next_invoice_number=3200 WHERE id=1")
        }
        assertNull(repository.createManualSession("sequence-work", version, SessionHistoryStore.Draft("sequence-work", job, 1793494800000, 1793498400000, "UTC", 0)).conflict)
        assertNull(prepare("higher-sequence", "higher-invoice").conflict)
        assertEquals("INV-3200", repository.invoice("higher-invoice").number)
        assertEquals(first.payload, repository.invoice("legacy-invoice").payload)
        inspect { db -> db.rawQuery("SELECT next_invoice_number FROM app_settings WHERE id=1", null).use { assertTrue(it.moveToFirst()); assertEquals(3201L, it.getLong(0)) } }
    }

}
