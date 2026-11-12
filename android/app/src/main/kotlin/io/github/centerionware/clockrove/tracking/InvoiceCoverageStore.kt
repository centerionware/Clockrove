package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.UUID

/** Durable source reservations, independent of mutable/deletable work rows. */
internal object InvoiceCoverageStore {
    data class Exclusion(val invoiceId: String, val number: String, val taskId: String? = null,
        val sessionId: String? = null, val start: Long? = null, val end: Long? = null)
    data class Preview(val report: ReportStore.Report, val excluded: List<Exclusion>, val legacy: List<Pair<String, String>>)
    private data class TimeClaim(val invoiceId: String, val number: String, val start: Long, val end: Long)

    fun install(db: SQLiteDatabase) {
        // Financial history survives job purge. Keep the opaque job identity and
        // frozen document; do not retain deleted job work/GPS to satisfy a FK.
        db.execSQL("CREATE TABLE invoices_retained(id TEXT PRIMARY KEY,job_id TEXT NOT NULL,customer_id TEXT REFERENCES customers(id),number TEXT NOT NULL UNIQUE,created_ms INTEGER NOT NULL,payload TEXT NOT NULL,pdf BLOB,logo_id TEXT REFERENCES logo_assets(id))")
        db.execSQL("INSERT INTO invoices_retained SELECT id,job_id,customer_id,number,created_ms,payload,pdf,logo_id FROM invoices")
        db.execSQL("DROP TABLE invoices")
        db.execSQL("ALTER TABLE invoices_retained RENAME TO invoices")
        db.execSQL("CREATE INDEX invoices_job_date ON invoices(job_id,created_ms,id)")
        db.execSQL("CREATE TABLE invoice_coverage(invoice_id TEXT PRIMARY KEY NOT NULL REFERENCES invoices(id),state TEXT NOT NULL CHECK(state IN('LEGACY','COMPLETE')),reviewed_ms INTEGER CHECK(reviewed_ms IS NULL OR reviewed_ms>=0),review_note TEXT NOT NULL DEFAULT '')")
        // Old snapshots lack stable task IDs and exact source intervals. Never
        // guess identity from titles, prices, or today's mutable report.
        db.execSQL("INSERT INTO invoice_coverage(invoice_id,state) SELECT id,'LEGACY' FROM invoices")
        db.execSQL("CREATE TABLE invoice_task_claims(id TEXT PRIMARY KEY NOT NULL,invoice_id TEXT NOT NULL REFERENCES invoices(id),job_id TEXT NOT NULL,task_id TEXT NOT NULL,released_ms INTEGER CHECK(released_ms IS NULL OR released_ms>=0))")
        db.execSQL("CREATE UNIQUE INDEX invoice_active_task ON invoice_task_claims(task_id) WHERE released_ms IS NULL")
        db.execSQL("CREATE INDEX invoice_tasks_job ON invoice_task_claims(job_id,invoice_id)")
        db.execSQL("CREATE TABLE invoice_time_claims(id TEXT PRIMARY KEY NOT NULL,invoice_id TEXT NOT NULL REFERENCES invoices(id),job_id TEXT NOT NULL,session_id TEXT NOT NULL,start_ms INTEGER NOT NULL CHECK(typeof(start_ms)='integer'),end_ms INTEGER NOT NULL CHECK(typeof(end_ms)='integer' AND end_ms>start_ms),released_ms INTEGER CHECK(released_ms IS NULL OR released_ms>=0))")
        db.execSQL("CREATE INDEX invoice_active_time ON invoice_time_claims(job_id,start_ms,end_ms) WHERE released_ms IS NULL")
        for (table in listOf("invoice_task_claims", "invoice_time_claims")) {
            for (event in listOf("INSERT", "UPDATE")) {
                db.execSQL("CREATE TRIGGER ${table}_job_${event.lowercase()} BEFORE $event ON $table WHEN NOT EXISTS(SELECT 1 FROM invoices i WHERE i.id=NEW.invoice_id AND i.job_id=NEW.job_id) BEGIN SELECT RAISE(ABORT,'Invoice claim job mismatch'); END")
            }
        }
        for (event in listOf("INSERT", "UPDATE")) {
            db.execSQL("CREATE TRIGGER invoice_time_overlap_${event.lowercase()} BEFORE $event ON invoice_time_claims WHEN NEW.released_ms IS NULL AND EXISTS(SELECT 1 FROM invoice_time_claims c WHERE c.id<>NEW.id AND c.job_id=NEW.job_id AND c.released_ms IS NULL AND c.start_ms<NEW.end_ms AND c.end_ms>NEW.start_ms) BEGIN SELECT RAISE(ABORT,'Invoice time already claimed'); END")
        }
    }

    fun preview(db: SQLiteDatabase, source: ReportStore.Report): Preview {
        val excluded = mutableListOf<Exclusion>()
        val legacy = mutableListOf<Pair<String, String>>()
        val intervals = mutableListOf<ReportStore.Interval>()
        val tasks = mutableListOf<ReportStore.Task>()
        source.totals.forEach { total ->
            val job = total.jobId
            db.rawQuery("SELECT i.id,i.number FROM invoices i LEFT JOIN invoice_coverage c ON c.invoice_id=i.id WHERE i.job_id=? AND (c.invoice_id IS NULL OR c.state='LEGACY') ORDER BY i.created_ms,i.id", arrayOf(job)).use { rows ->
                while (rows.moveToNext()) legacy.add(rows.getString(0) to rows.getString(1))
            }
            val claims = db.rawQuery("SELECT c.invoice_id,i.number,c.start_ms,c.end_ms FROM invoice_time_claims c JOIN invoices i ON i.id=c.invoice_id WHERE c.job_id=? AND c.released_ms IS NULL AND c.start_ms<? AND c.end_ms>? ORDER BY c.start_ms,c.end_ms,c.id",
                arrayOf(job, source.days.last().end.toString(), source.days.first().start.toString())).use { rows ->
                buildList { while (rows.moveToNext()) add(TimeClaim(rows.getString(0), rows.getString(1), rows.getLong(2), rows.getLong(3))) }
            }
            source.intervals.filter { it.jobId == job }.forEach { interval ->
                var cursor = interval.start
                for (claim in claims) {
                    if (claim.start >= interval.end) break
                    if (claim.end <= cursor) continue
                    if (claim.start > cursor) intervals.add(interval.copy(start = cursor, end = minOf(claim.start, interval.end)))
                    val start = maxOf(cursor, claim.start)
                    val end = minOf(interval.end, claim.end)
                    if (start < end && total.mode == "HOURLY") excluded.add(Exclusion(claim.invoiceId, claim.number, sessionId = interval.sessionId, start = start, end = end))
                    cursor = maxOf(cursor, end)
                    if (cursor >= interval.end) break
                }
                if (cursor < interval.end) intervals.add(interval.copy(start = cursor))
            }
            val taskClaims = db.rawQuery("SELECT c.task_id,c.invoice_id,i.number FROM invoice_task_claims c JOIN invoices i ON i.id=c.invoice_id WHERE c.job_id=? AND c.released_ms IS NULL", arrayOf(job)).use { rows ->
                buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getString(1) to rows.getString(2)) }
            }
            source.tasks.filter { it.jobId == job }.forEach { task ->
                val owner = taskClaims[task.id]
                if (owner == null) tasks.add(task) else if (total.mode == "TASK") excluded.add(Exclusion(owner.first, owner.second, taskId = task.id))
            }
        }
        val totals = source.totals.map { total ->
            val duration = intervals.filter { it.jobId == total.jobId }.fold(BigInteger.ZERO) { sum, interval -> sum.add(BigInteger.valueOf(interval.end - interval.start)) }
            val selected = tasks.filter { it.jobId == total.jobId }
            val missing = selected.count { it.priceMinor == null || it.currency != total.currency }
            val amount = if (total.mode == "HOURLY") total.hourlyMinor?.let { ReportStore.hourlyAmount(duration, it) }
                else if (missing > 0) null else selected.fold(BigInteger.ZERO) { sum, task -> sum.add(BigInteger.valueOf(requireNotNull(task.priceMinor))) }
            total.copy(durationMillis = duration, amountMinor = amount, missingPrices = missing)
        }
        return Preview(source.copy(intervals = intervals, tasks = tasks, totals = totals), excluded, legacy)
    }

    /** Caller holds the same transaction that inserts the immutable invoice. */
    fun reserve(db: SQLiteDatabase, invoiceId: String, report: ReportStore.Report, at: Long) {
        db.execSQL("INSERT INTO invoice_coverage(invoice_id,state) VALUES(?,'COMPLETE')", arrayOf(invoiceId))
        val total = report.totals.single()
        if (total.mode == "TASK") report.tasks.forEach { task ->
            val id = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO invoice_task_claims(id,invoice_id,job_id,task_id) VALUES(?,?,?,?)", arrayOf(id, invoiceId, total.jobId, task.id))
            RevisionStore.snapshotRows(db, "invoice_task_claims", "invoice_task_claim", listOf("id", "invoice_id", "job_id", "task_id", "released_ms"), at, "job_id", "id=?", arrayOf(id))
        } else report.intervals.forEach { interval ->
            val id = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO invoice_time_claims(id,invoice_id,job_id,session_id,start_ms,end_ms) VALUES(?,?,?,?,?,?)", arrayOf<Any?>(id, invoiceId, total.jobId, interval.sessionId, interval.start, interval.end))
            RevisionStore.snapshotRows(db, "invoice_time_claims", "invoice_time_claim", listOf("id", "invoice_id", "job_id", "session_id", "start_ms", "end_ms", "released_ms"), at, "job_id", "id=?", arrayOf(id))
        }
        RevisionStore.record(db, "invoice_coverage", invoiceId, total.jobId, mapOf("invoice_id" to invoiceId, "state" to "COMPLETE", "reviewed_ms" to null, "review_note" to ""), at)
    }
}
