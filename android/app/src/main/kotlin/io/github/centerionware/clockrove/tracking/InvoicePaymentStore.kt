package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.TimeZone

/** Financial ledger helpers; the repository owns their serialized SQLite transaction. */
internal object InvoicePaymentStore {
    private val idPattern = Regex("[A-Za-z0-9_-]{1,100}")
    private val zones = TimeZone.getAvailableIDs().toSet()
    private fun text(value: String, limit: Int) = value.length <= limit && '\u0000' !in value
    private fun amountColumn(name: String) = "$name TEXT NOT NULL CHECK(typeof($name)='text' AND length($name) BETWEEN 1 AND ${PaymentMath.maxDigits} AND ($name='0' OR (substr($name,1,1) BETWEEN '1' AND '9' AND $name NOT GLOB '*[^0-9]*')))"

    fun accountingEmail(db: SQLiteDatabase): String = db.rawQuery("SELECT receivables_email FROM invoice_accounting_settings WHERE id=1", null).use {
        check(it.moveToFirst()); it.getString(0)
    }
    fun saveAccountingEmail(db: SQLiteDatabase, value: String, at: Long) {
        require(BusinessStore.validEmail(value)) { "INVALID_ACCOUNTS_RECEIVABLE_EMAIL" }
        db.execSQL("UPDATE invoice_accounting_settings SET receivables_email=? WHERE id=1", arrayOf(value))
        RevisionStore.record(db, "invoice_accounting_settings", "1", null, mapOf("id" to 1, "receivables_email" to value), at)
    }
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE invoice_accounting_settings(id INTEGER PRIMARY KEY CHECK(id=1),receivables_email TEXT NOT NULL DEFAULT '')")
        db.execSQL("INSERT INTO invoice_accounting_settings(id) VALUES(1)")
        db.execSQL("CREATE TABLE invoice_payments(id TEXT PRIMARY KEY NOT NULL,original_invoice_id TEXT NOT NULL REFERENCES invoices(id),${amountColumn("amount_minor")},currency TEXT NOT NULL,received_ms INTEGER NOT NULL CHECK(received_ms>=0),timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL CHECK(offset_seconds BETWEEN -64800 AND 64800),received_label TEXT NOT NULL,method TEXT NOT NULL,reference TEXT NOT NULL,note TEXT NOT NULL,recorded_ms INTEGER NOT NULL CHECK(recorded_ms>=0),CHECK(amount_minor<>'0'))")
        db.execSQL("CREATE TABLE payment_destinations(payment_id TEXT PRIMARY KEY NOT NULL REFERENCES invoice_payments(id),invoice_id TEXT NOT NULL REFERENCES invoices(id))")
        db.execSQL("CREATE INDEX payment_destination_invoice ON payment_destinations(invoice_id,payment_id)")
        db.execSQL("CREATE TABLE invoice_payment_reversals(id TEXT PRIMARY KEY NOT NULL,payment_id TEXT NOT NULL UNIQUE REFERENCES invoice_payments(id),effective_ms INTEGER NOT NULL CHECK(effective_ms>=0),timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL CHECK(offset_seconds BETWEEN -64800 AND 64800),effective_label TEXT NOT NULL,note TEXT NOT NULL,recorded_ms INTEGER NOT NULL CHECK(recorded_ms>=0))")
        // Exact signed changes and the immutable balance snapshot are encoded as
        // canonical strings in a bounded native JSON event, never SQL SUM/REAL.
        db.execSQL("CREATE TABLE invoice_payment_events(id TEXT PRIMARY KEY NOT NULL,invoice_id TEXT NOT NULL REFERENCES invoices(id),payment_id TEXT NOT NULL REFERENCES invoice_payments(id),kind TEXT NOT NULL CHECK(kind IN('PAYMENT','REVERSAL','TRANSFER')),effective_ms INTEGER NOT NULL CHECK(effective_ms>=0),recorded_ms INTEGER NOT NULL CHECK(recorded_ms>=0),command_version INTEGER NOT NULL CHECK(command_version>0),ordinal INTEGER NOT NULL CHECK(ordinal>=0),payload TEXT NOT NULL,UNIQUE(command_version,ordinal))")
        db.execSQL("CREATE INDEX invoice_payment_event_order ON invoice_payment_events(invoice_id,command_version,ordinal)")
        InvoiceCopyStore.install(db)
        InvoiceLifecycleStore.install(db)
        for (table in listOf("invoice_payments", "invoice_payment_reversals", "invoice_payment_events")) {
            for (event in listOf("UPDATE", "DELETE")) db.execSQL("CREATE TRIGGER ${table}_immutable_${event.lowercase()} BEFORE $event ON $table BEGIN SELECT RAISE(ABORT,'Financial event is immutable'); END")
        }
    }

    data class Draft(val id: String, val invoiceId: String, val amountMinor: String, val currency: String,
        val received: Long, val timezone: String, val method: String = "", val reference: String = "", val note: String = "") {
        fun values(): List<Any?> = listOf(id, invoiceId, amountMinor, currency, received, timezone, method, reference, note)
    }
    data class Reversal(val id: String, val invoiceId: String, val paymentId: String, val effective: Long, val timezone: String, val note: String) {
        fun values(): List<Any?> = listOf(id, invoiceId, paymentId, effective, timezone, note)
    }
    data class Payment(val id: String, val originalInvoiceId: String, val amount: BigInteger, val currency: String,
        val received: Long, val timezone: String, val method: String, val reference: String, val note: String,
        val recorded: Long, val reversalId: String?, val reversalNote: String?, val offsetSeconds: Int, val receivedLabel: String,
        val currentInvoiceId: String)
    data class Snapshot(val invoice: InvoiceStore.Record, val document: InvoiceStore.Snapshot,
        val balance: PaymentMath.Balance, val collectedTax: BigInteger?, val payments: List<Payment>)

    fun balance(db: SQLiteDatabase, invoiceId: String, document: InvoiceStore.Snapshot): PaymentMath.Balance {
        var paid = BigInteger.ZERO
        db.rawQuery("SELECT p.amount_minor,p.currency FROM payment_destinations d JOIN invoice_payments p ON p.id=d.payment_id LEFT JOIN invoice_payment_reversals r ON r.payment_id=p.id WHERE d.invoice_id=? AND r.id IS NULL", arrayOf(invoiceId)).use { rows ->
            while (rows.moveToNext()) {
                require(rows.getString(1) == document.currency) { "INVALID_PAYMENT_CURRENCY" }
                paid = paid.add(PaymentMath.parseMinor(rows.getString(0)))
            }
        }
        return PaymentMath.balance(PaymentMath.parseMajor(document.total, document.digits), paid).copy(voided = InvoiceLifecycleStore.voidInfo(db, invoiceId) != null)
    }
    fun read(db: SQLiteDatabase, invoiceId: String): Snapshot {
        val invoice = InvoiceStore.read(db, invoiceId)
        val document = InvoiceStore.Snapshot.decode(invoice.payload)
        val tax = document.taxAmount?.let { PaymentMath.parseMajor(it, document.digits) }
        val payments = db.rawQuery("SELECT p.id,p.original_invoice_id,p.amount_minor,p.currency,p.received_ms,p.timezone,p.method,p.reference,p.note,p.recorded_ms,r.id,r.note,p.offset_seconds,p.received_label,d.invoice_id FROM payment_destinations d JOIN invoice_payments p ON p.id=d.payment_id LEFT JOIN invoice_payment_reversals r ON r.payment_id=p.id WHERE d.invoice_id=? OR p.original_invoice_id=? OR EXISTS(SELECT 1 FROM invoice_payment_events e WHERE e.invoice_id=? AND e.payment_id=p.id) ORDER BY p.received_ms,p.recorded_ms,p.id", arrayOf(invoiceId, invoiceId, invoiceId)).use { rows -> buildList {
            while (rows.moveToNext()) add(Payment(rows.getString(0), rows.getString(1), PaymentMath.parseMinor(rows.getString(2)), rows.getString(3), rows.getLong(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8), rows.getLong(9), if (rows.isNull(10)) null else rows.getString(10), if (rows.isNull(11)) null else rows.getString(11), rows.getInt(12), rows.getString(13), rows.getString(14)))
        } }
        require(payments.all { it.currency == document.currency }) { "INVALID_PAYMENT_CURRENCY" }
        val balance = balance(db, invoiceId, document)
        return Snapshot(invoice, document, balance, PaymentMath.collectedTax(balance.total, tax, balance.paid), payments)
    }

    fun record(db: SQLiteDatabase, value: Draft, at: Long): String? {
        if (!idPattern.matches(value.id) || !idPattern.matches(value.invoiceId) || value.received !in 0..at || value.timezone !in zones ||
            !text(value.method, 80) || !text(value.reference, 240) || !text(value.note, 4000)) return "INVALID_PAYMENT"
        val amount = try { PaymentMath.parseMinor(value.amountMinor) } catch (_: IllegalArgumentException) { return "INVALID_PAYMENT_AMOUNT" }
        if (amount.signum() <= 0) return "INVALID_PAYMENT_AMOUNT"
        if (db.rawQuery("SELECT 1 FROM invoice_payments WHERE id=?", arrayOf(value.id)).use { it.moveToFirst() }) return "PAYMENT_ID_EXISTS"
        val before = read(db, value.invoiceId)
        if (before.balance.voided) return "INVOICE_VOID"
        if (value.currency != before.document.currency) return "PAYMENT_CURRENCY_MISMATCH"
        if (amount > before.balance.due) return "PAYMENT_EXCEEDS_BALANCE"
        val after = PaymentMath.receive(before.balance.total, before.balance.paid, amount)
        db.execSQL("INSERT INTO invoice_payments VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(value.id, value.invoiceId, amount.toString(), value.currency, value.received, value.timezone, TimeZone.getTimeZone(value.timezone).getOffset(value.received) / 1000, timeLabel(value.received, value.timezone), value.method, value.reference, value.note, at))
        db.execSQL("INSERT INTO payment_destinations VALUES(?,?)", arrayOf(value.id, value.invoiceId))
        revision(db, "invoice_payments", "invoice_payment", value.id, before.invoice.jobId, at)
        RevisionStore.record(db, "payment_destination", value.id, before.invoice.jobId, mapOf("payment_id" to value.id, "invoice_id" to value.invoiceId), at)
        event(db, value.id, value.id, before, after, "PAYMENT", value.received, value.timezone, at, amount)
        InvoiceCopyStore.retain(db, "RECEIPT", value.id, before.invoice, after, at, "PAYMENT-${value.id}")
        return null
    }

    fun reverse(db: SQLiteDatabase, value: Reversal, at: Long): String? {
        if (!idPattern.matches(value.id) || !idPattern.matches(value.invoiceId) || !idPattern.matches(value.paymentId) ||
            value.effective !in 0..at || value.timezone !in zones || value.note.trim().length < 10 || !text(value.note, 4000)) return "INVALID_PAYMENT_REVERSAL"
        if (db.rawQuery("SELECT 1 FROM invoice_payment_reversals WHERE id=?", arrayOf(value.id)).use { it.moveToFirst() }) return "REVERSAL_ID_EXISTS"
        val before = read(db, value.invoiceId)
        if (before.balance.voided) return "INVOICE_VOID"
        val payment = before.payments.singleOrNull { it.id == value.paymentId && it.currentInvoiceId == value.invoiceId } ?: return "PAYMENT_UNAVAILABLE_OR_MOVED"
        if (payment.reversalId != null) return "PAYMENT_ALREADY_REVERSED"
        if (value.effective < payment.received) return "REVERSAL_BEFORE_PAYMENT"
        val after = PaymentMath.balance(before.balance.total, before.balance.paid.subtract(payment.amount))
        db.execSQL("INSERT INTO invoice_payment_reversals VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any?>(value.id, value.paymentId, value.effective, value.timezone, TimeZone.getTimeZone(value.timezone).getOffset(value.effective) / 1000, timeLabel(value.effective, value.timezone), value.note, at))
        revision(db, "invoice_payment_reversals", "invoice_payment_reversal", value.id, before.invoice.jobId, at)
        event(db, value.id, value.paymentId, before, after, "REVERSAL", value.effective, value.timezone, at, payment.amount.negate())
        InvoiceCopyStore.retain(db, "CORRECTION", value.id, before.invoice, after, at, "REVERSAL-${value.id}")
        return null
    }

    internal fun event(db: SQLiteDatabase, id: String, paymentId: String, before: Snapshot, after: PaymentMath.Balance,
        kind: String, effective: Long, timezone: String, at: Long, cashChange: BigInteger, ordinal: Int = 0,
        creditChange: BigInteger = BigInteger.ZERO, otherInvoiceId: String? = null) {
        val allocationChange = after.paid.subtract(before.balance.paid)
        require(cashChange.add(creditChange) == allocationChange) { "INVALID_PAYMENT_EVENT" }
        val tax = before.document.taxAmount?.let { PaymentMath.parseMajor(it, before.document.digits) }
        val taxChange = PaymentMath.taxChange(after.total, tax, before.balance.paid, after.paid)
        val commandVersion = db.rawQuery("SELECT version FROM state WHERE id=1", null).use { check(it.moveToFirst()); Math.addExact(it.getLong(0), 1L) }
        val values = mapOf("command_version" to commandVersion, "ordinal" to ordinal, "invoice_id" to before.invoice.id, "payment_id" to paymentId, "kind" to kind,
            "total_minor" to after.total.toString(), "before_paid_minor" to before.balance.paid.toString(), "paid_minor" to after.paid.toString(),
            "due_minor" to after.due.toString(), "cash_change_minor" to cashChange.toString(),
            "credit_change_minor" to creditChange.toString(), "other_invoice_id" to otherInvoiceId, "tax_change_minor" to taxChange?.toString(),
            "sales_change_minor" to taxChange?.let { allocationChange.subtract(it).toString() }, "currency" to before.document.currency,
            "effective_ms" to effective, "timezone" to timezone, "offset_seconds" to TimeZone.getTimeZone(timezone).getOffset(effective) / 1000, "time_label" to timeLabel(effective, timezone), "recorded_ms" to at)
        db.execSQL("INSERT INTO invoice_payment_events VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any?>("$kind-$id", before.invoice.id, paymentId, kind, effective, at, commandVersion, ordinal, org.json.JSONObject(values).toString()))
        RevisionStore.record(db, "invoice_payment_event", "$kind-$id", before.invoice.jobId, values, at)
    }

    private fun timeLabel(instant: Long, timezone: String): String = ReportCsv.instant(instant, timezone) + " · " + timezone
    private fun revision(db: SQLiteDatabase, table: String, kind: String, id: String, jobId: String, at: Long) {
        val values = db.rawQuery("SELECT * FROM $table WHERE id=?", arrayOf(id)).use { row ->
            check(row.moveToFirst()); row.columnNames.mapIndexed { index, name -> name to if (row.getType(index) == android.database.Cursor.FIELD_TYPE_INTEGER) row.getLong(index) else row.getString(index) }.toMap()
        }
        RevisionStore.record(db, kind, id, jobId, values, at)
    }
}
