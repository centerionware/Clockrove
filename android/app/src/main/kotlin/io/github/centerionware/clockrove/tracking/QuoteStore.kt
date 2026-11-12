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
        db.execSQL("CREATE TABLE quote_drafts(quote_id TEXT PRIMARY KEY NOT NULL REFERENCES quotes(id),customer_id TEXT,job_id TEXT,payload TEXT NOT NULL,updated_ms INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE quote_revisions(quote_id TEXT NOT NULL REFERENCES quotes(id),revision INTEGER NOT NULL CHECK(revision>0),customer_id TEXT,job_id TEXT,payload TEXT NOT NULL,logo_id TEXT REFERENCES logo_assets(id),created_ms INTEGER NOT NULL,PRIMARY KEY(quote_id,revision))")
        db.execSQL("CREATE TABLE quote_decisions(quote_id TEXT NOT NULL,revision INTEGER NOT NULL,decision TEXT NOT NULL CHECK(decision IN('ACCEPTED','DECLINED')),note TEXT NOT NULL,recorded_ms INTEGER NOT NULL,PRIMARY KEY(quote_id,revision),FOREIGN KEY(quote_id,revision) REFERENCES quote_revisions(quote_id,revision))")
        for (table in listOf("quote_revisions", "quote_decisions")) for (event in listOf("UPDATE", "DELETE")) {
            db.execSQL("CREATE TRIGGER ${table}_immutable_${event.lowercase()} BEFORE $event ON $table BEGIN SELECT RAISE(ABORT,'Issued quote history is immutable'); END")
        }
    }
    fun draft(db: SQLiteDatabase, id: String): Draft? = db.rawQuery("SELECT payload FROM quote_drafts WHERE quote_id=?", arrayOf(id)).use {
        if (it.moveToFirst()) Draft.decode(it.getString(0)) else null
    }
    fun save(db: SQLiteDatabase, value: Draft, at: Long): String? {
        totals(value)
        val payload = value.encode()
        require(payload.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "QUOTE_TOO_LARGE" }
        if (value.customerId != null && BusinessStore.customers(db).none { it.id == value.customerId }) return "CUSTOMER_UNAVAILABLE"
        if (value.jobId != null && !db.rawQuery("SELECT id FROM jobs WHERE id=?", arrayOf(value.jobId)).use { it.moveToFirst() }) return "JOB_UNAVAILABLE"
        db.execSQL("INSERT OR IGNORE INTO quotes VALUES(?,NULL,?)", arrayOf<Any?>(value.id, at))
        db.execSQL("INSERT OR REPLACE INTO quote_drafts VALUES(?,?,?,?,?)", arrayOf<Any?>(value.id, value.customerId, value.jobId, payload, at))
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
        val digits = Currency.getInstance(draft.currency).defaultFractionDigits
        val frozen = JSONObject(mapOf("draft" to JSONObject(draft.encode()), "number" to number, "revision" to revision,
            "created" to at, "digits" to digits, "subtotal" to InvoiceStore.amount(total.subtotal, digits),
            "tax" to InvoiceStore.amount(total.tax, digits), "total" to InvoiceStore.amount(total.total, digits),
            "customer" to customer?.name.orEmpty(), "email" to customer?.email.orEmpty(), "customerAddress" to customer?.address.orEmpty(),
            "job" to job?.settings?.name.orEmpty(), "company" to company.companyName,
            "companyEmail" to company.companyEmail, "companyPhone" to company.companyPhone, "companyAddress" to company.companyAddress,
            "companyWebsite" to company.companyWebsite, "style" to company.invoiceStyle, "header" to company.invoiceHeader, "footer" to company.invoiceFooter)).toString()
        require(frozen.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "QUOTE_TOO_LARGE" }
        val logo = db.rawQuery("SELECT logo_id FROM app_settings WHERE id=1", null).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }
        if (previous == null) {
            db.execSQL("UPDATE quotes SET number=? WHERE id=?", arrayOf(number, id))
            db.execSQL("UPDATE quote_counter SET next_number=? WHERE id=1", arrayOf(sequence + 1))
        }
        db.execSQL("INSERT INTO quote_revisions VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(id, revision, draft.customerId, draft.jobId, frozen, logo, at))
        db.execSQL("DELETE FROM quote_drafts WHERE quote_id=?", arrayOf(id))
        RevisionStore.record(db, "quote_revision", "$id:$revision", draft.jobId, mapOf("quote_id" to id, "revision" to revision, "payload" to frozen, "logo_id" to logo), at)
        RevisionStore.record(db, "quote_draft", id, draft.jobId, mapOf("id" to id), at, deleted = true)
        return null
    }
    fun decide(db: SQLiteDatabase, id: String, revision: Int, decision: String, note: String, confirmed: Boolean, at: Long): String? {
        if (!confirmed || decision !in setOf("ACCEPTED", "DECLINED") || note.trim().length < 10 || !text(note, 2000)) return "INVALID_QUOTE_DECISION"
        val latest = latest(db, id) ?: return "QUOTE_UNAVAILABLE"
        if (latest.revision != revision) return "QUOTE_REVISION_CHANGED"
        if (latest.decision != null) return "QUOTE_ALREADY_DECIDED"
        db.execSQL("INSERT INTO quote_decisions VALUES(?,?,?,?,?)", arrayOf<Any?>(id, revision, decision, note.trim(), at))
        val source = Draft.decode(JSONObject(latest.payload).getJSONObject("draft").toString())
        RevisionStore.record(db, "quote_decision", "$id:$revision", source.jobId, mapOf("quote_id" to id, "revision" to revision, "decision" to decision, "note" to note.trim(), "recorded_ms" to at), at)
        return null
    }
}
