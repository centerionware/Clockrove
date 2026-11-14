package io.github.centerionware.clockrove.invoice

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.centerionware.clockrove.tracking.BusinessReportStore
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.ReportCsv
import java.io.ByteArrayOutputStream

/** Private, paginated financial summary with supporting invoice, payment and cost rows. */
internal object BusinessReportPdf {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val LEFT = 42f
    private const val RIGHT = 553f
    private const val BOTTOM = 790f

    fun generate(report: BusinessReportStore.Report): ByteArray {
        val rowCount = report.summaries.size + report.invoices.size + report.cash.size + report.costs.size
        require(rowCount <= 10_000) { "BUSINESS_REPORT_PDF_TOO_LARGE" }
        val document = PdfDocument()
        try {
            val flow = Flow(document)
            flow.start()
            flow.write("Business report", 22f, true)
            flow.write("${report.selection.firstDate} through ${report.selection.lastDate} · ${report.selection.timezone}", 10f)
            flow.write("Dates are local to the selected timezone. Quotes are excluded; balances are as of the report end.", 9f)
            flow.space(12f)
            flow.heading("FINANCIAL SUMMARY")
            report.summaries.forEach { value ->
                flow.write("${value.currency} (${value.digits} fraction digits)", 12f, true)
                listOf(
                    "Invoiced sales · tax exclusive" to value.invoicedSales,
                    "Cash received · payment date" to value.cashReceived,
                    "Tax billed" to value.taxBilled,
                    "Tax collected with payments" to value.taxCollected,
                    "Outstanding as of report end" to value.outstanding,
                    "Labor charges" to value.labor,
                    "Supplies charges" to value.supplies,
                    "Unclassified charges" to value.unclassified,
                    "Recorded direct costs" to value.directCosts,
                    "Recorded overhead" to value.overhead,
                    "Estimated gross profit" to value.grossProfit,
                    "Estimated net profit" to value.netProfit,
                    "Estimated cash surplus" to value.cashSurplus,
                ).forEach { (label, amount) ->
                    flow.write("$label: ${money(amount, value.digits)} ${value.currency}", 9f)
                }
                flow.write("Gross margin ${value.grossMarginPercent ?: "undefined"}% · net margin ${value.netMarginPercent ?: "undefined"}%", 9f)
                flow.write("${value.costCount} recorded costs; ${value.unattributedCostCount} without a job", 9f)
                if (value.legacyInvoiceCount > 0 || value.unknownTaxInvoiceCount > 0) {
                    flow.write("Data limitations: ${value.legacyInvoiceCount} legacy invoices; ${value.unknownTaxInvoiceCount} invoices without reliable tax data.", 9f)
                }
                flow.space(10f)
            }
            flow.heading("INVOICE ISSUE AND VOID ACTIVITY")
            if (report.invoices.isEmpty()) flow.write("No invoice activity in this period.", 9f)
            report.invoices.forEach { value ->
                val amount = value.salesMinor?.let { "Sales ${money(it, value.digits)} ${value.currency}" }
                    ?: value.legacyTotalMinor?.let { "Legacy total ${money(it, value.digits)} ${value.currency}" }
                    ?: "Legacy total unavailable"
                flow.write("${value.kind} · ${value.number} · ${ReportCsv.instant(value.occurred, report.selection.timezone)}", 10f, true)
                flow.write("${value.customer.ifBlank { value.job }} · $amount · tax ${value.taxMinor?.let { money(it, value.digits) } ?: "unknown"}", 9f)
                if (value.reason.isNotBlank()) flow.write(value.reason, 9f)
            }
            flow.heading("PAYMENTS AND CREDIT EVENTS")
            if (report.cash.isEmpty()) flow.write("No payment or credit events in this period.", 9f)
            report.cash.forEach { value ->
                flow.write("${value.kind} · ${value.number} · ${ReportCsv.instant(value.occurred, report.selection.timezone)}", 10f, true)
                flow.write("${value.customer.ifBlank { value.job }} · cash ${money(value.cashMinor, value.digits)} ${value.currency} · credit ${money(value.creditMinor, value.digits)} · tax ${value.taxMinor?.let { money(it, value.digits) } ?: "unknown"}", 9f)
                if (value.reference.isNotBlank()) flow.write("Reference: ${value.reference}", 9f)
            }
            flow.heading("RECORDED COSTS")
            if (report.costs.isEmpty()) flow.write("No recorded costs in this period.", 9f)
            report.costs.forEach { value ->
                flow.write("${value.category} · ${money(value.amountMinor, value.digits)} ${value.currency} · ${ReportCsv.instant(value.occurred, value.timezone)} · ${value.timezone}", 10f, true)
                val detail = listOf(value.customer, value.job, value.task, value.vendor, value.reference, value.note)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (detail.isNotBlank()) flow.write(detail, 9f)
            }
            flow.space(12f)
            if (report.excludedUnknownCustomerCostCount > 0) {
                flow.write("${report.excludedUnknownCustomerCostCount} cost entries without a captured customer are excluded from this customer-filtered report.", 9f)
            }
            flow.write("Profit and margins are estimates using recorded costs only. Missing costs are unknown, not zero. Tax treatment depends on local rules; this report is not a tax return.", 8f)
            flow.finish()
            return ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun money(amount: java.math.BigInteger, digits: Int) = InvoiceStore.amount(amount, digits)

    private class Flow(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = 58f
        private val canvas: Canvas get() = requireNotNull(page).canvas

        private fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = android.graphics.Color.rgb(29, 40, 48)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        fun start() = newPage()
        fun finish() { page?.let(document::finishPage); page = null }
        fun space(points: Float) { y += points }

        fun heading(value: String) {
            space(6f)
            write(value, 11f, true)
            canvas.drawLine(LEFT, y + 2f, RIGHT, y + 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(185, 194, 198)
                strokeWidth = 0.7f
            })
            space(5f)
        }

        fun write(value: String, size: Float, bold: Boolean = false) {
            value.chunked(800).forEach { chunk ->
                val layout = StaticLayout.Builder.obtain(chunk, 0, chunk.length, paint(size, bold), (RIGHT - LEFT).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(2f, 1f)
                    .build()
                if (y + layout.height > BOTTOM) newPage()
                canvas.save()
                canvas.translate(LEFT, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + 3f
            }
        }

        private fun newPage() {
            finish()
            pageNumber++
            check(pageNumber <= 200) { "BUSINESS_REPORT_PDF_TOO_LARGE" }
            page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNumber).create())
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawText("Clockrove · Business report", LEFT, 30f, paint(9f, true))
            canvas.drawText("Page $pageNumber", RIGHT - 52f, 30f, paint(8f))
            canvas.drawLine(LEFT, 38f, RIGHT, 38f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(185, 194, 198)
                strokeWidth = 0.7f
            })
            y = 52f
        }
    }
}
