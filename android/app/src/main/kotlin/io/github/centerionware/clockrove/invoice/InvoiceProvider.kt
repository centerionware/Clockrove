package io.github.centerionware.clockrove.invoice

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.io.File
import java.io.FileNotFoundException

/** Nonexported, temporary-grant-only access to already prepared private invoices. */
class InvoiceProvider() : ContentProvider() {
    private var injectedRepository: TimerRepository? = null
    internal constructor(repository: TimerRepository) : this() { injectedRepository = repository }
    override fun onCreate() = true
    private fun record(uri: Uri): InvoiceStore.Record {
        val parts = uri.pathSegments
        val quote = parts.size == 3 && parts[0] == "quotes" && parts[1].matches(Regex("[A-Za-z0-9_-]{1,100}")) && parts[2].matches(Regex("[1-9][0-9]{0,9}")) && (parts[2].toIntOrNull() ?: 0) > 0
        val invoice = parts.size == 2 && parts[0] in setOf("invoices", "copies") && parts[1].matches(Regex("[A-Za-z0-9_-]{1,140}"))
        if (uri.scheme != "content" || uri.authority != "${requireNotNull(context).packageName}.invoices" || (!quote && !invoice)) throw FileNotFoundException("Invoice unavailable")
        return try { val repository = injectedRepository ?: TimerRepository.get(requireNotNull(context));
            (if (quote) repository.quoteDocument(parts[1], parts[2].toInt()) else if (parts[0] == "copies") repository.invoiceCopy(parts[1]) else repository.invoice(parts[1])).also { if (it.pdf == null) throw FileNotFoundException("Invoice unavailable") } }
            catch (_: Exception) { throw FileNotFoundException("Invoice unavailable") }
    }
    override fun getType(uri: Uri): String { record(uri); return "application/pdf" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val invoice = record(uri); val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> InvoiceDocuments.filename(invoice); OpenableColumns.SIZE -> requireNotNull(invoice.pdf).size; else -> null } }.toTypedArray<Any?>()) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val invoice = record(uri)
        val temporary = File.createTempFile("invoice-read-", ".pdf", requireNotNull(context).cacheDir)
        try {
            temporary.writeBytes(requireNotNull(invoice.pdf))
            return ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            // Keep the returned descriptor seekable, without retaining a named file.
            temporary.delete()
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Read only")
}
