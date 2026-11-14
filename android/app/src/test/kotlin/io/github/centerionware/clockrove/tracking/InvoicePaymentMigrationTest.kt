package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Independent frozen released schema, exported by Actions 43190055836. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class InvoicePaymentMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var repository: TimerRepository? = null
    private val at = 1793494800000L
    private val wide = "1234567890123456789012345678901234567890"
    private val pdf = "%PDF-synthetic-retained-v7".toByteArray()
    private val payload = """{"number":"INV-1000","created":1793494800000,"style":"MODERN","currency":"USD","digits":2,"company":"Synthetic company","companyContact":"","customer":"Synthetic customer","customerContact":"","job":"Synthetic v7 job","period":"2026-11-01","basis":"Completed tasks","header":"","footer":"","total":"107.50","email":"synthetic@example.test","subject":"Invoice INV-1000","body":"Synthetic invoice","lines":[{"title":"Synthetic tile","detail":"1 unit","amount":"100.00","quantityUnits":"10000","unitPriceMinor":"10000"}],"subtotal":"100.00","taxRateUnits":75000,"taxAmount":"7.50"}"""
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); app.getDatabasePath("clockrove.db").parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("clockrove.db"), null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            val statements = JSONArray(javaClass.getResourceAsStream("/schema_v7.json")!!.bufferedReader().use { it.readText() })
            for (index in 0 until statements.length()) db.execSQL(statements.getString(index))
            assertEquals(7, db.version)
            db.execSQL("INSERT INTO jobs(id,name) VALUES('job','Synthetic v7 job')")
            db.execSQL("INSERT INTO kanban_columns VALUES('done','job','Done',0,1)")
            db.execSQL("INSERT INTO task_templates VALUES('template','Synthetic tile','',NULL,'USD','job','tile')")
            db.execSQL("INSERT INTO template_money VALUES('template',?)", arrayOf(wide))
            db.execSQL("INSERT INTO template_quantities VALUES('template',260000)")
            db.execSQL("INSERT INTO kanban_cards(id,job_id,column_id,title,position,completed_ms,currency,template_id) VALUES('tile','job','done','Synthetic tile',0,1793494800000,'USD','template')")
            db.execSQL("INSERT INTO card_money VALUES('tile',?)", arrayOf(wide))
            db.execSQL("INSERT INTO task_quantities VALUES('tile',260000)")
            db.execSQL("INSERT INTO widget_configurations VALUES(17,'job','synthetic-v7-widget-token')")
            db.execSQL("INSERT INTO invoices(id,job_id,number,created_ms,payload,pdf) VALUES('invoice','job','INV-1000',?,?,?)", arrayOf<Any?>(at, payload, pdf))
            db.execSQL("INSERT INTO invoice_coverage VALUES('invoice','COMPLETE',?,'Synthetic v7 review')", arrayOf(at))
            db.execSQL("INSERT INTO invoice_task_claims VALUES('claim','invoice','job','tile',NULL)")
            // Installing schema 8 must not parse or rewrite unrelated old payloads.
            db.execSQL("INSERT INTO invoices(id,job_id,number,created_ms,payload) VALUES('bad-old-document','deleted-job','INV-1001',?,'unreadable synthetic payload')", arrayOf(at))
            db.execSQL("INSERT INTO invoice_coverage(invoice_id,state) VALUES('bad-old-document','LEGACY')")
            db.execSQL("UPDATE app_settings SET next_invoice_number=1002")
            db.execSQL("UPDATE state SET version=42")
            db.execSQL("INSERT INTO commands VALUES('old-command','synthetic-opaque-receipt',42,NULL)")
        }
    }
    @After fun close() { repository?.close() }
    @Test fun versionSevenUpgradePreservesExactMoneyDocumentsClaimsReceiptsAndAddsEmptyLedger() {
        val repo = TimerRepository(app, wallClock = { at }).also { repository = it }
        assertEquals(42L, repo.snapshot().version)
        assertEquals("", repo.businessSnapshot().settings.receivablesEmail)
        assertEquals(BigInteger(wide), repo.boardSnapshot("job").board.cards.single().priceMinor)
        assertEquals(260000L, repo.boardSnapshot("job").board.cards.single().quantityUnits)
        assertEquals(TaskClassification.UNCLASSIFIED, repo.boardSnapshot("job").board.cards.single().chargeType)
        assertEquals(BigInteger(wide), repo.taskTemplates().single().priceMinor)
        assertEquals(TaskClassification.UNCLASSIFIED, repo.taskTemplates().single().chargeType)
        assertEquals(TaskClassification.UNCLASSIFIED, InvoiceStore.Snapshot.decode(payload).lines.single().chargeType)
        assertEquals(payload, repo.invoice("invoice").payload); assertArrayEquals(pdf, repo.invoice("invoice").pdf)
        assertEquals("synthetic-v7-widget-token", repo.widgetSnapshot(17).configuration.token)
        assertTrue(repo.invoicePayments("invoice").second.payments.isEmpty())
        assertEquals(BigInteger.valueOf(10750), repo.invoicePayments("invoice").second.balance.due)
        assertNull(repo.recordInvoicePayment("record", 42, InvoicePaymentStore.Draft("payment", "invoice", "3000", "USD", at, "UTC")).conflict)
        assertEquals(BigInteger.valueOf(7750), repo.invoicePayments("invoice").second.balance.due)
        inspect { db ->
            assertEquals(12, db.version)
            db.rawQuery("SELECT hourly_mode,minimum_hours,task_minimum_minor FROM invoice_billing_defaults WHERE id=1", null).use {
                assertTrue(it.moveToFirst()); assertEquals(InvoiceBillingPolicy.MINIMUM_THEN_EXACT, it.getString(0)); assertEquals("0", it.getString(1)); assertEquals("0", it.getString(2))
            }
            db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1002L, it.getLong(0)) }
            db.rawQuery("SELECT released_ms FROM invoice_task_claims WHERE id='claim'", null).use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
            db.rawQuery("SELECT fingerprint FROM commands WHERE id='old-command'", null).use { it.moveToFirst(); assertEquals("synthetic-opaque-receipt", it.getString(0)) }
            db.rawQuery("SELECT r.payload,r.origin FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='card' AND h.entity_id='tile'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(TaskClassification.UNCLASSIFIED, org.json.JSONObject(it.getString(0)).getString("charge_type"))
                assertEquals("MIGRATION", it.getString(1))
            }
            db.rawQuery("SELECT r.payload,r.origin FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='template' AND h.entity_id='template'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(TaskClassification.UNCLASSIFIED, org.json.JSONObject(it.getString(0)).getString("charge_type"))
                assertEquals("MIGRATION", it.getString(1))
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun failedUpgradeRollsBackNewTablesAndKeepsReleasedFinancialRecords() {
        inspect { it.execSQL("CREATE TABLE invoice_payment_events(marker TEXT)") }
        val repo = TimerRepository(app, wallClock = { at }).also { repository = it }
        try { repo.snapshot(); fail("Conflicting schema must not be overwritten") } catch (_: android.database.SQLException) { }
        inspect { db ->
            assertEquals(7, db.version)
            db.rawQuery("SELECT payload,pdf FROM invoices WHERE id='invoice'", null).use { it.moveToFirst(); assertEquals(payload, it.getString(0)); assertArrayEquals(pdf, it.getBlob(1)) }
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN('invoice_payments','payment_destinations','invoice_accounting_settings')", null).use { assertFalse(it.moveToFirst()) }
            db.rawQuery("PRAGMA table_info(invoice_payment_events)", null).use { assertTrue(it.moveToFirst()); assertEquals("marker", it.getString(1)); assertFalse(it.moveToNext()) }
            db.rawQuery("SELECT version FROM state", null).use { it.moveToFirst(); assertEquals(42L, it.getLong(0)) }
            db.rawQuery("SELECT minor_text FROM card_money WHERE id='tile'", null).use { it.moveToFirst(); assertEquals(wide, it.getString(0)) }
        }
    }
}
