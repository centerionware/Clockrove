package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.util.Currency
import org.json.JSONArray
import org.json.JSONObject

/** Reviewed planning only. Actual work and exclusive invoice claims are separate commands. */
internal object QuoteScopeStore {
    private val id = Regex("[A-Za-z0-9_-]{1,128}")
    data class Item(val lineId: String, val taskId: String, val title: String, val existing: Boolean = false) {
        fun json() = JSONObject(mapOf("line_id" to lineId, "task_id" to taskId, "title" to title, "existing" to existing))
    }
    data class Draft(val quoteId: String, val revision: Int, val jobId: String, val items: List<Item>,
        val note: String, val confirmed: Boolean, val newJobName: String? = null,
        val historicalReviewed: Boolean = false, val jobDifferencesReviewed: Boolean = false) {
        fun values(): List<Any?> = listOf(quoteId, revision, jobId, JSONArray(items.map { it.json() }), note,
            confirmed, newJobName, historicalReviewed, jobDifferencesReviewed)
    }
    data class Binding(val lineId: String, val jobId: String, val taskId: String, val kind: String, val originalRevision: Int)
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE quote_scope_lines(quote_id TEXT NOT NULL REFERENCES quotes(id),line_id TEXT NOT NULL,job_id TEXT NOT NULL,task_id TEXT NOT NULL UNIQUE,kind TEXT NOT NULL CHECK(kind IN('ITEM','HOURS')),original_revision INTEGER NOT NULL,created_ms INTEGER NOT NULL,PRIMARY KEY(quote_id,line_id),FOREIGN KEY(quote_id,original_revision) REFERENCES quote_revisions(quote_id,revision))")
        db.execSQL("CREATE TABLE quote_scope_revisions(quote_id TEXT NOT NULL,revision INTEGER NOT NULL,line_id TEXT NOT NULL,note TEXT NOT NULL,recorded_ms INTEGER NOT NULL,historical_reviewed INTEGER NOT NULL CHECK(historical_reviewed IN(0,1)),job_differences_reviewed INTEGER NOT NULL CHECK(job_differences_reviewed IN(0,1)),PRIMARY KEY(quote_id,revision,line_id),FOREIGN KEY(quote_id,revision) REFERENCES quote_revisions(quote_id,revision),FOREIGN KEY(quote_id,line_id) REFERENCES quote_scope_lines(quote_id,line_id))")
        for (table in listOf("quote_scope_lines", "quote_scope_revisions")) for (event in listOf("UPDATE", "DELETE")) {
            db.execSQL("CREATE TRIGGER ${table}_immutable_${event.lowercase()} BEFORE $event ON $table BEGIN SELECT RAISE(ABORT,'Quote scope history is immutable'); END")
        }
    }
    fun bindings(db: SQLiteDatabase, quoteId: String): List<Binding> = db.rawQuery("SELECT line_id,job_id,task_id,kind,original_revision FROM quote_scope_lines WHERE quote_id=? ORDER BY line_id", arrayOf(quoteId)).use { rows -> buildList {
        while (rows.moveToNext()) add(Binding(rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4)))
    } }
    private fun used(db: SQLiteDatabase, table: String, kind: String, value: String): Boolean = db.rawQuery(
        "SELECT id FROM $table WHERE id=? UNION ALL SELECT entity_id FROM entity_heads WHERE kind=? AND entity_id=? LIMIT 1", arrayOf(value, kind, value)).use { it.moveToFirst() }
    fun apply(db: SQLiteDatabase, value: Draft, at: Long): String? {
        if (!value.confirmed || value.revision < 1 || value.note.length > 2000 || '\u0000' in value.note || at < 0 ||
            !id.matches(value.jobId) || value.items.size !in 1..2000 || value.items.map { it.lineId }.toSet().size != value.items.size ||
            value.items.map { it.taskId }.toSet().size != value.items.size || value.items.any { !id.matches(it.taskId) || it.title.isBlank() || it.title.length > 240 || '\u0000' in it.title }) return "INVALID_QUOTE_SCOPE"
        val issued = QuoteStore.revision(db, value.quoteId, value.revision) ?: return "QUOTE_UNAVAILABLE"
        if (issued.decision != "ACCEPTED") return "QUOTE_ACCEPTANCE_REQUIRED"
        if (QuoteStore.latest(db, value.quoteId)?.revision != value.revision && !value.historicalReviewed) return "QUOTE_HISTORICAL_REVIEW_REQUIRED"
        val frozen = JSONObject(issued.payload); val source = QuoteStore.Draft.decode(frozen.getJSONObject("draft").toString())
        if (Currency.getInstance(source.currency).defaultFractionDigits != frozen.getInt("digits")) return "QUOTE_CURRENCY_PRECISION_CHANGED"
        val byId = source.lines.associateBy { it.id }
        if (value.items.any { it.lineId !in byId }) return "QUOTE_LINE_UNAVAILABLE"
        val lines = value.items.map { byId.getValue(it.lineId) }
        val kind = lines.first().kind
        if (lines.any { it.kind != kind } || kind == "HOURS" && lines.map { it.unitPrice }.toSet().size != 1) return "QUOTE_SCOPE_SPLIT_REQUIRED"
        val previous = bindings(db, value.quoteId).associateBy { it.lineId }
        for (item in value.items) previous[item.lineId]?.let {
            if (it.jobId != value.jobId || it.taskId != item.taskId) return "QUOTE_SCOPE_ALREADY_MAPPED"
            if (it.kind != kind) return "QUOTE_SCOPE_KIND_CHANGED"
        }
        if (kind == "HOURS" && previous.values.any { binding -> binding.jobId == value.jobId && binding.kind == kind &&
                byId[binding.lineId]?.let { it.kind != kind || it.unitPrice != lines.first().unitPrice } == true }) return "QUOTE_SCOPE_SPLIT_REQUIRED"
        val exists = db.rawQuery("SELECT id FROM jobs WHERE id=?", arrayOf(value.jobId)).use { it.moveToFirst() }
        val missing = value.items.filter { it.lineId !in previous }
        if (!exists && value.newJobName == null) return "JOB_UNAVAILABLE"
        if (!exists && (used(db, "jobs", "job", value.jobId) || db.rawQuery("SELECT id FROM job_deletions WHERE id=?", arrayOf(value.jobId)).use { it.moveToFirst() })) return "JOB_ID_EXISTS"
        if (exists && value.newJobName != null && missing.isNotEmpty()) return "JOB_ID_EXISTS"
        if (value.newJobName != null && (value.newJobName.isBlank() || value.newJobName.length > 240 || '\u0000' in value.newJobName)) return "INVALID_JOB"
        val mode = if (kind == "HOURS") "HOURLY" else "TASK"
        val rate = if (kind == "HOURS") MoneyMath.parse(lines.first().unitPrice) else null
        val customer = source.customerId?.let { customerId -> BusinessStore.customers(db).firstOrNull { it.id == customerId } }
        if (!exists && source.customerId != null && customer == null) return "CUSTOMER_UNAVAILABLE"
        if (!exists && customer?.archived == true) return "CUSTOMER_ARCHIVED"
        val board = if (exists) BoardStore.read(db, value.jobId) else null
        if (board?.archived == true) return "JOB_ARCHIVED"
        if (board != null && board.currency != source.currency) return "CURRENCY_MISMATCH"
        val job = if (exists) BusinessStore.job(db, value.jobId) else null
        if (job != null && job.settings.billingMode != mode) return "QUOTE_JOB_MODE_MISMATCH"
        if (job != null && !value.jobDifferencesReviewed && (job.settings.customerId != source.customerId || job.effectiveTaxUnits != source.taxUnits || kind == "HOURS" && job.effectiveHourlyMinor != rate)) return "QUOTE_JOB_REVIEW_REQUIRED"
        val open = board?.columns?.firstOrNull { !it.completed }
        if (board != null && missing.any { !it.existing } && open == null) return "QUOTE_SCOPE_OPEN_COLUMN_REQUIRED"
        for (item in missing) {
            val task = board?.cards?.firstOrNull { it.id == item.taskId }
            if (item.existing && task == null) return "TASK_UNAVAILABLE"
            if (!item.existing && used(db, "kanban_cards", "card", item.taskId)) return "TASK_ID_EXISTS"
            if (db.rawQuery("SELECT task_id FROM quote_scope_lines WHERE task_id=?", arrayOf(item.taskId)).use { it.moveToFirst() }) return "QUOTE_TASK_ALREADY_MAPPED"
        }
        // Every rejection above precedes every write. Unexpected storage failures
        // below must throw and roll back the repository's entire transaction.
        if (!exists) {
            db.execSQL("INSERT INTO jobs(id,name,currency,billing_mode,customer_id,hourly_rate_minor) VALUES(?,?,?,?,?,?)", arrayOf<Any?>(value.jobId, value.newJobName!!.trim(), source.currency, mode, source.customerId, MoneyMath.legacy(rate)))
            BusinessSchema.seedBoard(db, value.jobId, at)
            MoneyStore.save(db, "job_money", value.jobId, value.jobId, rate, at)
            BillingTaxStore.saveJob(db, value.jobId, source.taxUnits, at)
            RevisionStore.snapshotRows(db, "jobs", "job", BusinessSchema.jobColumns, at, where = "id=?", args = arrayOf(value.jobId))
        }
        val column = open?.id ?: BoardStore.columns(db, value.jobId).firstOrNull { !it.completed }?.id
        for (item in value.items) {
            val line = byId.getValue(item.lineId)
            if (item.lineId !in previous) {
                if (!item.existing) {
                    // Reusable definitions contain no quote IDs/history. The
                    // retained scope tables carry provenance independently.
                    val description = listOf(line.detail, if (kind == "HOURS") "Estimated ${TaskQuantityStore.format(line.quantity.toLong())} hours. Record actual work with the job timer; this planning task is not a charge." else "").filter { it.isNotEmpty() }.joinToString("\n\n")
                    checkNull(BoardStore.saveCard(db, BoardStore.Draft(item.taskId, value.jobId, requireNotNull(column), item.title, description,
                        billable = kind == "ITEM", priceMinor = if (kind == "ITEM") MoneyMath.parse(line.unitPrice) else null, currency = source.currency,
                        quantityUnits = if (kind == "ITEM") line.quantity.toLong() else TaskQuantityStore.one,
                        chargeType = TaskClassification.UNCLASSIFIED), at))
                }
                db.execSQL("INSERT INTO quote_scope_lines VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(value.quoteId, item.lineId, value.jobId, item.taskId, kind, value.revision, at))
                RevisionStore.record(db, "quote_scope_line", "${value.quoteId}:${item.lineId}", value.jobId, mapOf("quote_id" to value.quoteId, "line_id" to item.lineId,
                    "job_id" to value.jobId, "task_id" to item.taskId, "kind" to kind, "original_revision" to value.revision, "created_ms" to at), at)
            }
            val linked = db.rawQuery("SELECT 1 FROM quote_scope_revisions WHERE quote_id=? AND revision=? AND line_id=?", arrayOf(value.quoteId, value.revision.toString(), item.lineId)).use { it.moveToFirst() }
            if (!linked) {
                db.execSQL("INSERT INTO quote_scope_revisions VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(value.quoteId, value.revision, item.lineId, value.note.trim(), at, if (value.historicalReviewed) 1 else 0, if (value.jobDifferencesReviewed) 1 else 0))
                RevisionStore.record(db, "quote_scope_revision", "${value.quoteId}:${value.revision}:${item.lineId}", value.jobId, mapOf("quote_id" to value.quoteId,
                    "revision" to value.revision, "line_id" to item.lineId, "note" to value.note.trim(), "recorded_ms" to at,
                    "historical_reviewed" to value.historicalReviewed, "job_differences_reviewed" to value.jobDifferencesReviewed), at)
            }
        }
        return null
    }
    private fun checkNull(problem: String?) { check(problem == null) { "Quote scope task validation changed: $problem" } }
}
