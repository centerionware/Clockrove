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
internal typealias RevisionMergeFailure = BackupImportStore.Failure
internal typealias RevisionMergeStarted = BackupImportStore.Started
internal typealias RevisionMergePreview = BackupImportStore.Preview
internal typealias RevisionMergeConflictPage = BackupImportStore.ConflictPage
internal typealias RevisionMergeApplied = BackupImportStore.Applied
internal typealias RevisionMergeExpected = BackupImportStore.Expected
internal typealias RevisionMergeHandle = BackupImportStore.Handle
internal typealias RevisionMergeNormalizedRow = BackupImportStore.NormalizedRow
internal typealias RevisionMergeApplyRow = BackupImportStore.ApplyRow
internal typealias RevisionMergeForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias RevisionMergeHeadAdvance = BackupImportStore.HeadAdvance
internal typealias RevisionMergeDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias RevisionMergePlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias RevisionMergePlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias RevisionMergeOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias RevisionMergeRevisionContent = BackupImportStore.RevisionContent
internal typealias RevisionMergeRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias RevisionMergeValidationColumn = BackupImportStore.ValidationColumn
internal typealias RevisionMergeBlobField = BackupImportStore.BlobField
internal typealias RevisionMergeLocalRow = BackupImportStore.LocalRow
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


internal fun BackupImportStore.normalizeImportedRuntimeRevisions(
        db: SQLiteDatabase,
        handle: RevisionMergeHandle,
        entities: Set<Pair<String, String>>,
    ): Long {
        var changed = 0L
        entities.forEach { (kind, entityId) ->
            val table = revisionDataTables[kind]
                ?: throw RevisionMergeFailure("APPLY_FAILED", "The backup could not be applied atomically.")
            val keyColumns = revisionDataKeyColumns[kind] ?: listOf("id")
            val keyValues = revisionDataKeyValues(kind, entityId)
            val key = keyColumns.zip(keyValues).toMap()
            val spec = handle.catalog[table]
                ?: throw RevisionMergeFailure("APPLY_FAILED", "The backup could not be applied atomically.")
            val row = findLocalRow(db, table, spec, key)
                ?: throw RevisionMergeFailure("APPLY_FAILED", "The backup could not be applied atomically.")
            val payloadValues = revisionPayloadValuesForDataRow(db, null, kind, row.values)
                ?: throw RevisionMergeFailure("APPLY_FAILED", "The backup could not be applied atomically.")
            val previousHead = db.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
                arrayOf(kind, entityId),
            ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
                ?: throw RevisionMergeFailure("APPLY_FAILED", "The backup could not be applied atomically.")
            val jobId = payloadValues["job_id"]?.toString()
                ?: row.values["job_id"]?.toString()
            val revisionId = RevisionStore.record(
                db,
                kind,
                entityId,
                jobId,
                payloadValues,
                wallClock(),
                origin = "BACKUP_RESTORE_NORMALIZE",
            )
            if (revisionId != previousHead) {
                val hasImportedParent = db.rawQuery(
                    "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                    arrayOf(revisionId, previousHead),
                ).use { it.moveToFirst() }
                if (!hasImportedParent) {
                    throw RevisionMergeFailure("APPLY_FAILED", "The backup could not be applied atomically.")
                }
                changed++
            }
        }
        return changed
    }

internal fun BackupImportStore.divergentEntityDataForRow(
        localDb: SQLiteDatabase,
        handle: RevisionMergeHandle,
        table: String,
        values: Map<String, Any?>,
    ): RevisionMergeDivergentEntityData? {
        val kind = revisionDataKinds[table] ?: return null
        val entityId = revisionEntityIdForDataRow(table, values) ?: return null
        val head = stagedEntityHead(handle, kind, entityId) ?: return null
        val localHead = findLocalRow(
            localDb, "entity_heads", handle.catalog["entity_heads"] ?: return null, head,
        ) ?: return null
        return divergentEntityDataForHead(localDb, handle, head, localHead)
    }

internal fun BackupImportStore.divergentEntityDataForHead(
        localDb: SQLiteDatabase,
        handle: RevisionMergeHandle,
        backupHead: Map<String, Any?>,
        localHead: RevisionMergeLocalRow,
    ): RevisionMergeDivergentEntityData? {
        val kind = backupHead["kind"]?.toString() ?: return null
        val entityId = backupHead["entity_id"]?.toString() ?: return null
        val localRevisionId = localHead.values["revision_id"]?.toString() ?: return null
        val backupRevisionId = backupHead["revision_id"]?.toString() ?: return null
        if (revisionHeadRelationship(localDb, handle, localRevisionId, backupRevisionId) != "DIVERGENT") return null
        val table = revisionDataTables[kind] ?: return null
        val columns = revisionDataColumns[kind] ?: return null
        val spec = handle.catalog[table] ?: return null
        val keyColumns = revisionDataKeyColumns[kind] ?: listOf("id")
        if (spec.primaryKey != keyColumns) return null
        val staged = handle.database.rawQuery(
            "SELECT ordinal,row_json FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
            arrayOf(table, jsonArray(revisionDataKeyValues(kind, entityId))),
        ).use { rows ->
            if (!rows.moveToFirst()) return null
            @Suppress("UNCHECKED_CAST")
            val values = jsonValue(JSONObject(rows.getString(1)).getJSONObject("values")) as? Map<String, Any?>
                ?: return null
            Triple(rows.getLong(0), values, stagedBlobs(handle, rows.getLong(0)))
        }
        val local = findLocalRow(localDb, table, spec, staged.second) ?: return null
        val localRevision = revisionContent(localDb, "entity_revisions", localRevisionId) ?: return null
        val backupRevision = revisionContent(handle.database, "validation_revisions", backupRevisionId) ?: return null
        val localPayloadValues = revisionPayloadValuesForDataRow(localDb, null, kind, local.values) ?: return null
        val backupPayloadValues = revisionPayloadValuesForDataRow(localDb, handle, kind, staged.second) ?: return null
        if (!validLiveRevision(localRevision, kind, entityId) ||
            !validLiveRevision(backupRevision, kind, entityId) ||
            localRevision.jobId != backupRevision.jobId ||
            !revisionJobMatchesData(
                localDb, handle, kind, localRevision.jobId, local.values, localPayloadValues, preferStaged = false,
            ) ||
            !revisionJobMatchesData(
                localDb, handle, kind, backupRevision.jobId, staged.second, backupPayloadValues, preferStaged = true,
            ) ||
            !revisionPayloadMatchesRow(
                localRevision.payload, columns, localPayloadValues, optionalRevisionPayloadKeys(kind),
            ) ||
            !revisionPayloadMatchesRow(
                backupRevision.payload, columns, backupPayloadValues, optionalRevisionPayloadKeys(kind),
            ) ||
            canonicalValues(localPayloadValues, columns.toSet()) ==
                canonicalValues(backupPayloadValues, columns.toSet()) ||
            !safeDivergentUpdate(localDb, handle, table, spec, staged.first, staged.second, local)) return null
        return RevisionMergeDivergentEntityData(
            entityId, table, staged.first, staged.second, backupPayloadValues, localPayloadValues,
            staged.third, local, spec,
        )
    }

internal fun BackupImportStore.stagedEntityHead(handle: RevisionMergeHandle, kind: String, entityId: String): Map<String, Any?>? =
        handle.database.rawQuery(
            "SELECT row_json FROM import_rows WHERE table_name='entity_heads' AND primary_key=? LIMIT 1",
            arrayOf(jsonArray(listOf(kind, entityId))),
        ).use { rows ->
            if (!rows.moveToFirst()) return null
            @Suppress("UNCHECKED_CAST")
            jsonValue(JSONObject(rows.getString(0)).getJSONObject("values")) as? Map<String, Any?>
        }

internal fun BackupImportStore.revisionEntityIdForDataRow(table: String, values: Map<String, Any?>): String? {
        val kind = revisionDataKinds[table] ?: return null
        val keyColumn = (revisionDataKeyColumns[kind] ?: listOf("id")).singleOrNull() ?: return null
        return values[keyColumn]?.toString()
    }

internal fun BackupImportStore.revisionDataKeyValues(kind: String, entityId: String): List<Any?> =
        (revisionDataKeyColumns[kind] ?: listOf("id")).map { column ->
            if (column == "id" && kind in integerRevisionDataKinds) entityId.toLongOrNull() ?: entityId
            else entityId
        }

internal fun BackupImportStore.revisionContent(db: SQLiteDatabase, table: String, revisionId: String): RevisionMergeRevisionContent? =
        db.rawQuery(
            "SELECT kind,entity_id,job_id,payload,deleted FROM ${quote(table)} WHERE id=?",
            arrayOf(revisionId),
        ).use { rows ->
            if (!rows.moveToFirst()) return null
            RevisionMergeRevisionContent(
                rows.getString(0), rows.getString(1),
                if (rows.isNull(2)) null else rows.getString(2),
                if (rows.isNull(3)) null else rows.getString(3), rows.getLong(4),
            )
        }

internal fun BackupImportStore.revisionPayloadValuesForDataRow(
        db: SQLiteDatabase,
        handle: RevisionMergeHandle?,
        kind: String,
        rowValues: Map<String, Any?>,
    ): Map<String, Any?>? {
        fun value(column: String): Any? = rowValues[column]

        fun linkedValue(table: String, keyColumn: String, keyValue: Any?, valueColumn: String): Any? {
            if (keyValue == null) return null
            val key = keyValue.toString()
            val staged = handle?.database?.rawQuery(
                "SELECT row_json FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
                arrayOf(table, jsonArray(listOf(key))),
            )?.use { rows ->
                if (!rows.moveToFirst()) null else {
                    val row = JSONObject(rows.getString(0)).getJSONObject("values")
                    if (!row.has(valueColumn) || row.isNull(valueColumn)) null else jsonValue(row.get(valueColumn))
                }
            }
            if (staged != null) return staged
            return db.rawQuery(
                "SELECT ${quote(valueColumn)} FROM ${quote(table)} WHERE ${quote(keyColumn)}=? LIMIT 1",
                arrayOf(key),
            ).use { rows -> if (!rows.moveToFirst() || rows.isNull(0)) null else cursorValue(rows, 0) }
        }

        val columns = revisionDataColumns[kind] ?: return null
        val values = when (kind) {
            "settings_money", "job_money", "card_money", "template_money" -> {
                val id = value("id") ?: return null
                val jobId = when (kind) {
                    "job_money" -> id.toString()
                    "card_money" -> linkedValue("kanban_cards", "id", id, "job_id") ?: return null
                    else -> null
                }
                // MoneyStore.save records the singleton settings-money ID as text,
                // although SQLite returns its INTEGER primary key as a Long.
                mapOf(
                    "id" to if (kind == "settings_money") id.toString() else id,
                    "minor_text" to value("minor_text"),
                    "job_id" to jobId,
                )
            }
            "task_quantity" -> {
                val cardId = value("card_id") ?: return null
                val jobId = linkedValue("kanban_cards", "id", cardId, "job_id") ?: return null
                mapOf("card_id" to cardId, "job_id" to jobId, "quantity_units" to value("quantity_units"))
            }
            "template_quantity" -> mapOf(
                "template_id" to (value("template_id") ?: return null),
                "quantity_units" to value("quantity_units"),
            )
            "payment_destination" -> mapOf(
                "payment_id" to (value("payment_id") ?: return null),
                "invoice_id" to (value("invoice_id") ?: return null),
            )
            "quote_draft" -> mapOf(
                "id" to (value("quote_id") ?: return null),
                "payload" to (value("payload") ?: return null),
            )
            else -> {
                if (columns.any { it !in rowValues }) return null
                columns.associateWith { rowValues[it] }
            }
        }
        return values.takeIf { it.keys.containsAll(columns) }
    }

internal fun BackupImportStore.revisionJobMatchesData(
        db: SQLiteDatabase,
        handle: RevisionMergeHandle,
        kind: String,
        revisionJobId: String?,
        rowValues: Map<String, Any?>,
        payloadValues: Map<String, Any?>,
        preferStaged: Boolean,
    ): Boolean {
        fun stagedOrLocal(table: String, keyColumn: String, key: Any?, valueColumn: String): Any? {
            if (key == null) return null
            val keyValue = key.toString()
            if (preferStaged) {
                handle.database.rawQuery(
                    "SELECT row_json FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
                    arrayOf(table, jsonArray(listOf(keyValue))),
                ).use { rows ->
                    if (rows.moveToFirst()) {
                        val values = JSONObject(rows.getString(0)).getJSONObject("values")
                        return if (!values.has(valueColumn) || values.isNull(valueColumn)) null else
                            jsonValue(values.get(valueColumn))
                    }
                }
            }
            return db.rawQuery(
                "SELECT ${quote(valueColumn)} FROM ${quote(table)} WHERE ${quote(keyColumn)}=? LIMIT 1",
                arrayOf(keyValue),
            ).use { rows -> if (!rows.moveToFirst() || rows.isNull(0)) null else cursorValue(rows, 0) }
        }

        val linkedJob = when {
            "job_id" in payloadValues -> payloadValues["job_id"]?.toString()
            kind == "job" -> rowValues["id"]?.toString()
            kind == "quote_draft" -> rowValues["job_id"]?.toString()
            kind == "invoice_coverage" -> stagedOrLocal(
                "invoices", "id", rowValues["invoice_id"], "job_id",
            )?.toString()
            kind == "payment_destination" -> stagedOrLocal(
                "invoices", "id", rowValues["invoice_id"], "job_id",
            )?.toString()
            kind == "task_comment" || kind == "task_attachment" -> stagedOrLocal(
                "kanban_cards", "id", rowValues["task_id"], "job_id",
            )?.toString()
            kind in setOf(
                "customer", "template", "settings", "billing_tax_settings", "invoice_billing_defaults",
                "invoice_accounting_settings", "settings_money", "template_money", "template_quantity",
                "location_recording_settings",
            ) -> null
            else -> return false
        }
        return linkedJob == revisionJobId
    }

internal fun BackupImportStore.optionalRevisionPayloadKeys(kind: String): Set<String> = when (kind) {
        "invoice_coverage", "payment_destination" -> setOf("predecessor_id")
        else -> emptySet()
    }

internal fun BackupImportStore.revisionPayloadMatchesRow(
        payload: String?,
        columns: List<String>,
        values: Map<String, Any?>,
        optionalKeys: Set<String> = emptySet(),
    ): Boolean {
        if (payload == null) return false
        val revisionValues = try {
            val json = JSONObject(payload)
            val keys = mutableSetOf<String>()
            val iterator = json.keys()
            while (iterator.hasNext()) keys.add(iterator.next())
            if (keys.size !in columns.size..(columns.size + optionalKeys.size) ||
                !keys.containsAll(columns) || (keys - columns.toSet()).any { it !in optionalKeys }) return false
            columns.associateWith { column ->
                if (json.isNull(column)) null else jsonValue(json.get(column))
            }
        } catch (_: Exception) {
            return false
        }
        return canonicalValues(revisionValues, columns.toSet()) == canonicalValues(values, columns.toSet())
    }

internal fun BackupImportStore.safeDivergentUpdate(
        db: SQLiteDatabase,
        handle: RevisionMergeHandle,
        table: String,
        spec: BackupExportStore.ImportTable,
        ordinal: Long,
        values: Map<String, Any?>,
        local: RevisionMergeLocalRow,
    ): Boolean {
        if (table == "sessions") {
            val sessionId = values["id"] as? String ?: return false
            val start = try { revisionInteger(values["start_ms"]) } catch (_: RevisionMergeFailure) { return false }
            val end = try { revisionInteger(values["end_ms"]) } catch (_: RevisionMergeFailure) { return false }
            if (end <= start) return false
            val localOverlap = db.rawQuery(
                "SELECT 1 FROM sessions WHERE id<>? AND start_ms<? " +
                    "AND (end_ms IS NULL OR end_ms>?) LIMIT 1",
                arrayOf(sessionId, end.toString(), start.toString()),
            ).use { it.moveToFirst() }
            val stagedOverlap = handle.database.rawQuery(
                "SELECT 1 FROM import_rows WHERE table_name='sessions' AND ordinal<>? " +
                    "AND session_start_ms<? AND (session_end_ms IS NULL OR session_end_ms>?) LIMIT 1",
                arrayOf(ordinal.toString(), end.toString(), start.toString()),
            ).use { it.moveToFirst() }
            if (localOverlap || stagedOverlap) return false
        }
        val foreignKeys = try { foreignKeySpecs(db, table, handle.catalog) } catch (_: Exception) { return false }
        if (foreignKeys.any { foreignKey ->
                val referenced = foreignKey.childColumns.map { values[it] }
                referenced.any { it != null } && !referenced.any { it == null } &&
                    !localForeignKeyExists(db, foreignKey, referenced)
            }) return false

        val primaryKey = spec.primaryKey
        val primaryWhere = primaryKey.joinToString(" AND ") { quote(it) + "=?" }
        val primaryArgs = primaryKey.map { local.values[it]?.toString().orEmpty() }
        db.rawQuery("PRAGMA index_list(${quote(table)})", null).use { indexes ->
            val uniqueIndex = indexes.getColumnIndex("unique")
            val nameIndex = indexes.getColumnIndex("name")
            val partialIndex = indexes.getColumnIndex("partial")
            while (indexes.moveToNext()) {
                if (indexes.getInt(uniqueIndex) == 0) continue
                val indexName = indexes.getString(nameIndex)
                val columns = mutableListOf<String>()
                db.rawQuery("PRAGMA index_info(${quote(indexName)})", null).use { details ->
                    while (details.moveToNext()) {
                        if (details.isNull(2)) return false
                        columns.add(details.getString(2))
                    }
                }
                if (columns.isEmpty() || primaryKey.all(columns::contains)) continue
                if (partialIndex >= 0 && indexes.getInt(partialIndex) != 0) {
                    if (table != "invoice_task_claims" || columns != listOf("task_id")) return false
                    if (values["released_ms"] != null) continue
                    val taskId = values["task_id"]?.toString() ?: return false
                    val claimId = values["id"]?.toString() ?: return false
                    val activeTaskCollision = db.rawQuery(
                        "SELECT 1 FROM invoice_task_claims WHERE task_id=? AND released_ms IS NULL " +
                            "AND id<>? LIMIT 1",
                        arrayOf(taskId, claimId),
                    ).use { it.moveToFirst() }
                    if (activeTaskCollision) return false
                    continue
                }
                if (columns.any { values[it] == null }) return false
                val where = columns.joinToString(" AND ") { quote(it) + "=?" }
                val args = columns.map { values.getValue(it).toString() } + primaryArgs
                val collision = db.rawQuery(
                    "SELECT 1 FROM ${quote(table)} WHERE $where AND NOT ($primaryWhere) LIMIT 1",
                    args.toTypedArray(),
                ).use { it.moveToFirst() }
                if (collision) return false
            }
        }
        return true
    }

internal fun BackupImportStore.divergentMergeChoiceForDataRow(
        handle: RevisionMergeHandle,
        table: String,
        values: Map<String, Any?>,
    ): String? {
        val kind = revisionDataKinds[table] ?: return null
        val entityId = revisionEntityIdForDataRow(table, values) ?: return null
        return handle.database.rawQuery(
            "SELECT c.selected_choice FROM import_rows r JOIN import_conflicts c ON c.row_ordinal=r.ordinal " +
                "WHERE r.table_name='entity_heads' AND r.primary_key=? LIMIT 1",
            arrayOf(jsonArray(listOf(kind, entityId))),
        ).use { rows ->
            if (!rows.moveToFirst() || rows.isNull(0)) return null
            rows.getString(0).takeIf { it in divergentHeadMergeChoices - "MERGE_IDENTICAL_CONTENT" }
        }
    }

internal fun BackupImportStore.plannedMergeForDataRow(
        localDb: SQLiteDatabase,
        handle: RevisionMergeHandle,
        data: RevisionMergeDivergentEntityData,
        choice: String,
    ): RevisionMergePlannedDivergentMerge {
        if (choice !in divergentHeadMergeChoices - "MERGE_IDENTICAL_CONTENT") {
            throw RevisionMergeFailure("UNSUPPORTED_CHOICE", "A backup conflict contains an unsupported choice.")
        }
        val kind = revisionDataKinds[data.table]
            ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
        val entityId = data.entityId
        val head = stagedEntityHead(handle, kind, entityId)
            ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
        val localHead = findLocalRow(
            localDb, "entity_heads", handle.catalog.getValue("entity_heads"), head,
        ) ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
        return RevisionMergePlannedDivergentMerge(
            kind, entityId, choice,
            localHead.values["revision_id"]?.toString()
                ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview."),
            head["revision_id"]?.toString()
                ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview."),
            data,
        )
    }

internal fun BackupImportStore.putConsistentMergePlan(
        plans: MutableMap<String, RevisionMergePlannedDivergentMerge>,
        plan: RevisionMergePlannedDivergentMerge,
    ) {
        val key = divergentMergeKey(plan.kind, plan.entityId)
        val previous = plans.putIfAbsent(key, plan)
        if (previous != null && (previous.choice != plan.choice ||
                previous.localRevision != plan.localRevision || previous.backupRevision != plan.backupRevision ||
                previous.data.ordinal != plan.data.ordinal)) {
            throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
        }
    }

internal fun BackupImportStore.mergeRevisionPayload(plan: RevisionMergePlannedDivergentMerge, values: Map<String, Any?>): String {
        val columns = revisionDataColumns.getValue(plan.kind)
        return JSONObject().apply {
            columns.sorted().forEach { column -> put(column, values[column] ?: JSONObject.NULL) }
        }.toString()
    }

internal fun BackupImportStore.applySelectedDivergentHeadMerge(
        db: SQLiteDatabase,
        handle: RevisionMergeHandle,
        plan: RevisionMergePlannedDivergentMerge,
    ) {
        val selectedRevisionId = if (plan.choice == "MERGE_KEEP_LOCAL_CONTENT") {
            plan.localRevision
        } else {
            plan.backupRevision
        }
        val selectedRevision = revisionContent(db, "entity_revisions", selectedRevisionId)
            ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
        val expectedValues = if (plan.choice == "MERGE_KEEP_LOCAL_CONTENT") {
            plan.data.local.values
        } else {
            plan.data.values.toMutableMap().apply {
                if (plan.data.table == "automation_policies") {
                    this["enabled"] = 0L
                    this["auto_start"] = 0L
                }
                if (plan.data.table == "work_sites") this["enabled"] = 0L
            }
        }
        val actual = findLocalRow(db, plan.data.table, plan.data.spec, expectedValues)
            ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
        val expectedPayloadValues = revisionPayloadValuesForDataRow(db, null, plan.kind, expectedValues)
            ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
        val actualPayloadValues = revisionPayloadValuesForDataRow(db, null, plan.kind, actual.values)
            ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
        val revisionColumns = revisionDataColumns.getValue(plan.kind).toSet()
        if (canonicalValues(actualPayloadValues, revisionColumns) !=
            canonicalValues(expectedPayloadValues, revisionColumns)) {
            throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
        }
        val payloadObject = JSONObject(mergeRevisionPayload(plan, expectedPayloadValues))
        val selectedPayload = JSONObject(selectedRevision.payload
            ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview."))
        optionalRevisionPayloadKeys(plan.kind).forEach { key ->
            if (selectedPayload.has(key)) payloadObject.put(key, selectedPayload.get(key))
        }
        val payload = payloadObject.toString()
        if (!revisionPayloadMatchesRow(
                payload,
                revisionDataColumns.getValue(plan.kind),
                actualPayloadValues,
                optionalRevisionPayloadKeys(plan.kind),
            ) || !revisionJobMatchesData(
                db, handle, plan.kind, selectedRevision.jobId, actual.values, actualPayloadValues,
                preferStaged = false,
            ) ||
            !validLiveRevision(selectedRevision, plan.kind, plan.entityId)) {
            throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
        }
        val mergedRevision = UUID.randomUUID().toString()
        db.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,?,?,?,?,0,?,'BACKUP_MERGE')",
            arrayOf<Any?>(mergedRevision, plan.kind, plan.entityId, selectedRevision.jobId, payload, wallClock()),
        )
        listOf(plan.localRevision, plan.backupRevision).distinct().sorted().forEach { parent ->
            db.execSQL(
                "INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)",
                arrayOf(mergedRevision, parent),
            )
        }
        val updated = db.update(
            "entity_heads",
            ContentValues().apply { put("revision_id", mergedRevision) },
            "kind=? AND entity_id=? AND revision_id=?",
            arrayOf(plan.kind, plan.entityId, plan.localRevision),
        )
        if (updated != 1) throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
    }

internal fun BackupImportStore.sameLiveHeadContentAndData(
        localDb: SQLiteDatabase,
        handle: RevisionMergeHandle,
        kind: String,
        entityId: String,
        localRevisionId: String,
        backupRevisionId: String,
    ): Boolean {
        val localRevision = localDb.rawQuery(
            "SELECT kind,entity_id,job_id,payload,deleted FROM entity_revisions WHERE id=?",
            arrayOf(localRevisionId),
        ).use { rows ->
            if (!rows.moveToFirst()) return false
            listOf(
                rows.getString(0), rows.getString(1),
                if (rows.isNull(2)) null else rows.getString(2),
                if (rows.isNull(3)) null else rows.getString(3),
                rows.getLong(4),
            )
        }
        val backupRevision = handle.database.rawQuery(
            "SELECT kind,entity_id,job_id,payload,deleted FROM validation_revisions WHERE id=?",
            arrayOf(backupRevisionId),
        ).use { rows ->
            if (!rows.moveToFirst()) return false
            listOf(
                rows.getString(0), rows.getString(1),
                if (rows.isNull(2)) null else rows.getString(2),
                if (rows.isNull(3)) null else rows.getString(3),
                rows.getLong(4),
            )
        }
        if (localRevision != backupRevision || localRevision[4] != 0L) return false

        val table = revisionDataTables[kind] ?: return false
        val spec = handle.catalog[table] ?: return false
        val keyColumns = revisionDataKeyColumns[kind] ?: listOf("id")
        if (spec.primaryKey != keyColumns) return false
        val dataRow = handle.database.rawQuery(
            "SELECT ordinal,row_json FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
            arrayOf(table, jsonArray(revisionDataKeyValues(kind, entityId))),
        ).use { rows ->
            if (!rows.moveToFirst()) return false
            val values = jsonValue(JSONObject(rows.getString(1)).getJSONObject("values")) as? Map<String, Any?>
                ?: return false
            Triple(rows.getLong(0), values, stagedBlobs(handle, rows.getLong(0)))
        }
        val localData = findLocalRow(localDb, table, spec, dataRow.second) ?: return false
        return sameRecord(localDb, handle, table, spec, dataRow.first, dataRow.second, dataRow.third, localData)
    }

internal fun BackupImportStore.headAdvanceForRow(handle: RevisionMergeHandle, rowOrdinal: Long): RevisionMergeHeadAdvance? =
        handle.database.rawQuery(
            "SELECT kind,entity_id,expected_local_revision,backup_revision " +
                "FROM preview_head_advances WHERE row_ordinal=?",
            arrayOf(rowOrdinal.toString()),
        ).use { rows ->
            if (!rows.moveToFirst()) null else RevisionMergeHeadAdvance(
                rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3),
            )
        }

internal fun BackupImportStore.applyIdenticalContentHeadMerge(
        db: SQLiteDatabase,
        handle: RevisionMergeHandle,
        backupHead: Map<String, Any?>,
    ) {
        val spec = handle.catalog.getValue("entity_heads")
        val localHead = findLocalRow(db, "entity_heads", spec, backupHead)
            ?: throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
        if (!canMergeDivergentHead(db, handle, backupHead, localHead)) {
            throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
        }
        val kind = backupHead.getValue("kind").toString()
        val entityId = backupHead.getValue("entity_id").toString()
        val localRevision = localHead.values.getValue("revision_id").toString()
        val backupRevision = backupHead.getValue("revision_id").toString()
        val revision = db.rawQuery(
            "SELECT job_id,payload,deleted FROM entity_revisions WHERE id=? AND kind=? AND entity_id=?",
            arrayOf(backupRevision, kind, entityId),
        ).use { rows ->
            if (!rows.moveToFirst() || rows.getInt(2) != 0 || rows.isNull(1)) {
                throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
            }
            (if (rows.isNull(0)) null else rows.getString(0)) to rows.getString(1)
        }
        val mergedRevision = UUID.randomUUID().toString()
        db.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,?,?,?,?,0,?,'BACKUP_MERGE')",
            arrayOf<Any?>(mergedRevision, kind, entityId, revision.first, revision.second, wallClock()),
        )
        listOf(localRevision, backupRevision).distinct().sorted().forEach { parent ->
            db.execSQL(
                "INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)",
                arrayOf(mergedRevision, parent),
            )
        }
        val updated = db.update(
            "entity_heads",
            ContentValues().apply { put("revision_id", mergedRevision) },
            "kind=? AND entity_id=? AND revision_id=?",
            arrayOf(kind, entityId, localRevision),
        )
        if (updated != 1) throw RevisionMergeFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
    }

internal fun BackupImportStore.isRevisionAncestor(
        db: SQLiteDatabase,
        parentsTable: String,
        ancestor: String,
        descendant: String,
    ): Boolean {
        val table = quote(parentsTable)
        return db.rawQuery(
            "WITH RECURSIVE reachable(revision_id) AS (" +
                "SELECT parent_id FROM $table WHERE revision_id=? UNION " +
                "SELECT p.parent_id FROM $table p JOIN reachable r ON p.revision_id=r.revision_id) " +
                "SELECT 1 FROM reachable WHERE revision_id=? LIMIT 1",
            arrayOf(descendant, ancestor),
        ).use { it.moveToFirst() }
    }
