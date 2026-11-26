package io.github.centerionware.clockrove.tracking

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/**
 * Older migrations and job purges could leave a row's revision head tombstoned,
 * redacted, or frozen before later columns existed. Export validates only graph
 * structure, so the damage surfaced as INVALID_REVISION_GRAPH when a fresh
 * archive was restored. Rebuild every revision-backed row's head from the row
 * itself before the next export; unchanged rows are skipped.
 */
internal object RevisionDataRepair {

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
            val payloadColumns = BackupImportStore.revisionDataColumns[kind] ?: return@forEach
            val queryColumns = when (kind) {
                "settings_money", "job_money", "card_money", "template_money" ->
                    listOf("id", "minor_text")
                "task_quantity" -> listOf("card_id", "quantity_units")
                "template_quantity" -> listOf("template_id", "quantity_units")
                "quote_draft" -> listOf("quote_id", "payload")
                else -> payloadColumns
            }
            db.query(table, queryColumns.toTypedArray(), null, null, null, null, null).use { rows ->
                while (rows.moveToNext()) {
                    val row = queryColumns.mapIndexed { index, name ->
                        name to cursorValue(rows, index)
                    }.toMap()
                    val entityId = entityIdFor(table, row) ?: continue
                    val head = head(db, kind, entityId)
                    val expectedDeleted = row["deleted_ms"] != null
                    val expected = payloadColumns.toSet()
                    if (head != null && head.deleted == expectedDeleted && head.payload != null &&
                        payloadCovers(head.payload, expected, optionalPayloadKeys(kind))) {
                        continue
                    }
                    // Money and quantity revisions derive columns such as job_id from
                    // their parent row, so rebuild through the same projection used by
                    // the import validator.
                    val values = payloadValuesForDataRow(db, null, kind, row)
                        ?: continue
                    val jobId = head?.jobId
                        ?: previousJobId(db, kind, entityId)
                        ?: if (kind == "job") entityId else values["job_id"]?.toString()
                    pending.add(
                        Pending(
                            kind, entityId, jobId,
                            values + optionalPayloadValues(head?.payload, kind),
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

    /** A lost head can still inherit the job scope from its remaining revisions. */
    private fun previousJobId(db: SQLiteDatabase, kind: String, entityId: String): String? = db.rawQuery(
        "SELECT job_id FROM entity_revisions WHERE kind=? AND entity_id=? AND job_id IS NOT NULL " +
            "ORDER BY changed_ms DESC LIMIT 1",
        arrayOf(kind, entityId),
    ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }

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
