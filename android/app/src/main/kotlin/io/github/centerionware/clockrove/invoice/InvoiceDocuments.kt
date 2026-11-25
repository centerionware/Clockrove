package io.github.centerionware.clockrove.invoice

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.localizedContext
import io.github.centerionware.clockrove.tracking.BusinessStore
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.TaxRate
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.math.BigInteger
import java.text.DateFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object InvoiceDocuments {
    /**
     * A stored PDF was rendered in whichever language generated it first. Preview,
     * save, email and share must follow the language on screen, so a requested
     * language always renders again. The small cache keeps page navigation from
     * rebuilding the whole document for every page.
     */
    private const val RENDER_CACHE_BYTES = 16 * 1024 * 1024
    private val rendered = object : LruCache<String, ByteArray>(RENDER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private fun render(key: String, languageTag: String?, generate: () -> ByteArray): ByteArray {
        if (languageTag == null) return generate()
        synchronized(rendered) { rendered.get(key) }?.let { return it }
        return generate().also { bytes -> synchronized(rendered) { rendered.put(key, bytes) } }
    }

    /** Concurrent renders may finish together; only the first derived PDF is retained. */
    fun ensure(context: Context, repository: TimerRepository, id: String, copyId: String? = null, languageTag: String? = null): InvoiceStore.Record {
        if (copyId == null) return ensureInvoice(context, repository, id, languageTag)
        val before = repository.invoiceCopy(copyId, languageTag)
        require(before.id == id) { "INVOICE_COPY_MISMATCH" }
        return renderCopy(context, repository, copyId, before, languageTag)
    }

    /** Content provider entry point: a copy URI carries the copy id, not its invoice id. */
    fun ensureCopy(context: Context, repository: TimerRepository, copyId: String, languageTag: String? = null): InvoiceStore.Record =
        renderCopy(context, repository, copyId, repository.invoiceCopy(copyId, languageTag), languageTag)

    private fun ensureInvoice(context: Context, repository: TimerRepository, id: String, languageTag: String?): InvoiceStore.Record {
        val before = repository.invoice(id)
        if (before.pdf != null && languageTag == null) return before
        val bytes = render("invoice:$id:$languageTag", languageTag) {
            InvoicePdf.generate(context, InvoiceStore.Snapshot.decode(before.payload), before.logo, languageTag = languageTag)
        }
        if (before.pdf == null) repository.cacheInvoicePdf(id, before.payload, bytes)
        return before.copy(pdf = bytes)
    }

    private fun renderCopy(context: Context, repository: TimerRepository, copyId: String, before: InvoiceStore.Record, languageTag: String?): InvoiceStore.Record {
        if (before.pdf != null && languageTag == null) return before
        val bytes = render("copy:$copyId:$languageTag", languageTag) {
            InvoicePdf.generate(context, InvoiceStore.Snapshot.decode(before.payload), before.logo, languageTag = languageTag)
        }
        if (before.pdf == null) repository.cacheInvoiceCopyPdf(copyId, before.payload, bytes, languageTag)
        return before.copy(pdf = bytes)
    }

    fun ensureQuote(context: Context, repository: TimerRepository, id: String, revision: Int, languageTag: String? = null): InvoiceStore.Record {
        val before = repository.quoteDocument(id, revision, languageTag)
        if (before.pdf != null && languageTag == null) return before
        val bytes = render("quote:$id:$revision:$languageTag", languageTag) {
            InvoicePdf.generate(context, InvoiceStore.Snapshot.decode(before.payload), before.logo, "QUOTE", languageTag)
        }
        if (before.pdf == null) repository.cacheQuotePdf(id, revision, before.payload, bytes, languageTag)
        return before.copy(pdf = bytes)
    }
    fun uri(context: Context, id: String, languageTag: String? = null): Uri = withLanguage(
        Uri.Builder().scheme("content").authority("${context.packageName}.invoices").appendPath("invoices").appendPath(id), languageTag,
    )
    fun uri(context: Context, record: InvoiceStore.Record, languageTag: String? = null): Uri = when {
        record.quoteRevision != null -> withLanguage(
            Uri.Builder().scheme("content").authority("${context.packageName}.invoices").appendPath("quotes").appendPath(record.id).appendPath(record.quoteRevision.toString()), languageTag,
        )
        record.copyId == null -> uri(context, record.id, languageTag)
        else -> withLanguage(
            Uri.Builder().scheme("content").authority("${context.packageName}.invoices").appendPath("copies").appendPath(record.copyId), languageTag,
        )
    }
    private fun withLanguage(builder: Uri.Builder, languageTag: String?): Uri {
        val tag = languageTag?.takeIf { it.isNotBlank() }
        return (if (tag == null) builder else builder.appendQueryParameter("lang", tag)).build()
    }
    fun filename(context: Context, record: InvoiceStore.Record, languageTag: String? = null): String {
        val strings = localizedContext(context, languageTag)
        if (record.quoteRevision != null) return strings.getString(
            R.string.document_filename_quote_revision,
            record.number,
            strings.getString(R.string.document_filename_revision),
            record.quoteRevision,
        )
        if (record.copyId == null) return "${record.number}.pdf"
        val statement = requireNotNull(InvoiceStore.Snapshot.decode(record.payload).statement)
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(statement.created))
        val kind = strings.getString(when (record.documentKind) {
            "RECEIPT" -> R.string.invoice_copy_caption_receipt
            "CORRECTION" -> R.string.invoice_copy_caption_correction
            "VOID" -> R.string.pdf_void_invoice_title
            else -> R.string.invoice_copy_caption_balance
        })
        return strings.getString(
            R.string.document_filename_copy,
            record.number,
            kind,
            date,
            record.copyId.takeLast(8),
        )
    }
    fun historicalNotice(context: Context, repository: TimerRepository, record: InvoiceStore.Record, languageTag: String? = null): String? {
        val strings = localizedContext(context, languageTag)
        if (record.quoteRevision != null) {
            val latest = repository.quote(record.id).latest ?: return null
            return if (latest.revision == record.quoteRevision) null else strings.getString(
                R.string.historical_quote_notice,
                record.number,
                record.quoteRevision.toString(),
                latest.revision.toString(),
            )
        }
        if (record.documentKind == "VOID") return null
        val state = repository.invoiceVoidContext(record.id) ?: return null
        return listOf(
            strings.getString(R.string.historical_invoice_void_notice, record.number),
            state.replacementNumber?.let {
                strings.getString(R.string.historical_invoice_replacement_notice, it)
            } ?: strings.getString(R.string.historical_invoice_balance_cancelled),
            strings.getString(R.string.historical_invoice_records_retained),
        ).joinToString(" ")
    }
    fun email(context: Context, record: InvoiceStore.Record, company: BusinessStore.Settings, notice: String? = null, languageTag: String? = null): Intent {
        val snapshot = InvoiceStore.Snapshot.decode(record.payload)
        require(BusinessStore.validEmail(snapshot.email)) { "INVALID_RECIPIENT" }
        val strings = localizedContext(context, languageTag)
        val subject = if (notice == null) snapshot.subject
            else strings.getString(R.string.historical_email_subject, snapshot.subject)
        val body = if (notice == null) snapshot.body else "$notice\n\n${snapshot.body}"
        require(subject.toByteArray(Charsets.UTF_8).size + body.toByteArray(Charsets.UTF_8).size <= 100_000) { "EMAIL_TOO_LARGE" }
        require(subject.none { it == '\r' || it == '\n' }) { "INVALID_SUBJECT" }
        val accounting = company.receivablesEmail.ifEmpty { company.companyEmail }
        require(BusinessStore.validEmail(accounting)) { "INVALID_ACCOUNTS_RECEIVABLE_EMAIL" }
        val cc = if (accounting.isEmpty() || accounting.equals(snapshot.email, ignoreCase = true)) emptyArray<String>() else arrayOf(accounting)
        val uri = uri(context, record, languageTag)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_EMAIL, if (snapshot.email.isEmpty()) emptyArray<String>() else arrayOf(snapshot.email))
            putExtra(Intent.EXTRA_CC, cc)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(attachmentLabel(context, record, languageTag), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun share(context: Context, record: InvoiceStore.Record, notice: String? = null, languageTag: String? = null): Intent {
        val uri = uri(context, record, languageTag)
        return Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; if (notice != null) putExtra(Intent.EXTRA_TEXT, notice); putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri(attachmentLabel(context, record, languageTag), uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    private fun attachmentLabel(context: Context, record: InvoiceStore.Record, languageTag: String? = null): String =
        localizedContext(context, languageTag).getString(
            if (record.quoteRevision == null) R.string.invoice_pdf_attachment else R.string.quote_pdf_attachment,
        )

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
    fun settingsPreview(context: Context, style: String, currency: String, digits: Int, logoBytes: ByteArray? = null, languageTag: String? = null): ByteArray =
        InvoicePdf.generate(context, sampleSnapshot(context, style, currency, digits, languageTag), logoBytes, languageTag = languageTag)

    /** Sample content in [languageTag] (or the saved preference), so tests can check the copy. */
    internal fun sampleSnapshot(context: Context, style: String, currency: String, digits: Int, languageTag: String? = null): InvoiceStore.Snapshot {
        require(style in setOf("MINIMAL", "CLASSIC", "MODERN"))
        val currencyDigits = Currency.getInstance(currency).defaultFractionDigits
        require(digits == currencyDigits && digits in 0..3)
        val factor = BigInteger.TEN.pow(digits)
        val subtotal = BigInteger.valueOf(312).multiply(factor)
        val unitPrice = BigInteger.valueOf(12).multiply(factor)
        val tax = TaxRate.amount(subtotal, 75000)
        val total = subtotal.add(tax)
        val now = System.currentTimeMillis()
        val strings = localizedContext(context, languageTag)
        val locale = strings.resources.configuration.locales[0] ?: Locale.getDefault()
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(now))
        val sampleNumber = strings.getString(R.string.invoice_preview_sample_number)
        val unit = strings.getString(R.string.invoice_preview_square_feet)
        val amount = InvoiceStore.amount(unitPrice, digits)
        val basis = strings.getString(
            R.string.invoice_preview_sample_basis,
            "26",
            unit,
            amount,
            currency,
        )
        val lineDetail = strings.getString(
            R.string.invoice_preview_sample_line_detail,
            "26",
            unit,
            amount,
            currency,
            day,
        )
        val snapshot = InvoiceStore.Snapshot(
            number = sampleNumber, created = now, style = style, currency = currency, digits = digits,
            company = strings.getString(R.string.invoice_preview_sample_company),
            companyContact = strings.getString(R.string.invoice_preview_sample_company_contact),
            customer = strings.getString(R.string.invoice_preview_sample_customer),
            customerContact = strings.getString(R.string.invoice_preview_sample_customer_contact),
            job = strings.getString(R.string.invoice_preview_sample_job),
            period = strings.getString(R.string.invoice_preview_sample_period, day, day),
            basis = basis,
            header = strings.getString(R.string.invoice_preview_sample_header),
            footer = strings.getString(R.string.invoice_preview_sample_footer),
            total = InvoiceStore.amount(total, digits),
            email = strings.getString(R.string.invoice_preview_sample_email),
            subject = strings.getString(R.string.invoice_preview_sample_email_subject, sampleNumber),
            body = strings.getString(R.string.invoice_preview_sample_email_body),
            lines = listOf(InvoiceStore.Line(
                strings.getString(R.string.invoice_preview_sample_line),
                lineDetail,
                InvoiceStore.amount(subtotal, digits), "260000", unitPrice.toString())),
            subtotal = InvoiceStore.amount(subtotal, digits), taxRateUnits = 75000,
            taxAmount = InvoiceStore.amount(tax, digits),
        )
        return snapshot
    }
}
