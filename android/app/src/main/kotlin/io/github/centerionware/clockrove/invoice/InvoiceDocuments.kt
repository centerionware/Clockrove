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
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.io.ByteArrayOutputStream
import java.io.File

internal object InvoiceDocuments {
    /** Concurrent renders may finish together; only the first derived PDF is retained. */
    fun ensure(repository: TimerRepository, id: String): InvoiceStore.Record {
        val before = repository.invoice(id)
        if (before.pdf != null) return before
        val bytes = InvoicePdf.generate(InvoiceStore.Snapshot.decode(before.payload), before.logo)
        repository.cacheInvoicePdf(id, before.payload, bytes)
        return repository.invoice(id).also { check(it.pdf != null) { "INVOICE_UNAVAILABLE" } }
    }
    fun uri(context: Context, id: String): Uri = Uri.Builder().scheme("content").authority("${context.packageName}.invoices").appendPath("invoices").appendPath(id).build()
    fun email(context: Context, record: InvoiceStore.Record): Intent {
        val snapshot = InvoiceStore.Snapshot.decode(record.payload)
        require(BusinessStore.validEmail(snapshot.email)) { "INVALID_RECIPIENT" }
        require(snapshot.subject.none { it == '\r' || it == '\n' }) { "INVALID_SUBJECT" }
        val uri = uri(context, record.id)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            putExtra(Intent.EXTRA_EMAIL, if (snapshot.email.isEmpty()) emptyArray<String>() else arrayOf(snapshot.email))
            putExtra(Intent.EXTRA_SUBJECT, snapshot.subject)
            putExtra(Intent.EXTRA_TEXT, snapshot.body)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Invoice PDF", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun share(context: Context, record: InvoiceStore.Record): Intent {
        val uri = uri(context, record.id)
        return Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("Invoice PDF", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun preview(context: Context, record: InvoiceStore.Record, index: Int): Map<String, Any> {
        require(index >= 0)
        val file = File.createTempFile("invoice-preview-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(requireNotNull(record.pdf))
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
}
