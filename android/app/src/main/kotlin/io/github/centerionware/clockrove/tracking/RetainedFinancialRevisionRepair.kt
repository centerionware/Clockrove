package io.github.centerionware.clockrove.tracking

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/**
 * Older job purges tombstoned or redacted revisions for records that are still
 * retained: actual costs, invoice coverage and claims, payment destinations, and
 * quote links. Export validates only graph structure, so that damage surfaced as
 * INVALID_REVISION_GRAPH when the owner restored a fresh archive. Rebuild each
 * retained row's head from the row itself before the next export.
 */
internal object RetainedFinancialRevisionRepair {
    private val repairableKinds = setOf(
        "actual_cost", "invoice_coverage", "invoice_task_claim", "invoice_time_claim",
        "payment_destination", "quote_draft", "quote_decision", "quote_invoice_link",
    )

    private data class Head(val jobId: String?, val payload: String?, val deleted: Boolean)

    private data class Pending(
        val kind: String,
        val entityId: String,
        val jobId: String?,
        val values: Map<String, Any?>,
        val deleted: Boolean,
    )

    fun repair(db: SQLiteDatabase, at: Long) {
        val pending = mutableListOf<Pending>()
        BackupImportStore.revisionDataTables.forEach { (kind, table) ->
            if (kind !in JobLifecycleStore.financialRevisionKinds || kind !in repairableKinds) return@forEach
            val payloadColumns = BackupImportStore.revisionDataColumns[kind] ?: return@forEach
            val queryColumns = if (kind == "quote_draft") listOf("quote_id", "payload") else payloadColumns
            db.query(table, queryColumns.toTypedArray(), null, null, null, null, null).use { rows ->
                while (rows.moveToNext()) {
                    val row = queryColumns.mapIndexed { index, name ->
                        name to cursorValue(rows, index)
                    }.toMap()
                    // Draft revisions identify the quote as id while the table stores quote_id.
                    val values = if (kind == "quote_draft") {
                        mapOf("id" to row["quote_id"], "payload" to row["payload"])
                    } else {
                        row
                    }
                    val entityId = if (kind == "quote_draft") {
                        values["id"]?.toString()
                    } else {
                        entityIdFor(table, values)
                    } ?: continue
                    val head = head(db, kind, entityId) ?: continue
                    val expectedDeleted = values["deleted_ms"] != null
                    val expected = payloadColumns.toSet()
                    if (head.deleted == expectedDeleted && head.payload != null &&
                        payloadCovers(head.payload, expected, optionalPayloadKeys(kind))) {
                        continue
                    }
                    pending.add(
                        Pending(
                            kind, entityId, head.jobId,
                            values + optionalPayloadValues(head.payload, kind),
                            expectedDeleted,
                        ),
                    )
                }
            }
        }
        if (pending.isEmpty()) return
        db.beginTransaction()
        try {
            pending.forEach { item ->
                RevisionStore.record(
                    db, item.kind, item.entityId, item.jobId, item.values, at,
                    deleted = item.deleted, origin = "REPAIR",
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun entityIdFor(table: String, values: Map<String, Any?>): String? {
        val kind = BackupImportStore.revisionDataKinds[table] ?: return null
        val keyValues = (BackupImportStore.revisionDataKeyColumns[kind] ?: listOf("id")).map { column ->
            values[column]?.toString() ?: return null
        }
        return when (kind) {
            "quote_decision" -> "${keyValues[0]}:${keyValues[1]}"
            else -> keyValues.singleOrNull()
        }
    }

    private fun optionalPayloadKeys(kind: String): Set<String> = when (kind) {
        "invoice_coverage", "payment_destination" -> setOf("predecessor_id")
        else -> emptySet()
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        else -> cursor.getString(index)
    }

    private fun head(db: SQLiteDatabase, kind: String, entityId: String): Head? = db.rawQuery(
        "SELECT r.job_id,r.payload,r.deleted FROM entity_heads h " +
            "JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind=? AND h.entity_id=?",
        arrayOf(kind, entityId),
    ).use { rows ->
        if (!rows.moveToFirst()) {
            null
        } else {
            Head(
                if (rows.isNull(0)) null else rows.getString(0),
                if (rows.isNull(1)) null else rows.getString(1),
                rows.getLong(2) != 0L,
            )
        }
    }

    private fun payloadCovers(payload: String, expected: Set<String>, optional: Set<String>): Boolean {
        val keys = try {
            val iterator = JSONObject(payload).keys()
            mutableSetOf<String>().apply { while (iterator.hasNext()) add(iterator.next()) }
        } catch (_: Exception) {
            return false
        }
        return keys.containsAll(expected) && (keys - expected).all { it in optional }
    }

    /** Keep fields the row has no column for, such as invoice replacement provenance. */
    private fun optionalPayloadValues(payload: String?, kind: String): Map<String, Any?> {
        val optional = optionalPayloadKeys(kind)
        if (payload == null || optional.isEmpty()) return emptyMap()
        return try {
            val json = JSONObject(payload)
            optional.mapNotNull { key ->
                when {
                    !json.has(key) -> null
                    json.isNull(key) -> key to null
                    else -> key to json.get(key).toString()
                }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
