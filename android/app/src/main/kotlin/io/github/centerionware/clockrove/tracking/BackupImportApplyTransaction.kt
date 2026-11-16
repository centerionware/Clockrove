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

internal typealias ApplyFailure = BackupImportStore.Failure
internal typealias ApplyStarted = BackupImportStore.Started
internal typealias ApplyPreview = BackupImportStore.Preview
internal typealias ApplyConflictPage = BackupImportStore.ConflictPage
internal typealias ApplyApplied = BackupImportStore.Applied
internal typealias ApplyExpected = BackupImportStore.Expected
internal typealias ApplyHandle = BackupImportStore.Handle
internal typealias ApplyNormalizedRow = BackupImportStore.NormalizedRow
internal typealias ApplyApplyRow = BackupImportStore.ApplyRow
internal typealias ApplyForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias ApplyHeadAdvance = BackupImportStore.HeadAdvance
internal typealias ApplyDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias ApplyPlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias ApplyPlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias ApplyOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias ApplyRevisionContent = BackupImportStore.RevisionContent
internal typealias ApplyRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias ApplyValidationColumn = BackupImportStore.ValidationColumn
internal typealias ApplyBlobField = BackupImportStore.BlobField
internal typealias ApplyLocalRow = BackupImportStore.LocalRow
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


internal data class BackupImportApplyCounts(
    val inserted: Long,
    val skipped: Long,
    val changed: Long,
)

internal fun BackupImportStore.applyRowsWithinTransaction(
    db: SQLiteDatabase,
    handle: ApplyHandle,
    preview: ApplyPreview,
    expectedLocalStateVersion: Long,
): BackupImportApplyCounts {
    var inserted = 0L
    var changed = 0L
    var skipped = 0L
                handle.revisionGraphValidated = true
                validateRevisionGraph(db, handle)

                validateApplyChoices(handle, preview.conflictCount)
                val omittedBackupGraph = omittedBackupGraph(db, handle)
                skipped = scalarLong(
                    handle.database,
                    "SELECT COUNT(*) FROM import_conflicts WHERE selected_choice NOT IN(" +
                        "'MERGE_IDENTICAL_CONTENT','MERGE_KEEP_LOCAL_CONTENT','MERGE_USE_BACKUP_CONTENT'," +
                        "'APPLY_BACKUP_DELETION','RESTORE_BACKUP_CONTENT')",
                )
                val plannedDivergentMerges = mutableMapOf<String, ApplyPlannedDivergentMerge>()
                val plannedDeletionResolutions = mutableMapOf<String, BackupImportDeletionResolution.Planned>()

                // Preflight every row before making the first local mutation. The transaction
                // remains open, so the decisions cannot race a repository writer.
                handle.database.rawQuery(
                    "SELECT r.ordinal,r.table_name,r.primary_key,r.row_json,r.session_start_ms,r.session_end_ms," +
                        "c.ordinal,c.selected_choice " +
                        "FROM import_rows r LEFT JOIN import_conflicts c ON c.row_ordinal=r.ordinal ORDER BY r.ordinal",
                    null,
                ).use { rows ->
                    while (rows.moveToNext()) {
                        val ordinal = rows.getLong(0)
                        val table = rows.getString(1)
                        val selectedChoice = if (rows.isNull(6)) null else rows.getString(7)
                        val row = JSONObject(rows.getString(3))
                        @Suppress("UNCHECKED_CAST")
                        val values = jsonValue(row.getJSONObject("values")) as? Map<String, Any?>
                            ?: throw ApplyFailure("STORAGE_UNAVAILABLE", "A staged backup row is invalid.")
                        if (isOmittedBackupGraphRow(table, values, omittedBackupGraph, rows.getLong(0))) continue
                        if (table == "jobs" && hasJobDeletionTombstone(db, values["id"]?.toString())) {
                            if (selectedChoice != "PRESERVE_LOCAL_DELETION") {
                                throw ApplyFailure("PREVIEW_STALE", "A deleted local job can no longer be restored from this preview.")
                            }
                            continue
                        }
                        val tombstoneRow = revisionTombstoneRows[table]
                        val tombstoneId = tombstoneRow?.let { values[it.entityIdColumn]?.toString() }
                        if (tombstoneRow != null && tombstoneId != null &&
                            hasLocalEntityDeletionTombstone(db, tombstoneRow.kind, tombstoneId)) {
                            if (selectedChoice == "PRESERVE_LOCAL_DELETION") {
                                continue
                            }
                            if (selectedChoice != "RESTORE_BACKUP_CONTENT" ||
                                table !in BackupImportDeletionResolution.restorableTables ||
                                findLocalRow(db, table, handle.catalog.getValue(table), values) != null ||
                                BackupImportDeletionResolution.stagedChoice(
                                    handle,
                                    "entity_heads",
                                    listOf(tombstoneRow.kind, tombstoneId),
                                ) != "RESTORE_BACKUP_CONTENT") {
                                throw ApplyFailure("PREVIEW_STALE", "A deleted local entity changed after preview.")
                            }
                        }
                        if (table == "job_deletions" && hasLocalJob(db, values["id"]?.toString()) &&
                            selectedChoice != "KEEP_LOCAL") {
                            throw ApplyFailure("ACTIVE_JOB_DELETION", "A backup deletion conflicts with a live local job.")
                        }
                        if (table == "entity_heads" &&
                            activeJobDeletionChoiceForHead(db, handle, values) == "KEEP_LOCAL") continue
                        if (table == "entity_heads" &&
                            localTombstoneChoiceForHead(db, handle, values) == "PRESERVE_LOCAL_DELETION") continue
                        if (table == "task_comment_events" && selectedChoice == "APPLY_BACKUP_DELETION") {
                            val commentId = values["comment_id"]?.toString()
                                ?: throw ApplyFailure("PREVIEW_STALE", "A backup comment deletion changed after preview.")
                            if (!BackupImportDeletionResolution.canApplyDeletion(db, "task_comment", commentId) ||
                                BackupImportDeletionResolution.stagedChoice(
                                    handle, "entity_heads", listOf("task_comment", commentId),
                                ) != "APPLY_BACKUP_DELETION") {
                                throw ApplyFailure(
                                    "UNSUPPORTED_CHOICE",
                                    "A backup comment deletion needs matching comment and attachment choices.",
                                )
                            }
                        }
                        if (table == "entity_heads") {
                            val kind = values["kind"]?.toString()
                            val entityId = values["entity_id"]?.toString()
                            val resolutionChoice = selectedChoice?.takeIf {
                                (it == "APPLY_BACKUP_DELETION" && kind != null &&
                                    kind in BackupImportDeletionResolution.deletableKinds) ||
                                    (it == "RESTORE_BACKUP_CONTENT" &&
                                        kind != null && kind in BackupImportDeletionResolution.deletableKinds)
                            }
                            if (resolutionChoice != null) {
                                if (kind == null || entityId == null) {
                                    throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                }
                                val localHead = findLocalRow(db, "entity_heads", handle.catalog.getValue("entity_heads"), values)
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                val localRevision = localHead.values["revision_id"]?.toString()
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                val backupRevision = values["revision_id"]?.toString()
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                val localContent = revisionContent(db, "entity_revisions", localRevision)
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                val backupContent = revisionContent(handle.database, "validation_revisions", backupRevision)
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                if (resolutionChoice == "APPLY_BACKUP_DELETION") {
                                    if (localContent.deleted != 0L || backupContent.deleted != 1L ||
                                        localContent.jobId != backupContent.jobId ||
                                        !BackupImportDeletionResolution.canApplyDeletion(db, kind, entityId)) {
                                        throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                    }
                                    if (kind == "task_comment") {
                                        BackupImportDeletionResolution.validateCommentDeletionChoices(db, handle, entityId)
                                    }
                                } else if (localContent.deleted != 1L || backupContent.deleted != 0L ||
                                    localContent.jobId != backupContent.jobId ||
                                    localTombstoneChoiceForHead(db, handle, values) != "RESTORE_BACKUP_CONTENT") {
                                    throw ApplyFailure("PREVIEW_STALE", "A backup restore changed after preview.")
                                }
                                plannedDeletionResolutions[divergentMergeKey(kind, entityId)] =
                                    BackupImportDeletionResolution.Planned(
                                        kind, entityId, localRevision, backupRevision,
                                    )
                                continue
                            }
                        }
                        if (selectedChoice == null && table in revisionDataKinds.keys) {
                            val mergeChoice = divergentMergeChoiceForDataRow(handle, table, values)
                            if (mergeChoice != null) {
                                if (mergeChoice == "KEEP_LOCAL") continue
                                val data = divergentEntityDataForRow(db, handle, table, values)
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
                                val plan = plannedMergeForDataRow(db, handle, data, mergeChoice)
                                putConsistentMergePlan(plannedDivergentMerges, plan)
                                continue
                            }
                        }
                        if (table == "entity_heads" && selectedChoice != null &&
                            selectedChoice in divergentHeadMergeChoices - "MERGE_IDENTICAL_CONTENT") {
                            val kind = values["kind"]?.toString()
                                ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
                            val entityId = values["entity_id"]?.toString()
                                ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
                            val headSpec = handle.catalog.getValue("entity_heads")
                            val localHead = findLocalRow(db, "entity_heads", headSpec, values)
                                ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
                            val data = divergentEntityDataForHead(db, handle, values, localHead)
                                ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
                            val plan = ApplyPlannedDivergentMerge(
                                kind,
                                entityId,
                                requireNotNull(selectedChoice),
                                localHead.values["revision_id"]?.toString()
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup head changed after preview."),
                                values["revision_id"]?.toString()
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup head changed after preview."),
                                data,
                            )
                            putConsistentMergePlan(plannedDivergentMerges, plan)
                            continue
                        }
                        if (selectedChoice != null && selectedChoice != "MERGE_IDENTICAL_CONTENT" &&
                            selectedChoice != "RESTORE_BACKUP_CONTENT") continue
                        val spec = handle.catalog[table]
                            ?: throw ApplyFailure("TABLE_NOT_ALLOWED", "The backup contains an unsupported table.")
                        if (selectedChoice == "MERGE_IDENTICAL_CONTENT") {
                            val localHead = findLocalRow(db, table, spec, values)
                            if (table != "entity_heads" || localHead == null ||
                                !canMergeDivergentHead(db, handle, values, localHead)) {
                                throw ApplyFailure("PREVIEW_STALE", "A divergent backup head changed after preview.")
                            }
                            continue
                        }
                        val headAdvance = headAdvanceForRow(handle, ordinal)
                        if (headAdvance != null) {
                            val localHead = findLocalRow(db, table, spec, values)
                            if (table != "entity_heads" || values["kind"] != headAdvance.kind ||
                                values["entity_id"] != headAdvance.entityId ||
                                values["revision_id"] != headAdvance.backupRevision ||
                                localHead == null ||
                                localHead.values["revision_id"] != headAdvance.expectedLocalRevision ||
                                !canAutoAdvanceHead(db, handle, values, localHead)) {
                                throw ApplyFailure("PREVIEW_STALE", "A backup revision head changed after preview.")
                            }
                            continue
                        }
                        val blobs = stagedBlobs(handle, ordinal)
                        val local = findLocalRow(db, table, spec, values)
                        if (local != null) {
                            if (!sameRecord(db, handle, table, spec, ordinal, values, blobs, local)) {
                                throw ApplyFailure("PREVIEW_STALE", "A backup row changed after preview; create a new preview.")
                            }
                            skipped++
                            continue
                        }
                        if (findLocalUniqueConflict(db, table, spec, values) != null) {
                            throw ApplyFailure("PREVIEW_STALE", "A backup row now conflicts with local data; create a new preview.")
                        }
                        val sessionStart = if (rows.isNull(4)) null else rows.getLong(4)
                        val sessionEnd = if (rows.isNull(5)) null else rows.getLong(5)
                        if (table == "sessions" && sessionEnd == null) {
                            throw ApplyFailure("PREVIEW_STALE", "An open backup session needs a fresh preview and explicit omission.")
                        }
                        if (table == "sessions" && sessionStart != null && sessionEnd != null &&
                            findOverlappingSessionId(db, handle, ordinal, sessionStart, sessionEnd, values["id"].toString()) != null) {
                            throw ApplyFailure("PREVIEW_STALE", "A backup session now overlaps local time; create a new preview.")
                        }
                        if (table == "location_points" && hasUnprovenLocalGpsCoverage(db, handle, values)) {
                            throw ApplyFailure("PREVIEW_STALE", "GPS deletion coverage changed after preview.")
                        }
                        if (table == "location_deletion_ranges" && selectedChoice != "APPLY_BACKUP_DELETION" &&
                            hasUnprovenLocalGpsPoints(db, values)) {
                            throw ApplyFailure("PREVIEW_STALE", "GPS deletion coverage changed after preview.")
                        }
                    }
                }

                // A divergent head merge and its selected business row are applied together.
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                val orderedTables = dependencyOrder(db, handle.catalog.keys.toList())
                val tableOrder = if ("entity_heads" in orderedTables) {
                    orderedTables.filterNot { it == "entity_heads" } + "entity_heads"
                } else {
                    orderedTables
                }
                val runtimeNormalizedEntities = linkedSetOf<Pair<String, String>>()
                tableOrder.forEach { table ->
                    handle.database.rawQuery(
                        "SELECT r.ordinal,r.row_json,c.ordinal,c.selected_choice FROM import_rows r " +
                            "LEFT JOIN import_conflicts c ON c.row_ordinal=r.ordinal " +
                            "WHERE r.table_name=? ORDER BY r.ordinal",
                        arrayOf(table),
                    ).use { rows ->
                        while (rows.moveToNext()) {
                            val ordinal = rows.getLong(0)
                            val rowJson = JSONObject(rows.getString(1))
                            @Suppress("UNCHECKED_CAST")
                            val values = jsonValue(rowJson.getJSONObject("values")) as? Map<String, Any?>
                                ?: throw ApplyFailure("STORAGE_UNAVAILABLE", "A staged backup row is invalid.")
                            if (isOmittedBackupGraphRow(table, values, omittedBackupGraph, ordinal)) continue
                            if (table == "entity_heads" &&
                                activeJobDeletionChoiceForHead(db, handle, values) == "KEEP_LOCAL") continue
                            if (table == "entity_heads" &&
                                localTombstoneChoiceForHead(db, handle, values) == "PRESERVE_LOCAL_DELETION") continue
                            if (!rows.isNull(2)) {
                                val selectedChoice = rows.getString(3)
                                if (table == "task_comment_events" && selectedChoice == "APPLY_BACKUP_DELETION") {
                                    val event = applyContentValues(
                                        handle,
                                        ApplyApplyRow(ordinal, table, values, stagedBlobs(handle, ordinal)),
                                    )
                                    try {
                                        db.insertOrThrow(table, null, event)
                                    } catch (_: Exception) {
                                        throw ApplyFailure("APPLY_FAILED", "The backup could not be applied atomically.")
                                    }
                                    inserted++
                                    changed++
                                    continue
                                }
                                if (table == "entity_heads" && selectedChoice in setOf(
                                        "APPLY_BACKUP_DELETION", "RESTORE_BACKUP_CONTENT",
                                    )) {
                                    val kind = values["kind"]?.toString()
                                        ?: throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                    val entityId = values["entity_id"]?.toString()
                                        ?: throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                    val plan = plannedDeletionResolutions[divergentMergeKey(kind, entityId)]
                                        ?: throw ApplyFailure("PREVIEW_STALE", "A backup deletion changed after preview.")
                                    when (selectedChoice) {
                                        "APPLY_BACKUP_DELETION" -> BackupImportDeletionResolution.applyBackupDeletion(
                                            this, db, handle, plan.kind, plan.entityId,
                                            plan.localRevision, plan.backupRevision,
                                        )
                                        "RESTORE_BACKUP_CONTENT" -> BackupImportDeletionResolution.applyBackupRestore(
                                            this, db, handle, plan.kind, plan.entityId, plan.backupRevision,
                                        )
                                    }
                                    changed++
                                    continue
                                }
                                if (selectedChoice in divergentHeadMergeChoices - "MERGE_IDENTICAL_CONTENT") {
                                    if (table != "entity_heads") {
                                        throw ApplyFailure("UNSUPPORTED_CHOICE", "A backup merge choice is invalid.")
                                    }
                                    val plan = plannedDivergentMerges[divergentMergeKey(
                                        values["kind"].toString(), values["entity_id"].toString(),
                                    )] ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
                                    applySelectedDivergentHeadMerge(db, handle, plan)
                                    changed++
                                    continue
                                }
                                if (selectedChoice == "APPLY_BACKUP_DELETION") {
                                    val isRangeRow = table == "location_deletion_ranges"
                                    val isRangeHead = table == "entity_heads" &&
                                        values["kind"] == "location_deletion_range"
                                    if (!isRangeRow && !isRangeHead) {
                                        throw ApplyFailure("UNSUPPORTED_CHOICE", "A backup deletion choice is invalid.")
                                    }
                                    // Insert/retain the range, then materialize causal point
                                    // tombstones after all selected range rows are present.
                                } else if (selectedChoice == "MERGE_IDENTICAL_CONTENT") {
                                    if (table != "entity_heads") {
                                        throw ApplyFailure("UNSUPPORTED_CHOICE", "A backup merge choice is invalid.")
                                    }
                                    applyIdenticalContentHeadMerge(db, handle, values)
                                    changed++
                                    continue
                                } else if (selectedChoice != "RESTORE_BACKUP_CONTENT" ||
                                    table !in BackupImportDeletionResolution.restorableTables) {
                                    continue
                                }
                            }
                            if (table in revisionDataKinds.keys) {
                                val kind = revisionDataKinds.getValue(table)
                                if (divergentMergeChoiceForDataRow(handle, table, values) == "KEEP_LOCAL") continue
                                val entityId = revisionEntityIdForDataRow(table, values)
                                    ?: throw ApplyFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
                                val plan = plannedDivergentMerges[divergentMergeKey(
                                    kind, entityId,
                                )]
                                if (plan != null) {
                                    if (plan.choice == "MERGE_USE_BACKUP_CONTENT") {
                                        val existingSiteRegistrationKey = if (table == "work_sites") {
                                            val siteId = plan.data.values["id"]?.toString()
                                                ?: throw ApplyFailure(
                                                    "PREVIEW_STALE",
                                                    "A divergent backup entity changed after preview.",
                                                )
                                            db.rawQuery(
                                                "SELECT registration_key FROM work_sites WHERE id=?",
                                                arrayOf(siteId),
                                            ).use { rows ->
                                                if (!rows.moveToFirst()) {
                                                    throw ApplyFailure(
                                                        "PREVIEW_STALE",
                                                        "A divergent backup entity changed after preview.",
                                                    )
                                                }
                                                rows.getString(0)
                                            }
                                        } else {
                                            null
                                        }
                                        val content = applyContentValues(
                                            handle,
                                            ApplyApplyRow(plan.data.ordinal, table, plan.data.values, plan.data.blobs),
                                            existingSiteRegistrationKey,
                                        )
                                        val where = plan.data.spec.primaryKey.joinToString(" AND ") {
                                            quote(it) + "=?"
                                        }
                                        val args = plan.data.spec.primaryKey.map {
                                            plan.data.values[it].toString()
                                        }.toTypedArray()
                                        if (db.update(table, content, where, args) != 1) {
                                            throw ApplyFailure("PREVIEW_STALE", "A divergent backup entity changed after preview.")
                                        }
                                    }
                                    continue
                                }
                            }
                            val spec = handle.catalog.getValue(table)
                            val headAdvance = headAdvanceForRow(handle, ordinal)
                            if (headAdvance != null) {
                                val updated = db.update(
                                    "entity_heads",
                                    ContentValues().apply { put("revision_id", headAdvance.backupRevision) },
                                    "kind=? AND entity_id=? AND revision_id=?",
                                    arrayOf(
                                        headAdvance.kind,
                                        headAdvance.entityId,
                                        headAdvance.expectedLocalRevision,
                                    ),
                                )
                                if (table != "entity_heads" || updated != 1) {
                                    throw ApplyFailure("PREVIEW_STALE", "A backup revision head changed after preview.")
                                }
                                changed++
                                continue
                            }
                            val blobs = stagedBlobs(handle, ordinal)
                            val local = findLocalRow(db, table, spec, values)
                            if (local != null) {
                                if (!sameRecord(db, handle, table, spec, ordinal, values, blobs, local)) {
                                    throw ApplyFailure("PREVIEW_STALE", "A backup row changed after preview; create a new preview.")
                                }
                                continue
                            }
                            val content = applyContentValues(handle, ApplyApplyRow(ordinal, table, values, blobs))
                            try {
                                db.insertOrThrow(table, null, content)
                            } catch (_: Exception) {
                                throw ApplyFailure("APPLY_FAILED", "The backup could not be applied atomically.")
                            }
                            when (table) {
                                "automation_policies" -> values["job_id"]?.toString()?.let {
                                    runtimeNormalizedEntities.add("automation" to it)
                                }
                                "work_sites" -> values["id"]?.toString()?.let {
                                    runtimeNormalizedEntities.add("site" to it)
                                }
                            }
                            inserted++
                            changed++
                        }
                    }
                }
                changed += normalizeImportedRuntimeRevisions(db, handle, runtimeNormalizedEntities)
                changed += applyGpsRangeDeletionChoices(db, omittedBackupGraph.gpsDeletionParentsByPoint)
                validateAppliedState(db)
                if (changed > 0) {
                    db.execSQL("UPDATE state SET version=version+1 WHERE id=1")
                    if (stateVersion(db) != expectedLocalStateVersion + 1L) {
                        throw ApplyFailure("APPLY_FAILED", "The backup could not be applied atomically.")
                    }
                }
                clearRevisionValidation(handle)
    return BackupImportApplyCounts(inserted, skipped, changed)
}
