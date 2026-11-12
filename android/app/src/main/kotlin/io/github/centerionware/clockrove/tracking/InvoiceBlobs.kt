package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor

/** Keep bounded document bytes out of the metadata cursor's fixed-size window. */
internal object InvoiceBlobs {
    fun original(db: SQLiteDatabase, id: String): ByteArray? = read(db, "invoices", "pdf", id, 20_000_000)
    fun copy(db: SQLiteDatabase, id: String): ByteArray? = read(db, "invoice_copies", "pdf", id, 20_000_000)
    fun logo(db: SQLiteDatabase, id: String): ByteArray? = read(db, "logo_assets", "bytes", id, 2_000_000)

    private fun read(db: SQLiteDatabase, table: String, column: String, id: String, maximum: Int): ByteArray? {
        // Table/column names are private constants. Only the identity is bound.
        val size = db.compileStatement("SELECT CASE WHEN $column IS NULL THEN -1 WHEN typeof($column)='blob' THEN length($column) ELSE -2 END FROM $table WHERE id=?").use {
            it.bindString(1, id); it.simpleQueryForLong()
        }
        if (size == -1L) return null
        check(size in 1..maximum.toLong()) { "INVALID_INVOICE_BLOB" }
        // Small normal documents avoid the descriptor/shared-memory allocation.
        // Their one-column row is bounded independently of payload and logo size.
        if (size <= 65536) return db.rawQuery("SELECT $column FROM $table WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst()); it.getBlob(0).also { bytes -> check(bytes.size.toLong() == size) { "INVOICE_BLOB_CHANGED" } }
        }
        val descriptor = db.compileStatement("SELECT $column FROM $table WHERE id=?").use {
            it.bindString(1, id)
            checkNotNull(it.simpleQueryForBlobFileDescriptor()) { "INVOICE_BLOB_UNAVAILABLE" }
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "INVOICE_BLOB_TRUNCATED" }
                offset += count
            }
            check(input.read() == -1) { "INVOICE_BLOB_CHANGED" }
            bytes
        }
    }
}
