package io.github.centerionware.billingtest

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.centerionware.clockrove.invoice.InvoiceDocuments
import io.github.centerionware.clockrove.tracking.BusinessStore
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.QuoteStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.io.File
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real issued quotes -> Android PDF -> rendered pages and exact extracted text. */
@RunWith(AndroidJUnit4::class)
class QuotePdfDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = TimerRepository.get(app)
    private val version get() = repository.snapshot().version
    private fun render(id: String, revision: Int, name: String, expected: List<String>): InvoiceStore.Record {
        val record = InvoiceDocuments.ensureQuote(repository, id, revision)
        val directory = File(app.getExternalFilesDir(null), "invoice-previews").apply { mkdirs() }
        File(directory, "$name.pdf").writeBytes(requireNotNull(record.pdf))
        val file = File.createTempFile("quote-real-", ".pdf", app.cacheDir)
        val text = StringBuilder()
        try {
            file.writeBytes(requireNotNull(record.pdf))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { pdf ->
                assertTrue(pdf.pageCount > 0)
                if (!name.contains("wide")) assertEquals("Short quote must fit one page: $name", 1, pdf.pageCount)
                for (index in 0 until pdf.pageCount) pdf.openPage(index).use { page ->
                    if (android.os.Build.VERSION.SDK_INT >= 35) {
                        val compact = CopyPdfTextApi35.read(page).filterNot { it.isWhitespace() }
                        assertTrue("Missing QUOTE heading on $name page ${index + 1}", compact.contains("QUOTE"))
                        text.append(compact.replace("Page${index + 1}·${record.number}·Revision$revision", "")
                            .replace("QUOTE", "").replace("${record.number}·Revision$revision", ""))
                    }
                    if (index == 0 || index == pdf.pageCount - 1) {
                        val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val suffix = if (index == 0) "" else "-page-${index + 1}"
                            File(directory, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } finally { bitmap.recycle() }
                    }
                }
            } }
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                File(directory, "$name-extracted.txt").writeText(text.toString())
                for (value in expected) assertTrue("Missing exact quote text $value in $name", text.contains(value))
                assertTrue(text.contains("ESTIMATEDTOTAL")); assertFalse(text.contains("BALANCEDUE"))
            }
        } finally { file.delete() }
        val email = InvoiceDocuments.email(app, record, repository.businessSnapshot().settings)
        assertArrayEquals(arrayOf("quote-customer@example.test"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertArrayEquals(arrayOf("accounts@example.test"), email.getStringArrayExtra(Intent.EXTRA_CC))
        val uri = InvoiceDocuments.uri(app, record)
        assertEquals(listOf("quotes", id, revision.toString()), uri.pathSegments)
        assertEquals(uri, email.clipData!!.getItemAt(0).uri)
        app.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(record.pdf, it.readBytes()) }
        assertTrue(email.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, email.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(email.getStringExtra(Intent.EXTRA_SUBJECT)!!.startsWith("Quote ${record.number}"))
        return record
    }
    @Test fun allStylesPreserveIssuedRevisionsAndExactWideEstimatesWithoutBillingWork() {
        fun financialState(): List<String> = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf("sessions", "kanban_cards", "invoices", "invoice_payments", "invoice_task_claims", "invoice_time_claims").map { table ->
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getString(0) }
            } + db.rawQuery("SELECT next_invoice_number FROM app_settings", null).use { it.moveToFirst(); it.getString(0) }
        }
        repository.snapshot()
        val financialBefore = financialState()
        assertNull(repository.saveCustomer("quote-real-customer", version, BusinessStore.Customer("quote-real-customer", "Synthetic quote customer", "quote-customer@example.test")).conflict)
        for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
            val token = style.lowercase(java.util.Locale.ROOT); val id = "quote-real-$token"
            assertNull(repository.saveBusinessSettings("quote-settings-$token", version, repository.businessSnapshot().settings.copy(currency = "USD", companyName = "Riverside Studio", companyEmail = "company@example.test", receivablesEmail = "accounts@example.test", invoiceStyle = style)).conflict)
            val draft = QuoteStore.Draft(id, "quote-real-customer", null, "Synthetic tiled floor estimate", "USD", 75000,
                listOf(QuoteStore.Line("tile", "Install synthetic tile", "Estimated area", "ITEM", "260000", "1250"),
                    QuoteStore.Line("hours", "Estimated preparation", "Estimate only", "HOURS", "15000", "4000")),
                "2026-12-01", "Valid after local acceptance. No advance payment requested.", "Synthetic notes")
            assertNull(repository.saveQuote("quote-save-$token", version, draft).conflict)
            assertNull(repository.issueQuote("quote-issue-$token", version, id, true).conflict)
            val original = render(id, 1, "clockrove-quote-$token", listOf("385.00", "28.88", "413.88", "Validuntil2026-12-01", "26units×12.50USDeach", "1.5estimatedhours×40.00USD/hour"))
            assertNull(repository.saveQuote("quote-revise-$token", version, draft.copy(lines = draft.lines.map { if (it.id == "tile") it.copy(unitPrice = "1350") else it })).conflict)
            assertNull(repository.issueQuote("quote-reissue-$token", version, id, true).conflict)
            render(id, 2, "clockrove-quote-revision-$token", listOf("411.00", "30.83", "441.83"))
            assertArrayEquals(original.pdf, repository.quoteDocument(id, 1).pdf)
            assertTrue(InvoiceDocuments.historicalNotice(repository, original)!!.contains("current issued revision is 2"))
            val wide = "9".repeat(1000)
            assertNull(repository.saveQuote("quote-wide-save-$token", version, draft.copy(lines = listOf(draft.lines.first().copy(quantity = "10000", unitPrice = wide)))).conflict)
            assertNull(repository.issueQuote("quote-wide-issue-$token", version, id, true).conflict)
            val subtotal = BigInteger(wide); val tax = subtotal.multiply(BigInteger("75000")).add(BigInteger("500000")).divide(BigInteger("1000000"))
            render(id, 3, "clockrove-quote-wide-$token", listOf(InvoiceStore.amount(subtotal, 2), InvoiceStore.amount(tax, 2), InvoiceStore.amount(subtotal.add(tax), 2)))
        }
        assertEquals(financialBefore, financialState())
    }
}
