package io.github.centerionware.clockrove.invoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.localizedContext
import io.github.centerionware.clockrove.tracking.ActualCostStore
import io.github.centerionware.clockrove.tracking.BusinessReportStore
import io.github.centerionware.clockrove.tracking.InvoiceStore
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** Private, paginated financial summary with supporting invoice, payment and cost rows. */
internal object BusinessReportPdf {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val LEFT = 42f
    private const val RIGHT = 553f
    private const val BOTTOM = 790f

    fun generate(context: Context, report: BusinessReportStore.Report): ByteArray {
        val strings = localizedContext(context)
        val locale = strings.resources.configuration.locales[0] ?: Locale.getDefault()
        val rowCount = report.summaries.size + report.invoices.size + report.cash.size + report.costs.size
        require(rowCount <= 10_000) { "BUSINESS_REPORT_PDF_TOO_LARGE" }
        val document = PdfDocument()
        val flow = Flow(document, strings)
        try {
            flow.start()
            flow.write("${strings.getString(R.string.app_name)} · ${strings.getString(R.string.report_pdf_business_report)}", 22f, true)
            val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply {
                timeZone = TimeZone.getTimeZone(report.selection.timezone)
            }
            val firstDate = dateFormat.format(reportDate(report.selection.firstDate, report.selection.timezone))
            val lastDate = dateFormat.format(reportDate(report.selection.lastDate, report.selection.timezone))
            flow.write(strings.getString(R.string.report_pdf_date_range, firstDate, lastDate, report.selection.timezone), 10f)
            flow.write(strings.getString(R.string.report_pdf_intro), 9f)
            flow.space(12f)
            flow.heading(strings.getString(R.string.report_pdf_financial_summary))
            report.summaries.forEach { value ->
                flow.write(strings.getString(R.string.report_pdf_currency_digits, value.currency, value.digits.toString()), 12f, true)
                listOf(
                    R.string.report_pdf_invoiced_sales_amount to value.invoicedSales,
                    R.string.report_pdf_cash_received_amount to value.cashReceived,
                    R.string.report_pdf_tax_billed_amount to value.taxBilled,
                    R.string.report_pdf_tax_collected_amount to value.taxCollected,
                    R.string.report_pdf_outstanding_amount to value.outstanding,
                    R.string.report_pdf_labor_amount to value.labor,
                    R.string.report_pdf_supplies_amount to value.supplies,
                    R.string.report_pdf_unclassified_amount to value.unclassified,
                    R.string.report_pdf_direct_costs_amount to value.directCosts,
                    R.string.report_pdf_overhead_amount to value.overhead,
                    R.string.report_pdf_gross_profit_amount to value.grossProfit,
                    R.string.report_pdf_net_profit_amount to value.netProfit,
                    R.string.report_pdf_cash_surplus_amount to value.cashSurplus,
                ).forEach { (label, amount) ->
                    val formatted = "${money(amount, value.digits)} ${value.currency}"
                    flow.write(strings.getString(label, formatted), 9f)
                }
                flow.write(
                    strings.getString(
                        R.string.report_pdf_margin_pair,
                        value.grossMarginPercent ?: "—",
                        value.netMarginPercent ?: "—",
                    ),
                    9f,
                )
                flow.write(
                    strings.getString(
                        R.string.report_pdf_cost_attribution,
                        NumberFormat.getIntegerInstance(locale).format(value.costCount),
                        NumberFormat.getIntegerInstance(locale).format(value.unattributedCostCount),
                    ),
                    9f,
                )
                if (value.legacyInvoiceCount > 0) {
                    flow.write(
                        strings.getString(
                            R.string.report_pdf_legacy_tax_missing,
                            NumberFormat.getIntegerInstance(locale).format(value.legacyInvoiceCount),
                        ),
                        9f,
                    )
                }
                if (value.unknownTaxInvoiceCount > 0) {
                    flow.write(
                        strings.getString(
                            R.string.report_pdf_unknown_tax_invoices,
                            NumberFormat.getIntegerInstance(locale).format(value.unknownTaxInvoiceCount),
                        ),
                        9f,
                    )
                }
                flow.space(10f)
            }
            flow.heading(
                strings.getString(
                    R.string.report_pdf_invoice_activity,
                    NumberFormat.getIntegerInstance(locale).format(report.invoices.size),
                ),
            )
            if (report.invoices.isEmpty()) flow.write(strings.getString(R.string.report_pdf_no_invoice_activity), 9f)
            report.invoices.forEach { value ->
                val occurred = dateTime(value.occurred, report.selection.timezone, locale)
                val amount = value.salesMinor?.let {
                    strings.getString(R.string.report_pdf_sales_amount, "${money(it, value.digits)} ${value.currency}")
                } ?: value.legacyTotalMinor?.let {
                    strings.getString(R.string.report_pdf_legacy_total, "${money(it, value.digits)} ${value.currency}")
                } ?: strings.getString(R.string.report_pdf_amount_unavailable)
                val tax = value.taxMinor?.let { money(it, value.digits) }
                    ?: strings.getString(R.string.report_pdf_amount_unavailable)
                val title = if (value.kind == "VOID_ADJUSTMENT") {
                    strings.getString(R.string.report_pdf_void_adjustment_title, value.number)
                } else {
                    value.number
                }
                flow.write("$title · $occurred", 10f, true)
                flow.write(
                    "${value.customer.ifBlank { value.job }} · $amount · ${strings.getString(R.string.report_pdf_tax_amount, tax)}",
                    9f,
                )
                if (value.reason.isNotBlank()) flow.write(value.reason, 9f)
            }
            flow.heading(
                strings.getString(
                    R.string.report_pdf_cash_events,
                    NumberFormat.getIntegerInstance(locale).format(report.cash.size),
                ),
            )
            if (report.cash.isEmpty()) flow.write(strings.getString(R.string.report_pdf_no_cash_activity), 9f)
            report.cash.forEach { value ->
                val title = when (value.kind) {
                    "TRANSFER" -> strings.getString(R.string.report_pdf_credit_transfer_title, value.number)
                    "REVERSAL" -> strings.getString(R.string.report_pdf_payment_correction_title, value.number)
                    else -> strings.getString(R.string.report_pdf_payment_title, value.number)
                }
                val occurred = dateTime(value.occurred, report.selection.timezone, locale)
                val tax = value.taxMinor?.let { money(it, value.digits) }
                    ?: strings.getString(R.string.report_pdf_amount_unavailable)
                flow.write("$title · $occurred", 10f, true)
                val cashAmount = strings.getString(
                    R.string.report_pdf_cash_amount,
                    "${money(value.cashMinor, value.digits)} ${value.currency}",
                )
                val creditAmount = strings.getString(
                    R.string.report_pdf_credit_amount,
                    "${money(value.creditMinor, value.digits)} ${value.currency}",
                )
                flow.write(
                    "${value.customer.ifBlank { value.job }} · $cashAmount · $creditAmount · " +
                        strings.getString(R.string.report_pdf_tax_amount, tax),
                    9f,
                )
                if (value.reference.isNotBlank()) {
                    flow.write(strings.getString(R.string.report_pdf_cash_reference, value.reference), 9f)
                }
            }
            flow.heading(
                strings.getString(
                    R.string.report_pdf_cost_entries,
                    NumberFormat.getIntegerInstance(locale).format(report.costs.size),
                ),
            )
            if (report.costs.isEmpty()) flow.write(strings.getString(R.string.report_pdf_no_recorded_costs), 9f)
            report.costs.forEach { value ->
                val category = strings.getString(costCategoryResource(value.category))
                val occurred = dateTime(value.occurred, value.timezone, locale)
                flow.write(
                    "$category · ${money(value.amountMinor, value.digits)} ${value.currency} · $occurred · ${value.timezone}",
                    10f,
                    true,
                )
                val detail = listOf(value.customer, value.job, value.task, value.vendor, value.reference, value.note)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (detail.isNotBlank()) flow.write(detail, 9f)
            }
            flow.space(12f)
            if (report.excludedUnknownCustomerCostCount > 0) {
                flow.write(
                    strings.getString(
                        R.string.report_pdf_excluded_customer_costs,
                        NumberFormat.getIntegerInstance(locale).format(report.excludedUnknownCustomerCostCount),
                    ),
                    9f,
                )
            }
            flow.write(strings.getString(R.string.report_pdf_disclaimer), 8f)
            flow.finish()
            return ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
        } finally {
            try {
                flow.finish()
            } finally {
                document.close()
            }
        }
    }

    private fun reportDate(value: String, zone: String): Date {
        val parts = value.split('-').map(String::toInt)
        require(parts.size == 3) { "INVALID_BUSINESS_REPORT_DATE" }
        return GregorianCalendar(TimeZone.getTimeZone(zone), Locale.ROOT).apply {
            isLenient = false
            clear()
            set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        }.time
    }

    private fun dateTime(value: Long, zone: String, locale: Locale): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).apply {
            timeZone = TimeZone.getTimeZone(zone)
        }.format(Date(value))

    private fun costCategoryResource(category: String): Int = when (category) {
        ActualCostStore.MATERIALS -> R.string.report_pdf_cost_materials
        ActualCostStore.SUBCONTRACT_LABOR -> R.string.report_pdf_cost_labor
        ActualCostStore.OTHER_DIRECT -> R.string.report_pdf_cost_other_direct
        ActualCostStore.OVERHEAD -> R.string.report_pdf_cost_overhead
        else -> R.string.actual_cost_unknown_category
    }

    private fun money(amount: java.math.BigInteger, digits: Int) = InvoiceStore.amount(amount, digits)

    private class Flow(private val document: PdfDocument, private val strings: Context) {
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = 58f
        private val canvas: Canvas get() = requireNotNull(page).canvas

        private fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = android.graphics.Color.rgb(29, 40, 48)
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
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
            canvas.drawText(
                "${strings.getString(R.string.app_name)} · ${strings.getString(R.string.report_pdf_business_report)}",
                LEFT,
                30f,
                paint(9f, true),
            )
            canvas.drawText(strings.getString(R.string.report_pdf_page, pageNumber.toString()), RIGHT - 52f, 30f, paint(8f))
            canvas.drawLine(LEFT, 38f, RIGHT, 38f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(185, 194, 198)
                strokeWidth = 0.7f
            })
            y = 52f
        }
    }
}
