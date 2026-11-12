package io.github.centerionware.billingtest

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.centerionware.clockrove.tracking.InvoiceBlobs
import io.github.centerionware.clockrove.tracking.InvoiceCopyStore
import io.github.centerionware.clockrove.tracking.InvoiceStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite/descriptor boundary. Opaque patterned bytes are NOT rendered-PDF evidence. */
@RunWith(AndroidJUnit4::class)
class InvoiceBlobStorageDeviceTest {
    @Test fun originalCopyAndLogoBytesSurviveActualCursorWindowLimit() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("document-storage-probe-", ".db", app.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                // Only storage reader columns are needed; this is independent of
                // the live app database, numbering and the all-style PDF fixtures.
                db.execSQL("CREATE TABLE invoices(id TEXT PRIMARY KEY,job_id TEXT,number TEXT,payload TEXT,pdf BLOB,logo_id TEXT)")
                db.execSQL("CREATE TABLE logo_assets(id TEXT PRIMARY KEY,bytes BLOB)")
                db.execSQL("CREATE TABLE invoice_copies(id TEXT PRIMARY KEY,invoice_id TEXT,kind TEXT,created_ms INTEGER,command_version INTEGER,metadata TEXT,pdf BLOB)")
                db.execSQL("CREATE TABLE invoice_payments(id TEXT,method TEXT,reference TEXT)")
                db.execSQL("CREATE TABLE invoice_payment_events(id TEXT,invoice_id TEXT,payment_id TEXT,kind TEXT,effective_ms INTEGER,payload TEXT,command_version INTEGER,ordinal INTEGER)")
                val original = ByteArray(3_100_000) { (it * 31).toByte() }
                val copy = ByteArray(3_200_000) { (it * 17 + 3).toByte() }
                val logo = ByteArray(1_900_000) { (it * 7 + 1).toByte() }
                val document = InvoiceStore.Snapshot(number = "INV-STORAGE", created = 1L, style = "MINIMAL", currency = "USD", digits = 2,
                    company = "Synthetic storage probe", companyContact = "", customer = "", customerContact = "", job = "Storage only",
                    period = "Synthetic", basis = "Opaque storage bytes; not a rendered PDF", header = "", footer = "", total = "1.00", email = "",
                    subject = "Storage probe", body = "Synthetic storage-only fixture", lines = emptyList())
                val payload = document.encode()
                db.execSQL("INSERT INTO logo_assets VALUES('logo',?)", arrayOf(logo))
                db.execSQL("INSERT INTO invoices VALUES('original','job','INV-STORAGE',?,?,'logo')", arrayOf<Any?>(payload, original))
                val metadata = JSONObject(mapOf("id" to "balance-storage", "invoice_id" to "original", "kind" to "BALANCE", "created_ms" to 1L,
                    "command_version" to 1L, "paid_minor" to "0", "due_minor" to "100", "event_id" to null)).toString()
                db.execSQL("INSERT INTO invoice_copies VALUES('balance-storage','original','BALANCE',1,1,?,?)", arrayOf<Any?>(metadata, copy))
                var cursorRejected = false
                try {
                    db.rawQuery("SELECT pdf FROM invoices WHERE id='original'", null).use {
                        cursorRejected = if (!it.moveToFirst()) true else { it.getBlob(0); false }
                    }
                } catch (failure: SQLiteException) {
                    val message = failure.message.orEmpty()
                    if (!message.contains("too big", true) && !message.contains("CursorWindow", true)) throw failure
                    cursorRejected = true
                } catch (failure: IllegalStateException) {
                    // API 24 can report an empty oversized window at getBlob,
                    // rather than throwing SQLiteBlobTooBigException at fill.
                    if (!failure.message.orEmpty().startsWith("Couldn't read row 0, col 0 from CursorWindow.")) throw failure
                    cursorRejected = true
                }
                if (Build.VERSION.SDK_INT == 24) assertTrue("Stock Android 7 must reproduce the oversized cursor row", cursorRejected)
                val record = InvoiceStore.read(db, "original")
                assertEquals(payload, record.payload); assertArrayEquals(original, record.pdf); assertArrayEquals(logo, record.logo)
                val savedCopy = InvoiceCopyStore.read(db, "balance-storage")
                assertArrayEquals(copy, savedCopy.pdf); assertArrayEquals(logo, savedCopy.logo)
                assertEquals("0.00", InvoiceStore.Snapshot.decode(savedCopy.payload).statement!!.paid)
                assertNull(InvoiceStore.read(db, "original", includePdf = false).pdf)
                val small = "Synthetic small storage bytes".toByteArray()
                db.execSQL("UPDATE invoices SET pdf=? WHERE id='original'", arrayOf(small))
                assertArrayEquals(small, InvoiceBlobs.original(db, "original"))
                db.execSQL("UPDATE invoices SET pdf=NULL WHERE id='original'")
                assertNull(InvoiceBlobs.original(db, "original"))
                db.execSQL("UPDATE logo_assets SET bytes=? WHERE id='logo'", arrayOf(ByteArray(2_000_001)))
                try { InvoiceBlobs.logo(db, "logo"); fail("Oversized logo bytes must be rejected before allocation") }
                catch (expected: IllegalStateException) { assertEquals("INVALID_INVOICE_BLOB", expected.message) }
                android.util.Log.i("ClockroveDocumentStorage", "Verified bounded original/copy/logo reads; cursorRejected=$cursorRejected api=${Build.VERSION.SDK_INT}")
            }
        } finally {
            file.delete(); File(file.path + "-journal").delete(); File(file.path + "-wal").delete(); File(file.path + "-shm").delete()
        }
    }
}
