package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.Currency
import org.json.JSONArray
import org.json.JSONObject

/** Estimates only. No command here writes work, invoice coverage or payments. */
internal object QuoteStore {
    private val ids = Regex("[A-Za-z0-9_-]{1,100}")
    private fun text(value: String, limit: Int) = value.length <= limit && '\u0000' !in value
    data class Line(val id: String, val title: String, val detail: String, val kind: String,
        val quantity: String, val unitPrice: String) {
        fun json() = JSONObject(mapOf("id" to id, "title" to title, "detail" to detail,
            "kind" to kind, "quantity" to quantity, "unitPrice" to unitPrice))
    }
    data class Draft(val id: String, val customerId: String?, val jobId: String?, val title: String,
        val currency: String, val taxUnits: Int, val lines: List<Line>, val validUntil: String? = null,
        val terms: String = "", val notes: String = "") {
        fun encode(): String = JSONObject(mapOf("id" to id, "customerId" to customerId,
            "jobId" to jobId, "title" to title, "currency" to currency, "taxUnits" to taxUnits,
            "lines" to JSONArray(lines.map { it.json() }), "validUntil" to validUntil,
            "terms" to terms, "notes" to notes)).toString()
        companion object {
            fun decode(payload: String): Draft {
                val v = JSONObject(payload); val rows = v.getJSONArray("lines")
                return Draft(v.getString("id"), if (v.isNull("customerId")) null else v.getString("customerId"),
                    if (v.isNull("jobId")) null else v.getString("jobId"), v.getString("title"), v.getString("currency"), v.getInt("taxUnits"),
                    (0 until rows.length()).map { val r = rows.getJSONObject(it); Line(r.getString("id"), r.getString("title"), r.getString("detail"), r.getString("kind"), r.getString("quantity"), r.getString("unitPrice")) },
                    if (v.isNull("validUntil")) null else v.getString("validUntil"), v.getString("terms"), v.getString("notes"))
            }
        }
    }
    data class Totals(val subtotal: BigInteger, val tax: BigInteger, val total: BigInteger)
    data class Revision(val quoteId: String, val revision: Int, val number: String, val payload: String,
        val created: Long, val decision: String?, val decisionNote: String?)
    data class Cursor(val created: Long, val id: String)
    data class Summary(val id: String, val number: String?, val created: Long, val title: String,
        val currency: String, val total: String, val validUntil: String?, val revision: Int?,
        val status: String, val hasDraft: Boolean, val jobId: String?, val customer: String)
    data class Page(val entries: List<Summary>, val next: Cursor?)
    data class RevisionSummary(val revision: Int, val created: Long, val decision: String?)
    fun currencyDigits(db: SQLiteDatabase, id: String?, currency: String): Int {
        require(BusinessStore.validCurrency(currency)) { "INVALID_CURRENCY" }
        val issued = id?.let { latest(db, it) }?.let { JSONObject(it.payload) }
        val frozen = issued?.takeIf { it.getJSONObject("draft").getString("currency") == currency }?.getInt("digits")
        return (frozen ?: Currency.getInstance(currency).defaultFractionDigits).also { require(it in 0..4) { "INVALID_CURRENCY" } }
    }

    fun revision(db: SQLiteDatabase, id: String, revision: Int): Revision? {
        require(revision > 0) { "INVALID_QUOTE_REVISION" }
        return db.rawQuery("SELECT q.number,r.payload,r.created_ms,d.decision,d.note FROM quote_revisions r JOIN quotes q ON q.id=r.quote_id LEFT JOIN quote_decisions d ON d.quote_id=r.quote_id AND d.revision=r.revision WHERE r.quote_id=? AND r.revision=?", arrayOf(id, revision.toString())).use {
            if (!it.moveToFirst()) null else Revision(id, revision, it.getString(0), it.getString(1), it.getLong(2), if (it.isNull(3)) null else it.getString(3), if (it.isNull(4)) null else it.getString(4))
        }
    }
    fun history(db: SQLiteDatabase, id: String, before: Int? = null, limit: Int = 20): List<RevisionSummary> {
        require(limit in 1..50 && (before == null || before > 0)) { "INVALID_QUOTE_PAGE" }
        return db.rawQuery("SELECT r.revision,r.created_ms,d.decision FROM quote_revisions r LEFT JOIN quote_decisions d ON d.quote_id=r.quote_id AND d.revision=r.revision WHERE r.quote_id=? AND r.revision<? ORDER BY r.revision DESC LIMIT ?",
            arrayOf(id, (before?.toLong() ?: (Int.MAX_VALUE.toLong() + 1)).toString(), limit.toString())).use { rows -> buildList {
                while (rows.moveToNext()) add(RevisionSummary(rows.getInt(0), rows.getLong(1), if (rows.isNull(2)) null else rows.getString(2)))
            } }
    }
    fun index(db: SQLiteDatabase, today: String, before: Cursor? = null, limit: Int = 20, status: String? = null): Page {
        require(validDate(today) && limit in 1..50 && (before == null || before.created >= 0 && ids.matches(before.id))) { "INVALID_QUOTE_PAGE" }
        // Metadata only: a valid draft plus issued document can exceed one cursor window.
        require(status == null || status in setOf("DRAFT", "ISSUED", "ACCEPTED", "DECLINED", "EXPIRED")) { "INVALID_QUOTE_PAGE" }
        val cursor = if (before == null) "" else "AND (q.created_ms<? OR (q.created_ms=? AND q.id<?))"
        val state = "CASE WHEN d.quote_id IS NOT NULL THEN 'DRAFT' WHEN c.decision IS NOT NULL THEN c.decision WHEN r.valid_until IS NOT NULL AND r.valid_until<? THEN 'EXPIRED' ELSE 'ISSUED' END"
        val args = mutableListOf(status.orEmpty(), today, status.orEmpty())
        if (before != null) args.addAll(listOf(before.created.toString(), before.created.toString(), before.id))
        args.add((limit + 1).toString())
        val keys = db.rawQuery("SELECT q.id,q.created_ms FROM quotes q LEFT JOIN quote_drafts d ON d.quote_id=q.id LEFT JOIN quote_revisions r ON r.quote_id=q.id AND r.revision=(SELECT MAX(x.revision) FROM quote_revisions x WHERE x.quote_id=q.id) LEFT JOIN quote_decisions c ON c.quote_id=r.quote_id AND c.revision=r.revision WHERE (?='' OR $state=?) $cursor ORDER BY q.created_ms DESC,q.id DESC LIMIT ?", args.toTypedArray()).use { rows -> buildList {
                while (rows.moveToNext()) add(Cursor(rows.getLong(1), rows.getString(0)))
            } }
        val summaries = keys.take(limit).map { key ->
            val draft = draft(db, key.id); val issued = latest(db, key.id)
            val frozen = issued?.let { JSONObject(it.payload) }
            val source = draft ?: Draft.decode(requireNotNull(frozen).getJSONObject("draft").toString())
            val validUntil = source.validUntil
            val status = if (draft != null) "DRAFT" else issued!!.decision ?: if (validUntil != null && validUntil < today) "EXPIRED" else "ISSUED"
            Summary(key.id, issued?.number, key.created, source.title, source.currency,
                if (draft != null) InvoiceStore.amount(totals(draft).total, currencyDigits(db, key.id, draft.currency)) else frozen!!.getString("total"),
                validUntil, issued?.revision, status, draft != null, source.jobId,
                if (draft != null) draft.customerId?.let { id -> BusinessStore.customers(db).firstOrNull { it.id == id }?.name }.orEmpty() else frozen!!.getString("customer"))
        }
        return Page(summaries, keys.takeIf { it.size > limit }?.get(limit - 1))
    }

    fun totals(draft: Draft): Totals {
        require(ids.matches(draft.id) && draft.lines.size in 1..2000 && draft.lines.map { it.id }.toSet().size == draft.lines.size) { "INVALID_QUOTE" }
        require(BusinessStore.validCurrency(draft.currency) && TaxRate.valid(draft.taxUnits)) { "INVALID_QUOTE" }
        require(draft.title.isNotBlank() && text(draft.title, 240) && text(draft.terms, 20000) && text(draft.notes, 20000)) { "INVALID_QUOTE" }
        require(draft.validUntil == null || validDate(draft.validUntil)) { "INVALID_QUOTE_DATE" }
        val subtotal = draft.lines.fold(BigInteger.ZERO) { sum, line ->
            require(ids.matches(line.id) && line.title.isNotBlank() && text(line.title, 500) && text(line.detail, 12000) && line.kind in setOf("ITEM", "HOURS")) { "INVALID_QUOTE_LINE" }
            require(line.quantity.length in 1..19 && line.quantity.matches(Regex("[1-9][0-9]*"))) { "INVALID_TASK_QUANTITY" }
            val quantity = BigInteger(line.quantity)
            require(quantity <= BigInteger.valueOf(Long.MAX_VALUE)) { "INVALID_TASK_QUANTITY" }
            sum.add(TaskQuantityStore.amount(MoneyMath.parse(line.unitPrice), quantity.toLong()))
        }
        val tax = TaxRate.amount(subtotal, draft.taxUnits); val total = subtotal.add(tax)
        require(total.toString().length <= PaymentMath.maxDigits) { "QUOTE_TOO_LARGE" }
        return Totals(subtotal, tax, total)
    }
    private fun validDate(date: String): Boolean {
        if (!date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) || date.startsWith("0000")) return false
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { isLenient = false; timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return try { parser.format(requireNotNull(parser.parse(date))) == date } catch (_: Exception) { false }
    }
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE quote_counter(id INTEGER PRIMARY KEY CHECK(id=1),next_number INTEGER NOT NULL CHECK(typeof(next_number)='integer' AND next_number>=1000))")
        db.execSQL("INSERT INTO quote_counter VALUES(1,1000)")
        // Job IDs deliberately survive a purged job, just like invoice identities.
        db.execSQL("CREATE TABLE quotes(id TEXT PRIMARY KEY NOT NULL,number TEXT UNIQUE,created_ms INTEGER NOT NULL)")
        db.execSQL("CREATE TRIGGER quote_identity_immutable BEFORE UPDATE OF id,number,created_ms ON quotes WHEN OLD.number IS NOT NULL BEGIN SELECT RAISE(ABORT,'Issued quote identity is immutable'); END")
        db.execSQL("CREATE TABLE quote_drafts(quote_id TEXT PRIMARY KEY NOT NULL REFERENCES quotes(id),customer_id TEXT,job_id TEXT,payload TEXT NOT NULL,updated_ms INTEGER NOT NULL,valid_until TEXT)")
        db.execSQL("CREATE TABLE quote_revisions(quote_id TEXT NOT NULL REFERENCES quotes(id),revision INTEGER NOT NULL CHECK(revision>0),customer_id TEXT,job_id TEXT,payload TEXT NOT NULL,logo_id TEXT REFERENCES logo_assets(id),created_ms INTEGER NOT NULL,valid_until TEXT,PRIMARY KEY(quote_id,revision))")
        db.execSQL("CREATE TABLE quote_decisions(quote_id TEXT NOT NULL,revision INTEGER NOT NULL,decision TEXT NOT NULL CHECK(decision IN('ACCEPTED','DECLINED')),note TEXT NOT NULL,recorded_ms INTEGER NOT NULL,PRIMARY KEY(quote_id,revision),FOREIGN KEY(quote_id,revision) REFERENCES quote_revisions(quote_id,revision))")
        db.execSQL("CREATE TABLE quote_pdfs(id TEXT PRIMARY KEY NOT NULL,quote_id TEXT NOT NULL,revision INTEGER NOT NULL,bytes BLOB NOT NULL,UNIQUE(quote_id,revision),FOREIGN KEY(quote_id,revision) REFERENCES quote_revisions(quote_id,revision))")
        db.execSQL("CREATE TRIGGER quote_pdf_immutable BEFORE UPDATE ON quote_pdfs BEGIN SELECT RAISE(ABORT,'Issued quote PDF is immutable'); END")
        db.execSQL("CREATE TRIGGER quote_pdf_retained BEFORE DELETE ON quote_pdfs BEGIN SELECT RAISE(ABORT,'Issued quote PDF is retained'); END")
        QuoteScopeStore.install(db)
        QuoteInvoiceStore.install(db)
        for (table in listOf("quote_revisions", "quote_decisions")) for (event in listOf("UPDATE", "DELETE")) {
            db.execSQL("CREATE TRIGGER ${table}_immutable_${event.lowercase()} BEFORE $event ON $table BEGIN SELECT RAISE(ABORT,'Issued quote history is immutable'); END")
        }
    }
    fun draft(db: SQLiteDatabase, id: String): Draft? = db.rawQuery("SELECT payload FROM quote_drafts WHERE quote_id=?", arrayOf(id)).use {
        if (it.moveToFirst()) Draft.decode(it.getString(0)) else null
    }
    fun save(db: SQLiteDatabase, value: Draft, at: Long): String? {
        if (at < 0) return "INVALID_CLOCK"
        // All validation is before any write. A known invalid draft returns a
        // command conflict so the editor remains editable, not an unknown reply.
        val payload = try {
            totals(value)
            value.encode().also { require(it.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "QUOTE_TOO_LARGE" } }
        } catch (failure: IllegalArgumentException) { return failure.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "INVALID_QUOTE" }
        if (value.customerId != null && BusinessStore.customers(db).none { it.id == value.customerId }) return "CUSTOMER_UNAVAILABLE"
        if (value.jobId != null && !db.rawQuery("SELECT id FROM jobs WHERE id=?", arrayOf(value.jobId)).use { it.moveToFirst() }) return "JOB_UNAVAILABLE"
        db.execSQL("INSERT OR IGNORE INTO quotes VALUES(?,NULL,?)", arrayOf<Any?>(value.id, at))
        db.execSQL("INSERT OR REPLACE INTO quote_drafts VALUES(?,?,?,?,?,?)", arrayOf<Any?>(value.id, value.customerId, value.jobId, payload, at, value.validUntil))
        RevisionStore.record(db, "quote_draft", value.id, value.jobId, mapOf("id" to value.id, "payload" to payload), at)
        return null
    }
    fun latest(db: SQLiteDatabase, id: String): Revision? = db.rawQuery("SELECT r.revision,q.number,r.payload,r.created_ms,d.decision,d.note FROM quote_revisions r JOIN quotes q ON q.id=r.quote_id LEFT JOIN quote_decisions d ON d.quote_id=r.quote_id AND d.revision=r.revision WHERE r.quote_id=? ORDER BY r.revision DESC LIMIT 1", arrayOf(id)).use {
        if (!it.moveToFirst()) null else Revision(id, it.getInt(0), it.getString(1), it.getString(2), it.getLong(3), if (it.isNull(4)) null else it.getString(4), if (it.isNull(5)) null else it.getString(5))
    }
    fun issue(db: SQLiteDatabase, id: String, confirmed: Boolean, at: Long): String? {
        if (!confirmed) return "QUOTE_CONFIRMATION_REQUIRED"
        val draft = draft(db, id) ?: return "QUOTE_DRAFT_UNAVAILABLE"
        val total = totals(draft)
        val company = BusinessStore.settings(db)
        if (!InvoiceEmailTemplate.valid(company.emailSubject, true)) return "INVALID_EMAIL_SUBJECT"
        if (!InvoiceEmailTemplate.valid(company.emailBody, false)) return "INVALID_EMAIL_BODY"
        val customer = draft.customerId?.let { customerId -> BusinessStore.customers(db).firstOrNull { it.id == customerId } }
        if (draft.customerId != null && customer == null) return "CUSTOMER_UNAVAILABLE"
        val job = draft.jobId?.let { jobId ->
            if (!db.rawQuery("SELECT id FROM jobs WHERE id=?", arrayOf(jobId)).use { it.moveToFirst() }) return "JOB_UNAVAILABLE"
            BusinessStore.job(db, jobId)
        }
        val previous = latest(db, id)
        if (previous?.revision == Int.MAX_VALUE) return "QUOTE_REVISION_EXHAUSTED"
        val revision = (previous?.revision ?: 0) + 1
        val sequence = db.rawQuery("SELECT next_number FROM quote_counter WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }
        if (previous == null && sequence == Long.MAX_VALUE) return "QUOTE_SEQUENCE_EXHAUSTED"
        val number = previous?.number ?: "Q-$sequence"
        val digits = currencyDigits(db, id, draft.currency)
        val values = mapOf("company_name" to company.companyName, "customer_name" to customer?.name.orEmpty(),
            "job_name" to draft.title, "invoice_number" to "$number revision $revision", "amount" to InvoiceStore.amount(total.total, digits), "currency" to draft.currency)
        val defaultBody = BusinessStore.Settings(company.currency).emailBody
        val body = if (company.emailBody == defaultBody) "Hello ${customer?.name.orEmpty()},\n\nPlease find quote $number, revision $revision attached.\nEstimated total: ${InvoiceStore.amount(total.total, digits)} ${draft.currency}\n\n${company.companyName}"
            else "QUOTE — estimate, not an invoice.\n\n" + InvoiceEmailTemplate.expand(company.emailBody, values, false)
        val subject = if (company.emailSubject == BusinessStore.Settings(company.currency).emailSubject)
            "Quote $number · Revision $revision — " + draft.title.map { if (it.isISOControl()) ' ' else it }.joinToString("")
            else "Quote · " + InvoiceEmailTemplate.expand(company.emailSubject, values, true)
        if (subject.toByteArray(Charsets.UTF_8).size + body.toByteArray(Charsets.UTF_8).size > 100_000) return "EMAIL_TOO_LARGE"
        val frozen = JSONObject(mapOf("draft" to JSONObject(draft.encode()), "number" to number, "revision" to revision,
            "created" to at, "digits" to digits, "subtotal" to InvoiceStore.amount(total.subtotal, digits),
            "tax" to InvoiceStore.amount(total.tax, digits), "total" to InvoiceStore.amount(total.total, digits),
            "customer" to customer?.name.orEmpty(), "email" to customer?.email.orEmpty(), "customerAddress" to customer?.address.orEmpty(),
            "job" to job?.settings?.name.orEmpty(), "company" to company.companyName,
            "companyEmail" to company.companyEmail, "companyPhone" to company.companyPhone, "companyAddress" to company.companyAddress,
            "companyWebsite" to company.companyWebsite, "style" to company.invoiceStyle, "header" to company.invoiceHeader, "footer" to company.invoiceFooter,
            "subject" to subject, "body" to body)).toString()
        if (frozen.toByteArray(Charsets.UTF_8).size > 1_000_000) return "QUOTE_TOO_LARGE"
        val logo = db.rawQuery("SELECT logo_id FROM app_settings WHERE id=1", null).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }
        if (previous == null) {
            db.execSQL("UPDATE quotes SET number=? WHERE id=?", arrayOf(number, id))
            db.execSQL("UPDATE quote_counter SET next_number=? WHERE id=1", arrayOf(sequence + 1))
        }
        db.execSQL("INSERT INTO quote_revisions VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any?>(id, revision, draft.customerId, draft.jobId, frozen, logo, at, draft.validUntil))
        db.execSQL("DELETE FROM quote_drafts WHERE quote_id=?", arrayOf(id))
        RevisionStore.record(db, "quote_revision", "$id:$revision", draft.jobId, mapOf("quote_id" to id, "revision" to revision, "payload" to frozen, "logo_id" to logo), at)
        RevisionStore.record(db, "quote_draft", id, draft.jobId, mapOf("id" to id), at, deleted = true)
        return null
    }
    /** Adapt the frozen estimate to the shared paginated renderer; never read live prices. */
    fun document(db: SQLiteDatabase, id: String, revision: Int): InvoiceStore.Record {
        val issued = requireNotNull(QuoteStore.revision(db, id, revision)) { "QUOTE_UNAVAILABLE" }
        val v = JSONObject(issued.payload); val draft = Draft.decode(v.getJSONObject("draft").toString())
        val digits = v.getInt("digits")
        val lines = draft.lines.map { line ->
            val units = line.quantity.toLong(); val price = MoneyMath.parse(line.unitPrice)
            val basis = "${TaskQuantityStore.format(units)} ${if (line.kind == "HOURS") "estimated hours" else "units"} × ${InvoiceStore.amount(price, digits)} ${draft.currency}${if (line.kind == "HOURS") "/hour" else " each"}"
            InvoiceStore.Line(line.title, listOf(basis, line.detail).filter { it.isNotEmpty() }.joinToString("\n"), InvoiceStore.amount(TaskQuantityStore.amount(price, units), digits), line.quantity, line.unitPrice)
        }
        val snapshot = InvoiceStore.Snapshot("${issued.number} · Revision $revision", issued.created, v.getString("style"), draft.currency, digits,
            v.getString("company"), listOf("companyEmail", "companyPhone", "companyAddress", "companyWebsite").map { v.getString(it) }.filter { it.isNotEmpty() }.joinToString("\n"),
            v.getString("customer"), listOf(v.getString("email"), v.getString("customerAddress")).filter { it.isNotEmpty() }.joinToString("\n"), draft.title,
            draft.validUntil?.let { "Valid until $it (calendar date)" } ?: "No expiry date set",
            "Estimate only · not an invoice · no payment due",
            v.getString("header"), listOf(draft.terms.takeIf { it.isNotEmpty() }?.let { "Terms\n$it" }, draft.notes.takeIf { it.isNotEmpty() }?.let { "Notes\n$it" }, v.getString("footer")).filterNotNull().filter { it.isNotEmpty() }.joinToString("\n\n"),
            v.getString("total"), v.getString("email"), v.getString("subject"), v.getString("body"), lines,
            v.getString("subtotal"), draft.taxUnits, v.getString("tax"))
        val logoId = db.rawQuery("SELECT logo_id FROM quote_revisions WHERE quote_id=? AND revision=?", arrayOf(id, revision.toString())).use { check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0) }
        val pdfId = "$id:$revision"
        val cached = db.rawQuery("SELECT id FROM quote_pdfs WHERE id=?", arrayOf(pdfId)).use { it.moveToFirst() }
        return InvoiceStore.Record(id, draft.jobId.orEmpty(), issued.number, snapshot.encode(), if (cached) InvoiceBlobs.quote(db, pdfId) else null,
            logoId?.let { InvoiceBlobs.logo(db, it) }, documentKind = "QUOTE", quoteRevision = revision)
    }
    fun cachePdf(db: SQLiteDatabase, id: String, revision: Int, payload: String, bytes: ByteArray): Boolean {
        require(bytes.size in 5..20_000_000 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) { "INVALID_QUOTE_PDF" }
        if (document(db, id, revision).payload != payload) return false
        val inserted = db.compileStatement("INSERT OR IGNORE INTO quote_pdfs VALUES(?,?,?,?)").use {
            it.bindString(1, "$id:$revision"); it.bindString(2, id); it.bindLong(3, revision.toLong()); it.bindBlob(4, bytes); it.executeInsert()
        }
        return inserted != -1L
    }
    fun decide(db: SQLiteDatabase, id: String, revision: Int, decision: String, note: String, confirmed: Boolean, at: Long): String? {
        if (!confirmed || decision !in setOf("ACCEPTED", "DECLINED") || !text(note, 2000)) return "INVALID_QUOTE_DECISION"
        val latest = latest(db, id) ?: return "QUOTE_UNAVAILABLE"
        if (latest.revision != revision) return "QUOTE_REVISION_CHANGED"
        if (latest.decision != null) return "QUOTE_ALREADY_DECIDED"
        db.execSQL("INSERT INTO quote_decisions VALUES(?,?,?,?,?)", arrayOf<Any?>(id, revision, decision, note.trim(), at))
        val source = Draft.decode(JSONObject(latest.payload).getJSONObject("draft").toString())
        RevisionStore.record(db, "quote_decision", "$id:$revision", source.jobId, mapOf("quote_id" to id, "revision" to revision, "decision" to decision, "note" to note.trim(), "recorded_ms" to at), at)
        return null
    }
}
