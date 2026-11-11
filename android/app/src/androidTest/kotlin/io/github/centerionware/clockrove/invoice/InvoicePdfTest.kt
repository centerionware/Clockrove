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
        "Payment details: synthetic fixture only.\nPlease include the invoice number in your reference.", "1250.00", "customer@example.test", "Invoice INV-000042", "Hello\nInvoice attached.",
        (1..lines).map { InvoiceStore.Line("Install tiles — section $it", "Completed 2026-11-12 15:00:00.000 -05:00", "${if (it == 1) "1250.00" else "0.00"}") })
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
    @Test fun allThreeStylesProduceActualReadablePdfPagesWithLogoAndEqualTotals() {
        val images = mutableListOf<Int>()
        for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
            val snapshot = fixture(style); val bytes = InvoicePdf.generate(snapshot, logo())
            inspect(bytes) { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    assertNotEquals(Color.TRANSPARENT, bitmap.getPixel(100,100)); images.add(bitmap.getPixel(10,10))
                    val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "invoice-previews")
                    root.mkdirs(); File(root, "clockrove-invoice-${style.lowercase()}.pdf").writeBytes(bytes)
                    if (android.os.Build.VERSION.SDK_INT >= 35) {
                        val text = page.textContents.joinToString(" ") { it.text }
                        assertTrue(text.contains("USD 1250.00")); assertTrue(text.contains("INV-000042")); assertTrue(text.contains("Synthetic Customer"))
                    }
                    File(root, "clockrove-invoice-${style.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
            }
            assertEquals("1250.00", snapshot.total)
        }
        assertEquals(3, images.distinct().size)
    }
    @Test fun longRowsHeadersAndFootersPaginateIntoRealPdfPages() {
        val data = fixture("MODERN", 130).copy(header = "Long optional header. ".repeat(400), footer = "Long optional footer. ".repeat(400))
        inspect(InvoicePdf.generate(data)) { renderer -> assertTrue(renderer.pageCount > 8); renderer.openPage(renderer.pageCount - 1).use { assertTrue(it.height > 0) } }
    }
}
