package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.TimeZone
import org.json.JSONArray

/** Owner-reviewed mapping only. Never infer source IDs from mutable titles/prices. */
internal object LegacyInvoiceStore {
    data class Span(val start: Long, val end: Long, val timezone: String)
    data class Draft(val invoiceId: String, val tasks: List<String>, val spans: List<Span>,
        val note: String, val confirmed: Boolean, val noSources: Boolean) {
        fun values(): List<Any?> = listOf(invoiceId, JSONArray(tasks),
            JSONArray(spans.map { JSONArray(listOf(it.start, it.end, it.timezone)) }), note, confirmed, noSources)
    }
    data class Task(val id: String, val title: String, val completed: Long?, val invoiceId: String?, val number: String?)
    data class ReservedTime(val start: Long, val end: Long, val timezone: String, val invoiceId: String, val number: String)
    data class Context(val invoiceId: String, val number: String, val jobId: String, val jobName: String,
        val total: String, val currency: String, val period: String, val legacy: Boolean, val jobDeleted: Boolean,
        val note: String, val reviewed: Long?, val tasks: List<Task>, val reservedTimes: List<ReservedTime>)

    fun context(db: SQLiteDatabase, invoiceId: String): Context {
        val header = db.rawQuery("SELECT i.job_id,i.number,i.payload,j.id,c.state,c.review_note,c.reviewed_ms FROM invoices i LEFT JOIN jobs j ON j.id=i.job_id LEFT JOIN invoice_coverage c ON c.invoice_id=i.id WHERE i.id=?", arrayOf(invoiceId)).use {
            require(it.moveToFirst()) { "INVOICE_UNAVAILABLE" }
            val frozen = InvoiceStore.Snapshot.decode(it.getString(2))
            Context(invoiceId, it.getString(1), it.getString(0), frozen.job, frozen.total, frozen.currency, frozen.period,
                it.isNull(4) || it.getString(4) == "LEGACY", it.isNull(3), if (it.isNull(5)) "" else it.getString(5),
                if (it.isNull(6)) null else it.getLong(6), emptyList(), emptyList())
        }
        val tasks = db.rawQuery("SELECT t.id,t.title,t.completed_ms,c.invoice_id,i.number FROM kanban_cards t LEFT JOIN invoice_task_claims c ON c.task_id=t.id AND c.released_ms IS NULL LEFT JOIN invoices i ON i.id=c.invoice_id WHERE t.job_id=? ORDER BY t.title,t.id", arrayOf(header.jobId)).use { rows ->
            buildList { while (rows.moveToNext()) add(Task(rows.getString(0), rows.getString(1), if (rows.isNull(2)) null else rows.getLong(2), if (rows.isNull(3)) null else rows.getString(3), if (rows.isNull(4)) null else rows.getString(4))) }
        }
        val times = db.rawQuery("SELECT c.start_ms,c.end_ms,c.timezone,c.invoice_id,i.number FROM invoice_time_claims c JOIN invoices i ON i.id=c.invoice_id WHERE c.job_id=? AND c.released_ms IS NULL ORDER BY c.start_ms,c.end_ms,c.id", arrayOf(header.jobId)).use { rows ->
            buildList { while (rows.moveToNext()) add(ReservedTime(rows.getLong(0), rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4))) }
        }
        return header.copy(tasks = tasks, reservedTimes = times)
    }

    /** Must run inside the versioned writer transaction, after receipt replay. */
    fun reconcile(db: SQLiteDatabase, value: Draft, at: Long): String? {
        if (InvoiceLifecycleStore.voidInfo(db, value.invoiceId) != null) return "INVOICE_VOID"
        if (!value.confirmed) return "LEGACY_CONFIRMATION_REQUIRED"
        if (at < 0) return "INVALID_CLOCK"
        val note = value.note.trim()
        if (note.length !in 10..4000 || note.contains('\u0000')) return "LEGACY_REVIEW_NOTE_REQUIRED"
        if (value.tasks.size > 2000 || value.spans.size > 1000 || value.tasks.distinct().size != value.tasks.size || value.tasks.any { it.isBlank() }) return "INVALID_INVOICE_SOURCES"
        val empty = value.tasks.isEmpty() && value.spans.isEmpty()
        if (empty != value.noSources) return "LEGACY_SOURCE_CHOICE_REQUIRED"
        val header = db.rawQuery("SELECT i.job_id,c.state,EXISTS(SELECT 1 FROM jobs j WHERE j.id=i.job_id) FROM invoices i LEFT JOIN invoice_coverage c ON c.invoice_id=i.id WHERE i.id=?", arrayOf(value.invoiceId)).use {
            if (!it.moveToFirst()) return "INVOICE_UNAVAILABLE"
            if (!it.isNull(1) && it.getString(1) == "COMPLETE") return "INVOICE_ALREADY_RECONCILED"
            if (it.getInt(2) == 0) return "JOB_UNAVAILABLE"
            it.getString(0)
        }
        val zones = TimeZone.getAvailableIDs().toSet()
        val maximumSpan = BigInteger.valueOf(3660L * 86400000)
        for (span in value.spans) {
            val duration = BigInteger.valueOf(span.end).subtract(BigInteger.valueOf(span.start))
            if (span.timezone !in zones || duration.signum() <= 0 || duration > maximumSpan) return "INVALID_COVERED_TIME"
            try { SessionTime.format(span.start, span.timezone); SessionTime.format(span.end, span.timezone) }
            catch (_: IllegalArgumentException) { return "INVALID_COVERED_TIME" }
        }
        val ordered = value.spans.sortedWith(compareBy({ it.start }, { it.end }))
        if (ordered.zipWithNext().any { (left, right) -> left.end > right.start }) return "INVALID_COVERED_TIME"
        for (chunk in value.tasks.chunked(400)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val found = db.rawQuery("SELECT id FROM kanban_cards WHERE job_id=? AND id IN($placeholders)", (listOf(header) + chunk).toTypedArray()).use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            if (found.size != chunk.size) return "INVOICE_SOURCE_UNAVAILABLE"
            if (db.rawQuery("SELECT id FROM invoice_task_claims WHERE released_ms IS NULL AND task_id IN($placeholders) LIMIT 1", chunk.toTypedArray()).use { it.moveToFirst() }) return "INVOICE_SOURCE_ALREADY_CLAIMED"
        }
        for (span in ordered) {
            if (db.rawQuery("SELECT id FROM invoice_time_claims WHERE job_id=? AND released_ms IS NULL AND start_ms<? AND end_ms>? LIMIT 1",
                arrayOf(header, span.end.toString(), span.start.toString())).use { it.moveToFirst() }) return "INVOICE_SOURCE_ALREADY_CLAIMED"
        }
        // All validation happens before writes. No prices, invoice number, cached
        // PDF, logo, taxes, payment status or timer state are changed here.
        value.tasks.forEach { InvoiceCoverageStore.reserveTask(db, value.invoiceId, header, it, at) }
        ordered.forEach { InvoiceCoverageStore.reserveTime(db, value.invoiceId, header, "legacy:${value.invoiceId}", it.start, it.end, it.timezone, at) }
        db.execSQL("INSERT OR REPLACE INTO invoice_coverage(invoice_id,state,reviewed_ms,review_note) VALUES(?,'COMPLETE',?,?)", arrayOf<Any?>(value.invoiceId, at, note))
        RevisionStore.record(db, "invoice_coverage", value.invoiceId, header, mapOf("invoice_id" to value.invoiceId,
            "state" to "COMPLETE", "reviewed_ms" to at, "review_note" to note, "confirmed" to true, "no_sources" to value.noSources), at, origin = "LEGACY_RECONCILIATION")
        return null
    }
}
