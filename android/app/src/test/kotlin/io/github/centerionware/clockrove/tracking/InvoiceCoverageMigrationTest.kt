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
@Config(sdk = [24, 35])
class InvoiceCoverageMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var repository: TimerRepository? = null
    private val now = 1793577600000L
    private val originalPdf = "%PDF-legacy-fixture".toByteArray()
    // Released snapshot shape has no task/source identity or tax fields.
    private val originalPayload = JSONObject(mapOf("number" to "INV-1000", "created" to now,
        "style" to "MODERN", "currency" to "USD", "digits" to 2,
        "company" to "Synthetic company", "companyContact" to "", "customer" to "Synthetic customer", "customerContact" to "",
        "job" to "Synthetic legacy job", "period" to "2026-11-01 — 2026-11-01 · UTC", "basis" to "Finalized time · hourly rate",
        "header" to "", "footer" to "", "total" to "100.00", "email" to "synthetic@example.test", "subject" to "Invoice INV-1000",
        "body" to "Synthetic invoice", "lines" to JSONArray().put(JSONObject(mapOf("title" to "Finalized work", "detail" to "3600 seconds", "amount" to "100.00"))))).toString()
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); app.getDatabasePath("clockrove.db").parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("clockrove.db"), null).use { db ->
            javaClass.getResourceAsStream("/schema_v6.sql")!!.bufferedReader().use { source ->
                source.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
            }
            db.execSQL("INSERT INTO jobs(id,name) VALUES('legacy-job','Synthetic legacy job')")
            db.execSQL("INSERT INTO sessions VALUES('legacy-work','legacy-job',1793491200000,1793494800000,'UTC',0,'MANUAL')")
            db.execSQL("INSERT INTO location_points VALUES('legacy-point','legacy-work',1793491201000,1793491202000,1.0,2.0,3.0)")
            db.execSQL("INSERT INTO widget_configurations VALUES(17,'legacy-job','legacy-widget-token')")
            db.execSQL("INSERT INTO invoices(id,job_id,number,created_ms,payload,pdf) VALUES('legacy-invoice','legacy-job','INV-1000',?,?,?)", arrayOf<Any?>(now, originalPayload, originalPdf))
            db.execSQL("UPDATE app_settings SET next_invoice_number=1001,default_hourly_minor=10000")
            db.execSQL("UPDATE state SET version=42")
            db.execSQL("INSERT INTO commands VALUES('legacy-command','synthetic-opaque-fingerprint',42,NULL)")
            db.version = 6
        }
    }
    @After fun close() { repository?.close() }
    private fun open() = TimerRepository(app, wallClock = { now }).also { repository = it }
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    @Test fun releasedVersionSixMigratesWithoutRewritingDocumentsWorkWidgetsOrReceipts() {
        val repo = open()
        assertEquals(42L, repo.snapshot().version)
        val invoice = repo.invoice("legacy-invoice")
        assertEquals(originalPayload, invoice.payload); assertArrayEquals(originalPdf, invoice.pdf)
        assertNull(InvoiceStore.Snapshot.decode(invoice.payload).taxRateUnits)
        assertNull(InvoiceStore.Snapshot.decode(invoice.payload).taxAmount)
        assertEquals(0, repo.businessSnapshot().settings.defaultTaxUnits)
        assertEquals(java.math.BigInteger.valueOf(10000), repo.businessSnapshot().settings.defaultHourlyMinor)
        assertNull(repo.jobDetail("legacy-job").job.settings.taxUnits)
        assertEquals("legacy-widget-token", repo.widgetSnapshot(17).configuration.token)
        assertEquals("legacy-point", repo.locationPoints("legacy-work").single().id)
        inspect { db ->
            assertEquals(10, db.version)
            db.rawQuery("SELECT state,reviewed_ms FROM invoice_coverage WHERE invoice_id='legacy-invoice'", null).use { assertTrue(it.moveToFirst()); assertEquals("LEGACY", it.getString(0)); assertTrue(it.isNull(1)) }
            db.rawQuery("SELECT fingerprint,version FROM commands WHERE id='legacy-command'", null).use { assertTrue(it.moveToFirst()); assertEquals("synthetic-opaque-fingerprint", it.getString(0)); assertEquals(42L, it.getLong(1)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun releasedTasksAndTemplatesKeepTheirSinglePriceAndDefaultToOneUnit() {
        inspect { db ->
            db.execSQL("INSERT INTO kanban_columns VALUES('done','legacy-job','Done',0,1)")
            db.execSQL("INSERT INTO task_templates VALUES('template','Synthetic tile','',500,'USD','legacy-job','tile')")
            db.execSQL("INSERT INTO kanban_cards(id,job_id,column_id,title,position,completed_ms,price_minor,currency,template_id) VALUES('tile','legacy-job','done','Synthetic tile',0,1793494800000,500,'USD','template')")
        }
        val repo = open()
        val card = repo.boardSnapshot("legacy-job").board.cards.single()
        assertEquals(java.math.BigInteger.valueOf(500L), card.priceMinor); assertEquals(10000L, card.quantityUnits)
        val template = repo.taskTemplates().single()
        assertEquals(java.math.BigInteger.valueOf(500L), template.priceMinor); assertEquals(10000L, template.quantityUnits)
        val task = repo.report(ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf("legacy-job"))).report.tasks.single()
        assertEquals(10000L, task.quantityUnits); assertEquals(java.math.BigInteger.valueOf(500L), task.priceMinor)
        assertEquals(originalPayload, repo.invoice("legacy-invoice").payload)
        inspect { db ->
            db.rawQuery("SELECT typeof(minor_text),minor_text FROM card_money WHERE id='tile'", null).use { it.moveToFirst(); assertEquals("text", it.getString(0)); assertEquals("500", it.getString(1)) }
            db.rawQuery("SELECT minor_text FROM template_money WHERE id='template'", null).use { it.moveToFirst(); assertEquals("500", it.getString(0)) }
        }
    }
    @Test fun ambiguousLegacyCoverageBlocksNewBillingWithoutInventingClaimsOrNumbers() {
        val repo = open()
        val result = repo.prepareInvoice("new-invoice", 42, "new-invoice", ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf("legacy-job")))
        assertEquals("LEGACY_INVOICE_REVIEW_REQUIRED", result.conflict)
        assertEquals(42L, repo.snapshot().version)
        inspect { db ->
            db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1001L, it.getLong(0)) }
            for (table in listOf("invoice_task_claims", "invoice_time_claims")) db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            val preview = InvoiceCoverageStore.preview(db, ReportStore.read(db, ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf("legacy-job"))))
            assertEquals(listOf("legacy-invoice" to "INV-1000"), preview.legacy)
        }
        assertEquals(originalPayload, repo.invoice("legacy-invoice").payload)
    }
    @Test fun failedMigrationRollsBackVersionDocumentsForeignKeysAndAllNewTables() {
        inspect { it.execSQL("CREATE TABLE invoice_coverage(conflicting TEXT)") }
        try { open().snapshot(); fail("Malformed migration succeeded") } catch (_: android.database.SQLException) {}
        inspect { db ->
            assertEquals(6, db.version)
            db.rawQuery("SELECT payload,pdf FROM invoices WHERE id='legacy-invoice'", null).use { assertTrue(it.moveToFirst()); assertEquals(originalPayload, it.getString(0)); assertArrayEquals(originalPdf, it.getBlob(1)) }
            db.rawQuery("PRAGMA foreign_key_list(invoices)", null).use { rows -> assertTrue(generateSequence { if (rows.moveToNext()) rows.getString(2) else null }.any { it == "jobs" }) }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name IN('invoice_time_claims','invoice_task_claims','invoices_retained')", null).use { assertFalse(it.moveToFirst()) }
            db.rawQuery("SELECT COUNT(*) FROM sessions", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }
}
