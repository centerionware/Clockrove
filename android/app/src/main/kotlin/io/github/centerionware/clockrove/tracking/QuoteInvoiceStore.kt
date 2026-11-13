package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Same source claims as ordinary invoices; retained links describe original issue. */
internal object QuoteInvoiceStore {
    data class Draft(val invoiceId: String, val request: QuoteInvoiceReviewStore.Request, val choices: QuoteInvoiceReviewStore.Choices) {
        fun fingerprint(): String = JSONObject(mapOf("invoice" to invoiceId, "quote" to request.quoteId, "revision" to request.revision,
            "first" to request.selection.firstDate, "last" to request.selection.lastDate, "timezone" to request.selection.timezone,
            "jobs" to JSONArray(request.selection.jobIds), "tasks" to JSONArray(choices.taskIds),
            "times" to JSONArray(choices.times.map { JSONObject(mapOf("session" to it.sessionId, "start" to it.start, "end" to it.end)) }),
            "currentPrices" to choices.currentPrices, "currentTax" to choices.currentTax, "currentParty" to choices.currentParty,
            "note" to choices.note, "confirmed" to choices.confirmed, "historicalReviewed" to choices.historicalReviewed)).toString()
    }
    data class Link(val invoiceId: String, val quoteId: String, val revision: Int, val originalInvoiceId: String, val created: Long, val payload: String)
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE quote_invoice_links(invoice_id TEXT PRIMARY KEY NOT NULL REFERENCES invoices(id),quote_id TEXT NOT NULL,revision INTEGER NOT NULL,original_invoice_id TEXT NOT NULL REFERENCES invoices(id),created_ms INTEGER NOT NULL CHECK(created_ms>=0),payload TEXT NOT NULL,FOREIGN KEY(quote_id,revision) REFERENCES quote_revisions(quote_id,revision))")
        db.execSQL("CREATE INDEX quote_invoice_history ON quote_invoice_links(quote_id,created_ms,invoice_id)")
        for (event in listOf("UPDATE", "DELETE")) db.execSQL("CREATE TRIGGER quote_invoice_immutable_${event.lowercase()} BEFORE $event ON quote_invoice_links BEGIN SELECT RAISE(ABORT,'Quote invoice origin is immutable'); END")
    }
    data class Summary(val invoiceId: String, val number: String, val revision: Int, val originalInvoiceId: String, val created: Long)
    fun links(db: SQLiteDatabase, quoteId: String, before: Pair<Long, String>? = null, limit: Int = 20): List<Summary> {
        require(limit in 1..50)
        val tail = if (before == null) "" else " AND (q.created_ms<? OR (q.created_ms=? AND q.invoice_id<?))"
        val args = if (before == null) arrayOf(quoteId, limit.toString()) else arrayOf(quoteId, before.first.toString(), before.first.toString(), before.second, limit.toString())
        return db.rawQuery("SELECT q.invoice_id,i.number,q.revision,q.original_invoice_id,q.created_ms FROM quote_invoice_links q JOIN invoices i ON i.id=q.invoice_id WHERE q.quote_id=?$tail ORDER BY q.created_ms DESC,q.invoice_id DESC LIMIT ?", args).use { rows -> buildList {
            while (rows.moveToNext()) add(Summary(rows.getString(0), rows.getString(1), rows.getInt(2), rows.getString(3), rows.getLong(4)))
        } }
    }
    fun origin(db: SQLiteDatabase, invoiceId: String): Link? = db.rawQuery("SELECT invoice_id,quote_id,revision,original_invoice_id,created_ms,payload FROM quote_invoice_links WHERE invoice_id=?", arrayOf(invoiceId)).use {
        if (!it.moveToFirst()) null else Link(it.getString(0), it.getString(1), it.getInt(2), it.getString(3), it.getLong(4), it.getString(5))
    }
    private fun retain(db: SQLiteDatabase, link: Link, jobId: String, at: Long) {
        db.execSQL("INSERT INTO quote_invoice_links VALUES(?,?,?,?,?,?)", arrayOf<Any?>(link.invoiceId, link.quoteId, link.revision, link.originalInvoiceId, link.created, link.payload))
        RevisionStore.record(db, "quote_invoice_link", link.invoiceId, jobId, mapOf("invoice_id" to link.invoiceId, "quote_id" to link.quoteId,
            "revision" to link.revision, "original_invoice_id" to link.originalInvoiceId, "created_ms" to link.created, "payload" to link.payload), at)
    }
    fun copyOrigin(db: SQLiteDatabase, source: InvoiceStore.Record, replacementId: String, at: Long) {
        val link = origin(db, source.id)?.copy(invoiceId = replacementId, created = at) ?: return
        retain(db, link, source.jobId, at)
    }
    fun prepare(db: SQLiteDatabase, draft: Draft, at: Long): String? {
        if (!draft.invoiceId.matches(Regex("[A-Za-z0-9_-]{1,100}")) || at < 0) return "INVALID_INVOICE"
        if (db.rawQuery("SELECT 1 FROM invoices WHERE id=?", arrayOf(draft.invoiceId)).use { it.moveToFirst() }) return "INVOICE_ID_EXISTS"
        val review = QuoteInvoiceReviewStore.review(db, draft.request, draft.choices)
        review.problem?.let { return it }
        val reviewed = requireNotNull(review.value)
        val context = reviewed.context
        val byTask = context.tasks.associateBy { it.actual.id }
        val origin = JSONObject(mapOf("kind" to "ORIGINAL_ISSUE_PROVENANCE", "quote_number" to context.issued.number,
            "quote_id" to context.issued.quoteId, "revision" to context.issued.revision,
            "accepted_tax_units" to context.agreement.taxUnits, "chosen_tax_units" to reviewed.taxUnits,
            "current_prices" to draft.choices.currentPrices, "current_tax" to draft.choices.currentTax, "current_party" to draft.choices.currentParty,
            "note" to draft.choices.note.trim(), "historical_reviewed" to draft.choices.historicalReviewed,
            "lines" to JSONArray(context.lines.map { it.json() }),
            "tasks" to JSONArray(reviewed.report.tasks.map { task -> val source = byTask.getValue(task.id)
                JSONObject(mapOf("task_id" to task.id, "line_id" to source.accepted.id, "quantity_units" to task.quantityUnits.toString(),
                    "chosen_unit_price_minor" to task.priceMinor!!.toString(), "current_unit_price_minor" to source.actual.priceMinor?.toString())) }),
            "times" to JSONArray(reviewed.report.intervals.map { JSONObject(mapOf("session_id" to it.sessionId, "start_ms" to it.start, "end_ms" to it.end, "timezone" to reviewed.report.selection.timezone)) }),
            "hourly_minor" to reviewed.report.totals.single().hourlyMinor?.toString())).toString()
        if (origin.toByteArray(Charsets.UTF_8).size > 1_000_000) return "INVOICE_TOO_LARGE"
        InvoiceStore.prepareReviewed(db, draft.invoiceId, context.coverage.copy(report = reviewed.report), at, reviewed)?.let { return it }
        retain(db, Link(draft.invoiceId, draft.request.quoteId, draft.request.revision, draft.invoiceId, at, origin), context.job.settings.id, at)
        return null
    }
}
