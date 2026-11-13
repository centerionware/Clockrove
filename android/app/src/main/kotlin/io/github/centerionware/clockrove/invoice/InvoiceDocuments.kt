package io.github.centerionware.clockrove.invoice

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.centerionware.clockrove.tracking.BusinessStore
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.TaxRate
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object InvoiceDocuments {
    /** Concurrent renders may finish together; only the first derived PDF is retained. */
    fun ensure(repository: TimerRepository, id: String, copyId: String? = null): InvoiceStore.Record {
        fun read() = (if (copyId == null) repository.invoice(id) else repository.invoiceCopy(copyId)).also { require(it.id == id) { "INVOICE_COPY_MISMATCH" } }
        val before = read()
        if (before.pdf != null) return before
        val bytes = InvoicePdf.generate(InvoiceStore.Snapshot.decode(before.payload), before.logo)
        if (copyId == null) repository.cacheInvoicePdf(id, before.payload, bytes) else repository.cacheInvoiceCopyPdf(copyId, before.payload, bytes)
        return read().also { check(it.pdf != null) { "INVOICE_UNAVAILABLE" } }
    }
    fun ensureQuote(repository: TimerRepository, id: String, revision: Int): InvoiceStore.Record {
        val before = repository.quoteDocument(id, revision)
        if (before.pdf != null) return before
        val bytes = InvoicePdf.generate(InvoiceStore.Snapshot.decode(before.payload), before.logo, "QUOTE")
        repository.cacheQuotePdf(id, revision, before.payload, bytes)
        return repository.quoteDocument(id, revision).also { check(it.pdf != null) { "QUOTE_UNAVAILABLE" } }
    }
    fun uri(context: Context, id: String): Uri = Uri.Builder().scheme("content").authority("${context.packageName}.invoices").appendPath("invoices").appendPath(id).build()
    fun uri(context: Context, record: InvoiceStore.Record): Uri = if (record.quoteRevision != null) Uri.Builder().scheme("content").authority("${context.packageName}.invoices").appendPath("quotes").appendPath(record.id).appendPath(record.quoteRevision.toString()).build()
        else if (record.copyId == null) uri(context, record.id) else Uri.Builder().scheme("content").authority("${context.packageName}.invoices").appendPath("copies").appendPath(record.copyId).build()
    fun filename(record: InvoiceStore.Record): String {
        if (record.quoteRevision != null) return "${record.number}-revision-${record.quoteRevision}.pdf"
        if (record.copyId == null) return "${record.number}.pdf"
        val statement = requireNotNull(InvoiceStore.Snapshot.decode(record.payload).statement)
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(statement.created))
        return "${record.number}-${record.documentKind.lowercase(java.util.Locale.ROOT)}-$date-${record.copyId.takeLast(8)}.pdf"
    }
    fun historicalNotice(repository: TimerRepository, record: InvoiceStore.Record): String? {
        if (record.quoteRevision != null) {
            val latest = repository.quote(record.id).latest ?: return null
            return if (latest.revision == record.quoteRevision) null else "Historical quote: ${record.number} revision ${record.quoteRevision}. The current issued revision is ${latest.revision}. This estimate is not an invoice or payment request."
        }
        if (record.documentKind == "VOID") return null
        val state = repository.invoiceVoidContext(record.id) ?: return null
        return "Historical copy: ${record.number} is VOID. Do not pay this invoice." +
            (state.replacementNumber?.let { " Replaced by $it; consult the replacement for its current balance." } ?: " Its unpaid balance was cancelled.") +
            " The original document and payment history remain retained."
    }
    fun email(context: Context, record: InvoiceStore.Record, company: BusinessStore.Settings, notice: String? = null): Intent {
        val snapshot = InvoiceStore.Snapshot.decode(record.payload)
        require(BusinessStore.validEmail(snapshot.email)) { "INVALID_RECIPIENT" }
        val subject = if (notice == null) snapshot.subject else "Historical copy · ${snapshot.subject}"
        val body = if (notice == null) snapshot.body else "$notice\n\n${snapshot.body}"
        require(subject.toByteArray(Charsets.UTF_8).size + body.toByteArray(Charsets.UTF_8).size <= 100_000) { "EMAIL_TOO_LARGE" }
        require(subject.none { it == '\r' || it == '\n' }) { "INVALID_SUBJECT" }
        val accounting = company.receivablesEmail.ifEmpty { company.companyEmail }
        require(BusinessStore.validEmail(accounting)) { "INVALID_ACCOUNTS_RECEIVABLE_EMAIL" }
        val cc = if (accounting.isEmpty() || accounting.equals(snapshot.email, ignoreCase = true)) emptyArray<String>() else arrayOf(accounting)
        val uri = uri(context, record)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_EMAIL, if (snapshot.email.isEmpty()) emptyArray<String>() else arrayOf(snapshot.email))
            putExtra(Intent.EXTRA_CC, cc)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(if (record.quoteRevision == null) "Invoice PDF" else "Quote PDF", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun share(context: Context, record: InvoiceStore.Record, notice: String? = null): Intent {
        val uri = uri(context, record)
        return Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; if (notice != null) putExtra(Intent.EXTRA_TEXT, notice); putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri(if (record.quoteRevision == null) "Invoice PDF" else "Quote PDF", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun preview(context: Context, record: InvoiceStore.Record, index: Int): Map<String, Any> {
        return preview(context, requireNotNull(record.pdf), index)
    }
    fun preview(context: Context, pdfBytes: ByteArray, index: Int): Map<String, Any> {
        require(index >= 0)
        val file = File.createTempFile("invoice-preview-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(pdfBytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                require(index < renderer.pageCount)
                renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                        return mapOf("page" to index, "pages" to renderer.pageCount, "png" to bytes)
                    } finally { bitmap.recycle() }
                }
            } }
        } finally { file.delete() }
    }

    /** Real native sample document for Settings; it consumes no invoice number and writes no records. */
    fun settingsPreview(style: String, currency: String, digits: Int, logoBytes: ByteArray? = null): ByteArray {
        require(style in setOf("MINIMAL", "CLASSIC", "MODERN"))
        val currencyDigits = Currency.getInstance(currency).defaultFractionDigits
        require(digits == currencyDigits && digits in 0..3)
        val factor = BigInteger.TEN.pow(digits)
        val subtotal = BigInteger.valueOf(312).multiply(factor)
        val unitPrice = BigInteger.valueOf(12).multiply(factor)
        val tax = TaxRate.amount(subtotal, 75000)
        val total = subtotal.add(tax)
        val now = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
        val snapshot = InvoiceStore.Snapshot(
            number = "INV-SAMPLE-1000", created = now, style = style, currency = currency, digits = digits,
            company = "Sample Company LLC",
            companyContact = "billing@example.test\n+1 555 0100\n123 Sample Street\nExample City, CA 90210\nexample.test",
            customer = "Taylor Morgan",
            customerContact = "taylor.morgan@example.test\n+1 555 0126\n410 Example Avenue\nSampletown, CA 90210",
            job = "Kitchen tile installation", period = "$day — $day · UTC",
            basis = "26 sq ft × ${InvoiceStore.amount(unitPrice, digits)} $currency per sq ft",
            header = "Thank you for choosing us for your project.",
            footer = "Payment due within 14 days. Sample preview only — no payment is due.",
            total = InvoiceStore.amount(total, digits), email = "taylor.morgan@example.test",
            subject = "Invoice INV-SAMPLE-1000", body = "Please find your sample invoice attached.",
            lines = listOf(InvoiceStore.Line("Install porcelain tile",
                "26 sq ft × ${InvoiceStore.amount(unitPrice, digits)} $currency per sq ft\nCompleted $day",
                InvoiceStore.amount(subtotal, digits), "260000", unitPrice.toString())),
            subtotal = InvoiceStore.amount(subtotal, digits), taxRateUnits = 75000,
            taxAmount = InvoiceStore.amount(tax, digits),
        )
        return InvoicePdf.generate(snapshot, logoBytes)
    }
}
