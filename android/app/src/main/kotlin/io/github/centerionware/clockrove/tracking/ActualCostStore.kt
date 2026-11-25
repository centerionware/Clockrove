package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.TimeZone
import org.json.JSONObject

/** Exact, auditable cash costs. Job/task names are snapshots so deletion never erases accounting history. */
internal object ActualCostStore {
    /** Columns frozen into every revision payload; keep aligned with the backup validator. */
    internal val revisionColumns = listOf(
        "id", "job_id", "job_name", "customer_id", "customer_name", "task_id", "task_name",
        "amount_minor", "currency", "category", "occurred_ms", "timezone", "offset_seconds",
        "vendor", "reference", "note", "created_ms", "updated_ms", "deleted_ms",
    )
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
        val customerId: String?,
        val customerName: String,
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
        db.execSQL("CREATE TABLE actual_costs(id TEXT PRIMARY KEY NOT NULL,job_id TEXT,job_name TEXT NOT NULL,customer_id TEXT,customer_name TEXT NOT NULL DEFAULT '',task_id TEXT,task_name TEXT NOT NULL,$amount,currency TEXT NOT NULL,category TEXT NOT NULL CHECK(category IN('MATERIALS','SUBCONTRACT_LABOR','OTHER_DIRECT','OVERHEAD')),occurred_ms INTEGER NOT NULL CHECK(occurred_ms>=0),timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL CHECK(offset_seconds BETWEEN -64800 AND 64800),vendor TEXT NOT NULL,reference TEXT NOT NULL,note TEXT NOT NULL,created_ms INTEGER NOT NULL CHECK(created_ms>=0),updated_ms INTEGER NOT NULL CHECK(updated_ms>=created_ms),deleted_ms INTEGER CHECK(deleted_ms IS NULL OR deleted_ms>=created_ms),CHECK(task_id IS NULL OR job_id IS NOT NULL))")
        db.execSQL("CREATE INDEX actual_costs_date ON actual_costs(occurred_ms,id)")
        db.execSQL("CREATE INDEX actual_costs_job_date ON actual_costs(job_id,occurred_ms,id)")
        db.execSQL("CREATE INDEX actual_costs_customer_date ON actual_costs(customer_id,occurred_ms,id)")
    }

    /** Existing costs deliberately stay customer-unattributed; historical ownership is unknown. */
    fun migrateCustomerSnapshot(db: SQLiteDatabase, at: Long) {
        db.execSQL("ALTER TABLE actual_costs ADD COLUMN customer_id TEXT")
        db.execSQL("ALTER TABLE actual_costs ADD COLUMN customer_name TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX actual_costs_customer_date ON actual_costs(customer_id,occurred_ms,id)")
        // Schema-14 heads predate these columns. Snapshot them now so an exported
        // archive's cost rows still match their head payload on restore.
        snapshotRetained(db, at, "MIGRATION")
    }

    /**
     * Retained costs must keep a head that matches the row: live while the row is
     * live, a tombstone once it is removed, and a payload with every current column.
     * Older builds redacted or tombstoned cost revisions when their job was purged,
     * and schema-14 heads lack the customer columns. Repair those heads from the
     * row so a fresh export can be restored. [RevisionStore.record] is idempotent,
     * so this is safe to run whenever the database opens.
     */
    fun repairRetainedHeads(db: SQLiteDatabase, at: Long) {
        val expected = revisionColumns.toSet()
        val needsRepair = db.rawQuery(
            "SELECT a.id,r.payload,r.deleted,a.deleted_ms FROM actual_costs a " +
                "LEFT JOIN entity_heads h ON h.kind='actual_cost' AND h.entity_id=a.id " +
                "LEFT JOIN entity_revisions r ON r.id=h.revision_id",
            null,
        ).use { rows ->
            var pending = false
            while (rows.moveToNext()) {
                val payload = if (rows.isNull(1)) null else rows.getString(1)
                val expectedDeleted = !rows.isNull(3)
                val headMissing = rows.isNull(2)
                val headDeleted = !headMissing && rows.getLong(2) != 0L
                if (headMissing || headDeleted != expectedDeleted || payload == null ||
                    !payloadHasColumns(payload, expected)) {
                    pending = true
                }
            }
            pending
        }
        if (!needsRepair) return
        db.beginTransaction()
        try {
            snapshotRetained(db, at, "REPAIR")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun snapshotRetained(db: SQLiteDatabase, at: Long, origin: String) {
        db.query("actual_costs", revisionColumns.toTypedArray(), null, null, null, null, "id").use { rows ->
            while (rows.moveToNext()) {
                val values = revisionColumns.mapIndexed { index, name ->
                    name to when (rows.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> rows.getLong(index)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> rows.getDouble(index)
                        else -> rows.getString(index)
                    }
                }.toMap()
                val id = requireNotNull(values["id"]).toString()
                RevisionStore.record(
                    db, "actual_cost", id, values["job_id"] as String?, values, at,
                    deleted = values["deleted_ms"] != null, origin = origin,
                )
            }
        }
    }

    private fun payloadHasColumns(payload: String, expected: Set<String>): Boolean {
        val keys = try {
            val iterator = JSONObject(payload).keys()
            buildSet { while (iterator.hasNext()) add(iterator.next()) }
        } catch (_: Exception) {
            return false
        }
        return keys == expected
    }

    fun list(db: SQLiteDatabase, before: Cursor? = null, limit: Int = 100): Page {
        require(limit in 1..200)
        require(before == null || (before.occurred >= 0 && idPattern.matches(before.id)))
        val rows = if (before == null) {
            db.rawQuery(
                "SELECT id,job_id,job_name,customer_id,customer_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms FROM actual_costs WHERE deleted_ms IS NULL ORDER BY occurred_ms DESC,id DESC LIMIT ?",
                arrayOf((limit + 1).toString()),
            )
        } else {
            db.rawQuery(
                "SELECT id,job_id,job_name,customer_id,customer_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms FROM actual_costs WHERE deleted_ms IS NULL AND (occurred_ms<? OR (occurred_ms=? AND id<?)) ORDER BY occurred_ms DESC,id DESC LIMIT ?",
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
        "SELECT id,job_id,job_name,customer_id,customer_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms,deleted_ms FROM actual_costs WHERE id=?",
        arrayOf(id),
    ).use { rows -> if (rows.moveToFirst()) Stored(entry(rows), if (rows.isNull(18)) null else rows.getLong(18)) else null }

    private fun entry(rows: android.database.Cursor) = Entry(
        rows.getString(0), if (rows.isNull(1)) null else rows.getString(1), rows.getString(2),
        if (rows.isNull(3)) null else rows.getString(3), rows.getString(4),
        if (rows.isNull(5)) null else rows.getString(5), rows.getString(6), MoneyMath.parse(rows.getString(7)),
        rows.getString(8), rows.getString(9), rows.getLong(10), rows.getString(11), rows.getInt(12),
        rows.getString(13), rows.getString(14), rows.getString(15), rows.getLong(16), rows.getLong(17),
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
        val job = if (value.jobId == null) null else {
            db.rawQuery("SELECT j.name,j.customer_id,c.name FROM jobs j LEFT JOIN customers c ON c.id=j.customer_id WHERE j.id=?", arrayOf(value.jobId)).use {
                if (it.moveToFirst()) Triple(it.getString(0), if (it.isNull(1)) null else it.getString(1), if (it.isNull(2)) "" else it.getString(2))
                else previous?.entry?.takeIf { old -> old.jobId == value.jobId }?.let { old -> Triple(old.jobName, old.customerId, old.customerName) }
                    ?: return "COST_JOB_UNAVAILABLE"
            }
        }
        // Editing a cost must not rewrite the customer attribution captured when
        // it was first recorded, even if the job's customer later changes.
        val old = previous?.entry?.takeIf { it.jobId == value.jobId }
        val jobName = job?.first.orEmpty()
        val customerId = if (old != null) old.customerId else job?.second
        val customerName = if (old != null) old.customerName else job?.third.orEmpty()
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
            "customer_id" to customerId, "customer_name" to customerName,
            "task_id" to value.taskId, "task_name" to taskName, "amount_minor" to amount.toString(),
            "currency" to value.currency, "category" to value.category, "occurred_ms" to value.occurred,
            "timezone" to value.timezone, "offset_seconds" to offset, "vendor" to value.vendor.trim(),
            "reference" to value.reference.trim(), "note" to value.note.trim(), "created_ms" to created,
            "updated_ms" to changed, "deleted_ms" to null,
        )
        db.execSQL(
            "INSERT OR REPLACE INTO actual_costs(id,job_id,job_name,customer_id,customer_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms,deleted_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
            arrayOf<Any?>(value.id, value.jobId, jobName, customerId, customerName, value.taskId, taskName, amount.toString(), value.currency,
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
            "customer_id" to previous.entry.customerId, "customer_name" to previous.entry.customerName,
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
