package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
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
    fun read(db: SQLiteDatabase, copyId: String): InvoiceStore.Record {
        val row = db.rawQuery("SELECT invoice_id,kind,created_ms,command_version,metadata,pdf FROM invoice_copies WHERE id=?", arrayOf(copyId)).use {
            require(it.moveToFirst()) { "INVOICE_COPY_UNAVAILABLE" }
            Copy(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), JSONObject(it.getString(4)), if (it.isNull(5)) null else it.getBlob(5))
        }
        require(row.metadata.getString("id") == copyId && row.metadata.getString("invoice_id") == row.invoiceId &&
            row.metadata.getString("kind") == row.kind && row.metadata.getLong("created_ms") == row.created && row.metadata.getLong("command_version") == row.version) { "INVALID_INVOICE_COPY" }
        val original = InvoiceStore.read(db, row.invoiceId)
        val document = InvoiceStore.Snapshot.decode(original.payload)
        val paid = PaymentMath.parseMinor(row.metadata.getString("paid_minor"))
        val due = PaymentMath.parseMinor(row.metadata.getString("due_minor"))
        val total = PaymentMath.parseMajor(document.total, document.digits)
        if (row.kind == "VOID") {
            val cancelled = PaymentMath.parseMinor(row.metadata.getString("cancelled_minor"))
            require(due == BigInteger.ZERO && paid.add(cancelled) == total) { "INVALID_INVOICE_COPY" }
            val replacementId = if (row.metadata.isNull("replacement_id")) null else row.metadata.getString("replacement_id")
            val replacementNumber = replacementId?.let { InvoiceStore.read(db, it).number }
            val detail = replacementNumber?.let { "Replaced by $it. Existing payments were transferred; no new cash received." }
                ?: "Cancelled. No payment is due on this invoice."
            val statement = InvoiceStore.Statement("VOID", "VOID", row.created, InvoiceStore.amount(paid, document.digits),
                InvoiceStore.amount(BigInteger.ZERO, document.digits), listOf(InvoiceStore.Line("Cancelled unpaid balance", detail, InvoiceStore.amount(cancelled, document.digits))), copyId)
            val copy = document.copy(subject = "Voided invoice · ${document.number}",
                body = "Invoice ${document.number} is VOID. $detail\nOriginal total: ${document.total} ${document.currency}\nCredit transferred: ${statement.paid} ${document.currency}\nBalance due: ${statement.due} ${document.currency}\nCopy reference: $copyId", statement = statement)
            return original.copy(payload = copy.encode(), pdf = row.pdf, copyId = copyId, documentKind = "VOID")
        }
        val balance = PaymentMath.balance(total, paid)
        require(balance.due == due) { "INVALID_INVOICE_COPY" }
        val eventId = if (row.metadata.isNull("event_id")) null else row.metadata.getString("event_id")
        require((row.kind == "BALANCE") == (eventId == null)) { "INVALID_INVOICE_COPY" }
        val events = events(db, row.invoiceId, row.version, eventId, document.digits)
        val statement = InvoiceStore.Statement(row.kind, balance.status, row.created,
            InvoiceStore.amount(paid, document.digits), InvoiceStore.amount(due, document.digits), events, copyId)
        val caption = when (row.kind) { "RECEIPT" -> "Payment receipt"; "CORRECTION" -> "Payment correction"; else -> "Invoice balance copy" }
        val status = balance.status.replace('_', ' ')
        val body = document.body + "\n\n$caption ${document.number}\nStatus: $status\nOriginal total: ${document.total} ${document.currency}\nPaid: ${statement.paid} ${document.currency}\nBalance due: ${statement.due} ${document.currency}\nCopy reference: $copyId"
        val copy = document.copy(subject = "$caption · ${document.subject}", body = body, statement = statement)
        return original.copy(payload = copy.encode(), pdf = row.pdf, copyId = copyId, documentKind = row.kind)
    }
    private data class Copy(val invoiceId: String, val kind: String, val created: Long, val version: Long, val metadata: JSONObject, val pdf: ByteArray?)
    private fun events(db: SQLiteDatabase, invoiceId: String, version: Long, eventId: String?, digits: Int): List<InvoiceStore.Line> {
        val args = if (eventId == null) arrayOf(invoiceId, version.toString()) else arrayOf(invoiceId, version.toString(), eventId)
        return db.rawQuery("SELECT e.kind,e.effective_ms,e.payload,p.method,p.reference FROM invoice_payment_events e JOIN invoice_payments p ON p.id=e.payment_id WHERE e.invoice_id=? AND e.command_version<=? ${if (eventId == null) "" else "AND e.id=?"} ORDER BY e.command_version,e.ordinal LIMIT 5001", args).use { rows -> buildList {
            while (rows.moveToNext()) {
                require(size < 5000) { "PAYMENT_HISTORY_TOO_LARGE" }
                val kind = rows.getString(0); val value = JSONObject(rows.getString(2))
                val cash = BigInteger(value.getString("cash_change_minor"))
                val credited = BigInteger(value.getString("paid_minor")).subtract(BigInteger(value.getString("before_paid_minor")))
                val amount = if (kind == "TRANSFER") credited else cash
                val title = when (kind) { "PAYMENT" -> "Payment received"; "REVERSAL" -> "Payment reversed"; else -> "Credit transferred" }
                val detail = listOf(value.getString("time_label"), rows.getString(3), rows.getString(4)).filter { it.isNotBlank() }.joinToString("\n")
                add(InvoiceStore.Line(title, detail, InvoiceStore.amount(amount, digits)))
            }
            require(eventId == null || size == 1) { "INVOICE_COPY_EVENT_UNAVAILABLE" }
        } }
    }
}
