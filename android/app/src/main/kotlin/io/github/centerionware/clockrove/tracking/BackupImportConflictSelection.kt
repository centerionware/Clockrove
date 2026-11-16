package io.github.centerionware.clockrove.tracking

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Base64
import io.flutter.plugin.common.StandardMethodCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** Bounded native staging and read-only preview for authenticated backup imports. */
internal typealias ConflictSelectionFailure = BackupImportStore.Failure
internal typealias ConflictSelectionStarted = BackupImportStore.Started
internal typealias ConflictSelectionPreview = BackupImportStore.Preview
internal typealias ConflictSelectionConflictPage = BackupImportStore.ConflictPage
internal typealias ConflictSelectionApplied = BackupImportStore.Applied
internal typealias ConflictSelectionExpected = BackupImportStore.Expected
internal typealias ConflictSelectionHandle = BackupImportStore.Handle
internal typealias ConflictSelectionNormalizedRow = BackupImportStore.NormalizedRow
internal typealias ConflictSelectionApplyRow = BackupImportStore.ApplyRow
internal typealias ConflictSelectionForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias ConflictSelectionHeadAdvance = BackupImportStore.HeadAdvance
internal typealias ConflictSelectionDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias ConflictSelectionPlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias ConflictSelectionPlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias ConflictSelectionOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias ConflictSelectionRevisionContent = BackupImportStore.RevisionContent
internal typealias ConflictSelectionRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias ConflictSelectionValidationColumn = BackupImportStore.ValidationColumn
internal typealias ConflictSelectionBlobField = BackupImportStore.BlobField
internal typealias ConflictSelectionLocalRow = BackupImportStore.LocalRow
private const val MAX_REQUEST_BYTES = BackupImportStore.MAX_REQUEST_BYTES
private const val MAX_PAGE_BYTES = BackupImportStore.MAX_PAGE_BYTES
private const val MAX_BLOB_CHUNK_BYTES = BackupImportStore.MAX_BLOB_CHUNK_BYTES
private const val MAX_ROW_COUNT_PER_PAGE = BackupImportStore.MAX_ROW_COUNT_PER_PAGE
private const val MAX_CONFLICT_PAGE_BYTES = BackupImportStore.MAX_CONFLICT_PAGE_BYTES
private const val MIN_CONFLICT_PAGE_BYTES = BackupImportStore.MIN_CONFLICT_PAGE_BYTES
private const val STORAGE_CHUNK_BYTES = BackupImportStore.STORAGE_CHUNK_BYTES
private const val MAX_STAGE_DATABASE_BYTES = BackupImportStore.MAX_STAGE_DATABASE_BYTES
private const val MAX_STAGE_PHYSICAL_BYTES = BackupImportStore.MAX_STAGE_PHYSICAL_BYTES
private const val REVISION_VALIDATION_BATCH_SIZE = BackupImportStore.REVISION_VALIDATION_BATCH_SIZE
private const val FILE_PREFIX = BackupImportStore.FILE_PREFIX
private val idPattern = BackupImportStore.idPattern
private val blobIdPattern = BackupImportStore.blobIdPattern
private val digestPattern = BackupImportStore.digestPattern
private val immutableTables = BackupImportStore.immutableTables
private val mergeOnlyTables = BackupImportStore.mergeOnlyTables
private val summaryColumns = BackupImportStore.summaryColumns
private val uniqueColumns = BackupImportStore.uniqueColumns
private val revisionDataTables = BackupImportStore.revisionDataTables
private val revisionDataColumns = BackupImportStore.revisionDataColumns
private val revisionDataKeyColumns = BackupImportStore.revisionDataKeyColumns
private val integerRevisionDataKinds = BackupImportStore.integerRevisionDataKinds
private val revisionDataKinds = BackupImportStore.revisionDataKinds
private val supportedRevisionKinds = BackupImportStore.supportedRevisionKinds
private val revisionTombstoneRows = BackupImportStore.revisionTombstoneRows
private val divergentHeadMergeChoices = BackupImportStore.divergentHeadMergeChoices
private val customerDeletionChoices = BackupImportStore.customerDeletionChoices


internal fun BackupImportStore.resolveAggregateConflicts(
        handle: ConflictSelectionHandle,
        selectedConflictId: String,
        selectedConflict: JSONObject,
        selectedChoice: String,
    ) {
        val selectedDeletionGraph = selectedConflict.isCommentDeletionConflict()
        val intent = if (selectedDeletionGraph &&
            selectedConflict.optString("kind") == "BACKUP_COMMENT_DELETION" && selectedChoice == "KEEP_LOCAL") {
            "LOCAL_CONTENT_PRESERVE"
        } else {
            conflictChoiceIntent(selectedChoice)
        } ?: return
        if (intent == "NEUTRAL") return
        val aggregateKeys = selectedConflict.optJSONArray("aggregateKeys")?.let { json ->
            buildSet { for (index in 0 until json.length()) add(json.optString(index)) }.toMutableSet()
        }.orEmpty().toMutableSet()
        if (aggregateKeys.isEmpty()) return

        data class StagedConflict(val id: String, val json: JSONObject)
        val scopedDeletionIntent = intent in setOf(
            "BACKUP_DELETION", "BACKUP_RESTORE", "LOCAL_CONTENT_PRESERVE", "LOCAL_DELETION_PRESERVE",
        )
        if (!selectedDeletionGraph && !scopedDeletionIntent) {
            aggregateKeys.addAll(expandAggregateKeys(handle, aggregateKeys))
        } else {
            handle.database.execSQL("DROP TABLE IF EXISTS aggregate_conflict_keys")
            handle.database.execSQL("CREATE TABLE aggregate_conflict_keys(group_key TEXT PRIMARY KEY)")
            aggregateKeys.forEach { key ->
                handle.database.insertOrThrow(
                    "aggregate_conflict_keys", null, ContentValues().apply { put("group_key", key) },
                )
            }
        }
        val aggregateMembers = handle.database.rawQuery(
            "SELECT DISTINCT c.conflict_id,c.conflict_json FROM import_conflicts c " +
                "JOIN import_aggregate_keys g ON g.row_ordinal=c.row_ordinal " +
                "JOIN aggregate_conflict_keys k ON k.group_key=g.group_key ORDER BY c.ordinal",
            null,
        ).use { rows -> buildList {
            while (rows.moveToNext()) add(StagedConflict(rows.getString(0), JSONObject(rows.getString(1))))
        } }
        val selectedIdentity = selectedConflict.deletionEntityIdentity()
        val members = aggregateMembers.filter { staged ->
            when {
                selectedDeletionGraph -> staged.id == selectedConflictId ||
                    staged.json.isCommentDeletionConflict() || staged.json.isCommentDeletionDependencyConflict()
                scopedDeletionIntent -> staged.id == selectedConflictId ||
                    (selectedIdentity != null && staged.json.deletionEntityIdentity() == selectedIdentity)
                else -> staged.id == selectedConflictId || !staged.json.isCommentDeletionConflict()
            }
        }
        val choices = members.mapNotNull { staged ->
            val allowed = staged.json.optJSONArray("allowedChoices") ?: return@mapNotNull null
            val candidate = if (staged.id == selectedConflictId) selectedChoice else
                choiceForConflictIntent(allowed, intent)
            val compatibleIntent = candidate?.let(::conflictChoiceIntent)
            // Keeping a live local row and preserving a local tombstone are the same
            // local-wins decision for records that share an aggregate conflict group.
            val localPreserve = setOf("LOCAL_ONLY", "LOCAL_CONTENT_PRESERVE", "LOCAL_DELETION_PRESERVE")
            val compatible = compatibleIntent == "NEUTRAL" || compatibleIntent == intent ||
                (intent == "LOCAL_MERGE" && compatibleIntent == "LOCAL_ONLY") ||
                (intent in localPreserve && compatibleIntent in localPreserve)
            if (candidate == null || !compatible) {
                throw ConflictSelectionFailure(
                    "AGGREGATE_CONFLICT_UNSUPPORTED",
                    "Related backup records do not support one consistent conflict choice: " +
                        "${staged.json.optString("kind")}/${staged.json.optString("table")} " +
                        "allowed=$allowed intent=$intent candidate=$candidate compatible=$compatibleIntent",
                )
            }
            staged.id to (staged.json to candidate)
        }.toMap()
        members.forEach { staged ->
            val (json, memberChoice) = choices[staged.id]
                ?: throw ConflictSelectionFailure("STORAGE_UNAVAILABLE", "A staged backup conflict is invalid.")
            json.put("aggregateSelectionIntent", intent)
            writeConflictChoice(handle, staged.id, json, memberChoice)
        }
        handle.database.execSQL("DROP TABLE IF EXISTS aggregate_conflict_keys")
    }

private fun JSONObject.isCommentDeletionConflict(): Boolean {
    val table = optString("table")
    return when (optString("kind")) {
        "BACKUP_COMMENT_DELETION" -> table == "task_comment_events"
        "BACKUP_ENTITY_DELETION" -> table == "entity_heads" &&
            optJSONArray("primaryKey")?.optString(0).orEmpty() in
                BackupImportDeletionResolution.deletableKinds
        "ENTITY_DELETION_TOMBSTONE" -> table in BackupImportDeletionResolution.restorableTables ||
            (table == "entity_heads" &&
                optJSONArray("primaryKey")?.optString(0).orEmpty() in
                    BackupImportDeletionResolution.deletableKinds)
        else -> false
    }
}

private fun JSONObject.isCommentDeletionDependencyConflict(): Boolean =
    optString("kind") == "DEPENDENT_ON_CONFLICT" && optString("table") == "task_attachments" &&
        (optJSONArray("allowedChoices")?.let { choices ->
            (0 until choices.length()).any {
                choices.optString(it) in setOf("PRESERVE_LOCAL_DELETION", "RESTORE_BACKUP_CONTENT")
            }
        } ?: false)

private fun JSONObject.deletionEntityIdentity(): Pair<String, String>? {
    val table = optString("table")
    val primaryKey = optJSONArray("primaryKey") ?: return null
    if (table == "entity_heads" && primaryKey.length() == 2) {
        return primaryKey.optString(0).takeIf(String::isNotEmpty)?.let { kind ->
            primaryKey.optString(1).takeIf(String::isNotEmpty)?.let { entityId -> kind to entityId }
        }
    }
    val kind = revisionDataKinds[table]
        ?: BackupImportStore.revisionTombstoneRows[table]?.kind
        ?: return null
    val entityId = primaryKey.optString(0).takeIf(String::isNotEmpty) ?: return null
    return kind to entityId
}

internal fun BackupImportStore.writeConflictChoice(handle: ConflictSelectionHandle, id: String, json: JSONObject, choice: String) {
        json.put("resolved", true)
        json.put("selectedChoice", choice)
        val updated = handle.database.update(
            "import_conflicts",
            ContentValues().apply {
                put("selected_choice", choice)
                put("conflict_json", json.toString())
            },
            "conflict_id=?",
            arrayOf(id),
        )
        if (updated != 1) throw ConflictSelectionFailure("CONFLICT_UNAVAILABLE", "The backup conflict is unavailable.")
    }

internal fun BackupImportStore.conflictChoiceIntent(choice: String): String? = when (choice) {
        "MERGE_KEEP_LOCAL_CONTENT" -> "LOCAL_MERGE"
        "MERGE_USE_BACKUP_CONTENT" -> "BACKUP_MERGE"
        "MERGE_IDENTICAL_CONTENT" -> "NEUTRAL"
        "APPLY_BACKUP_DELETION" -> "BACKUP_DELETION"
        "RESTORE_BACKUP_CONTENT" -> "BACKUP_RESTORE"
        "PRESERVE_LOCAL_CONTENT" -> "LOCAL_CONTENT_PRESERVE"
        "PRESERVE_LOCAL_DELETION" -> "LOCAL_DELETION_PRESERVE"
        "KEEP_LOCAL", "PRESERVE_DELETION", "OMIT_BACKUP_RECORD",
        "OMIT_OPEN_SESSION", "OMIT_BACKUP_SESSION" -> "LOCAL_ONLY"
        else -> null
    }

internal fun BackupImportStore.choiceForConflictIntent(allowed: JSONArray, intent: String): String? {
        val preferred = when (intent) {
            "LOCAL_MERGE" -> listOf(
                "MERGE_KEEP_LOCAL_CONTENT", "KEEP_LOCAL", "PRESERVE_LOCAL_DELETION", "PRESERVE_DELETION",
                "OMIT_BACKUP_RECORD", "OMIT_OPEN_SESSION", "OMIT_BACKUP_SESSION",
            )
            "BACKUP_MERGE" -> listOf("MERGE_USE_BACKUP_CONTENT")
            "BACKUP_RESTORE" -> listOf("RESTORE_BACKUP_CONTENT")
            "LOCAL_CONTENT_PRESERVE" -> listOf(
                "PRESERVE_LOCAL_CONTENT", "KEEP_LOCAL", "OMIT_BACKUP_RECORD",
            )
            "LOCAL_DELETION_PRESERVE" -> listOf(
                "PRESERVE_LOCAL_DELETION", "PRESERVE_DELETION", "KEEP_LOCAL", "OMIT_BACKUP_RECORD",
            )
            "NEUTRAL" -> listOf("MERGE_IDENTICAL_CONTENT")
            "BACKUP_DELETION" -> listOf("APPLY_BACKUP_DELETION")
            "LOCAL_ONLY" -> listOf(
                "KEEP_LOCAL", "PRESERVE_LOCAL_CONTENT", "PRESERVE_LOCAL_DELETION", "PRESERVE_DELETION",
                "OMIT_BACKUP_RECORD",
                "OMIT_OPEN_SESSION", "OMIT_BACKUP_SESSION",
            )
            else -> emptyList()
        }
        if ((0 until allowed.length()).any { allowed.optString(it) == "MERGE_IDENTICAL_CONTENT" }) {
            return "MERGE_IDENTICAL_CONTENT"
        }
        val localPreserve = setOf("LOCAL_ONLY", "LOCAL_CONTENT_PRESERVE", "LOCAL_DELETION_PRESERVE")
        return preferred.firstOrNull { candidate ->
            val candidateIntent = conflictChoiceIntent(candidate)
            (candidateIntent == intent ||
                (intent == "LOCAL_MERGE" && candidateIntent == "LOCAL_ONLY") ||
                (intent in localPreserve && candidateIntent in localPreserve)) &&
                (0 until allowed.length()).any { allowed.optString(it) == candidate }
        }
    }

internal fun BackupImportStore.coupledGpsRangeId(handle: ConflictSelectionHandle, conflictId: String): String? =
        handle.database.rawQuery(
            "SELECT r.table_name,r.row_json FROM import_conflicts c JOIN import_rows r " +
                "ON r.ordinal=c.row_ordinal WHERE c.conflict_id=?",
            arrayOf(conflictId),
        ).use { rows ->
            if (!rows.moveToFirst()) return null
            val table = rows.getString(0)
            val values = JSONObject(rows.getString(1)).getJSONObject("values")
            when {
                table == "location_deletion_ranges" -> values.optString("id").takeIf(String::isNotEmpty)
                table == "entity_heads" && values.optString("kind") == "location_deletion_range" ->
                    values.optString("entity_id").takeIf(String::isNotEmpty)
                else -> null
            }
        }

internal fun BackupImportStore.resolveCoupledGpsRangeConflicts(handle: ConflictSelectionHandle, rangeId: String, choice: String) {
        val db = handle.database
        val rowKeys = listOf(
            "location_deletion_ranges" to jsonArray(listOf(rangeId)),
            "entity_heads" to jsonArray(listOf("location_deletion_range", rangeId)),
        )
        val conflictIds = rowKeys.flatMap { (table, primaryKey) ->
            db.rawQuery(
                "SELECT c.conflict_id,c.conflict_json FROM import_conflicts c " +
                    "JOIN import_rows r ON r.ordinal=c.row_ordinal " +
                    "WHERE r.table_name=? AND r.primary_key=?",
                arrayOf(table, primaryKey),
            ).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                    val json = JSONObject(rows.getString(1))
                    if (json.optString("kind") !in setOf(
                            "GPS_DELETION_COVERAGE", "GPS_DELETION_RANGE_HEAD",
                        )) continue
                    val allowed = json.optJSONArray("allowedChoices") ?: continue
                    if ((0 until allowed.length()).none { allowed.optString(it) == choice }) continue
                    add(rows.getString(0))
                    }
                }
            }
        }
        conflictIds.forEach { coupledConflictId ->
            val json = db.rawQuery(
                "SELECT conflict_json FROM import_conflicts WHERE conflict_id=?",
                arrayOf(coupledConflictId),
            ).use { rows -> if (rows.moveToFirst()) JSONObject(rows.getString(0)) else return@forEach }
            json.put("resolved", true)
            json.put("selectedChoice", choice)
            db.update("import_conflicts", ContentValues().apply {
                put("selected_choice", choice)
                put("conflict_json", json.toString())
            }, "conflict_id=?", arrayOf(coupledConflictId))
        }
    }

internal fun BackupImportStore.validateApplyChoices(handle: ConflictSelectionHandle, expectedConflictCount: Long) {
        if (scalarLong(handle.database, "SELECT COUNT(*) FROM import_conflicts") != expectedConflictCount ||
            scalarLong(handle.database,
                "SELECT COUNT(*) FROM import_conflicts c LEFT JOIN import_rows r " +
                    "ON r.ordinal=c.row_ordinal WHERE r.ordinal IS NULL") != 0L) {
            throw ConflictSelectionFailure("STORAGE_UNAVAILABLE", "The staged backup conflicts are invalid.")
        }
        if (scalarLong(handle.database, "SELECT COUNT(*) FROM import_conflicts WHERE selected_choice IS NULL") != 0L) {
            throw ConflictSelectionFailure("UNRESOLVED_CONFLICTS", "Choose an option for every backup conflict before applying.")
        }
        val selectedIntents = mutableMapOf<String, String>()
        handle.database.rawQuery(
            "SELECT selected_choice,conflict_json FROM import_conflicts ORDER BY ordinal",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                val choice = rows.getString(0)
                val conflict = JSONObject(rows.getString(1))
                val allowed = conflict.optJSONArray("allowedChoices")
                if (!isSupportedChoice(conflict.optString("kind"), conflict.optString("table"), choice) ||
                    allowed == null || (0 until allowed.length()).none { allowed.optString(it) == choice } ||
                    !conflict.optBoolean("resolved") || conflict.optString("selectedChoice") != choice) {
                    throw ConflictSelectionFailure("UNSUPPORTED_CHOICE", "A backup conflict contains an unsupported choice.")
                }
                val intent = conflict.optString("aggregateSelectionIntent")
                    .takeIf(String::isNotEmpty) ?: conflictChoiceIntent(choice) ?: continue
                if (intent == "NEUTRAL") continue
                val groupKeys = conflict.optJSONArray("aggregateKeys") ?: continue
                for (index in 0 until groupKeys.length()) {
                    val key = groupKeys.optString(index)
                    val previous = selectedIntents.putIfAbsent(key, intent)
                    if (previous != null && previous != intent) {
                        throw ConflictSelectionFailure(
                            "AGGREGATE_CONFLICT_UNSUPPORTED",
                            "Related backup records do not have one consistent conflict choice.",
                        )
                    }
                }
            }
        }
    }

internal fun BackupImportStore.isSupportedChoice(kind: String, table: String, choice: String): Boolean = when (kind) {
        "ACTIVE_JOB_DELETION" -> table == "job_deletions" && choice == "KEEP_LOCAL"
        "JOB_DELETION_TOMBSTONE" -> table == "jobs" && choice == "PRESERVE_LOCAL_DELETION"
        "ENTITY_DELETION_TOMBSTONE" ->
            (table in revisionTombstoneRows || table == "entity_heads") &&
                (choice == "PRESERVE_LOCAL_DELETION" ||
                    (choice == "RESTORE_BACKUP_CONTENT" &&
                        (table == "entity_heads" || table in BackupImportDeletionResolution.restorableTables)))
        "BACKUP_ENTITY_DELETION" -> table == "entity_heads" &&
            choice in setOf("PRESERVE_LOCAL_CONTENT", "APPLY_BACKUP_DELETION")
        "BACKUP_COMMENT_DELETION" -> table == "task_comment_events" &&
            choice in setOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION")
        "OPEN_SESSION" -> choice == "OMIT_OPEN_SESSION"
        "SESSION_OVERLAP" -> choice == "KEEP_LOCAL" || choice == "OMIT_BACKUP_SESSION"
        // Covered points can be omitted as an entity graph, or a selected backup range can
        // delete local points by adding both their prior head and the range as causal parents.
        "GPS_DELETION_COVERAGE" -> (table == "location_points" && choice == "PRESERVE_DELETION") ||
            (table == "location_deletion_ranges" && choice in setOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"))
        "GPS_DELETION_RANGE_HEAD" -> table == "entity_heads" &&
            choice in setOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION")
        "GPS_TOMBSTONE" -> table == "location_points" && choice == "PRESERVE_LOCAL_DELETION"
        "DEPENDENT_ON_CONFLICT" -> choice == "OMIT_BACKUP_RECORD" ||
            (table == "task_attachments" && choice in setOf(
                "PRESERVE_LOCAL_DELETION", "RESTORE_BACKUP_CONTENT",
            ))
        "UNIQUE_VALUE_IN_USE" -> choice == "KEEP_LOCAL" || choice == "OMIT_BACKUP_RECORD"
        "IMMUTABLE_RECORD_DIFFERS" -> choice == "KEEP_LOCAL" ||
            (choice == "OMIT_BACKUP_RECORD" && table !in mergeOnlyTables)
        "EXISTING_RECORD_DIFFERS" -> choice == "KEEP_LOCAL" ||
            (table == "entity_heads" && choice in divergentHeadMergeChoices) ||
            (choice == "OMIT_BACKUP_RECORD" && table !in mergeOnlyTables)
        else -> false
    }

internal fun BackupImportStore.dependencyOrder(db: SQLiteDatabase, tables: List<String>): List<String> {
        val included = tables.toSet()
        val dependencies = tables.associateWith { table ->
            foreignKeySpecs(db, table, emptyMap()).map { it.parentTable }.filter(included::contains).toSet() +
                when (table) {
                    "invoice_task_claims", "invoice_time_claims", "invoice_copies", "invoice_payments",
                    "invoice_payment_events", "invoice_payment_reversals", "invoice_voids" -> setOf("invoices").filter(included::contains)
                    else -> emptySet()
                }
        }
        val result = mutableListOf<String>()
        val remaining = tables.toMutableSet()
        while (remaining.isNotEmpty()) {
            val ready = tables.filter { it in remaining && dependencies.getValue(it).none(remaining::contains) }
            if (ready.isEmpty()) {
                // Cyclic foreign keys remain deferred until commit; keep their archive order stable.
                result.addAll(tables.filter(remaining::contains))
                break
            }
            result.addAll(ready)
            remaining.removeAll(ready.toSet())
        }
        return result
    }

internal fun BackupImportStore.aggregateKeysForStagedRow(handle: ConflictSelectionHandle, rowOrdinal: Long, table: String): Set<String> {
        val exists = handle.database.rawQuery(
            "SELECT 1 FROM import_rows WHERE ordinal=? AND table_name=? LIMIT 1",
            arrayOf(rowOrdinal.toString(), table),
        ).use { it.moveToFirst() }
        if (!exists) return emptySet()
        return handle.database.rawQuery(
            "SELECT group_key FROM import_aggregate_keys WHERE row_ordinal=? ORDER BY group_key",
            arrayOf(rowOrdinal.toString()),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
    }

internal fun BackupImportStore.attachAggregateGroupKey(handle: ConflictSelectionHandle, rowOrdinal: Long, groupKey: String) {
        handle.database.insertWithOnConflict(
            "import_aggregate_keys",
            null,
            ContentValues().apply {
                put("row_ordinal", rowOrdinal)
                put("group_key", groupKey)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val conflicts = handle.database.rawQuery(
            "SELECT conflict_id,conflict_json FROM import_conflicts WHERE row_ordinal=?",
            arrayOf(rowOrdinal.toString()),
        ).use { rows -> buildList {
            while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1))
        } }
        conflicts.forEach { (conflictId, conflictJson) ->
            val json = JSONObject(conflictJson)
            val keys = json.optJSONArray("aggregateKeys")
            val merged = buildSet {
                if (keys != null) for (index in 0 until keys.length()) add(keys.optString(index))
                add(groupKey)
            }
            json.put("aggregateKeys", JSONArray(merged.sorted()))
            handle.database.update(
                "import_conflicts",
                ContentValues().apply { put("conflict_json", json.toString()) },
                "conflict_id=?",
                arrayOf(conflictId),
            )
        }
    }

internal fun BackupImportStore.buildAggregateKeyIndex(handle: ConflictSelectionHandle) {
        val db = handle.database
        var lastOrdinal = -1L
        while (true) {
            val batch = db.rawQuery(
                "SELECT ordinal,table_name,row_json FROM import_rows WHERE ordinal>? ORDER BY ordinal LIMIT 256",
                arrayOf(lastOrdinal.toString()),
            ).use { rows -> buildList {
                while (rows.moveToNext()) {
                    @Suppress("UNCHECKED_CAST")
                    val values = jsonValue(JSONObject(rows.getString(2)).getJSONObject("values"))
                        as? Map<String, Any?> ?: invalidArchiveRow()
                    add(Triple(rows.getLong(0), rows.getString(1), values))
                }
            } }
            if (batch.isEmpty()) return
            batch.forEach { (ordinal, table, values) ->
                aggregateKeysForRow(handle, table, values).forEach { groupKey ->
                    db.insertOrThrow("import_aggregate_keys", null, ContentValues().apply {
                        put("row_ordinal", ordinal)
                        put("group_key", groupKey)
                    })
                }
                lastOrdinal = ordinal
            }
        }
    }

internal fun BackupImportStore.aggregateKeysForRow(
        handle: ConflictSelectionHandle,
        table: String,
        values: Map<String, Any?>,
    ): Set<String> {
        fun key(prefix: String, value: Any?): String? = value?.toString()?.takeIf(String::isNotEmpty)
            ?.let { "$prefix:$it" }
        fun stagedValue(rowTable: String, keyColumn: String, keyValue: Any?, valueColumn: String): Any? {
            if (keyValue == null) return null
            val rowKey = handle.catalog[rowTable]?.primaryKey?.let { columns ->
                if (columns == listOf(keyColumn)) jsonArray(listOf(keyValue)) else null
            } ?: return null
            return handle.database.rawQuery(
                "SELECT row_json FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
                arrayOf(rowTable, rowKey),
            ).use { rows ->
                if (!rows.moveToFirst()) null else {
                    val row = JSONObject(rows.getString(0)).getJSONObject("values")
                    if (!row.has(valueColumn) || row.isNull(valueColumn)) null else jsonValue(row.get(valueColumn))
                }
            }
        }
        val result = linkedSetOf<String>()
        fun add(vararg value: String?) { value.filterNotNull().forEach(result::add) }
        fun addInvoice(invoiceId: Any?) { add(key("invoice", invoiceId)) }
        fun addQuote(quoteId: Any?) { add(key("quote", quoteId)) }
        fun addJob(jobId: Any?) { add(key("job-config", jobId)) }

        when (table) {
            "entity_heads" -> {
                val revisionId = values["revision_id"]?.toString() ?: return emptySet()
                val revision = handle.database.rawQuery(
                    "SELECT kind,entity_id,job_id,payload FROM validation_revisions WHERE id=? LIMIT 1",
                    arrayOf(revisionId),
                ).use { rows ->
                    if (!rows.moveToFirst()) return emptySet()
                    listOf(
                        rows.getString(0), rows.getString(1),
                        if (rows.isNull(2)) null else rows.getString(2),
                        if (rows.isNull(3)) null else rows.getString(3),
                    )
                }
                @Suppress("UNCHECKED_CAST")
                val payload = (revision[3] as? String)?.let { raw ->
                    try { jsonValue(JSONObject(raw)) as? Map<String, Any?> } catch (_: Exception) { null }
                }.orEmpty()
                result.addAll(revisionAggregateKeys(
                    revision[0] as String, revision[1] as String, revision[2] as String?, payload,
                ))
                if (revision[0] == "quote_scope_revision") {
                    val parts = (revision[1] as String).split(':')
                    if (parts.size == 3) {
                        val scopeKey = jsonArray(listOf(parts[0], parts[1].toLongOrNull() ?: return result, parts[2]))
                        handle.database.rawQuery(
                            "SELECT row_json FROM import_rows WHERE table_name='quote_scope_revisions' " +
                                "AND primary_key=? LIMIT 1",
                            arrayOf(scopeKey),
                        ).use { scopeRows ->
                            if (scopeRows.moveToFirst()) {
                                val scope = JSONObject(scopeRows.getString(0)).getJSONObject("values")
                                val lineKey = jsonArray(listOf(parts[0], parts[2]))
                                handle.database.rawQuery(
                                    "SELECT row_json FROM import_rows WHERE table_name='quote_scope_lines' " +
                                        "AND primary_key=? LIMIT 1",
                                    arrayOf(lineKey),
                                ).use { lineRows ->
                                    if (lineRows.moveToFirst()) {
                                        val line = JSONObject(lineRows.getString(0)).getJSONObject("values")
                                        result.add("card-fields:${line.optString("task_id")}")
                                    }
                                }
                                result.add("quote:${scope.optString("quote_id")}")
                            }
                        }
                    }
                }
            }
            "entity_revisions" -> {
                val payload = (values["payload"] as? String)?.let { raw ->
                    try { jsonValue(JSONObject(raw)) as? Map<String, Any?> } catch (_: Exception) { null }
                }.orEmpty()
                result.addAll(revisionAggregateKeys(
                    values["kind"]?.toString() ?: return emptySet(),
                    values["entity_id"]?.toString() ?: return emptySet(), values["job_id"]?.toString(), payload,
                ))
            }
            "revision_parents" -> listOf("revision_id", "parent_id").forEach { revisionIdColumn ->
                val revisionId = values[revisionIdColumn]?.toString() ?: return@forEach
                handle.database.rawQuery(
                    "SELECT kind,entity_id,job_id,payload FROM validation_revisions WHERE id=? LIMIT 1",
                    arrayOf(revisionId),
                ).use { rows ->
                    if (rows.moveToFirst()) {
                        @Suppress("UNCHECKED_CAST")
                        val payload = if (rows.isNull(3)) emptyMap() else try {
                            jsonValue(JSONObject(rows.getString(3))) as? Map<String, Any?> ?: emptyMap()
                        } catch (_: Exception) { emptyMap() }
                        result.addAll(revisionAggregateKeys(
                            rows.getString(0), rows.getString(1), if (rows.isNull(2)) null else rows.getString(2),
                            payload,
                        ))
                    }
                }
            }
            "app_settings", "settings_money", "billing_tax_settings", "invoice_billing_defaults",
            "invoice_accounting_settings", "location_recording_settings" -> {
                add("global:settings")
                if (table == "app_settings") add(key("logo", values["logo_id"]))
            }
            "logo_assets" -> add(key("logo", values["id"]))
            "jobs" -> {
                addJob(values["id"])
            }
            "customers" -> add(key("customer", values["id"]))
            "job_tax_rates", "job_location_recording", "job_invoice_billing_policy",
            "job_money", "automation_policies" -> {
                addJob(values["job_id"])
            }
            "work_sites" -> add(key("site", values["id"]))
            "kanban_columns" -> add(key("column", values["id"]))
            "actual_costs" -> add(key("actual-cost", values["id"]))
            "sessions" -> add(key("session", values["id"]))
            "invoice_task_claims", "invoice_time_claims" -> addInvoice(values["invoice_id"])
            "kanban_cards" -> {
                add(key("card-fields", values["id"]))
            }
            "card_money", "task_quantities", "task_comments", "task_attachments" -> {
                when (table) {
                    "card_money" -> add(key("card-fields", values["id"]))
                    "task_quantities" -> add(key("card-fields", values["card_id"]))
                    "task_comments" -> {
                        add(key("task-comment", values["id"]))
                        add(key("card-fields", values["task_id"]))
                    }
                    else -> {
                        add(key("task-attachment", values["id"]))
                        add(key("task-comment", values["comment_id"]))
                        add(key("card-fields", values["task_id"]))
                    }
                }
            }
            "task_comment_events" -> {
                add(key("task-comment", values["comment_id"]))
            }
            "task_templates" -> {
                add(key("template-fields", values["id"]))
            }
            "template_money", "template_quantities" ->
                add(key("template-fields", values["id"] ?: values["template_id"]))
            "invoice_coverage" -> addInvoice(values["invoice_id"])
            "invoices" -> {
                addInvoice(values["id"])
            }
            "invoice_payments" -> {
                add(key("payment", values["id"]))
                addInvoice(values["original_invoice_id"])
            }
            "payment_destinations" -> {
                add(key("payment", values["payment_id"]))
                addInvoice(values["invoice_id"])
            }
            "invoice_payment_events" -> {
                add(key("payment", values["payment_id"]))
                addInvoice(values["invoice_id"])
            }
            "invoice_payment_reversals" -> {
                val paymentId = values["payment_id"]
                add(key("payment", paymentId))
                addInvoice(stagedValue("invoice_payments", "id", paymentId, "original_invoice_id"))
            }
            "invoice_copies", "invoice_voids" -> {
                addInvoice(values["invoice_id"])
                addInvoice(values["replacement_id"])
            }
            "quotes", "quote_drafts", "quote_revisions", "quote_decisions", "quote_pdfs" -> {
                addQuote(values["quote_id"] ?: values["id"])
            }
            "quote_scope_lines" -> {
                addQuote(values["quote_id"])
                add(key("card-fields", values["task_id"]))
            }
            "quote_scope_revisions" -> {
                addQuote(values["quote_id"])
                val lineKey = jsonArray(listOf(values["quote_id"], values["line_id"]))
                handle.database.rawQuery(
                    "SELECT row_json FROM import_rows WHERE table_name='quote_scope_lines' AND primary_key=? LIMIT 1",
                    arrayOf(lineKey),
                ).use { rows ->
                    if (rows.moveToFirst()) {
                        val line = JSONObject(rows.getString(0)).getJSONObject("values")
                        add(key("card-fields", line.optString("task_id")))
                    }
                }
            }
            "quote_invoice_links" -> {
                addQuote(values["quote_id"])
                addInvoice(values["invoice_id"])
                addInvoice(values["original_invoice_id"])
            }
            "location_points" -> {
                val sessionId = values["session_id"]
                add(key("session", sessionId))
            }
            "location_deletion_ranges" -> add(key("gps-range", values["id"]))
            "job_deletions" -> addJob(values["id"])
            else -> values["job_id"]?.let(::addJob)
        }
        return result
    }

internal fun BackupImportStore.revisionAggregateKeys(
        kind: String,
        entityId: String,
        jobId: String?,
        payload: Map<String, Any?>,
    ): Set<String> {
        val result = linkedSetOf<String>()
        fun key(prefix: String, value: Any?): String? = value?.toString()?.takeIf(String::isNotEmpty)
            ?.let { "$prefix:$it" }
        fun add(vararg values: String?) { values.filterNotNull().forEach(result::add) }
        fun addJob(value: Any?) { add(key("job-config", value)) }
        fun addInvoice(value: Any?) { add(key("invoice", value)) }
        fun addQuote(value: Any?) { add(key("quote", value)) }
        when (kind) {
            "settings", "settings_money", "billing_tax_settings", "invoice_billing_defaults",
            "invoice_accounting_settings", "location_recording_settings" -> {
                add("global:settings")
                payload["logo_id"]?.let { add(key("logo", it)) }
            }
            "logo" -> add(key("logo", entityId))
            "customer" -> add(key("customer", entityId))
            "job" -> addJob(entityId)
            "card", "card_money", "task_quantity" -> {
                add(key("card-fields", payload["card_id"] ?: entityId))
            }
            "task_comment" -> {
                add(key("task-comment", payload["id"] ?: entityId))
                add(key("card-fields", payload["task_id"]))
            }
            "task_attachment" -> {
                add(key("task-attachment", entityId))
                add(key("task-comment", payload["comment_id"]))
                add(key("card-fields", payload["task_id"]))
            }
            "template", "template_money", "template_quantity" ->
                add(key("template-fields", payload["template_id"] ?: entityId))
            "job_tax_rate", "job_money", "job_location_recording", "job_invoice_billing_policy",
            "automation" -> addJob(jobId ?: entityId)
            "site" -> add(key("site", entityId))
            "column" -> add(key("column", entityId))
            "actual_cost" -> add(key("actual-cost", entityId))
            "invoice", "invoice_coverage", "invoice_task_claim", "invoice_time_claim", "invoice_payment",
            "invoice_payment_event", "invoice_payment_reversal", "invoice_copy", "invoice_void",
            "payment_destination" -> {
                payload["invoice_id"]?.let(::addInvoice)
                payload["original_invoice_id"]?.let(::addInvoice)
                if (kind == "invoice") addInvoice(entityId)
                if (kind == "invoice_payment") add(key("payment", entityId))
                payload["payment_id"]?.let { add(key("payment", it)) }
                payload["replacement_id"]?.let(::addInvoice)
            }
            "quote_draft", "quote_revision", "quote_decision", "quote_scope_line", "quote_scope_revision",
            "quote_invoice_link" -> {
                val quoteId = payload["quote_id"] ?: payload["id"] ?: entityId.substringBefore(':')
                addQuote(quoteId)
                payload["invoice_id"]?.let(::addInvoice)
                payload["original_invoice_id"]?.let(::addInvoice)
                payload["task_id"]?.let { add(key("card-fields", it)) }
            }
            "session" -> add(key("session", entityId))
            "location" -> {
                val sessionId = payload["session_id"]
                add(key("session", sessionId))
            }
            "location_deletion_range" -> add(key("gps-range", entityId))
            else -> if (jobId != null) addJob(jobId)
        }
        return result
    }

internal fun BackupImportStore.expandAggregateKeys(handle: ConflictSelectionHandle, seedKeys: Set<String>): Set<String> {
        if (seedKeys.isEmpty()) return emptySet()
        val db = handle.database
        db.execSQL("DROP TABLE IF EXISTS aggregate_conflict_keys")
        db.execSQL("CREATE TABLE aggregate_conflict_keys(group_key TEXT PRIMARY KEY)")
        seedKeys.forEach { key ->
            db.insertOrThrow("aggregate_conflict_keys", null, ContentValues().apply { put("group_key", key) })
        }
        while (true) {
            val previousCount = scalarLong(db, "SELECT COUNT(*) FROM aggregate_conflict_keys")
            db.execSQL(
                "INSERT OR IGNORE INTO aggregate_conflict_keys(group_key) " +
                    "SELECT DISTINCT source.group_key FROM import_aggregate_keys source " +
                    "JOIN import_aggregate_keys bridge ON bridge.row_ordinal=source.row_ordinal " +
                    "JOIN aggregate_conflict_keys selected ON selected.group_key=bridge.group_key",
            )
            if (scalarLong(db, "SELECT COUNT(*) FROM aggregate_conflict_keys") == previousCount) break
        }
        return db.rawQuery("SELECT group_key FROM aggregate_conflict_keys ORDER BY group_key", null)
            .use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
    }

internal fun BackupImportStore.resolveConflictCore(
        db: SQLiteDatabase,
        importId: String,
        previewToken: String,
        expectedLocalStateVersion: Long,
        conflictId: String,
        choice: String,
    ) {
        val handle = requireHandle(importId)
        requireCurrentPreview(db, handle, previewToken, expectedLocalStateVersion)
        val conflictJson = handle.database.rawQuery(
            "SELECT conflict_json FROM import_conflicts WHERE conflict_id=?",
            arrayOf(conflictId),
        ).use { rows ->
            if (!rows.moveToFirst()) throw ConflictSelectionFailure("CONFLICT_UNAVAILABLE", "The backup conflict is unavailable.")
            JSONObject(rows.getString(0))
        }
        val choices = conflictJson.optJSONArray("allowedChoices")
            ?: throw ConflictSelectionFailure("STORAGE_UNAVAILABLE", "A staged backup conflict is invalid.")
        if ((0 until choices.length()).none { choices.optString(it) == choice } ||
            !isSupportedChoice(conflictJson.optString("kind"), conflictJson.optString("table"), choice)) {
            throw ConflictSelectionFailure("UNSUPPORTED_CHOICE", "That backup conflict choice is not supported.")
        }
        handle.database.beginTransaction()
        try {
            writeConflictChoice(handle, conflictId, conflictJson, choice)
            resolveAggregateConflicts(handle, conflictId, conflictJson, choice)
            coupledGpsRangeId(handle, conflictId)?.let { rangeId ->
                resolveCoupledGpsRangeConflicts(handle, rangeId, choice)
            }
            handle.database.setTransactionSuccessful()
        } finally {
            handle.database.endTransaction()
        }
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
    }
