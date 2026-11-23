package io.github.centerionware.clockrove.tracking

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.centerionware.clockrove.R
import java.math.BigInteger
import org.json.JSONObject

/** Immutable balance/receipt intent. PDFs are derived later without changing cash or invoice numbering. */
internal object InvoiceCopyStore {
    private val token = Regex("[A-Za-z0-9_-]{1,100}")
    data class Summary(val id: String, val kind: String, val created: Long, val version: Long, val paid: String, val due: String)
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE invoice_copies(id TEXT PRIMARY KEY NOT NULL,invoice_id TEXT NOT NULL REFERENCES invoices(id),kind TEXT NOT NULL CHECK(kind IN('BALANCE','RECEIPT','CORRECTION','VOID')),created_ms INTEGER NOT NULL CHECK(created_ms>=0),command_version INTEGER NOT NULL CHECK(command_version>0),metadata TEXT NOT NULL,pdf BLOB)")
        db.execSQL("CREATE INDEX invoice_copy_history ON invoice_copies(invoice_id,command_version,id)")
        db.execSQL("CREATE TRIGGER invoice_copy_immutable BEFORE UPDATE OF id,invoice_id,kind,created_ms,command_version,metadata ON invoice_copies BEGIN SELECT RAISE(ABORT,'Financial copy is immutable'); END")
        db.execSQL("CREATE TRIGGER invoice_copy_retained BEFORE DELETE ON invoice_copies BEGIN SELECT RAISE(ABORT,'Financial copy is retained'); END")
    }
    fun id(kind: String, value: String): String {
        require(kind in setOf("BALANCE", "RECEIPT", "CORRECTION", "VOID") && token.matches(value)) { "INVALID_INVOICE_COPY" }
        return "${kind.lowercase(java.util.Locale.ROOT)}-$value"
    }
    fun list(db: SQLiteDatabase, invoiceId: String): List<Summary> = db.rawQuery("SELECT id,kind,created_ms,command_version,metadata FROM invoice_copies WHERE invoice_id=? ORDER BY command_version DESC,id", arrayOf(invoiceId)).use { rows -> buildList {
        while (rows.moveToNext()) {
            val value = JSONObject(rows.getString(4))
            add(Summary(rows.getString(0), rows.getString(1), rows.getLong(2), rows.getLong(3), value.getString("paid_minor"), value.getString("due_minor")))
        }
    } }
    /** Caller has validated the ledger and owns the same native write transaction. */
    fun retain(db: SQLiteDatabase, kind: String, copyToken: String, invoice: InvoiceStore.Record,
        balance: PaymentMath.Balance, at: Long, eventId: String? = null) {
        val copyId = id(kind, copyToken)
        val version = db.rawQuery("SELECT version FROM state WHERE id=1", null).use { check(it.moveToFirst()); Math.addExact(it.getLong(0), 1L) }
        val values = mapOf("id" to copyId, "invoice_id" to invoice.id, "kind" to kind, "created_ms" to at, "command_version" to version,
            "paid_minor" to balance.paid.toString(), "due_minor" to balance.due.toString(), "event_id" to eventId)
        val metadata = JSONObject(values).toString()
        db.execSQL("INSERT INTO invoice_copies(id,invoice_id,kind,created_ms,command_version,metadata) VALUES(?,?,?,?,?,?)", arrayOf<Any?>(copyId, invoice.id, kind, at, version, metadata))
        RevisionStore.record(db, "invoice_copy", copyId, invoice.jobId, values, at)
    }
    /** Freeze cancellation independently from the unchanged original invoice/PDF. */
    fun retainVoid(db: SQLiteDatabase, before: InvoicePaymentStore.Snapshot, replacementId: String?, at: Long) {
        val copyId = id("VOID", before.invoice.id)
        val version = db.rawQuery("SELECT version FROM state WHERE id=1", null).use { check(it.moveToFirst()); Math.addExact(it.getLong(0), 1L) }
        val values = mapOf("id" to copyId, "invoice_id" to before.invoice.id, "kind" to "VOID", "created_ms" to at, "command_version" to version,
            "paid_minor" to before.balance.paid.toString(), "due_minor" to "0", "cancelled_minor" to before.balance.due.toString(), "replacement_id" to replacementId)
        db.execSQL("INSERT INTO invoice_copies(id,invoice_id,kind,created_ms,command_version,metadata) VALUES(?,?,'VOID',?,?,?)",
            arrayOf<Any?>(copyId, before.invoice.id, at, version, JSONObject(values).toString()))
        RevisionStore.record(db, "invoice_copy", copyId, before.invoice.jobId, values, at)
    }
    fun prepareBalance(db: SQLiteDatabase, copyToken: String, invoiceId: String, at: Long): String? {
        if (!token.matches(copyToken) || !token.matches(invoiceId) || at < 0) return "INVALID_INVOICE_COPY"
        val copyId = id("BALANCE", copyToken)
        if (db.rawQuery("SELECT 1 FROM invoice_copies WHERE id=?", arrayOf(copyId)).use { it.moveToFirst() }) return "INVOICE_COPY_ID_EXISTS"
        val state = InvoicePaymentStore.read(db, invoiceId)
        if (state.balance.voided) return "INVOICE_VOID"
        retain(db, "BALANCE", copyToken, state.invoice, state.balance, at)
        return null
    }
    fun read(db: SQLiteDatabase, copyId: String, strings: Context): InvoiceStore.Record {
        val row = db.rawQuery("SELECT invoice_id,kind,created_ms,command_version,metadata FROM invoice_copies WHERE id=?", arrayOf(copyId)).use {
            require(it.moveToFirst()) { "INVOICE_COPY_UNAVAILABLE" }
            Copy(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), JSONObject(it.getString(4)))
        }
        require(row.metadata.getString("id") == copyId && row.metadata.getString("invoice_id") == row.invoiceId &&
            row.metadata.getString("kind") == row.kind && row.metadata.getLong("created_ms") == row.created && row.metadata.getLong("command_version") == row.version) { "INVALID_INVOICE_COPY" }
        val cachedPdf = InvoiceBlobs.copy(db, copyId)
        val original = InvoiceStore.read(db, row.invoiceId, includePdf = false)
        val document = InvoiceStore.Snapshot.decode(original.payload)
        val paid = PaymentMath.parseMinor(row.metadata.getString("paid_minor"))
        val due = PaymentMath.parseMinor(row.metadata.getString("due_minor"))
        val total = PaymentMath.parseMajor(document.total, document.digits)
        if (row.kind == "VOID") {
            val cancelled = PaymentMath.parseMinor(row.metadata.getString("cancelled_minor"))
            require(due == BigInteger.ZERO && paid.add(cancelled) == total) { "INVALID_INVOICE_COPY" }
            val replacementId = if (row.metadata.isNull("replacement_id")) null else row.metadata.getString("replacement_id")
            val replacementNumber = replacementId?.let { InvoiceStore.read(db, it).number }
            val detail = replacementNumber?.let {
                strings.getString(R.string.invoice_void_detail_replaced, it)
            } ?: strings.getString(R.string.invoice_void_detail_cancelled)
            val statement = InvoiceStore.Statement("VOID", "VOID", row.created, InvoiceStore.amount(paid, document.digits),
                InvoiceStore.amount(BigInteger.ZERO, document.digits), listOf(InvoiceStore.Line(strings.getString(R.string.invoice_void_cancelled_balance), detail, InvoiceStore.amount(cancelled, document.digits))), copyId)
            val copy = document.copy(
                subject = strings.getString(R.string.invoice_void_subject, document.number),
                body = strings.getString(
                    R.string.invoice_void_body,
                    document.number,
                    strings.getString(R.string.pdf_status_void),
                    detail,
                    document.total,
                    document.currency,
                    statement.paid,
                    document.currency,
                    statement.due,
                    document.currency,
                    copyId,
                ),
                statement = statement,
            )
            return original.copy(payload = copy.encode(), pdf = cachedPdf, copyId = copyId, documentKind = "VOID")
        }
        val balance = PaymentMath.balance(total, paid)
        require(balance.due == due) { "INVALID_INVOICE_COPY" }
        val eventId = if (row.metadata.isNull("event_id")) null else row.metadata.getString("event_id")
        require((row.kind == "BALANCE") == (eventId == null)) { "INVALID_INVOICE_COPY" }
        val events = events(db, row.invoiceId, row.version, eventId, document.digits, strings)
        val statement = InvoiceStore.Statement(row.kind, balance.status, row.created,
            InvoiceStore.amount(paid, document.digits), InvoiceStore.amount(due, document.digits), events, copyId)
        val caption = strings.getString(when (row.kind) {
            "RECEIPT" -> R.string.invoice_copy_caption_receipt
            "CORRECTION" -> R.string.invoice_copy_caption_correction
            else -> R.string.invoice_copy_caption_balance
        })
        val statusResource = when (balance.status) {
            "PAID" -> R.string.pdf_status_paid
            "PARTIAL", "PARTIALLY_PAID" -> R.string.pdf_status_partial
            "VOID" -> R.string.pdf_status_void
            "NOTHING_DUE" -> R.string.pdf_status_nothing_due
            else -> R.string.pdf_status_unpaid
        }
        val status = strings.getString(statusResource)
        val body = listOf(
            strings.getString(R.string.invoice_copy_caption_number, caption, document.number),
            strings.getString(R.string.invoice_copy_status, status),
            strings.getString(R.string.invoice_copy_original_total, document.total, document.currency),
            strings.getString(R.string.invoice_copy_paid, statement.paid, document.currency),
            strings.getString(R.string.invoice_copy_balance_due, statement.due, document.currency),
            strings.getString(R.string.pdf_copy_reference, copyId),
        ).joinToString("\n")
        val copy = document.copy(
            subject = strings.getString(R.string.invoice_copy_subject, caption, document.subject),
            body = document.body + "\n\n" + body,
            statement = statement,
        )
        return original.copy(payload = copy.encode(), pdf = cachedPdf, copyId = copyId, documentKind = row.kind)
    }
    private data class Copy(val invoiceId: String, val kind: String, val created: Long, val version: Long, val metadata: JSONObject)
    private fun events(db: SQLiteDatabase, invoiceId: String, version: Long, eventId: String?, digits: Int, strings: Context): List<InvoiceStore.Line> {
        val args = if (eventId == null) arrayOf(invoiceId, version.toString()) else arrayOf(invoiceId, version.toString(), eventId)
        return db.rawQuery("SELECT e.kind,e.effective_ms,e.payload,p.method,p.reference FROM invoice_payment_events e JOIN invoice_payments p ON p.id=e.payment_id WHERE e.invoice_id=? AND e.command_version<=? ${if (eventId == null) "" else "AND e.id=?"} ORDER BY e.command_version,e.ordinal LIMIT 5001", args).use { rows -> buildList {
            while (rows.moveToNext()) {
                require(size < 5000) { "PAYMENT_HISTORY_TOO_LARGE" }
                val kind = rows.getString(0); val value = JSONObject(rows.getString(2))
                val cash = BigInteger(value.getString("cash_change_minor"))
                val credited = BigInteger(value.getString("paid_minor")).subtract(BigInteger(value.getString("before_paid_minor")))
                val amount = if (kind == "TRANSFER") credited else cash
                val title = strings.getString(when (kind) {
                    "PAYMENT" -> R.string.invoice_copy_payment_received
                    "REVERSAL" -> R.string.invoice_copy_payment_reversed
                    else -> R.string.invoice_copy_credit_transferred
                })
                val detail = listOf(value.getString("time_label"), rows.getString(3), rows.getString(4)).filter { it.isNotBlank() }.joinToString("\n")
                add(InvoiceStore.Line(title, detail, InvoiceStore.amount(amount, digits)))
            }
            require(eventId == null || size == 1) { "INVOICE_COPY_EVENT_UNAVAILABLE" }
        } }
    }
}
