package io.github.centerionware.clockrove.invoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.localizedContext
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.ImageLimits
import io.github.centerionware.clockrove.tracking.TaxRate
import io.github.centerionware.clockrove.tracking.ReportCsv
import java.io.ByteArrayOutputStream

/** Three native, paginated layouts. Render on a worker, outside repository writes. */
internal object InvoicePdf {
    fun generate(context: Context, snapshot: InvoiceStore.Snapshot, logoBytes: ByteArray? = null, documentKind: String = "INVOICE"): ByteArray {
        require(documentKind in setOf("INVOICE", "QUOTE"))
        val logo = logoBytes?.let {
            require(it.size <= ImageLimits.NORMALIZED_BYTES) { "INVALID_LOGO" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, options)
            require(options.outWidth in 1..2048 && options.outHeight in 1..2048) { "INVALID_LOGO" }
            requireNotNull(BitmapFactory.decodeByteArray(it, 0, it.size)) { "INVALID_LOGO" }
        }
        try {
            val document = PdfDocument()
            try {
                val strings = localizedContext(context)
                val flow = Flow(document, snapshot, logo, documentKind == "QUOTE", strings)
                flow.start()
                snapshot.statement?.let {
                    flow.text(strings.getString(R.string.pdf_payment_recorded, ReportCsv.instant(it.created, "UTC")), 9f)
                    flow.text(strings.getString(R.string.pdf_copy_reference, it.reference), 8f)
                    flow.space(8f)
                }
                flow.parties(snapshot, strings.getString(
                    if (documentKind == "QUOTE") R.string.pdf_prepared_for else R.string.pdf_bill_to,
                ))
                flow.text(snapshot.job, 18f, bold = true)
                flow.text(snapshot.period, 10f)
                flow.text(snapshot.basis, 9f)
                if (snapshot.statement == null || snapshot.header.isNotBlank()) {
                    flow.space(12f)
                    flow.text(snapshot.header, 11f)
                    flow.space(12f)
                } else flow.space(8f)
                flow.tableHeader()
                snapshot.lines.forEach { flow.line(it) }
                flow.total()
                snapshot.statement?.let {
                    val paidLabel = when (it.kind) {
                        "VOID" -> R.string.pdf_credit_transferred
                        "REPLACEMENT" -> R.string.pdf_credit_at_issue
                        else -> R.string.pdf_paid_to_date
                    }
                    flow.text(strings.getString(paidLabel, snapshot.currency, it.paid), 12f, bold = true)
                    flow.text(strings.getString(R.string.pdf_balance_due, snapshot.currency, it.due), 16f, bold = true)
                    flow.space(8f)
                    if (it.events.isNotEmpty()) {
                        if (it.kind == "BALANCE") flow.text(strings.getString(R.string.pdf_payments_credits), 12f, bold = true)
                        it.events.forEach { event -> flow.line(event, compact = true) }
                    }
                    flow.text(if (it.kind == "VOID") {
                        strings.getString(R.string.pdf_cancelled_warning)
                    } else {
                        strings.getString(R.string.pdf_saved_payment_note, snapshot.number)
                    }, 9f)
                }
                if (snapshot.footer.isNotBlank()) {
                    flow.space(20f)
                    flow.text(snapshot.footer, 10f)
                }
                flow.finish()
                return ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
            } finally { document.close() }
        } finally { logo?.recycle() }
    }
    private class Flow(
        val document: PdfDocument,
        val data: InvoiceStore.Snapshot,
        val logo: Bitmap?,
        val quote: Boolean,
        val strings: Context,
    ) {
        private data class PartyParagraph(val value: String, val size: Float, val bold: Boolean = false)
        private class PartyColumn(val paragraphs: List<PartyParagraph>) {
            var paragraphIndex = 0
            var lineIndex = 0
            var gapPending = false
            val hasContent get() = paragraphIndex < paragraphs.size
        }

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
        private fun titlePaint(value: String, size: Float, color: Int = ink, centered: Boolean = false): TextPaint {
            val title = paint(size, true, color)
            val logoSpace = if (logo == null) 0f else if (centered) 190f else 95f
            val available = width - 2 * margin - logoSpace
            val measured = title.measureText(value)
            if (measured > available) title.textScaleX = available / measured
            return title
        }

        fun start() {
            pageNumber++
            check(pageNumber <= 200) { "INVOICE_TOO_LARGE" }
            page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            canvas.drawColor(if (classic) Color.rgb(253, 252, 248) else Color.WHITE)
            val heading = strings.getString(
                if (quote) R.string.pdf_quote_title else when (data.statement?.kind) {
                    "RECEIPT" -> R.string.pdf_receipt_title
                    "CORRECTION" -> R.string.pdf_payment_update_title
                    "VOID" -> R.string.pdf_void_invoice_title
                    else -> R.string.pdf_invoice_title
                },
            )
            if (modern) {
                canvas.drawRect(0f, 0f, width.toFloat(), 84f, Paint().apply { color = ink })
                canvas.drawText(heading, margin, 43f, titlePaint(heading, 25f, Color.WHITE))
                canvas.drawText(data.number, margin, 65f, paint(10f, false, Color.WHITE))
            } else if (classic) {
                val title = titlePaint(heading, 28f, centered = true)
                canvas.drawText(heading, (width - title.measureText(heading)) / 2, 43f, title)
                canvas.drawText(data.number, margin, 73f, paint(10f))
                canvas.drawLine(margin, 83f, width - margin, 83f, paint(1f).apply { strokeWidth = 1.5f })
                canvas.drawLine(margin, 87f, width - margin, 87f, paint(1f).apply { strokeWidth = .5f })
            } else {
                val title = if (data.statement == null && !quote) {
                    strings.getString(R.string.pdf_invoice_title_sentence)
                } else heading
                canvas.drawText(title, margin, 46f, titlePaint(title, 28f))
                canvas.drawText(data.number, margin, 69f, paint(10f))
                canvas.drawLine(margin, 84f, width - margin, 84f, paint(1f).apply { strokeWidth = .7f })
            }
            data.statement?.let {
                val status = when (it.status) {
                    "PAID" -> strings.getString(R.string.pdf_status_paid)
                    "UNPAID" -> strings.getString(R.string.pdf_status_unpaid)
                    "PARTIALLY_PAID" -> strings.getString(R.string.pdf_status_partial)
                    "VOID" -> strings.getString(R.string.pdf_status_void)
                    "NOTHING_DUE" -> strings.getString(R.string.pdf_status_nothing_due)
                    else -> it.status.replace('_', ' ')
                }
                val statusPaint = paint(14f, true, if (modern) Color.WHITE else ink)
                val available = width - margin - 260f - if (logo == null) 0f else 95f
                val measured = statusPaint.measureText(status)
                if (measured > available) statusPaint.textScaleX = available / measured
                canvas.drawText(status, 260f, if (modern) 65f else if (classic) 73f else 69f, statusPaint)
            }
            logo?.let {
                val box = RectF(width - margin - 85, 20f, width - margin, 69f)
                val scale = minOf(box.width() / it.width, box.height() / it.height)
                val w = it.width * scale; val h = it.height * scale
                // A white backing keeps transparent and dark logos legible in every style.
                canvas.drawRoundRect(box, 4f, 4f, Paint().apply { color = Color.WHITE })
                canvas.drawBitmap(it, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            canvas.drawText(strings.getString(R.string.pdf_page_footer, pageNumber.toString(), data.number), margin, 820f, paint(8f, color = Color.DKGRAY))
            y = 110f
        }
        fun finish() { page?.let { document.finishPage(it) }; page = null }
        fun next() { finish(); start() }
        fun ensure(space: Float) { if (y + space > bottom) next() }
        fun space(value: Float) { if (y + value <= bottom) y += value else next() }
        fun layout(value: CharSequence, size: Float, bold: Boolean, available: Int, alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout = StaticLayout.Builder.obtain(value, 0, value.length, paint(size, bold), available)
            .setAlignment(alignment).setIncludePad(false).setLineSpacing(2f, 1f).build()

        /** Keep customer and company details side by side without allowing either to take over the page. */
        fun parties(snapshot: InvoiceStore.Snapshot, customerHeading: String) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(snapshot.created))
            val issued = strings.getString(R.string.pdf_issued, date)
            val customer = mutableListOf<PartyParagraph>()
            if (snapshot.customer.isNotBlank() || snapshot.customerContact.isNotBlank()) {
                customer += PartyParagraph(customerHeading, 9f, true)
                if (snapshot.customer.isNotBlank()) customer += PartyParagraph(snapshot.customer, 14f, true)
                if (snapshot.customerContact.isNotBlank()) customer += PartyParagraph(snapshot.customerContact, 10f)
            }
            val company = mutableListOf<PartyParagraph>()
            if (snapshot.company.isNotBlank()) {
                company += PartyParagraph(strings.getString(R.string.pdf_from), 9f, true)
                company += PartyParagraph(snapshot.company, 18f, true)
            }
            if (snapshot.companyContact.isNotBlank()) company += PartyParagraph(snapshot.companyContact, 10f)
            company += PartyParagraph(issued, 9f)

            val columnWidth = ((width - margin * 2) * .47f).toInt()
            val leftX = margin
            val rightX = width - margin - columnWidth
            val left = PartyColumn(customer)
            val right = PartyColumn(company)
            while (left.hasContent || right.hasContent) {
                val pageTop = y
                val leftBottom = drawPartyColumn(left, leftX, pageTop, columnWidth, false)
                val rightBottom = drawPartyColumn(right, rightX, pageTop, columnWidth, true)
                y = maxOf(leftBottom, rightBottom)
                if (left.hasContent || right.hasContent) next()
            }
            space(if (snapshot.statement == null) 16f else 8f)
        }

        private fun drawPartyColumn(column: PartyColumn, x: Float, top: Float, available: Int, rightAligned: Boolean): Float {
            var columnY = top
            while (column.hasContent) {
                val paragraph = column.paragraphs[column.paragraphIndex]
                val content = paragraph.value
                if (content.isBlank()) {
                    column.paragraphIndex++
                    column.lineIndex = 0
                    continue
                }
                val block = layout(content, paragraph.size, paragraph.bold, available,
                    if (rightAligned) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                val minimumLineHeight = block.getLineBottom(column.lineIndex) - block.getLineTop(column.lineIndex)
                val gap = if (column.gapPending) if (classic) 3f else 5f else 0f
                if (columnY + gap + minimumLineHeight > bottom) return columnY
                columnY += gap
                column.gapPending = false
                val lineTop = block.getLineTop(column.lineIndex)
                var lastLine = column.lineIndex
                while (lastLine + 1 < block.lineCount && block.getLineBottom(lastLine + 1) - lineTop <= bottom - columnY) lastLine++
                val segmentHeight = block.getLineBottom(lastLine) - lineTop
                canvas.save()
                canvas.clipRect(x, columnY, x + available, columnY + segmentHeight)
                canvas.translate(x, columnY - lineTop)
                block.draw(canvas)
                canvas.restore()
                columnY += segmentHeight
                column.lineIndex = lastLine + 1
                if (column.lineIndex >= block.lineCount) {
                    column.paragraphIndex++
                    column.lineIndex = 0
                    column.gapPending = true
                } else return columnY
            }
            return columnY
        }

        fun text(value: String, size: Float, bold: Boolean = false, available: Int = (width - margin * 2).toInt(), alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            richText(value, size, bold, available, alignment)
        }

        private fun financialRow(label: String, amount: String) {
            val value = "$label   ${data.currency} $amount"
            val styled = SpannableString(value).apply {
                val start = value.lastIndexOf(amount)
                if (start >= 0) setSpan(StyleSpan(Typeface.BOLD), start, start + amount.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            richText(styled, 11f, false, (width - margin * 2).toInt(), Layout.Alignment.ALIGN_OPPOSITE)
        }

        private fun richText(value: CharSequence, size: Float, bold: Boolean, available: Int, alignment: Layout.Alignment) {
            if (value.isBlank()) return
            val layout = layout(value, size, bold, available, alignment)
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
                y += segmentHeight + if (classic) 3 else 5
                firstLine = lastLine + 1
                if (firstLine < layout.lineCount) next()
            }
        }
        fun tableHeader() {
            ensure(36f)
            if (modern) canvas.drawRoundRect(RectF(margin, y, width - margin, y + 27), 4f, 4f, Paint().apply { color = Color.rgb(232, 244, 238) })
            canvas.drawText(strings.getString(R.string.pdf_description), margin + 8, y + 18, paint(9f, true))
            val amount = strings.getString(R.string.pdf_amount_heading, data.currency)
            canvas.drawText(amount, width - margin - 8 - paint(9f, true).measureText(amount), y + 18, paint(9f, true))
            y += 36
        }
        fun line(value: InvoiceStore.Line, compact: Boolean = false) {
            ensure(45f)
            val amount = paint(11f, true)
            val fitsColumn = amount.measureText(value.amount) <= width - margin * 2 - 352 - 16
            if (value.amount.isNotBlank() && fitsColumn) {
                canvas.drawText(value.amount, width - margin - 8 - amount.measureText(value.amount), y + 12, amount)
            }
            text(value.title, 11f, true, 352)
            text(if (compact) value.detail.replace("\n", " · ") else value.detail, 9f, available = if (compact) (width - margin * 2).toInt() else 352)
            if (value.amount.isNotBlank() && !fitsColumn) {
                // Exact values must never overlap descriptions or disappear off-page.
                text(strings.getString(R.string.pdf_amount_row, data.currency, value.amount), 11f, true)
            }
            space(if (compact) 2f else if (classic) 4f else 8f)
            canvas.drawLine(margin, y, width - margin, y, Paint().apply { color = Color.rgb(222, 228, 224); strokeWidth = .5f })
            space(if (compact) 6f else if (classic) 8f else 12f)
        }
        fun total() {
            if (data.subtotal != null && data.taxRateUnits != null && data.taxAmount != null) {
                ensure(145f)
                space(8f)
                val subtotalLabel = if (data.statement == null) R.string.pdf_subtotal else R.string.pdf_invoice_subtotal
                val taxLabel = if (data.statement == null) R.string.pdf_tax_rate else R.string.pdf_invoice_tax_rate
                financialRow(strings.getString(subtotalLabel), requireNotNull(data.subtotal))
                financialRow(
                    strings.getString(taxLabel, TaxRate.format(data.taxRateUnits)),
                    requireNotNull(data.taxAmount),
                )
            }
            val value = "${data.currency} ${data.total}"
            val label = strings.getString(when {
                quote -> R.string.pdf_estimated_total
                data.statement == null -> R.string.pdf_total
                else -> R.string.pdf_invoice_total
            })
            val amountWidth = minOf(345f, width - margin * 2 - 36f - paint(12f, true).measureText(label))
            if (paint(9f, true).measureText(value) > amountWidth) {
                // A bounded font cannot fit every arbitrary-precision total on one
                // line. Reuse the paginating paragraph flow without dropping digits.
                ensure(90f); space(12f)
                canvas.drawLine(margin, y, width - margin, y, paint(1f).apply { strokeWidth = if (classic) 2f else 1f })
                val stackedLabel = if (quote || data.statement == null) label
                    else strings.getString(R.string.pdf_original_invoice_total)
                space(12f)
                text(stackedLabel, 12f, true)
                text(value, 12f, true)
                space(12f)
                return
            }
            ensure(90f); space(12f)
            val box = RectF(margin, y, width - margin, y + 55)
            if (modern) canvas.drawRoundRect(box, 8f, 8f, Paint().apply { color = Color.rgb(232, 244, 238) })
            else canvas.drawLine(margin, y, width - margin, y, paint(1f).apply { strokeWidth = if (classic) 2f else 1f })
            canvas.drawText(label, margin + 12, y + 33, paint(12f, true))
            var size = 21f
            while (paint(size, true).measureText(value) > amountWidth && size > 9) size -= 1
            val amount = paint(size, true)
            canvas.drawText(value, width - margin - 12 - amount.measureText(value), y + 34, amount)
            y += 68
        }
    }
}
