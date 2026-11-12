package io.github.centerionware.clockrove.invoice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.TaxRate
import java.io.ByteArrayOutputStream

/** Three native, paginated layouts. Render on a worker, outside repository writes. */
internal object InvoicePdf {
    fun generate(snapshot: InvoiceStore.Snapshot, logoBytes: ByteArray? = null): ByteArray {
        val logo = logoBytes?.let {
            require(it.size <= 2_000_000) { "INVALID_LOGO" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, options)
            require(options.outWidth in 1..2048 && options.outHeight in 1..2048) { "INVALID_LOGO" }
            requireNotNull(BitmapFactory.decodeByteArray(it, 0, it.size)) { "INVALID_LOGO" }
        }
        try {
            val document = PdfDocument()
            try {
                val flow = Flow(document, snapshot, logo)
                flow.start()
                flow.text(snapshot.company, 22f, bold = true)
                flow.text(snapshot.companyContact, 10f)
                flow.text("Issued " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(snapshot.created)) + " UTC", 9f)
                flow.space(16f)
                if (snapshot.customer.isNotBlank() || snapshot.customerContact.isNotBlank()) {
                    flow.text("BILL TO", 9f, bold = true)
                    flow.text(snapshot.customer, 14f, bold = true)
                    flow.text(snapshot.customerContact, 10f)
                    flow.space(16f)
                }
                flow.text(snapshot.job, 18f, bold = true)
                flow.text(snapshot.period, 10f)
                flow.text(snapshot.basis, 9f)
                flow.space(12f)
                flow.text(snapshot.header, 11f)
                flow.space(12f)
                flow.tableHeader()
                snapshot.lines.forEach { flow.line(it) }
                flow.total()
                if (snapshot.footer.isNotBlank()) {
                    flow.space(20f)
                    flow.text(snapshot.footer, 10f)
                }
                flow.finish()
                return ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
            } finally { document.close() }
        } finally { logo?.recycle() }
    }
    private class Flow(val document: PdfDocument, val data: InvoiceStore.Snapshot, val logo: Bitmap?) {
        val modern = data.style == "MODERN"
        val classic = data.style == "CLASSIC"
        val ink = if (modern) Color.rgb(23, 107, 85) else if (classic) Color.rgb(29, 46, 72) else Color.rgb(25, 29, 33)
        val width = 595; val height = 842; val margin = 44f; val bottom = 794f
        var page: PdfDocument.Page? = null
        val canvas: Canvas get() = requireNotNull(page).canvas
        var y = 110f; var pageNumber = 0
        fun paint(size: Float, bold: Boolean = false, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = Typeface.create(if (classic) "serif" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        fun start() {
            pageNumber++
            check(pageNumber <= 200) { "INVOICE_TOO_LARGE" }
            page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            canvas.drawColor(if (classic) Color.rgb(253, 252, 248) else Color.WHITE)
            if (modern) {
                canvas.drawRect(0f, 0f, width.toFloat(), 84f, Paint().apply { color = ink })
                canvas.drawText("INVOICE", margin, 43f, paint(25f, true, Color.WHITE))
                canvas.drawText(data.number, margin, 65f, paint(10f, false, Color.WHITE))
            } else if (classic) {
                val title = paint(28f, true)
                canvas.drawText("INVOICE", (width - title.measureText("INVOICE")) / 2, 43f, title)
                canvas.drawText(data.number, margin, 73f, paint(10f))
                canvas.drawLine(margin, 83f, width - margin, 83f, paint(1f).apply { strokeWidth = 1.5f })
                canvas.drawLine(margin, 87f, width - margin, 87f, paint(1f).apply { strokeWidth = .5f })
            } else {
                canvas.drawText("Invoice", margin, 46f, paint(28f, true))
                canvas.drawText(data.number, margin, 69f, paint(10f))
                canvas.drawLine(margin, 84f, width - margin, 84f, paint(1f).apply { strokeWidth = .7f })
            }
            logo?.let {
                val box = RectF(width - margin - 85, 20f, width - margin, 69f)
                val scale = minOf(box.width() / it.width, box.height() / it.height)
                val w = it.width * scale; val h = it.height * scale
                // A white backing keeps transparent and dark logos legible in every style.
                canvas.drawRoundRect(box, 4f, 4f, Paint().apply { color = Color.WHITE })
                canvas.drawBitmap(it, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            canvas.drawText("Page $pageNumber · ${data.number}", margin, 820f, paint(8f, color = Color.DKGRAY))
            y = 110f
        }
        fun finish() { page?.let { document.finishPage(it) }; page = null }
        fun next() { finish(); start() }
        fun ensure(space: Float) { if (y + space > bottom) next() }
        fun space(value: Float) { if (y + value <= bottom) y += value else next() }
        fun layout(value: String, size: Float, bold: Boolean, available: Int): StaticLayout = StaticLayout.Builder.obtain(value, 0, value.length, paint(size, bold), available)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(2f, 1f).build()
        fun text(value: String, size: Float, bold: Boolean = false, available: Int = (width - margin * 2).toInt()) {
            if (value.isBlank()) return
            val layout = layout(value, size, bold, available)
            var firstLine = 0
            while (firstLine < layout.lineCount) {
                ensure(size + 4)
                val top = layout.getLineTop(firstLine)
                var lastLine = firstLine
                while (lastLine + 1 < layout.lineCount && layout.getLineBottom(lastLine + 1) - top <= bottom - y) lastLine++
                val segmentHeight = layout.getLineBottom(lastLine) - top
                if (segmentHeight > bottom - y) { next(); continue }
                canvas.save()
                canvas.clipRect(margin, y, margin + available, y + segmentHeight)
                canvas.translate(margin, y - top)
                layout.draw(canvas)
                canvas.restore()
                y += segmentHeight + 5
                firstLine = lastLine + 1
                if (firstLine < layout.lineCount) next()
            }
        }
        fun tableHeader() {
            ensure(36f)
            if (modern) canvas.drawRoundRect(RectF(margin, y, width - margin, y + 27), 4f, 4f, Paint().apply { color = Color.rgb(232, 244, 238) })
            canvas.drawText("DESCRIPTION", margin + 8, y + 18, paint(9f, true))
            val amount = "AMOUNT (${data.currency})"
            canvas.drawText(amount, width - margin - 8 - paint(9f, true).measureText(amount), y + 18, paint(9f, true))
            y += 36
        }
        fun line(value: InvoiceStore.Line) {
            ensure(45f)
            if (value.amount.isNotBlank()) {
                val amount = paint(11f, true)
                canvas.drawText(value.amount, width - margin - 8 - amount.measureText(value.amount), y + 12, amount)
            }
            text(value.title, 11f, true, 352)
            text(value.detail, 9f, available = 352)
            space(8f)
            canvas.drawLine(margin, y, width - margin, y, Paint().apply { color = Color.rgb(222, 228, 224); strokeWidth = .5f })
            space(12f)
        }
        fun total() {
            if (data.subtotal != null && data.taxRateUnits != null && data.taxAmount != null) {
                ensure(145f)
                space(8f)
                text("Subtotal   ${data.currency} ${data.subtotal}", 11f)
                text("Tax (${TaxRate.format(data.taxRateUnits)}%)   ${data.currency} ${data.taxAmount}", 11f)
            }
            ensure(90f); space(12f)
            val box = RectF(margin, y, width - margin, y + 55)
            if (modern) canvas.drawRoundRect(box, 8f, 8f, Paint().apply { color = Color.rgb(232, 244, 238) })
            else canvas.drawLine(margin, y, width - margin, y, paint(1f).apply { strokeWidth = if (classic) 2f else 1f })
            canvas.drawText("TOTAL", margin + 12, y + 33, paint(12f, true))
            val value = "${data.currency} ${data.total}"
            var size = 21f
            while (paint(size, true).measureText(value) > 345 && size > 9) size -= 1
            val amount = paint(size, true)
            canvas.drawText(value, width - margin - 12 - amount.measureText(value), y + 34, amount)
            y += 68
        }
    }
}
