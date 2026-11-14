package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.TimeZone

/** Exact, auditable cash costs. Job/task names are snapshots so deletion never erases accounting history. */
internal object ActualCostStore {
    const val MATERIALS = "MATERIALS"
    const val SUBCONTRACT_LABOR = "SUBCONTRACT_LABOR"
    const val OTHER_DIRECT = "OTHER_DIRECT"
    const val OVERHEAD = "OVERHEAD"
    val categories = setOf(MATERIALS, SUBCONTRACT_LABOR, OTHER_DIRECT, OVERHEAD)

    private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val zones = TimeZone.getAvailableIDs().toSet()

    data class Draft(
        val id: String,
        val jobId: String?,
        val taskId: String?,
        val amountMinor: String,
        val currency: String,
        val category: String,
        val occurred: Long,
        val timezone: String,
        val vendor: String,
        val reference: String,
        val note: String,
    )

    data class Entry(
        val id: String,
        val jobId: String?,
        val jobName: String,
        val taskId: String?,
        val taskName: String,
        val amountMinor: BigInteger,
        val currency: String,
        val category: String,
        val occurred: Long,
        val timezone: String,
        val offsetSeconds: Int,
        val vendor: String,
        val reference: String,
        val note: String,
        val created: Long,
        val updated: Long,
    )

    data class Cursor(val occurred: Long, val id: String)
    data class Page(val entries: List<Entry>, val next: Cursor?)

    private data class Stored(val entry: Entry, val deleted: Long?)

    private fun amountColumn() = "amount_minor TEXT NOT NULL CHECK(typeof(amount_minor)='text' AND length(amount_minor) BETWEEN 1 AND ${MoneyMath.maxDigits} AND (amount_minor='0' OR (substr(amount_minor,1,1) BETWEEN '1' AND '9' AND amount_minor NOT GLOB '*[^0-9]*')))"

    fun install(db: SQLiteDatabase) {
        val amount = amountColumn()
        db.execSQL("CREATE TABLE actual_costs(id TEXT PRIMARY KEY NOT NULL,job_id TEXT,job_name TEXT NOT NULL,task_id TEXT,task_name TEXT NOT NULL,$amount,currency TEXT NOT NULL,category TEXT NOT NULL CHECK(category IN('MATERIALS','SUBCONTRACT_LABOR','OTHER_DIRECT','OVERHEAD')),occurred_ms INTEGER NOT NULL CHECK(occurred_ms>=0),timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL CHECK(offset_seconds BETWEEN -64800 AND 64800),vendor TEXT NOT NULL,reference TEXT NOT NULL,note TEXT NOT NULL,created_ms INTEGER NOT NULL CHECK(created_ms>=0),updated_ms INTEGER NOT NULL CHECK(updated_ms>=created_ms),deleted_ms INTEGER CHECK(deleted_ms IS NULL OR deleted_ms>=created_ms),CHECK(task_id IS NULL OR job_id IS NOT NULL))")
        db.execSQL("CREATE INDEX actual_costs_date ON actual_costs(occurred_ms,id)")
        db.execSQL("CREATE INDEX actual_costs_job_date ON actual_costs(job_id,occurred_ms,id)")
    }

    fun list(db: SQLiteDatabase, before: Cursor? = null, limit: Int = 100): Page {
        require(limit in 1..200)
        require(before == null || (before.occurred >= 0 && idPattern.matches(before.id)))
        val rows = if (before == null) {
            db.rawQuery(
                "SELECT id,job_id,job_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms FROM actual_costs WHERE deleted_ms IS NULL ORDER BY occurred_ms DESC,id DESC LIMIT ?",
                arrayOf((limit + 1).toString()),
            )
        } else {
            db.rawQuery(
                "SELECT id,job_id,job_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms FROM actual_costs WHERE deleted_ms IS NULL AND (occurred_ms<? OR (occurred_ms=? AND id<?)) ORDER BY occurred_ms DESC,id DESC LIMIT ?",
                arrayOf(before.occurred.toString(), before.occurred.toString(), before.id, (limit + 1).toString()),
            )
        }
        val found = rows.use { cursor -> buildList { while (cursor.moveToNext()) add(entry(cursor)) } }
        val hasMore = found.size > limit
        val entries = found.take(limit)
        val last = entries.lastOrNull()
        return Page(entries, if (hasMore && last != null) Cursor(last.occurred, last.id) else null)
    }

    private fun stored(db: SQLiteDatabase, id: String): Stored? = db.rawQuery(
        "SELECT id,job_id,job_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms,deleted_ms FROM actual_costs WHERE id=?",
        arrayOf(id),
    ).use { rows -> if (rows.moveToFirst()) Stored(entry(rows), if (rows.isNull(16)) null else rows.getLong(16)) else null }

    private fun entry(rows: android.database.Cursor) = Entry(
        rows.getString(0), if (rows.isNull(1)) null else rows.getString(1), rows.getString(2),
        if (rows.isNull(3)) null else rows.getString(3), rows.getString(4), MoneyMath.parse(rows.getString(5)),
        rows.getString(6), rows.getString(7), rows.getLong(8), rows.getString(9), rows.getInt(10),
        rows.getString(11), rows.getString(12), rows.getString(13), rows.getLong(14), rows.getLong(15),
    )

    private fun bounded(value: String, limit: Int) = value.length <= limit && '\u0000' !in value

    fun save(db: SQLiteDatabase, value: Draft, at: Long): String? {
        if (!idPattern.matches(value.id) || value.occurred !in 0..at || value.timezone !in zones ||
            !BusinessStore.validCurrency(value.currency) || value.category !in categories ||
            !bounded(value.vendor, 240) || !bounded(value.reference, 240) || !bounded(value.note, 4000) ||
            (value.jobId != null && !idPattern.matches(value.jobId)) ||
            (value.taskId != null && (!idPattern.matches(value.taskId) || value.jobId == null))) return "INVALID_ACTUAL_COST"
        val amount = try { MoneyMath.parse(value.amountMinor) } catch (_: IllegalArgumentException) { return "INVALID_ACTUAL_COST_AMOUNT" }
        if (amount.signum() <= 0) return "INVALID_ACTUAL_COST_AMOUNT"

        val previous = stored(db, value.id)
        if (previous?.deleted != null) return "ACTUAL_COST_UNAVAILABLE"
        val jobName = if (value.jobId == null) "" else {
            db.rawQuery("SELECT name FROM jobs WHERE id=?", arrayOf(value.jobId)).use {
                if (it.moveToFirst()) it.getString(0)
                else previous?.entry?.takeIf { old -> old.jobId == value.jobId }?.jobName ?: return "COST_JOB_UNAVAILABLE"
            }
        }
        val taskName = if (value.taskId == null) "" else {
            db.rawQuery("SELECT title FROM kanban_cards WHERE id=? AND job_id=?", arrayOf(value.taskId, value.jobId)).use {
                if (it.moveToFirst()) it.getString(0)
                else previous?.entry?.takeIf { old -> old.taskId == value.taskId && old.jobId == value.jobId }?.taskName ?: return "COST_TASK_UNAVAILABLE"
            }
        }
        val offset = TimeZone.getTimeZone(value.timezone).getOffset(value.occurred) / 1000
        val created = previous?.entry?.created ?: at
        val changed = maxOf(at, previous?.entry?.updated ?: created, created)
        val values = mapOf(
            "id" to value.id, "job_id" to value.jobId, "job_name" to jobName,
            "task_id" to value.taskId, "task_name" to taskName, "amount_minor" to amount.toString(),
            "currency" to value.currency, "category" to value.category, "occurred_ms" to value.occurred,
            "timezone" to value.timezone, "offset_seconds" to offset, "vendor" to value.vendor.trim(),
            "reference" to value.reference.trim(), "note" to value.note.trim(), "created_ms" to created,
            "updated_ms" to changed, "deleted_ms" to null,
        )
        db.execSQL(
            "INSERT OR REPLACE INTO actual_costs(id,job_id,job_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms,deleted_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
            arrayOf<Any?>(value.id, value.jobId, jobName, value.taskId, taskName, amount.toString(), value.currency,
                value.category, value.occurred, value.timezone, offset, value.vendor.trim(), value.reference.trim(),
                value.note.trim(), created, changed),
        )
        RevisionStore.record(db, "actual_cost", value.id, value.jobId, values, changed)
        return null
    }

    fun remove(db: SQLiteDatabase, id: String, at: Long): String? {
        if (!idPattern.matches(id)) return "INVALID_ACTUAL_COST"
        val previous = stored(db, id) ?: return "ACTUAL_COST_UNAVAILABLE"
        if (previous.deleted != null) return "ACTUAL_COST_UNAVAILABLE"
        val deleted = maxOf(at, previous.entry.updated, previous.entry.created)
        db.execSQL("UPDATE actual_costs SET updated_ms=?,deleted_ms=? WHERE id=? AND deleted_ms IS NULL", arrayOf<Any>(deleted, deleted, id))
        val values = mapOf(
            "id" to id, "job_id" to previous.entry.jobId, "job_name" to previous.entry.jobName,
            "task_id" to previous.entry.taskId, "task_name" to previous.entry.taskName,
            "amount_minor" to previous.entry.amountMinor.toString(), "currency" to previous.entry.currency,
            "category" to previous.entry.category, "occurred_ms" to previous.entry.occurred,
            "timezone" to previous.entry.timezone, "offset_seconds" to previous.entry.offsetSeconds,
            "vendor" to previous.entry.vendor, "reference" to previous.entry.reference, "note" to previous.entry.note,
            "created_ms" to previous.entry.created, "updated_ms" to deleted, "deleted_ms" to deleted,
        )
        RevisionStore.record(db, "actual_cost", id, previous.entry.jobId, values, deleted, deleted = true)
        return null
    }
}
