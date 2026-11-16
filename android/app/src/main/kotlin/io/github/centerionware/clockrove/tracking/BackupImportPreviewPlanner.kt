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
internal typealias PreviewPlannerFailure = BackupImportStore.Failure
internal typealias PreviewPlannerStarted = BackupImportStore.Started
internal typealias PreviewPlannerPreview = BackupImportStore.Preview
internal typealias PreviewPlannerConflictPage = BackupImportStore.ConflictPage
internal typealias PreviewPlannerApplied = BackupImportStore.Applied
internal typealias PreviewPlannerExpected = BackupImportStore.Expected
internal typealias PreviewPlannerHandle = BackupImportStore.Handle
internal typealias PreviewPlannerNormalizedRow = BackupImportStore.NormalizedRow
internal typealias PreviewPlannerApplyRow = BackupImportStore.ApplyRow
internal typealias PreviewPlannerForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias PreviewPlannerHeadAdvance = BackupImportStore.HeadAdvance
internal typealias PreviewPlannerDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias PreviewPlannerPlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias PreviewPlannerPlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias PreviewPlannerOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias PreviewPlannerRevisionContent = BackupImportStore.RevisionContent
internal typealias PreviewPlannerRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias PreviewPlannerValidationColumn = BackupImportStore.ValidationColumn
internal typealias PreviewPlannerBlobField = BackupImportStore.BlobField
internal typealias PreviewPlannerLocalRow = BackupImportStore.LocalRow
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


internal fun BackupImportStore.buildPreview(db: SQLiteDatabase, handle: PreviewPlannerHandle): PreviewPlannerPreview {
        val localVersion: Long
        val localOpenSessions: Long
        var added = 0L
        var unchanged = 0L
        var openSessions = 0L
        var conflictOrdinal = 0L
        db.beginTransactionNonExclusive()
        try {
            localVersion = stateVersion(db)
            localOpenSessions = scalarLong(db, "SELECT COUNT(*) FROM sessions WHERE end_ms IS NULL")
            handle.database.beginTransaction()
            try {
                handle.database.delete("import_conflicts", null, null)
                handle.database.execSQL("DROP TABLE IF EXISTS preview_head_advances")
                handle.database.execSQL(
                    "CREATE TABLE preview_head_advances(" +
                        "row_ordinal INTEGER PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL," +
                        "expected_local_revision TEXT NOT NULL,backup_revision TEXT NOT NULL)",
                )
                handle.database.execSQL(
                    "CREATE TABLE preview_unique_keys(" +
                        "table_name TEXT NOT NULL,columns_json TEXT NOT NULL,values_json TEXT NOT NULL," +
                        "first_ordinal INTEGER NOT NULL,first_primary_key TEXT NOT NULL," +
                        "PRIMARY KEY(table_name,columns_json,values_json))",
                )
                handle.database.execSQL("DROP TABLE IF EXISTS import_aggregate_keys")
                handle.database.execSQL(
                    "CREATE TABLE import_aggregate_keys(row_ordinal INTEGER NOT NULL,group_key TEXT NOT NULL," +
                        "PRIMARY KEY(row_ordinal,group_key))",
                )
                handle.database.execSQL(
                    "CREATE INDEX import_aggregate_group ON import_aggregate_keys(group_key,row_ordinal)",
                )
                buildAggregateKeyIndex(handle)
                handle.database.rawQuery(
                    "SELECT ordinal,table_name,primary_key,row_json,session_start_ms,session_end_ms FROM import_rows ORDER BY ordinal",
                    null,
                ).use { rows ->
                    while (rows.moveToNext()) {
                        val ordinal = rows.getLong(0)
                        val table = rows.getString(1)
                        val keyJson = rows.getString(2)
                        val row = JSONObject(rows.getString(3))
                        @Suppress("UNCHECKED_CAST")
                        val values = jsonValue(row.getJSONObject("values")) as Map<String, Any?>
                        if (table == "job_deletions" && hasLocalJob(db, values["id"]?.toString())) {
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "ACTIVE_JOB_DELETION",
                                listOf("KEEP_LOCAL"), null,
                                localIdentifier = values["id"]?.toString(),
                                localSummary = mapOf("identifier" to "Live local job"),
                                backupSummary = safeSummary(values),
                            )
                            continue
                        }
                        if (table == "task_comment_events" && values["action"] == "DELETED") {
                            val commentId = values["comment_id"]?.toString()
                            if (commentId != null && localIdentifierExists(db, "task_comments", commentId)) {
                                conflictOrdinal = addConflict(
                                    handle, conflictOrdinal, ordinal, table, keyJson, "BACKUP_COMMENT_DELETION",
                                    listOf("KEEP_LOCAL"), "KEEP_LOCAL",
                                    localIdentifier = commentId,
                                    localSummary = mapOf("identifier" to "Live local comment"),
                                    backupSummary = safeSummary(values),
                                )
                                continue
                            }
                        }
                        if (table == "entity_heads" &&
                            activeJobDeletionChoiceForHead(db, handle, values) != null) continue
                        if (table == "jobs" && hasJobDeletionTombstone(db, values["id"]?.toString())) {
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "JOB_DELETION_TOMBSTONE",
                                listOf("PRESERVE_LOCAL_DELETION"), "PRESERVE_LOCAL_DELETION",
                                localIdentifier = values["id"]?.toString(),
                                localSummary = mapOf("identifier" to "Permanently deleted job"),
                                backupSummary = safeSummary(values),
                            )
                            continue
                        }
                        val tombstoneRow = revisionTombstoneRows[table]
                        val tombstoneId = tombstoneRow?.let { values[it.entityIdColumn]?.toString() }
                        if (tombstoneRow != null && tombstoneId != null &&
                            hasLocalEntityDeletionTombstone(db, tombstoneRow.kind, tombstoneId)) {
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "ENTITY_DELETION_TOMBSTONE",
                                listOf("PRESERVE_LOCAL_DELETION"), "PRESERVE_LOCAL_DELETION",
                                localIdentifier = tombstoneId,
                                localSummary = mapOf("identifier" to "Deleted local entity"),
                                backupSummary = safeSummary(values),
                            )
                            continue
                        }
                        val spec = handle.catalog.getValue(table)
                        val blobFields = stagedBlobs(handle, ordinal)
                        val earlierBackupDuplicate = registerStagedUniqueKeys(
                            handle, table, ordinal, keyJson, values,
                        )
                        if (earlierBackupDuplicate != null) {
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "UNIQUE_VALUE_IN_USE",
                            listOf("KEEP_LOCAL", "OMIT_BACKUP_RECORD"), "KEEP_LOCAL",
                                localIdentifier = "BACKUP:${earlierBackupDuplicate.first}",
                                localSummary = mapOf("identifier" to "Earlier backup record"),
                                backupSummary = safeSummary(values),
                            )
                            continue
                        }
                        if (table == "location_points" && hasUnprovenLocalGpsCoverage(db, handle, values)) {
                            val canPreserveDeletion = canSafelyPreserveLocalGpsDeletion(db, handle, values)
                            val choices = if (canPreserveDeletion) listOf("PRESERVE_DELETION") else emptyList()
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "GPS_DELETION_COVERAGE",
                                choices,
                                if (canPreserveDeletion) "PRESERVE_DELETION" else null,
                                localSummary = mapOf(
                                    "identifier" to if (canPreserveDeletion) {
                                        "Deletion coverage"
                                    } else {
                                        "No local point or tombstone can preserve this deletion"
                                    },
                                ),
                                backupSummary = safeSummary(values),
                            )
                            continue
                        }
                        if (table == "location_points" && hasLocalLocationTombstone(db, values)) {
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "GPS_TOMBSTONE",
                                listOf("PRESERVE_LOCAL_DELETION"), "PRESERVE_LOCAL_DELETION",
                                localSummary = mapOf("identifier" to "Point deletion"),
                                backupSummary = safeSummary(values),
                            )
                            continue
                        }
                        if (table == "location_deletion_ranges") {
                            val hasUnprovenLocalPoints = hasUnprovenLocalGpsPoints(db, values)
                            val hasUnprovenBackupPoints = backupGpsPointsWithoutRangeParent(handle, values).isNotEmpty()
                            if (hasUnprovenLocalPoints || hasUnprovenBackupPoints) {
                                val choices = listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION")
                                conflictOrdinal = addConflict(
                                    handle, conflictOrdinal, ordinal, table, keyJson, "GPS_DELETION_COVERAGE",
                                    choices, null,
                                    localSummary = mapOf(
                                        "identifier" to if (hasUnprovenLocalPoints) {
                                            "Unproven local GPS points are covered"
                                        } else {
                                            "Unproven backup GPS points are covered"
                                        },
                                    ),
                                    backupSummary = safeSummary(values),
                                )
                                continue
                            }
                        }
                        val local = findLocalRow(db, table, spec, values)
                        val sessionStart = if (rows.isNull(4)) null else rows.getLong(4)
                        val sessionEnd = if (rows.isNull(5)) null else rows.getLong(5)
                        if (table == "sessions" && sessionEnd == null) {
                            openSessions++
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "OPEN_SESSION",
                                listOf("OMIT_OPEN_SESSION"), "OMIT_OPEN_SESSION",
                                backupSummary = safeSummary(values),
                            )
                            continue
                        }
                        if (table == "sessions" && sessionStart != null && sessionEnd != null) {
                            val overlappingId = findOverlappingSessionId(
                                db, handle, ordinal, sessionStart, sessionEnd, values["id"].toString(),
                            )
                            if (overlappingId != null) {
                                conflictOrdinal = addConflict(
                                    handle, conflictOrdinal, ordinal, table, keyJson, "SESSION_OVERLAP",
                                    listOf("KEEP_LOCAL", "OMIT_BACKUP_SESSION"),
                                    "KEEP_LOCAL", localIdentifier = overlappingId,
                                    localSummary = mapOf("identifier" to overlappingId),
                                    backupSummary = safeSummary(values),
                                )
                                continue
                            }
                        }
                        if (local != null) {
                            val same = sameRecord(db, handle, table, spec, ordinal, values, blobFields, local)
                            if (!same && table == "entity_revisions") {
                                // Revision IDs are immutable graph identities. Reusing an ID for
                                // different content cannot be repaired by a row-level choice.
                                invalidRevisionGraph()
                            }
                            val coordinatedDivergentEntity = !same && table in revisionDataKinds.keys &&
                                divergentEntityDataForRow(db, handle, table, values) != null
                            if (same) {
                                unchanged++
                            } else if (coordinatedDivergentEntity) {
                                // The associated entity-head choice controls this row atomically.
                            } else if (table == "entity_heads" && canAutoAdvanceHead(
                                    db, handle, values, local,
                                )) {
                                handle.database.insertOrThrow(
                                    "preview_head_advances",
                                    null,
                                    ContentValues().apply {
                                        put("row_ordinal", ordinal)
                                        put("kind", values.getValue("kind").toString())
                                        put("entity_id", values.getValue("entity_id").toString())
                                        put("expected_local_revision", local.values.getValue("revision_id").toString())
                                        put("backup_revision", values.getValue("revision_id").toString())
                                    },
                                )
                                added++
                            } else {
                                val immutable = table in immutableTables
                                val revisionRelationship = if (table == "entity_heads") {
                                    revisionHeadRelationship(
                                        db,
                                        handle,
                                        local.values.getValue("revision_id").toString(),
                                        values.getValue("revision_id").toString(),
                                    )
                                } else {
                                    null
                                }
                                val incomingRevision = if (table == "entity_heads") {
                                    values["revision_id"]?.toString()?.let {
                                        revisionContent(handle.database, "validation_revisions", it)
                                    }
                                } else {
                                    null
                                }
                                val currentRevision = if (table == "entity_heads") {
                                    local.values["revision_id"]?.toString()?.let {
                                        revisionContent(db, "entity_revisions", it)
                                    }
                                } else {
                                    null
                                }
                                val backupDeletionAgainstLive = table == "entity_heads" &&
                                    incomingRevision?.deleted == 1L && currentRevision?.deleted == 0L
                                val mergeableDivergentHead = table == "entity_heads" &&
                                    revisionRelationship == "DIVERGENT" &&
                                    canMergeDivergentHead(db, handle, values, local)
                                val selectableDivergentHead = table == "entity_heads" &&
                                    revisionRelationship == "DIVERGENT" &&
                                    divergentEntityDataForHead(db, handle, values, local) != null
                                val gpsRangeHeadChoice = table == "entity_heads" &&
                                    values["kind"] == "location_deletion_range" &&
                                    revisionRelationship == "DIVERGENT" &&
                                    hasStagedGpsRangeCoverageConflict(
                                        db, handle, values.getValue("entity_id").toString(),
                                    )
                                val localGpsTombstoneCanBePreserved = table == "entity_heads" &&
                                    values["kind"] == "location" &&
                                    hasLocalLocationTombstone(db, values.getValue("entity_id").toString())
                                val localEntityDeletionCanBePreserved = if (table == "entity_heads") {
                                    val kind = values["kind"]?.toString()
                                    val entityId = values["entity_id"]?.toString()
                                    kind != null && entityId != null &&
                                        revisionTombstoneRows.values.any { it.kind == kind } &&
                                        hasLocalEntityDeletionTombstone(db, kind, entityId)
                                } else {
                                    false
                                }
                                val unsupportedDivergentHead = table == "entity_heads" &&
                                    revisionRelationship == "DIVERGENT" && !mergeableDivergentHead &&
                                    !selectableDivergentHead &&
                                    !gpsRangeHeadChoice &&
                                    !localEntityDeletionCanBePreserved &&
                                    !localGpsTombstoneCanBePreserved
                                val choices = when {
                                    backupDeletionAgainstLive -> listOf("KEEP_LOCAL")
                                    localEntityDeletionCanBePreserved -> listOf("PRESERVE_LOCAL_DELETION")
                                    gpsRangeHeadChoice -> listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION")
                                    immutable -> listOf("KEEP_LOCAL", "OMIT_BACKUP_RECORD")
                                    mergeableDivergentHead -> listOf("KEEP_LOCAL", "MERGE_IDENTICAL_CONTENT")
                                    selectableDivergentHead -> divergentHeadChoices(
                                        values["kind"]?.toString().orEmpty(),
                                    )
                                    // Do not expose a one-sided choice when this kind has no
                                    // validated adapter to preserve both divergent histories.
                                    unsupportedDivergentHead -> emptyList()
                                    table in mergeOnlyTables -> listOf("KEEP_LOCAL")
                                    else -> listOf("KEEP_LOCAL")
                                }
                                conflictOrdinal = addConflict(
                                    handle, conflictOrdinal, ordinal, table, keyJson,
                                    when {
                                        backupDeletionAgainstLive -> "BACKUP_ENTITY_DELETION"
                                        localEntityDeletionCanBePreserved -> "ENTITY_DELETION_TOMBSTONE"
                                        gpsRangeHeadChoice -> "GPS_DELETION_RANGE_HEAD"
                                        immutable -> "IMMUTABLE_RECORD_DIFFERS"
                                        else -> "EXISTING_RECORD_DIFFERS"
                                    },
                                    choices,
                                    when {
                                        backupDeletionAgainstLive -> "KEEP_LOCAL"
                                        localEntityDeletionCanBePreserved -> "PRESERVE_LOCAL_DELETION"
                                        gpsRangeHeadChoice -> null
                                        unsupportedDivergentHead -> null
                                        selectableDivergentHead -> "MERGE_KEEP_LOCAL_CONTENT"
                                        else -> "KEEP_LOCAL"
                                    },
                                    localSummary = if (localEntityDeletionCanBePreserved) {
                                        mapOf("identifier" to "Deleted local entity")
                                    } else {
                                        safeSummary(local.values)
                                    },
                                    backupSummary = safeSummary(values),
                                    revisionRelationship = revisionRelationship,
                                )
                            }
                            continue
                        }
                        val uniqueConflict = findLocalUniqueConflict(db, table, spec, values)
                        if (uniqueConflict != null) {
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "UNIQUE_VALUE_IN_USE",
                                listOf("KEEP_LOCAL", "OMIT_BACKUP_RECORD"), "KEEP_LOCAL", uniqueConflict,
                                localSummary = mapOf("identifier" to uniqueConflict.take(128)),
                                backupSummary = safeSummary(values),
                            )
                            continue
                        }
                        added++
                    }
                }
                val dependentConflicts = addDependentReferenceConflicts(db, handle, conflictOrdinal)
                conflictOrdinal = dependentConflicts.first
                added -= dependentConflicts.second
                handle.database.execSQL("DROP TABLE preview_unique_keys")
                handle.database.setTransactionSuccessful()
            } finally {
                handle.database.endTransaction()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val token = previewToken(handle, localVersion, conflictOrdinal)
        return PreviewPlannerPreview(
            handle.id, token, handle.expected.schemaVersion, localVersion,
            handle.recordCount, handle.mediaCount, handle.expected.mediaBytes,
            added, unchanged, conflictOrdinal, openSessions, localOpenSessions,
            applyReady = conflictOrdinal == 0L && openSessions == 0L && localOpenSessions == 0L,
        )
    }

internal fun BackupImportStore.findLocalRow(
        db: SQLiteDatabase,
        table: String,
        spec: BackupExportStore.ImportTable,
        values: Map<String, Any?>,
    ): PreviewPlannerLocalRow? {
        val where = spec.primaryKey.joinToString(" AND ") { quote(it) + "=?" }
        val args = spec.primaryKey.map { values.getValue(it).toString() }.toTypedArray()
        val selected = spec.valueColumns.map(::quote).toMutableList()
        spec.blobColumns.forEachIndexed { index, column ->
            selected.add("length(" + quote(column) + ") AS __blob_size_" + index)
        }
        val query = "SELECT " + selected.joinToString(",") + " FROM " + quote(table) + " WHERE " + where
        return db.rawQuery(query, args).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val rowValues = spec.valueColumns.associateWith { column ->
                cursorValue(cursor, cursor.getColumnIndexOrThrow(column))
            }
            val sizes = spec.blobColumns.mapIndexed { index, column ->
                val sizeIndex = cursor.getColumnIndexOrThrow("__blob_size_" + index)
                column to if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
            }.toMap()
            PreviewPlannerLocalRow(rowValues, sizes)
        }
    }

internal fun BackupImportStore.sameRecord(
        db: SQLiteDatabase,
        handle: PreviewPlannerHandle,
        table: String,
        spec: BackupExportStore.ImportTable,
        rowOrdinal: Long,
        values: Map<String, Any?>,
        staged: Map<String, LongArray>,
        local: PreviewPlannerLocalRow,
    ): Boolean {
        val valuesMatch = canonicalValues(values, spec.valueColumns) ==
            canonicalValues(local.values, spec.valueColumns)
        // Privacy deletion deliberately redacts nonfinancial history payloads.
        val redactedRevisionAncestor = if (
            !valuesMatch && table == "entity_revisions" && values["payload"] != null &&
            local.values["payload"] == null
        ) {
            val kind = values["kind"]?.toString()
            val entityId = values["entity_id"]?.toString()
            val revisionId = values["id"]?.toString()
            val identityColumns = spec.valueColumns.filterNot { it == "payload" }.toSet()
            val identityMatches = canonicalValues(values, identityColumns) ==
                canonicalValues(local.values, identityColumns)
            val localHead = if (kind == null || entityId == null || revisionId == null) null else {
                db.rawQuery(
                    "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
                    arrayOf(kind, entityId),
                ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
            }
            kind != null && kind !in JobLifecycleStore.financialRevisionKinds && identityMatches &&
                localHead != null && revisionId != null && localHead != revisionId &&
                revisionHeadRelationship(db, handle, localHead, revisionId) == "BACKUP_ANCESTOR"
        } else {
            false
        }
        if (!valuesMatch && !redactedRevisionAncestor) return false
        spec.blobColumns.forEach { column ->
            val source = staged[column]
            val localSize = local.blobSizes[column]
            if (source == null) {
                if (localSize != null) return false
            } else {
                if (localSize == null || localSize != source[1]) return false
                if (localSize > BackupExportStore.MAX_SNAPSHOT_BYTES) return false
                val incomingDigest = digestFile(handle.mediaFile, source[0], source[1])
                val localDigest = digestDatabaseBlob(db, table, column, spec, values, localSize)
                if (!MessageDigest.isEqual(incomingDigest, localDigest)) return false
            }
        }
        return true
    }

internal fun BackupImportStore.registerStagedUniqueKeys(
        handle: PreviewPlannerHandle,
        table: String,
        ordinal: Long,
        primaryKey: String,
        values: Map<String, Any?>,
    ): Pair<Long, String>? {
        for (columns in uniqueColumnsForRow(table, values)) {
            val keyValues = columns.map { values[it] }
            if (keyValues.any { it == null }) continue
            val columnsJson = JSONArray(columns).toString()
            val valuesJson = jsonArray(keyValues)
            val earlier = handle.database.rawQuery(
                "SELECT first_ordinal,first_primary_key FROM preview_unique_keys " +
                    "WHERE table_name=? AND columns_json=? AND values_json=? LIMIT 1",
                arrayOf(table, columnsJson, valuesJson),
            ).use { rows ->
                if (!rows.moveToFirst()) null else rows.getLong(0) to rows.getString(1)
            }
            if (earlier != null) return earlier
            handle.database.execSQL(
                "INSERT INTO preview_unique_keys VALUES(?,?,?,?,?)",
                arrayOf<Any?>(table, columnsJson, valuesJson, ordinal, primaryKey),
            )
        }
        return null
    }

internal fun BackupImportStore.uniqueColumnsForRow(table: String, values: Map<String, Any?>): List<List<String>> =
        buildList {
            addAll(uniqueColumns[table].orEmpty())
            // This index applies only to unreleased claims; released history may repeat a task.
            if (table == "invoice_task_claims" && values["released_ms"] == null) {
                add(listOf("task_id"))
            }
        }

internal fun BackupImportStore.findLocalUniqueConflict(
        db: SQLiteDatabase,
        table: String,
        spec: BackupExportStore.ImportTable,
        values: Map<String, Any?>,
    ): String? {
        uniqueColumnsForRow(table, values).forEach { columns ->
            if (columns.any { values[it] == null }) return@forEach
            val where = columns.joinToString(" AND ") { quote(it) + "=?" }
            val partialPredicate = if (table == "invoice_task_claims" && columns == listOf("task_id")) {
                " AND released_ms IS NULL"
            } else {
                ""
            }
            val args = columns.map { values.getValue(it).toString() }.toTypedArray()
            val existing = db.rawQuery("SELECT " + spec.primaryKey.joinToString(",") { quote(it) } +
                " FROM " + quote(table) + " WHERE " + where + partialPredicate + " LIMIT 1", args).use { cursor ->
                if (!cursor.moveToFirst()) null else (0 until cursor.columnCount).joinToString("|") { cursor.getString(it) }
            }
            if (existing != null) return existing
        }
        return null
    }

internal fun BackupImportStore.findOverlappingSessionId(
        db: SQLiteDatabase,
        handle: PreviewPlannerHandle,
        ordinal: Long,
        start: Long,
        end: Long,
        sessionId: String,
    ): String? {
        val openLocalId = db.rawQuery(
            "SELECT id FROM sessions WHERE end_ms IS NULL AND start_ms<? AND id<>? LIMIT 1",
            arrayOf(end.toString(), sessionId),
        ).use { if (it.moveToFirst()) "LOCAL_OPEN:" + it.getString(0) else null }
        if (openLocalId != null) return openLocalId
        val localId = db.rawQuery(
            "SELECT id FROM sessions WHERE end_ms IS NOT NULL AND start_ms<? AND end_ms>? AND id<>? LIMIT 1",
            arrayOf(end.toString(), start.toString(), sessionId),
        ).use { if (it.moveToFirst()) "LOCAL:" + it.getString(0) else null }
        if (localId != null) return localId
        return handle.database.rawQuery(
            "SELECT row_json FROM import_rows WHERE table_name='sessions' AND ordinal<? AND session_end_ms IS NOT NULL AND session_start_ms<? AND session_end_ms>? ORDER BY ordinal LIMIT 1",
            arrayOf(ordinal.toString(), end.toString(), start.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val row = JSONObject(cursor.getString(0)).getJSONObject("values")
                "BACKUP:" + row.optString("id")
            }
        }
    }

internal fun BackupImportStore.addConflict(
        handle: PreviewPlannerHandle,
        ordinal: Long,
        rowOrdinal: Long,
        table: String,
        keyJson: String,
        reason: String,
        choices: List<String>,
        defaultChoice: String?,
        localIdentifier: String? = null,
        localSummary: Map<String, Any?> = emptyMap(),
        backupSummary: Map<String, Any?> = emptyMap(),
        revisionRelationship: String? = null,
    ): Long {
        val conflictId = conflictId(handle, rowOrdinal, table, keyJson, reason)
        val aggregateKeys = aggregateKeysForStagedRow(handle, rowOrdinal, table)
        val conflict = JSONObject()
            .put("conflictId", conflictId)
            .put("kind", reason)
            .put("table", table)
            .put("primaryKey", JSONArray(keyJson))
            .put("localIdentifier", localIdentifier ?: JSONObject.NULL)
            .put("localSummary", safeSummaryObject(localSummary))
            .put("backupSummary", safeSummaryObject(backupSummary))
            .put("allowedChoices", JSONArray(choices))
            .put("aggregateKeys", JSONArray(aggregateKeys.sorted()))
            .put("defaultChoice", defaultChoice ?: JSONObject.NULL)
            .put("resolved", false)
        if (revisionRelationship != null) conflict.put("revisionRelationship", revisionRelationship)
        handle.database.insertOrThrow("import_conflicts", null, ContentValues().apply {
            put("ordinal", ordinal)
            put("conflict_id", conflictId)
            put("row_ordinal", rowOrdinal)
            put("conflict_json", conflict.toString())
        })
        return ordinal + 1
    }

internal fun BackupImportStore.addDependentReferenceConflicts(
        localDb: SQLiteDatabase,
        handle: PreviewPlannerHandle,
        firstConflictOrdinal: Long,
    ): Pair<Long, Long> {
        data class Target(val table: String, val columns: List<String>)

        val targets = handle.catalog.keys.flatMap { childTable ->
            foreignKeySpecs(localDb, childTable, handle.catalog).map {
                Target(it.parentTable, it.parentColumns)
            }
        }.distinct().sortedWith(compareBy<Target> { it.table }.thenBy { it.columns.joinToString("\u0000") })
        if (targets.isEmpty()) return firstConflictOrdinal to 0L
        val foreignKeysByChild = handle.catalog.keys.associateWith { childTable ->
            foreignKeySpecs(localDb, childTable, handle.catalog)
        }

        val staged = handle.database
        staged.execSQL(
            "CREATE TABLE preview_reference_rows(" +
                "parent_table TEXT NOT NULL,columns_json TEXT NOT NULL,values_json TEXT NOT NULL," +
                "row_ordinal INTEGER NOT NULL,PRIMARY KEY(parent_table,columns_json,values_json,row_ordinal))",
        )
        staged.execSQL(
            "CREATE INDEX preview_reference_lookup ON preview_reference_rows(" +
                "parent_table,columns_json,values_json,row_ordinal)",
        )
        val targetsByTable = targets.groupBy(Target::table)
        targetsByTable.forEach { (table, tableTargets) ->
            staged.rawQuery(
                "SELECT ordinal,row_json FROM import_rows WHERE table_name=? ORDER BY ordinal",
                arrayOf(table),
            ).use { rows ->
                while (rows.moveToNext()) {
                    val ordinal = rows.getLong(0)
                    val values = JSONObject(rows.getString(1)).getJSONObject("values")
                    tableTargets.forEach targetLoop@{ target ->
                        val referenceValues = target.columns.map { column ->
                            if (values.isNull(column)) null else values.get(column)
                        }
                        if (referenceValues.any { it == null }) return@targetLoop
                        staged.insertOrThrow("preview_reference_rows", null, ContentValues().apply {
                            put("parent_table", target.table)
                            put("columns_json", JSONArray(target.columns).toString())
                            put("values_json", jsonArray(referenceValues))
                            put("row_ordinal", ordinal)
                        })
                    }
                }
            }
        }

        var nextConflictOrdinal = firstConflictOrdinal
        var totalAdded = 0L
        while (true) {
            var passAdded = 0L
            staged.rawQuery(
                "SELECT ordinal,table_name,primary_key,row_json FROM import_rows ORDER BY ordinal",
                null,
            ).use { rows ->
                while (rows.moveToNext()) {
                    val rowOrdinal = rows.getLong(0)
                    val alreadyConflicted = staged.rawQuery(
                        "SELECT 1 FROM import_conflicts WHERE row_ordinal=? LIMIT 1",
                        arrayOf(rowOrdinal.toString()),
                    ).use { it.moveToFirst() }
                    if (alreadyConflicted) continue
                    val table = rows.getString(1)
                    val keyJson = rows.getString(2)
                    val rowJson = JSONObject(rows.getString(3))
                    @Suppress("UNCHECKED_CAST")
                    val values = jsonValue(rowJson.getJSONObject("values")) as? Map<String, Any?>
                        ?: throw PreviewPlannerFailure("STORAGE_UNAVAILABLE", "A staged backup row is invalid.")
                    val blockedParentGroups = linkedMapOf<Long, MutableSet<String>>()
                    foreignKeysByChild.getValue(table).forEach { foreignKey ->
                        val referenceValues = foreignKey.childColumns.map { values[it] }
                        if (referenceValues.any { it == null } || localForeignKeyExists(localDb, foreignKey, referenceValues)) {
                            return@forEach
                        }
                        val keyArgs = arrayOf(
                            foreignKey.parentTable,
                            JSONArray(foreignKey.parentColumns).toString(),
                            jsonArray(referenceValues),
                        )
                        val hasNonConflictedStagedParent = staged.rawQuery(
                            "SELECT 1 FROM preview_reference_rows p LEFT JOIN import_conflicts c " +
                                "ON c.row_ordinal=p.row_ordinal WHERE p.parent_table=? AND p.columns_json=? " +
                                "AND p.values_json=? AND c.row_ordinal IS NULL LIMIT 1",
                            keyArgs,
                        ).use { it.moveToFirst() }
                        if (hasNonConflictedStagedParent) return@forEach
                        val conflictedParents = staged.rawQuery(
                            "SELECT DISTINCT p.row_ordinal FROM preview_reference_rows p JOIN import_conflicts c " +
                                "ON c.row_ordinal=p.row_ordinal WHERE p.parent_table=? AND p.columns_json=? " +
                                "AND p.values_json=? ORDER BY p.row_ordinal",
                            keyArgs,
                        ).use { parentRows ->
                            buildList { while (parentRows.moveToNext()) add(parentRows.getLong(0)) }
                        }
                        if (conflictedParents.isEmpty()) return@forEach
                        val groupKey = "dependency:${foreignKey.parentTable}:" +
                            "${JSONArray(foreignKey.parentColumns).toString()}:${jsonArray(referenceValues)}"
                        conflictedParents.forEach { parentOrdinal ->
                            blockedParentGroups.getOrPut(parentOrdinal) { linkedSetOf() }.add(groupKey)
                        }
                    }
                    if (blockedParentGroups.isNotEmpty()) {
                        blockedParentGroups.forEach { (parentOrdinal, groupKeys) ->
                            groupKeys.forEach { groupKey ->
                                attachAggregateGroupKey(handle, parentOrdinal, groupKey)
                                attachAggregateGroupKey(handle, rowOrdinal, groupKey)
                            }
                        }
                        nextConflictOrdinal = addConflict(
                            handle,
                            nextConflictOrdinal,
                            rowOrdinal,
                            table,
                            keyJson,
                            "DEPENDENT_ON_CONFLICT",
                            listOf("OMIT_BACKUP_RECORD"),
                            null,
                            localSummary = mapOf("identifier" to "A referenced backup record requires a choice"),
                            backupSummary = safeSummary(values),
                        )
                        passAdded++
                    }
                }
            }
            if (passAdded == 0L) break
            totalAdded += passAdded
        }
        staged.execSQL("DROP TABLE preview_reference_rows")
        return nextConflictOrdinal to totalAdded
    }

internal fun BackupImportStore.finishImportCore(db: SQLiteDatabase, importId: String): PreviewPlannerPreview {
        val handle = requireHandle(importId)
        handle.preview?.let { return it }
        if (handle.recordCount != handle.expected.recordCount ||
            handle.mediaCount != handle.expected.mediaCount ||
            handle.allocatedMediaBytes != handle.expected.mediaBytes ||
            handle.receivedMediaBytes != handle.expected.mediaBytes) {
            throw PreviewPlannerFailure("INCOMPLETE_ARCHIVE", "The backup records or media are incomplete.")
        }
        val incompleteBlobs = scalarLong(handle.database,
            "SELECT COUNT(*) FROM import_blobs WHERE received_bytes<>size_bytes")
        if (incompleteBlobs != 0L) throw PreviewPlannerFailure("INCOMPLETE_ARCHIVE", "The backup media are incomplete.")
        RandomAccessFile(handle.mediaFile, "rw").use { it.fd.sync() }
        if (!handle.revisionGraphValidated) {
            validateRevisionGraph(db, handle)
            handle.revisionGraphValidated = true
        }
        val preview = try {
            buildPreview(db, handle)
        } finally {
            if (handle.revisionGraphValidated) clearRevisionValidation(handle)
        }
        handle.preview = preview
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
        return preview
    }
