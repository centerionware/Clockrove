package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.TimeZone
import org.json.JSONArray

/** Audited cancellation/replacement. Only the serialized repository may call writers. */
internal object InvoiceLifecycleStore {
    private val token = Regex("[A-Za-z0-9_-]{1,100}")
    private val zones = TimeZone.getAvailableIDs().toSet()
    data class VoidInfo(val replacementId: String?, val credited: String, val cancelled: String,
        val at: Long, val timezone: String, val reason: String)
    data class VoidDraft(val invoiceId: String, val reason: String, val timezone: String, val confirmed: Boolean) {
        fun values(): List<Any?> = listOf(invoiceId, reason, timezone, confirmed)
    }
    /** Indices refer only to immutable original rows; new work comes from an actual report. */
    data class LineEdit(val sourceIndex: Int, val title: String, val detail: String, val amountMinor: String?,
        val quantityUnits: String? = null, val unitPriceMinor: String? = null) {
        fun values() = JSONArray(listOf(sourceIndex, title, detail, amountMinor, quantityUnits, unitPriceMinor))
    }
    data class ReplacementDraft(val invoiceId: String, val replacementId: String, val lines: List<LineEdit>,
        val taxUnits: Int, val reason: String, val timezone: String, val confirmed: Boolean,
        val releaseClaimIds: List<String> = emptyList(), val addedWork: ReportStore.Selection? = null) {
        fun values(): List<Any?> = listOf(invoiceId, replacementId, JSONArray(lines.map { it.values() }), taxUnits,
            reason, timezone, confirmed, JSONArray(releaseClaimIds), addedWork?.let {
                JSONArray(listOf(it.firstDate, it.lastDate, it.timezone, JSONArray(it.jobIds)))
            })
    }
    data class Claim(val id: String, val taskId: String? = null, val sessionId: String? = null,
        val start: Long? = null, val end: Long? = null, val timezone: String? = null, val label: String = "")

    private fun amountColumn(name: String) = "$name TEXT NOT NULL CHECK(typeof($name)='text' AND length($name) BETWEEN 1 AND ${PaymentMath.maxDigits} AND ($name='0' OR (substr($name,1,1) BETWEEN '1' AND '9' AND $name NOT GLOB '*[^0-9]*')))"
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE invoice_voids(invoice_id TEXT PRIMARY KEY NOT NULL REFERENCES invoices(id),replacement_id TEXT UNIQUE REFERENCES invoices(id),${amountColumn("credited_minor")},${amountColumn("cancelled_minor")},created_ms INTEGER NOT NULL CHECK(created_ms>=0),timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL,time_label TEXT NOT NULL,reason TEXT NOT NULL,command_version INTEGER NOT NULL CHECK(command_version>0),CHECK(replacement_id IS NULL OR replacement_id<>invoice_id))")
        for (event in listOf("UPDATE", "DELETE")) db.execSQL("CREATE TRIGGER invoice_void_immutable_${event.lowercase()} BEFORE $event ON invoice_voids BEGIN SELECT RAISE(ABORT,'Invoice cancellation is immutable'); END")
    }
    fun voidInfo(db: SQLiteDatabase, invoiceId: String): VoidInfo? = db.rawQuery(
        "SELECT replacement_id,credited_minor,cancelled_minor,created_ms,timezone,reason FROM invoice_voids WHERE invoice_id=?", arrayOf(invoiceId)).use {
        if (!it.moveToFirst()) null else VoidInfo(if (it.isNull(0)) null else it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getString(4), it.getString(5))
    }
    fun claims(db: SQLiteDatabase, invoiceId: String): List<Claim> = buildList {
        db.rawQuery("SELECT c.id,c.task_id,t.title FROM invoice_task_claims c LEFT JOIN kanban_cards t ON t.id=c.task_id AND t.job_id=c.job_id WHERE c.invoice_id=? AND c.released_ms IS NULL ORDER BY c.id", arrayOf(invoiceId)).use {
            while (it.moveToNext()) add(Claim(it.getString(0), taskId = it.getString(1), label = (if (it.isNull(2)) "Unavailable task" else it.getString(2)) + " · " + it.getString(1)))
        }
        db.rawQuery("SELECT id,session_id,start_ms,end_ms,timezone FROM invoice_time_claims WHERE invoice_id=? AND released_ms IS NULL ORDER BY start_ms,id", arrayOf(invoiceId)).use {
            while (it.moveToNext()) add(Claim(it.getString(0), sessionId = it.getString(1), start = it.getLong(2), end = it.getLong(3), timezone = it.getString(4)))
        }
    }
    private fun validText(value: String, limit: Int) = value.length <= limit && '\u0000' !in value
    private fun invalid(invoiceId: String, reason: String, timezone: String, confirmed: Boolean, at: Long): String? = when {
        !confirmed -> "INVOICE_VOID_CONFIRMATION_REQUIRED"
        !token.matches(invoiceId) || at < 0 || timezone !in zones -> "INVALID_INVOICE_VOID"
        reason.trim().length < 10 || !validText(reason, 4000) -> "INVOICE_VOID_REASON_REQUIRED"
        else -> null
    }
    private fun ineligible(before: InvoicePaymentStore.Snapshot): String? = when {
        before.balance.voided -> "INVOICE_VOID"
        before.balance.total.signum() > 0 && before.balance.due.signum() == 0 -> "PAID_INVOICE_CANNOT_VOID"
        else -> null
    }
    fun void(db: SQLiteDatabase, value: VoidDraft, at: Long): String? {
        invalid(value.invoiceId, value.reason, value.timezone, value.confirmed, at)?.let { return it }
        val before = InvoicePaymentStore.read(db, value.invoiceId)
        ineligible(before)?.let { return it }
        if (before.balance.paid.signum() > 0) return "PARTIAL_INVOICE_REQUIRES_REPLACEMENT"
        // No mutation until every expected rejection has been checked.
        release(db, before.invoice, claims(db, value.invoiceId), at)
        retainVoid(db, before, null, value.reason, value.timezone, at)
        InvoiceCopyStore.retainVoid(db, before, null, at)
        return null
    }
    fun replace(db: SQLiteDatabase, value: ReplacementDraft, at: Long): String? {
        invalid(value.invoiceId, value.reason, value.timezone, value.confirmed, at)?.let { return it }
        if (!token.matches(value.replacementId) || value.replacementId == value.invoiceId) return "INVALID_REPLACEMENT"
        if (db.rawQuery("SELECT 1 FROM invoices WHERE id=?", arrayOf(value.replacementId)).use { it.moveToFirst() }) return "INVOICE_ID_EXISTS"
        val before = InvoicePaymentStore.read(db, value.invoiceId)
        ineligible(before)?.let { return it }
        if (!TaxRate.valid(value.taxUnits)) return "INVALID_TAX_RATE"
        if (value.lines.size > 7000 || value.lines.map { it.sourceIndex }.distinct().size != value.lines.size) return "INVALID_REPLACEMENT_LINES"
        val source = before.document
        val edited = mutableListOf<InvoiceStore.Line>()
        for (line in value.lines) {
            val old = source.lines.getOrNull(line.sourceIndex) ?: return "INVALID_REPLACEMENT_LINES"
            if (line.title.isBlank() || !validText(line.title, 500) || !validText(line.detail, 12000)) return "INVALID_REPLACEMENT_LINES"
            // Informational time rows cannot become new charges. Amounts are minor-unit text.
            if (old.amount.isBlank() != (line.amountMinor == null)) return "INVALID_REPLACEMENT_LINES"
            val amount = try { line.amountMinor?.let(PaymentMath::parseMinor) } catch (_: IllegalArgumentException) { return "INVALID_REPLACEMENT_AMOUNT" }
            if (old.quantityUnits != null && line.quantityUnits == null || (line.quantityUnits == null) != (line.unitPriceMinor == null)) return "INVALID_REPLACEMENT_LINES"
            var detail = line.detail
            if (line.quantityUnits != null) {
                if (amount == null) return "INVALID_REPLACEMENT_LINES"
                val quantity = line.quantityUnits.toLongOrNull()?.takeIf { it > 0 && it.toString() == line.quantityUnits } ?: return "INVALID_TASK_QUANTITY"
                val price = try { MoneyMath.parse(requireNotNull(line.unitPriceMinor)) } catch (_: IllegalArgumentException) { return "INVALID_MONEY" }
                if (TaskQuantityStore.amount(price, quantity) != amount) return "INVALID_REPLACEMENT_AMOUNT"
                // Price/quantity captions are derived, never stale text from the predecessor.
                detail = "${TaskQuantityStore.format(quantity)} × ${InvoiceStore.amount(price, source.digits)} ${source.currency} each" +
                    line.detail.substringAfter('\n', "").let { if (it.isBlank()) "" else "\n$it" }
            }
            if (line.quantityUnits == null && amount != null && detail == old.detail && amount != PaymentMath.parseMajor(old.amount, source.digits)) {
                detail = "Adjusted amount. Original basis:\n$detail"
            }
            edited.add(InvoiceStore.Line(line.title, detail, amount?.let { InvoiceStore.amount(it, source.digits) }.orEmpty(), line.quantityUnits, line.unitPriceMinor, old.chargeType))
        }
        val held = claims(db, value.invoiceId)
        if (value.releaseClaimIds.size > 7000 || value.releaseClaimIds.distinct().size != value.releaseClaimIds.size ||
            value.releaseClaimIds.any { id -> held.none { it.id == id } }) return "INVALID_RELEASED_CLAIMS"
        val added = value.addedWork?.let { selection ->
            if (selection.jobIds != listOf(before.invoice.jobId)) return "INVALID_REPLACEMENT_SOURCES"
            val preview = InvoiceCoverageStore.preview(db, ReportStore.read(db, selection))
            InvoiceStore.coverageProblem(preview)?.let { return it }
            if (preview.report.totals.single().currency != source.currency) return "PAYMENT_CURRENCY_MISMATCH"
            preview.report
        }
        if (added != null) edited.addAll(InvoiceStore.billableLines(added))
        if (edited.size > 7000 || edited.none { it.amount.isNotBlank() }) return "NO_BILLABLE_ITEMS"
        val subtotal = edited.filter { it.amount.isNotBlank() }.fold(BigInteger.ZERO) { sum, line ->
            sum.add(PaymentMath.parseMajor(line.amount, source.digits))
        }
        val tax = TaxRate.amount(subtotal, value.taxUnits)
        val total = subtotal.add(tax)
        if (total.toString().length > PaymentMath.maxDigits) return "INVOICE_TOO_LARGE"
        if (total < before.balance.paid) return "REPLACEMENT_BELOW_CREDIT"
        val company = BusinessStore.settings(db)
        if (!InvoiceEmailTemplate.valid(company.emailSubject, true)) return "INVALID_EMAIL_SUBJECT"
        if (!InvoiceEmailTemplate.valid(company.emailBody, false)) return "INVALID_EMAIL_BODY"
        val sequence = db.rawQuery("SELECT next_invoice_number FROM app_settings WHERE id=1", null).use { check(it.moveToFirst()); it.getLong(0).coerceAtLeast(1000) }
        if (sequence == Long.MAX_VALUE) return "INVOICE_SEQUENCE_EXHAUSTED"
        val number = "INV-$sequence"
        val balance = PaymentMath.balance(total, before.balance.paid)
        val formatted = InvoiceStore.amount(total, source.digits)
        val billingPolicy = org.json.JSONObject(mapOf(
            "kind" to "REPLACEMENT", "predecessorPolicy" to source.billingPolicy,
            "reviewedLines" to JSONArray(edited.map { line -> mapOf("title" to line.title, "detail" to line.detail,
                "amount" to line.amount, "quantityUnits" to line.quantityUnits, "unitPriceMinor" to line.unitPriceMinor, "chargeType" to line.chargeType) }),
            "addedWorkPolicy" to added?.totals?.singleOrNull()?.let { policy -> mapOf(
                "mode" to policy.mode, "hourlyMode" to policy.policyHourlyMode,
                "minimumHours" to policy.policyMinimumHours, "minimumTaskFeeMinor" to policy.policyTaskMinimumMinor?.toString(),
                "exactDurationMillis" to policy.durationMillis.toString(), "billedHours" to policy.billedHours,
                "taskMinimumAdjustmentMinor" to policy.minimumTaskAdjustmentMinor.toString()) })).toString()
        val tokens = mapOf("company_name" to source.company, "customer_name" to source.customer, "job_name" to source.job,
            "invoice_number" to number, "amount" to formatted, "currency" to source.currency)
        val replacement = source.copy(number = number, created = at, lines = edited,
            subtotal = InvoiceStore.amount(subtotal, source.digits), taxRateUnits = value.taxUnits, taxAmount = InvoiceStore.amount(tax, source.digits), total = formatted,
            subject = InvoiceEmailTemplate.expand(company.emailSubject, tokens, true), body = InvoiceEmailTemplate.expand(company.emailBody, tokens, false) +
                "\n\nReplacement of ${source.number}\nCredit at issue: ${InvoiceStore.amount(balance.paid, source.digits)} ${source.currency}\nBalance due at issue: ${InvoiceStore.amount(balance.due, source.digits)} ${source.currency}",
            basis = "Replacement of ${source.number} · reviewed line amounts · tax rounded half up",
            statement = InvoiceStore.Statement("REPLACEMENT", balance.status, at, InvoiceStore.amount(balance.paid, source.digits), InvoiceStore.amount(balance.due, source.digits),
                listOf(InvoiceStore.Line("Credit from ${source.number}", "Existing payments transferred; no new cash received.", InvoiceStore.amount(balance.paid, source.digits))), "Replaces ${source.number}"),
            billingPolicy = billingPolicy)
        val payload = replacement.encode()
        if (payload.toByteArray(Charsets.UTF_8).size > 1_000_000) return "INVOICE_TOO_LARGE"
        // From here, unexpected SQL/revision failures throw and roll back the whole command.
        db.execSQL("INSERT INTO invoices(id,job_id,customer_id,number,created_ms,payload,logo_id) SELECT ?,job_id,customer_id,?,?,?,logo_id FROM invoices WHERE id=?",
            arrayOf<Any?>(value.replacementId, number, at, payload, value.invoiceId))
        QuoteInvoiceStore.copyOrigin(db, before.invoice, value.replacementId, at)
        transferCoverage(db, before.invoice, value.replacementId, held, value.releaseClaimIds.toSet(), added, at)
        transferPayments(db, before, InvoiceStore.read(db, value.replacementId), replacement, value.timezone, at)
        retainVoid(db, before, value.replacementId, value.reason, value.timezone, at)
        InvoiceCopyStore.retainVoid(db, before, value.replacementId, at)
        db.execSQL("UPDATE app_settings SET next_invoice_number=? WHERE id=1", arrayOf<Any?>(sequence + 1))
        RevisionStore.snapshotRows(db, "invoices", "invoice", listOf("id", "job_id", "customer_id", "number", "created_ms", "payload", "logo_id"), at, "job_id", "id=?", arrayOf(value.replacementId))
        RevisionStore.snapshotRows(db, "app_settings", "settings", BusinessSchema.settingsColumns, at)
        return null
    }
    private fun release(db: SQLiteDatabase, invoice: InvoiceStore.Record, claims: List<Claim>, at: Long) {
        for (claim in claims) {
            val task = claim.taskId != null
            val table = if (task) "invoice_task_claims" else "invoice_time_claims"
            db.execSQL("UPDATE $table SET released_ms=? WHERE id=? AND invoice_id=? AND released_ms IS NULL", arrayOf<Any?>(at, claim.id, invoice.id))
            RevisionStore.snapshotRows(db, table, if (task) "invoice_task_claim" else "invoice_time_claim",
                if (task) listOf("id", "invoice_id", "job_id", "task_id", "released_ms") else listOf("id", "invoice_id", "job_id", "session_id", "start_ms", "end_ms", "timezone", "released_ms"), at, "job_id", "id=?", arrayOf(claim.id))
        }
    }
    private fun transferCoverage(db: SQLiteDatabase, original: InvoiceStore.Record, replacementId: String,
        held: List<Claim>, released: Set<String>, added: ReportStore.Report?, at: Long) {
        release(db, original, held, at)
        held.filter { it.id !in released }.forEach { claim ->
            if (claim.taskId != null) InvoiceCoverageStore.reserveTask(db, replacementId, original.jobId, claim.taskId, at)
            else InvoiceCoverageStore.reserveTime(db, replacementId, original.jobId, requireNotNull(claim.sessionId), requireNotNull(claim.start), requireNotNull(claim.end), requireNotNull(claim.timezone), at)
        }
        if (added != null) {
            val total = added.totals.single()
            if (total.mode == "TASK") added.tasks.forEach { InvoiceCoverageStore.reserveTask(db, replacementId, total.jobId, it.id, at) }
            else added.intervals.forEach { InvoiceCoverageStore.reserveTime(db, replacementId, total.jobId, it.sessionId, it.start, it.end, added.selection.timezone, at) }
        }
        // Unresolved legacy coverage stays unresolved on the successor; never guess.
        val state = db.rawQuery("SELECT state FROM invoice_coverage WHERE invoice_id=?", arrayOf(original.id)).use { if (it.moveToFirst()) it.getString(0) else "LEGACY" }
        db.execSQL("INSERT INTO invoice_coverage(invoice_id,state) VALUES(?,?)", arrayOf(replacementId, state))
        RevisionStore.record(db, "invoice_coverage", replacementId, original.jobId, mapOf("invoice_id" to replacementId, "state" to state, "reviewed_ms" to null, "review_note" to "", "predecessor_id" to original.id), at)
    }
    private fun transferPayments(db: SQLiteDatabase, original: InvoicePaymentStore.Snapshot, successor: InvoiceStore.Record,
        document: InvoiceStore.Snapshot, timezone: String, at: Long) {
        var oldBalance = original.balance
        var newBalance = PaymentMath.balance(PaymentMath.parseMajor(document.total, document.digits), BigInteger.ZERO)
        var ordinal = 0
        for (payment in original.payments.filter { it.reversalId == null && it.currentInvoiceId == original.invoice.id }) {
            val oldAfter = PaymentMath.balance(oldBalance.total, oldBalance.paid.subtract(payment.amount))
            val newAfter = PaymentMath.receive(newBalance.total, newBalance.paid, payment.amount)
            InvoicePaymentStore.event(db, "${successor.id}-out-${payment.id}", payment.id, original.copy(balance = oldBalance), oldAfter,
                "TRANSFER", at, timezone, at, BigInteger.ZERO, ordinal++, payment.amount.negate(), successor.id)
            InvoicePaymentStore.event(db, "${successor.id}-in-${payment.id}", payment.id,
                InvoicePaymentStore.Snapshot(successor, document, newBalance, null, emptyList()), newAfter,
                "TRANSFER", at, timezone, at, BigInteger.ZERO, ordinal++, payment.amount, original.invoice.id)
            db.execSQL("UPDATE payment_destinations SET invoice_id=? WHERE payment_id=? AND invoice_id=?", arrayOf(successor.id, payment.id, original.invoice.id))
            RevisionStore.record(db, "payment_destination", payment.id, successor.jobId, mapOf("payment_id" to payment.id, "invoice_id" to successor.id, "predecessor_id" to original.invoice.id), at)
            oldBalance = oldAfter; newBalance = newAfter
        }
        check(oldBalance.paid == BigInteger.ZERO && newBalance.paid == original.balance.paid) { "Credit transfer mismatch" }
    }
    private fun retainVoid(db: SQLiteDatabase, before: InvoicePaymentStore.Snapshot, replacementId: String?, reason: String, timezone: String, at: Long) {
        val version = db.rawQuery("SELECT version FROM state WHERE id=1", null).use { check(it.moveToFirst()); Math.addExact(it.getLong(0), 1L) }
        val values = mapOf("invoice_id" to before.invoice.id, "replacement_id" to replacementId, "credited_minor" to before.balance.paid.toString(),
            "cancelled_minor" to before.balance.due.toString(), "created_ms" to at, "timezone" to timezone, "offset_seconds" to TimeZone.getTimeZone(timezone).getOffset(at) / 1000,
            "time_label" to ReportCsv.instant(at, timezone) + " · " + timezone, "reason" to reason.trim(), "command_version" to version)
        db.execSQL("INSERT INTO invoice_voids VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(before.invoice.id, replacementId, before.balance.paid.toString(), before.balance.due.toString(), at, timezone,
            TimeZone.getTimeZone(timezone).getOffset(at) / 1000, values["time_label"], reason.trim(), version))
        RevisionStore.record(db, "invoice_void", before.invoice.id, before.invoice.jobId, values, at)
    }
}
