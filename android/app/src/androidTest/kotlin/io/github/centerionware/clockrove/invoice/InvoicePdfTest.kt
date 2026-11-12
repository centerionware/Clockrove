package io.github.centerionware.clockrove.invoice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.github.centerionware.clockrove.tracking.InvoiceStore
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class InvoicePdfTest {
    private fun fixture(style: String, lines: Int = 3) = InvoiceStore.Snapshot("INV-000042", 1793534400000, style, "USD", 2,
        "Riverside Studio", "billing@example.test\n+1 555 0100\n12 Synthetic Street", "Synthetic Customer", "customer@example.test\n24 Synthetic Avenue",
        "Kitchen renovation", "2026-11-01 — 2026-11-30 · America/New_York", "Completed billable tasks · fixed prices", "Thank you for choosing Riverside Studio.",
        "Payment details: synthetic fixture only.\nPlease include the invoice number in your reference.", "1343.75", "customer@example.test", "Invoice INV-000042", "Hello\nInvoice attached.",
        (1..lines).map { InvoiceStore.Line("Install tiles — section $it", "Completed 2026-11-12 15:00:00.000 -05:00", "${if (it == 1) "1250.00" else "0.00"}") }, subtotal = "1250.00", taxRateUnits = 75000, taxAmount = "93.75")
    private fun logo(): ByteArray {
        val b = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.WHITE); drawCircle(40f, 40f, 28f, Paint().apply { color = Color.rgb(23,107,85) }); drawText("RS", 78f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it); b.recycle() }.toByteArray()
    }
    private fun inspect(bytes: ByteArray, block: (PdfRenderer) -> Unit) {
        assertTrue(bytes.size > 1000); assertEquals("%PDF-", bytes.take(5).toByteArray().toString(Charsets.US_ASCII))
        val file = File.createTempFile("synthetic-invoice", ".pdf", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try { file.writeBytes(bytes); ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(block) } } finally { file.delete() }
    }
    private fun exactMoneyPdf(snapshot: InvoiceStore.Snapshot, name: String, expected: List<String>) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "invoice-previews").apply { mkdirs() }
        val bytes = InvoicePdf.generate(snapshot, logo())
        File(root, "$name.pdf").writeBytes(bytes)
        inspect(bytes) { renderer ->
            val text = StringBuilder()
            for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    // Remove only known page furniture before joining wrapped text.
                    // An exact number can span pages; never strip/reformat its digits.
                    val compact = PdfTextApi35.read(page).filterNot { it.isWhitespace() }
                    text.append(compact.replace("Page${index + 1}·${snapshot.number}", "")
                        .replace("INVOICE${snapshot.number}", "").replace("Invoice${snapshot.number}", ""))
                }
                if (index == 0 || index == renderer.pageCount - 1) {
                    val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val suffix = if (index == 0) "" else "-page-${index + 1}"
                        File(root, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                File(root, "$name-extracted.txt").writeText(text.toString())
                expected.groupingBy { it }.eachCount().forEach { (value, required) ->
                    var count = 0; var offset = 0
                    while (offset < text.length) {
                        val found = text.indexOf(value, offset)
                        if (found < 0) break
                        count++; offset = found + value.length
                    }
                    assertTrue("Exact amount missing or clipped in $name: expected $required copies, got $count", count >= required)
                }
            }
        }
    }
    @Test fun wideMoneyRemainsCompleteInAllActualPdfStyles() {
        val subtotal = "26000000000000000000.00"
        val tax = "1950000000000000000.00"
        val total = "27950000000000000000.00"
        val longValue = "1234567890".repeat(100) + ".00"
        for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
            val snapshot = fixture(style).copy(subtotal = subtotal, taxAmount = tax, total = total,
                lines = listOf(InvoiceStore.Line("Synthetic exact quantity", "26 × 1000000000000000000.00 USD each", subtotal, "260000", "100000000000000000000")))
            exactMoneyPdf(snapshot, "clockrove-wide-invoice-${style.lowercase()}", listOf(subtotal, subtotal, tax, total, "1000000000000000000.00USDeach"))
            val longSnapshot = fixture(style).copy(subtotal = longValue, taxRateUnits = 0, taxAmount = "0.00", total = longValue,
                lines = listOf(InvoiceStore.Line("Synthetic exact long amount", "One unit, exact decimal text", longValue)))
            exactMoneyPdf(longSnapshot, "clockrove-long-exact-invoice-${style.lowercase()}",
                listOf(longValue, longValue, longValue, "SubtotalUSD", "TOTALUSD"))
        }
    }
    @Test fun allThreeStylesProduceActualReadablePdfPagesWithLogoAndEqualTotals() {
        val images = mutableListOf<Int>()
        for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
            val snapshot = fixture(style); val bytes = InvoicePdf.generate(snapshot, logo())
            val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "invoice-previews")
            root.mkdirs(); File(root, "clockrove-invoice-${style.lowercase()}.pdf").writeBytes(bytes)
            inspect(bytes) { renderer ->
                if (renderer.pageCount != 1) for (index in setOf(0, renderer.pageCount - 1)) renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(root, "clockrove-invoice-${style.lowercase()}-failure-page-${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
                assertEquals("Short $style invoice should fit on one page", 1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    assertNotEquals(Color.TRANSPARENT, bitmap.getPixel(100,100)); images.add(bitmap.getPixel(10,10))
                    if (android.os.Build.VERSION.SDK_INT >= 35) {
                        val text = PdfTextApi35.read(page)
                        val compact = text.filterNot { it.isWhitespace() }
                        assertTrue(compact.contains("USD1343.75")); assertTrue(compact.contains("SubtotalUSD1250.00")); assertTrue(compact.contains("Tax(7.5%)USD93.75"))
                        assertTrue(text.contains("INV-000042")); assertTrue(text.contains("Synthetic Customer"))
                    }
                    File(root, "clockrove-invoice-${style.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
            }
            assertEquals("1343.75", snapshot.total)
        }
        assertEquals(3, images.distinct().size)
    }
    @Test fun blankOptionalFieldsKeepTheActualTotalOnTheFinalPage() {
        val data = fixture("MODERN", 20).copy(company = "", companyContact = "", customer = "", customerContact = "", header = "", footer = "")
        inspect(InvoicePdf.generate(data)) { renderer ->
            renderer.openPage(renderer.pageCount - 1).use { page ->
                if (android.os.Build.VERSION.SDK_INT >= 35) assertTrue(PdfTextApi35.read(page).contains("TOTAL"))
            }
        }
    }
    @Test fun longRowsHeadersAndFootersPaginateIntoRealPdfPages() {
        for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
            val data = fixture(style, 130).copy(header = "Long optional header. ".repeat(400), footer = "Long optional footer. ".repeat(400))
            val bytes = InvoicePdf.generate(data)
            val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "invoice-previews")
            root.mkdirs()
            File(root, "clockrove-invoice-${style.lowercase()}-multipage.pdf").writeBytes(bytes)
            inspect(bytes) { renderer ->
                assertTrue(renderer.pageCount > 8)
                val text = StringBuilder()
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        if (android.os.Build.VERSION.SDK_INT >= 35) text.append(PdfTextApi35.read(page)).append('\n')
                        if (index in setOf(0, renderer.pageCount / 2, renderer.pageCount - 1)) {
                            val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                File(root, "clockrove-invoice-${style.lowercase()}-multipage-page-${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            } finally { bitmap.recycle() }
                        }
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    File(root, "clockrove-invoice-${style.lowercase()}-multipage-extracted.txt").writeText(text.toString())
                    try {
                        // Framework extraction can put a line break between
                        // individual glyphs even though the page renders normally.
                        // Keep their order and require each complete priced row.
                        val compact = text.toString().filterNot { it.isWhitespace() }
                        assertTrue(compact.contains("USD1343.75")); assertTrue(compact.contains("SubtotalUSD1250.00")); assertTrue(compact.contains("Tax(7.5%)USD93.75"))
                        data.lines.forEachIndexed { index, line ->
                            val pricedRow = (line.title + line.amount).filterNot { it.isWhitespace() }
                            assertTrue("Missing priced row ${index + 1} in $style", compact.contains(pricedRow))
                        }
                        assertTrue(compact.contains("Longoptionalfooter."))
                    } catch (failure: AssertionError) {
                        // Preserve every failing page for comparison with the
                        // actual framework extraction; never synthesize a pass.
                        for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                File(root, "clockrove-invoice-${style.lowercase()}-failure-page-${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            } finally { bitmap.recycle() }
                        }
                        throw failure
                    }
                }
            }
        }
    }
}

/** Keep API-35-only types out of JUnit's eagerly reflected API-24 test class. */
@android.annotation.TargetApi(35)
private object PdfTextApi35 {
    fun read(page: PdfRenderer.Page): String = page.textContents.joinToString(" ") { it.text }
}
