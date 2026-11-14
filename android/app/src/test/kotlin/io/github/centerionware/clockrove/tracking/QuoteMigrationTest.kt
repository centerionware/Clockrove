package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Frozen v8 SQL is independent of the current installer. Never rewrite it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class QuoteMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var repository: TimerRepository? = null
    private val at = 1793494800000L
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); app.getDatabasePath("clockrove.db").parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("clockrove.db"), null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            val statements = JSONArray(javaClass.getResourceAsStream("/schema_v8.json")!!.bufferedReader().use { it.readText() })
            for (i in 0 until statements.length()) db.execSQL(statements.getString(i))
            assertEquals(8, db.version)
            // Do not parse/rewrite old financial payloads during schema upgrade.
            db.execSQL("INSERT INTO invoices(id,job_id,number,created_ms,payload,pdf) VALUES('invoice','purged-job','INV-1000',?,'synthetic frozen document',?)", arrayOf<Any?>(at, "%PDF-frozen".toByteArray()))
            db.execSQL("INSERT INTO invoice_coverage(invoice_id,state) VALUES('invoice','COMPLETE')")
            db.execSQL("INSERT INTO invoice_task_claims VALUES('claim','invoice','purged-job','task',NULL)")
            db.execSQL("INSERT INTO invoice_payments VALUES('payment','invoice','3000','USD',?,'UTC',0,'Frozen date','Cash','Synthetic reference','Private note',?)", arrayOf<Any?>(at, at))
            db.execSQL("INSERT INTO payment_destinations VALUES('payment','invoice')")
            db.execSQL("INSERT INTO invoice_copies VALUES('receipt','invoice','RECEIPT',?,42,'synthetic frozen metadata',?)", arrayOf<Any?>(at, "%PDF-receipt".toByteArray()))
            db.execSQL("UPDATE app_settings SET next_invoice_number=1001")
            db.execSQL("UPDATE invoice_accounting_settings SET receivables_email='ar@example.test'")
            db.execSQL("UPDATE state SET version=42")
            db.execSQL("INSERT INTO commands VALUES('existing-command','synthetic-opaque-fingerprint',42,NULL)")
        }
    }
    @After fun close() { repository?.close() }
    @Test fun versionEightUpgradeAddsIndependentEmptyQuotesAndRetainsFinancialHistory() {
        val repo = TimerRepository(app, wallClock = { at }).also { repository = it }
        assertEquals(42L, repo.snapshot().version)
        assertEquals("ar@example.test", repo.businessSnapshot().settings.receivablesEmail)
        assertNull(repo.quote("not-created").latest)
        inspect { db ->
            assertEquals(14, db.version)
            for (table in listOf("quotes", "quote_drafts", "quote_revisions", "quote_decisions", "quote_scope_lines", "quote_scope_revisions", "quote_invoice_links")) db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT next_number FROM quote_counter", null).use { it.moveToFirst(); assertEquals(1000L, it.getLong(0)) }
            db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1001L, it.getLong(0)) }
            db.rawQuery("SELECT payload,pdf FROM invoices", null).use { it.moveToFirst(); assertEquals("synthetic frozen document", it.getString(0)); assertArrayEquals("%PDF-frozen".toByteArray(), it.getBlob(1)) }
            db.rawQuery("SELECT amount_minor FROM invoice_payments", null).use { it.moveToFirst(); assertEquals("3000", it.getString(0)) }
            db.rawQuery("SELECT invoice_id FROM payment_destinations", null).use { it.moveToFirst(); assertEquals("invoice", it.getString(0)) }
            db.rawQuery("SELECT metadata,pdf FROM invoice_copies", null).use { it.moveToFirst(); assertEquals("synthetic frozen metadata", it.getString(0)); assertArrayEquals("%PDF-receipt".toByteArray(), it.getBlob(1)) }
            db.rawQuery("SELECT released_ms FROM invoice_task_claims", null).use { it.moveToFirst(); assertTrue(it.isNull(0)) }
            db.rawQuery("SELECT fingerprint FROM commands WHERE id='existing-command'", null).use { it.moveToFirst(); assertEquals("synthetic-opaque-fingerprint", it.getString(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun failedQuoteUpgradeRollsBackAllNewTablesAndPreservesVersionEight() {
        inspect { it.execSQL("CREATE TABLE quote_revisions(marker TEXT)") }
        val repo = TimerRepository(app, wallClock = { at }).also { repository = it }
        try { repo.snapshot(); fail("Conflicting table must not be overwritten") } catch (_: android.database.SQLException) { }
        inspect { db ->
            assertEquals(8, db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN('quotes','quote_counter','quote_drafts','quote_decisions')", null).use { assertFalse(it.moveToFirst()) }
            db.rawQuery("SELECT amount_minor FROM invoice_payments", null).use { it.moveToFirst(); assertEquals("3000", it.getString(0)) }
            db.rawQuery("SELECT version FROM state", null).use { it.moveToFirst(); assertEquals(42L, it.getLong(0)) }
        }
    }
    @Test fun lateScopeSchemaFailureRollsBackQuoteTablesAndKeepsExistingPayments() {
        inspect { it.execSQL("CREATE TABLE quote_scope_lines(marker TEXT)") }
        val repo = TimerRepository(app, wallClock = { at }).also { repository = it }
        try { repo.snapshot(); fail("Conflicting scope table must not be overwritten") } catch (_: android.database.SQLException) { }
        inspect { db ->
            assertEquals(8, db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN('quotes','quote_counter','quote_drafts','quote_revisions','quote_decisions','quote_pdfs','quote_scope_revisions')", null).use { assertFalse(it.moveToFirst()) }
            db.rawQuery("PRAGMA table_info(quote_scope_lines)", null).use { assertTrue(it.moveToFirst()); assertEquals("marker", it.getString(1)); assertFalse(it.moveToNext()) }
            db.rawQuery("SELECT amount_minor FROM invoice_payments", null).use { it.moveToFirst(); assertEquals("3000", it.getString(0)) }
            db.rawQuery("SELECT metadata,pdf FROM invoice_copies", null).use { it.moveToFirst(); assertEquals("synthetic frozen metadata", it.getString(0)); assertArrayEquals("%PDF-receipt".toByteArray(), it.getBlob(1)) }
        }
    }
    @Test fun lateInvoiceOriginSchemaFailureRollsBackScopeAndRetainsReleasedFinancialData() {
        inspect { it.execSQL("CREATE TABLE quote_invoice_links(marker TEXT)") }
        val repo = TimerRepository(app, wallClock = { at }).also { repository = it }
        try { repo.snapshot(); fail("Conflicting origin table must not be overwritten") } catch (_: android.database.SQLException) { }
        inspect { db ->
            assertEquals(8, db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN('quotes','quote_counter','quote_drafts','quote_revisions','quote_decisions','quote_pdfs','quote_scope_lines','quote_scope_revisions')", null).use { assertFalse(it.moveToFirst()) }
            db.rawQuery("PRAGMA table_info(quote_invoice_links)", null).use { assertTrue(it.moveToFirst()); assertEquals("marker", it.getString(1)); assertFalse(it.moveToNext()) }
            db.rawQuery("SELECT amount_minor FROM invoice_payments", null).use { it.moveToFirst(); assertEquals("3000", it.getString(0)) }
            db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); assertEquals(1001L, it.getLong(0)) }
        }
    }

}
