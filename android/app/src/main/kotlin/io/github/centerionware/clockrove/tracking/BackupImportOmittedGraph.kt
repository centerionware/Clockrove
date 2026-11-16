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
internal typealias OmittedGraphFailure = BackupImportStore.Failure
internal typealias OmittedGraphStarted = BackupImportStore.Started
internal typealias OmittedGraphPreview = BackupImportStore.Preview
internal typealias OmittedGraphConflictPage = BackupImportStore.ConflictPage
internal typealias OmittedGraphApplied = BackupImportStore.Applied
internal typealias OmittedGraphExpected = BackupImportStore.Expected
internal typealias OmittedGraphHandle = BackupImportStore.Handle
internal typealias OmittedGraphNormalizedRow = BackupImportStore.NormalizedRow
internal typealias OmittedGraphApplyRow = BackupImportStore.ApplyRow
internal typealias OmittedGraphForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias OmittedGraphHeadAdvance = BackupImportStore.HeadAdvance
internal typealias OmittedGraphDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias OmittedGraphPlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias OmittedGraphPlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias OmittedGraphOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias OmittedGraphRevisionContent = BackupImportStore.RevisionContent
internal typealias OmittedGraphRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias OmittedGraphValidationColumn = BackupImportStore.ValidationColumn
internal typealias OmittedGraphBlobField = BackupImportStore.BlobField
internal typealias OmittedGraphLocalRow = BackupImportStore.LocalRow
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


internal fun BackupImportStore.revisionHeadRelationship(
        localDb: SQLiteDatabase,
        handle: OmittedGraphHandle,
        localHead: String,
        backupHead: String,
    ): String = when {
        localHead == backupHead -> "SAME"
        isRevisionAncestor(handle.database, "validation_parents", localHead, backupHead) -> "BACKUP_DESCENDANT"
        isRevisionAncestor(localDb, "revision_parents", backupHead, localHead) -> "BACKUP_ANCESTOR"
        else -> "DIVERGENT"
    }

internal fun BackupImportStore.canAutoAdvanceHead(
        localDb: SQLiteDatabase,
        handle: OmittedGraphHandle,
        backupHead: Map<String, Any?>,
        localHead: OmittedGraphLocalRow,
    ): Boolean {
        val kind = backupHead["kind"]?.toString() ?: return false
        val entityId = backupHead["entity_id"]?.toString() ?: return false
        val localRevisionId = localHead.values["revision_id"]?.toString() ?: return false
        val backupRevisionId = backupHead["revision_id"]?.toString() ?: return false
        if (revisionHeadRelationship(localDb, handle, localRevisionId, backupRevisionId) != "BACKUP_DESCENDANT") {
            return false
        }
        return sameLiveHeadContentAndData(localDb, handle, kind, entityId, localRevisionId, backupRevisionId)
    }

internal fun BackupImportStore.canMergeDivergentHead(
        localDb: SQLiteDatabase,
        handle: OmittedGraphHandle,
        backupHead: Map<String, Any?>,
        localHead: OmittedGraphLocalRow,
    ): Boolean {
        val kind = backupHead["kind"]?.toString() ?: return false
        val entityId = backupHead["entity_id"]?.toString() ?: return false
        val localRevisionId = localHead.values["revision_id"]?.toString() ?: return false
        val backupRevisionId = backupHead["revision_id"]?.toString() ?: return false
        if (revisionHeadRelationship(localDb, handle, localRevisionId, backupRevisionId) != "DIVERGENT") {
            return false
        }
        return sameLiveHeadContentAndData(localDb, handle, kind, entityId, localRevisionId, backupRevisionId)
    }

internal fun BackupImportStore.omittedBackupGraph(localDb: SQLiteDatabase, handle: OmittedGraphHandle): OmittedGraphOmittedBackupGraph {
        val omittedSessions = mutableSetOf<String>()
        val omittedRangeRevisionIds = mutableSetOf<String>()
        val gpsDeletionParentsByPoint = mutableMapOf<String, MutableSet<String>>()
        val omittedAggregateKeys = mutableSetOf<String>()
        val protectedDeletionEntities = mutableSetOf<String>()
        handle.database.rawQuery(
            "SELECT c.selected_choice,c.conflict_json,r.table_name,r.row_json " +
                "FROM import_conflicts c JOIN import_rows r ON r.ordinal=c.row_ordinal ORDER BY c.ordinal",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                val choice = rows.getString(0)
                val conflict = JSONObject(rows.getString(1))
                if (choice in setOf(
                        "PRESERVE_LOCAL_CONTENT", "PRESERVE_LOCAL_DELETION", "RESTORE_BACKUP_CONTENT",
                    )) {
                    val table = rows.getString(2)
                    val values = JSONObject(rows.getString(3)).getJSONObject("values")
                    if (table == "entity_heads") {
                        protectedDeletionEntities.add("${values.optString("kind")}:${values.optString("entity_id")}")
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val rowValues = jsonValue(values) as? Map<String, Any?>
                        rowValues?.let { revisionEntityDataKey(table, it)?.let(protectedDeletionEntities::add) }
                    }
                }
                if (conflict.optString("aggregateSelectionIntent") != "LOCAL_ONLY") continue
                val keys = conflict.optJSONArray("aggregateKeys") ?: continue
                for (index in 0 until keys.length()) omittedAggregateKeys.add(keys.optString(index))
            }
        }
        omittedAggregateKeys.addAll(expandAggregateKeys(handle, omittedAggregateKeys))
        val omittedRowOrdinals = mutableSetOf<Long>()
        if (omittedAggregateKeys.isNotEmpty()) {
            val protectedRevisionIds = mutableSetOf<String>()
            if (protectedDeletionEntities.isNotEmpty()) {
                handle.database.rawQuery("SELECT id,kind,entity_id FROM validation_revisions", null).use { rows ->
                    while (rows.moveToNext()) {
                        if ("${rows.getString(1)}:${rows.getString(2)}" in protectedDeletionEntities) {
                            protectedRevisionIds.add(rows.getString(0))
                        }
                    }
                }
            }
            val candidates = handle.database.rawQuery(
                "SELECT DISTINCT r.ordinal,r.table_name,r.row_json FROM import_aggregate_keys a " +
                    "JOIN aggregate_conflict_keys k ON k.group_key=a.group_key " +
                    "JOIN import_rows r ON r.ordinal=a.row_ordinal",
                null,
            ).use { rows -> buildList {
                while (rows.moveToNext()) add(Triple(rows.getLong(0), rows.getString(1), rows.getString(2)))
            } }
            candidates.forEach { (ordinal, table, rowJson) ->
                val values = JSONObject(rowJson).getJSONObject("values")
                val protected = when (table) {
                    "entity_heads", "entity_revisions" ->
                        "${values.optString("kind")}:${values.optString("entity_id")}" in protectedDeletionEntities
                    "revision_parents" -> values.optString("revision_id") in protectedRevisionIds ||
                        values.optString("parent_id") in protectedRevisionIds
                    else -> false
                }
                if (!protected) omittedRowOrdinals.add(ordinal)
            }
        }
        handle.database.rawQuery(
            "SELECT r.row_json FROM import_rows r JOIN import_conflicts c ON c.row_ordinal=r.ordinal " +
                "WHERE r.table_name='sessions' AND c.selected_choice IN('OMIT_BACKUP_SESSION','OMIT_OPEN_SESSION')",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                val record = JSONObject(rows.getString(0)).getJSONObject("values")
                omittedSessions.add(record.getString("id"))
            }
        }
        val omittedPoints = mutableSetOf<String>()
        handle.database.rawQuery("SELECT row_json FROM import_rows WHERE table_name='location_points'", null).use { rows ->
            while (rows.moveToNext()) {
                val values = JSONObject(rows.getString(0)).getJSONObject("values")
                if (values.optString("session_id") in omittedSessions) omittedPoints.add(values.getString("id"))
            }
        }
        val omittedEntities = mutableSetOf<String>().apply {
            omittedSessions.forEach { add("session:$it") }
            omittedPoints.forEach { add("location:$it") }
        }
        handle.database.rawQuery(
            "SELECT r.table_name,r.row_json,c.selected_choice,c.conflict_json FROM import_rows r " +
                "JOIN import_conflicts c ON c.row_ordinal=r.ordinal ORDER BY c.ordinal",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                val table = rows.getString(0)
                val row = JSONObject(rows.getString(1)).getJSONObject("values")
                val choice = rows.getString(2)
                val conflict = JSONObject(rows.getString(3))
                if (table != "entity_heads" && choice in setOf(
                        "KEEP_LOCAL", "OMIT_BACKUP_RECORD",
                        "OMIT_BACKUP_SESSION", "OMIT_OPEN_SESSION",
                    )) {
                    @Suppress("UNCHECKED_CAST")
                    val rowValues = jsonValue(row) as? Map<String, Any?>
                    rowValues?.let { revisionEntityDataKey(table, it)?.let(omittedEntities::add) }
                }
                val revisionRow = revisionTombstoneRows[table]
                if (revisionRow != null && choice in setOf(
                        "KEEP_LOCAL", "OMIT_BACKUP_RECORD",
                        "OMIT_BACKUP_SESSION", "OMIT_OPEN_SESSION",
                    )) {
                    if (!row.isNull(revisionRow.entityIdColumn)) {
                        omittedEntities.add("${revisionRow.kind}:${row.get(revisionRow.entityIdColumn)}")
                    }
                }
                if (table == "entity_heads" && choice == "KEEP_LOCAL") {
                    omittedEntities.add("${row.optString("kind")}:${row.optString("entity_id")}")
                }
                if (table == "location_points" && choice in setOf("PRESERVE_DELETION", "PRESERVE_LOCAL_DELETION")) {
                    val pointId = row.optString("id")
                    omittedPoints.add(pointId)
                    omittedEntities.add("location:$pointId")
                }
                if (table == "location_deletion_ranges" &&
                    conflict.optString("kind") == "GPS_DELETION_COVERAGE") {
                    val rangeId = revisionString(row.opt("id"))
                    val rangeRevisionId = revisionString(row.opt("revision_id"))
                    when (choice) {
                        "KEEP_LOCAL" -> {
                            omittedEntities.add("location_deletion_range:$rangeId")
                            omittedRangeRevisionIds.add(rangeRevisionId)
                        }
                        "APPLY_BACKUP_DELETION" -> {
                            @Suppress("UNCHECKED_CAST")
                            val rangeValues = jsonValue(row) as? Map<String, Any?> ?: invalidRevisionGraph()
                            backupGpsPointsWithoutRangeParent(handle, rangeValues).forEach { pointId ->
                                omittedPoints.add(pointId)
                                omittedEntities.add("location:$pointId")
                            }
                            localGpsPointsWithoutRangeParent(localDb, rangeValues).forEach { pointId ->
                                omittedPoints.add(pointId)
                                omittedEntities.add("location:$pointId")
                                gpsDeletionParentsByPoint.getOrPut(pointId) { mutableSetOf() }
                                    .add(rangeRevisionId)
                            }
                        }
                    }
                }
            }
        }
        handle.database.rawQuery("SELECT row_json FROM import_rows WHERE table_name='entity_heads'", null).use { rows ->
            while (rows.moveToNext()) {
                val values = jsonValue(JSONObject(rows.getString(0)).getJSONObject("values")) as? Map<String, Any?>
                    ?: continue
                if (activeJobDeletionChoiceForHead(
                        localDb,
                        handle,
                        values,
                    ) == "KEEP_LOCAL") {
                    omittedEntities.add("${values["kind"]}:${values["entity_id"]}")
                }
            }
        }
        if (omittedRangeRevisionIds.isNotEmpty()) {
            handle.database.rawQuery(
                "SELECT row_json FROM import_rows WHERE table_name='revision_parents'",
                null,
            ).use { rows ->
                while (rows.moveToNext()) {
                    val edge = JSONObject(rows.getString(0)).getJSONObject("values")
                    if (edge.optString("parent_id") !in omittedRangeRevisionIds) continue
                    val childRevision = edge.optString("revision_id")
                    val child = handle.database.rawQuery(
                        "SELECT row_json FROM import_rows WHERE table_name='entity_revisions' AND primary_key=? LIMIT 1",
                        arrayOf(jsonArray(listOf(childRevision))),
                    ).use { childRows ->
                        if (!childRows.moveToFirst()) null else
                            JSONObject(childRows.getString(0)).getJSONObject("values")
                    } ?: invalidRevisionGraph()
                    if (child.optString("kind") == "location") {
                        val pointId = child.optString("entity_id")
                        omittedEntities.add("location:$pointId")
                        omittedPoints.add(pointId)
                    }
                }
            }
        }
        val omittedRevisions = mutableSetOf<String>()
        omittedRevisions.addAll(omittedRangeRevisionIds)
        handle.database.rawQuery("SELECT row_json FROM import_rows WHERE table_name='entity_revisions'", null).use { rows ->
            while (rows.moveToNext()) {
                val values = JSONObject(rows.getString(0)).getJSONObject("values")
                val key = values.optString("kind") + ":" + values.optString("entity_id")
                if (key in omittedEntities) omittedRevisions.add(values.getString("id"))
            }
        }
        handle.database.rawQuery(
            "SELECT r.row_json,c.selected_choice FROM import_rows r JOIN import_conflicts c " +
                "ON c.row_ordinal=r.ordinal WHERE r.table_name='entity_revisions' " +
                "AND c.selected_choice='OMIT_BACKUP_RECORD'",
            null,
        ).use { rows ->
            while (rows.moveToNext()) {
                omittedRevisions.add(
                    JSONObject(rows.getString(0)).getJSONObject("values").getString("id"),
                )
            }
        }
        omittedEntities.removeAll(protectedDeletionEntities)
        return OmittedGraphOmittedBackupGraph(
            omittedSessions,
            omittedPoints,
            omittedEntities,
            omittedRevisions,
            gpsDeletionParentsByPoint.mapValues { (_, parents) -> parents.toSet() },
            omittedRowOrdinals,
        )
    }

internal fun BackupImportStore.isOmittedBackupGraphRow(
        table: String,
        values: Map<String, Any?>,
        omitted: OmittedGraphOmittedBackupGraph,
        ordinal: Long,
    ): Boolean {
        if (ordinal in omitted.omittedRowOrdinals) return true
        val entityKey = revisionEntityDataKey(table, values)
        if (entityKey != null && entityKey in omitted.revisionEntityKeys) return true
        return when (table) {
        "sessions" -> values["id"]?.toString()?.let(omitted.sessionIds::contains) == true
        "location_points" -> values["id"]?.toString()?.let(omitted.locationPointIds::contains) == true
        "location_deletion_ranges" -> "location_deletion_range:${values["id"]}" in omitted.revisionEntityKeys
        "entity_revisions", "entity_heads" ->
            (values["kind"]?.toString() + ":" + values["entity_id"]?.toString()) in omitted.revisionEntityKeys
        "revision_parents" -> values["revision_id"]?.toString()?.let(omitted.revisionIds::contains) == true ||
            values["parent_id"]?.toString()?.let(omitted.revisionIds::contains) == true
        else -> false
        }
    }

internal fun BackupImportStore.revisionEntityDataKey(table: String, values: Map<String, Any?>): String? {
        fun key(kind: String, column: String = "id"): String? =
            values[column]?.toString()?.let { "$kind:$it" }
        fun composite(kind: String, vararg columns: String): String? {
            val parts = mutableListOf<String>()
            columns.forEach { column ->
                parts.add(values[column]?.toString() ?: return null)
            }
            return "$kind:${parts.joinToString(":")}"
        }
        return when (table) {
            "jobs" -> key("job")
            "customers" -> key("customer")
            "work_sites" -> key("site")
            "kanban_columns" -> key("column")
            "kanban_cards" -> key("card")
            "task_templates" -> key("template")
            "sessions" -> key("session")
            "task_comments" -> key("task_comment")
            "task_comment_events" -> key("task_comment", "comment_id")
            "task_attachments" -> key("task_attachment")
            "actual_costs" -> key("actual_cost")
            "location_points" -> key("location")
            "location_deletion_ranges" -> key("location_deletion_range")
            "app_settings" -> key("settings")
            "settings_money", "job_money", "card_money", "template_money" ->
                key(table.removeSuffix("_money") + "_money")
            "automation_policies" -> key("automation", "job_id")
            "job_tax_rates" -> key("job_tax_rate", "job_id")
            "billing_tax_settings" -> key("billing_tax_settings")
            "task_quantities" -> key("task_quantity", "card_id")
            "template_quantities" -> key("template_quantity", "template_id")
            "job_invoice_billing_policy" -> key("job_invoice_billing_policy", "job_id")
            "invoice_billing_defaults" -> key("invoice_billing_defaults")
            "job_location_recording" -> key("job_location_recording", "job_id")
            "location_recording_settings" -> key("location_recording_settings")
            "invoice_accounting_settings" -> key("invoice_accounting_settings")
            "invoices" -> key("invoice")
            "invoice_coverage" -> key("invoice_coverage", "invoice_id")
            "invoice_task_claims" -> key("invoice_task_claim")
            "invoice_time_claims" -> key("invoice_time_claim")
            "invoice_payments" -> key("invoice_payment")
            "invoice_payment_reversals" -> key("invoice_payment_reversal")
            "invoice_payment_events" -> key("invoice_payment_event")
            "invoice_copies" -> key("invoice_copy")
            "invoice_voids" -> key("invoice_void", "invoice_id")
            "payment_destinations" -> key("payment_destination", "payment_id")
            "quote_drafts" -> key("quote_draft", "quote_id")
            "quote_revisions", "quote_pdfs" -> composite("quote_revision", "quote_id", "revision")
            "quote_decisions" -> composite("quote_decision", "quote_id", "revision")
            "quote_scope_lines" -> composite("quote_scope_line", "quote_id", "line_id")
            "quote_scope_revisions" -> composite("quote_scope_revision", "quote_id", "revision", "line_id")
            "quote_invoice_links" -> key("quote_invoice_link", "invoice_id")
            "job_deletions" -> key("job")
            "logo_assets" -> key("logo")
            else -> null
        }
    }

internal fun BackupImportStore.hasJobDeletionTombstone(db: SQLiteDatabase, jobId: String?): Boolean {
        if (jobId == null) return false
        return db.rawQuery("SELECT 1 FROM job_deletions WHERE id=? LIMIT 1", arrayOf(jobId))
            .use { it.moveToFirst() }
    }

internal fun BackupImportStore.hasLocalEntityDeletionTombstone(db: SQLiteDatabase, kind: String, entityId: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind=? AND h.entity_id=? AND r.deleted=1 LIMIT 1",
            arrayOf(kind, entityId),
        ).use { it.moveToFirst() }

internal fun BackupImportStore.hasLocalJob(db: SQLiteDatabase, jobId: String?): Boolean {
        if (jobId == null) return false
        return db.rawQuery("SELECT 1 FROM jobs WHERE id=? LIMIT 1", arrayOf(jobId))
            .use { it.moveToFirst() }
    }

internal fun BackupImportStore.activeJobDeletionChoiceForHead(
        localDb: SQLiteDatabase,
        handle: OmittedGraphHandle,
        head: Map<String, Any?>,
    ): String? {
        val kind = head["kind"]?.toString() ?: return null
        val entityId = head["entity_id"]?.toString() ?: return null
        val revisionId = head["revision_id"]?.toString() ?: return null
        val revision = revisionContent(handle.database, "validation_revisions", revisionId) ?: return null
        val jobId = if (kind == "job") entityId else revision.jobId ?: return null
        if (!hasLocalJob(localDb, jobId) || !stagedIdentifierExists(handle, "job_deletions", jobId)) return null
        val key = jsonArray(listOf(jobId))
        return handle.database.rawQuery(
            "SELECT c.selected_choice FROM import_rows r LEFT JOIN import_conflicts c ON c.row_ordinal=r.ordinal " +
                "WHERE r.table_name='job_deletions' AND r.primary_key=? LIMIT 1",
            arrayOf(key),
        ).use { rows ->
            if (!rows.moveToFirst() || rows.isNull(0)) "UNRESOLVED" else rows.getString(0)
        }
    }

internal fun BackupImportStore.localTombstoneChoiceForHead(
        localDb: SQLiteDatabase,
        handle: OmittedGraphHandle,
        head: Map<String, Any?>,
    ): String? {
        val kind = head["kind"]?.toString() ?: return null
        val entityId = head["entity_id"]?.toString() ?: return null
        val rowEntry = revisionTombstoneRows.entries.firstOrNull { it.value.kind == kind } ?: return null
        if (!hasLocalEntityDeletionTombstone(localDb, kind, entityId)) return null
        val primaryKey = jsonArray(listOf(entityId))
        val entityHeadKey = jsonArray(listOf(kind, entityId))
        val ordinal = handle.database.rawQuery(
            "SELECT ordinal FROM import_rows WHERE table_name='entity_heads' AND primary_key=? LIMIT 1",
            arrayOf(entityHeadKey),
        ).use { rows -> if (rows.moveToFirst()) rows.getLong(0) else null } ?: handle.database.rawQuery(
            "SELECT ordinal FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
            arrayOf(rowEntry.key, primaryKey),
        ).use { rows -> if (rows.moveToFirst()) rows.getLong(0) else return null }
        return handle.database.rawQuery(
            "SELECT selected_choice FROM import_conflicts WHERE row_ordinal=? LIMIT 1",
            arrayOf(ordinal.toString()),
        ).use { rows -> if (!rows.moveToFirst() || rows.isNull(0)) "UNRESOLVED" else rows.getString(0) }
    }
