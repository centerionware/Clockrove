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

internal typealias GpsDeletionFailure = BackupImportStore.Failure
internal typealias GpsDeletionStarted = BackupImportStore.Started
internal typealias GpsDeletionPreview = BackupImportStore.Preview
internal typealias GpsDeletionConflictPage = BackupImportStore.ConflictPage
internal typealias GpsDeletionApplied = BackupImportStore.Applied
internal typealias GpsDeletionExpected = BackupImportStore.Expected
internal typealias GpsDeletionHandle = BackupImportStore.Handle
internal typealias GpsDeletionNormalizedRow = BackupImportStore.NormalizedRow
internal typealias GpsDeletionApplyRow = BackupImportStore.ApplyRow
internal typealias GpsDeletionForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias GpsDeletionHeadAdvance = BackupImportStore.HeadAdvance
internal typealias GpsDeletionDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias GpsDeletionPlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias GpsDeletionPlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias GpsDeletionOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias GpsDeletionRevisionContent = BackupImportStore.RevisionContent
internal typealias GpsDeletionRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias GpsDeletionValidationColumn = BackupImportStore.ValidationColumn
internal typealias GpsDeletionBlobField = BackupImportStore.BlobField
internal typealias GpsDeletionLocalRow = BackupImportStore.LocalRow
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


internal fun BackupImportStore.jsonArray(values: List<Any?>): String = JSONArray().apply {
        values.forEach { put(it ?: JSONObject.NULL) }
    }

internal fun BackupImportStore.stagedIdentifierExists(handle: GpsDeletionHandle, table: String, id: String): Boolean =
        handle.database.rawQuery(
            "SELECT 1 FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
            arrayOf(table, jsonArray(listOf(id))),
        ).use { it.moveToFirst() }

internal fun BackupImportStore.localIdentifierExists(db: SQLiteDatabase, table: String, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM ${quote(table)}

internal fun BackupImportStore.hasUnprovenLocalGpsCoverage(
        localDb: SQLiteDatabase,
        handle: GpsDeletionHandle,
        point: Map<String, Any?>,
    ): Boolean {
        val pointId = revisionString(point["id"])
        val measuredAt = revisionInteger(point["measured_ms"])
        val sessionId = revisionString(point["session_id"])
        val jobId = stagedSessionJobId(localDb, handle, sessionId) ?: invalidReference()
        val pointRevision = handle.database.rawQuery(
            "SELECT revision_id FROM validation_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { if (it.moveToFirst()) it.getString(0) else invalidReference() }
        var unproven = false
        localDb.rawQuery(
            "SELECT revision_id FROM location_deletion_ranges WHERE start_ms<=? AND end_ms>? " +
                "AND (job_id IS NULL OR job_id=?)",
            arrayOf(measuredAt.toString(), measuredAt.toString(), jobId),
        ).use { rows ->
            while (rows.moveToNext() && !unproven) {
                unproven = !hasDirectCausalParent(handle, pointRevision, rows.getString(0))
            }
        }
        return unproven
    }

internal fun BackupImportStore.backupGpsPointsWithoutRangeParent(
        handle: GpsDeletionHandle,
        range: Map<String, Any?>,
    ): Set<String> {
        val start = revisionInteger(range["start_ms"])
        val end = revisionInteger(range["end_ms"])
        val rangeJobId = nullableRevisionString(range["job_id"])
        val rangeRevisionId = revisionString(range["revision_id"])
        val jobFilter = if (rangeJobId == null) "" else " AND job_id=?"
        val args = buildList {
            add(start.toString())
            add(end.toString())
            rangeJobId?.let(::add)
            add(rangeRevisionId)
        }.toTypedArray()
        return handle.database.rawQuery(
            "SELECT p.id FROM validation_points p WHERE measured_ms>=? AND measured_ms<?$jobFilter " +
                "AND NOT EXISTS(SELECT 1 FROM validation_parents rp " +
                "WHERE rp.revision_id=p.revision_id AND rp.parent_id=?) ORDER BY p.id",
            args,
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
    }

internal fun BackupImportStore.hasLocalLocationTombstone(db: SQLiteDatabase, pointId: String): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind='location' AND h.entity_id=? AND r.deleted=1 LIMIT 1",
            arrayOf(pointId),
        ).use { it.moveToFirst() }
    }

internal fun BackupImportStore.canSafelyPreserveLocalGpsDeletion(
        db: SQLiteDatabase,
        handle: GpsDeletionHandle,
        point: Map<String, Any?>,
    ): Boolean {
        val pointId = revisionString(point["id"])
        if (!localIdentifierExists(db, "location_points", pointId)) {
            if (hasLocalLocationTombstone(db, point)) return true
            val measuredAt = revisionInteger(point["measured_ms"])
            val sessionId = revisionString(point["session_id"])
            val jobId = stagedSessionJobId(db, handle, sessionId) ?: return false
            return scalarLong(
                db,
                "SELECT COUNT(*) FROM location_deletion_ranges WHERE start_ms<=? AND end_ms>? " +
                    "AND (job_id IS NULL OR job_id=?)",
                arrayOf(measuredAt.toString(), measuredAt.toString(), jobId),
            ) > 0L
        }
        val localPoint = db.rawQuery(
            "SELECT p.measured_ms,s.job_id,h.revision_id,r.deleted " +
                "FROM location_points p JOIN sessions s ON s.id=p.session_id " +
                "JOIN entity_heads h ON h.kind='location' AND h.entity_id=p.id " +
                "JOIN entity_revisions r ON r.id=h.revision_id WHERE p.id=?",
            arrayOf(pointId),
        ).use { rows ->
            if (!rows.moveToFirst()) return false
            if (rows.getLong(3) != 0L) return false
            Triple(rows.getLong(0), rows.getString(1), rows.getString(2))
        }
        return db.rawQuery(
            "SELECT revision_id FROM location_deletion_ranges WHERE start_ms<=? AND end_ms>? " +
                "AND (job_id IS NULL OR job_id=?)",
            arrayOf(localPoint.first.toString(), localPoint.first.toString(), localPoint.second),
        ).use { ranges ->
            while (ranges.moveToNext()) {
                val hasCausalParent = db.rawQuery(
                    "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=? LIMIT 1",
                    arrayOf(localPoint.third, ranges.getString(0)),
                ).use { it.moveToFirst() }
                if (!hasCausalParent) return@use false
            }
            true
        }
    }

internal fun BackupImportStore.hasDirectCausalParent(handle: GpsDeletionHandle, childRevision: String, parentRevision: String): Boolean =
        handle.database.rawQuery(
            "SELECT 1 FROM validation_parents WHERE revision_id=? AND parent_id=? LIMIT 1",
            arrayOf(childRevision, parentRevision),
        ).use { it.moveToFirst() }

internal fun BackupImportStore.stagedSessionJobId(
        localDb: SQLiteDatabase,
        handle: GpsDeletionHandle,
        sessionId: String,
    ): String? {
        val primaryKey = jsonArray(listOf(sessionId))
        val staged = handle.database.rawQuery(
            "SELECT row_json FROM import_rows WHERE table_name='sessions' AND primary_key=? LIMIT 1",
            arrayOf(primaryKey),
        ).use { rows ->
            if (!rows.moveToFirst()) null else {
                val values = jsonValue(JSONObject(rows.getString(0)).getJSONObject("values")) as? Map<*, *>
                    ?: invalidReference()
                values["job_id"] as? String ?: invalidReference()
            }
        }
        if (staged != null) return staged
        return localDb.rawQuery("SELECT job_id FROM sessions WHERE id=?", arrayOf(sessionId)).use {
            if (!it.moveToFirst()) null else it.getString(0)
        }
    }

internal fun BackupImportStore.hasUnprovenLocalGpsPoints(db: SQLiteDatabase, range: Map<String, Any?>): Boolean {
        val start = revisionInteger(range["start_ms"])
        val end = revisionInteger(range["end_ms"])
        val revisionId = revisionString(range["revision_id"])
        val jobId = nullableRevisionString(range["job_id"])
        val jobFilter = if (jobId == null) "" else " AND s.job_id=?"
        val args = buildList {
            add(start.toString())
            add(end.toString())
            jobId?.let(::add)
            add(revisionId)
        }.toTypedArray()
        return db.rawQuery(
            "SELECT 1 FROM location_points p JOIN sessions s ON s.id=p.session_id " +
                "WHERE p.measured_ms>=? AND p.measured_ms<?$jobFilter AND NOT EXISTS(" +
                "SELECT 1 FROM entity_heads h WHERE h.kind='location' AND h.entity_id=p.id " +
                "AND EXISTS(SELECT 1 FROM revision_parents rp WHERE rp.revision_id=h.revision_id AND rp.parent_id=?)) LIMIT 1",
            args,
        ).use { it.moveToFirst() }
    }

internal fun BackupImportStore.hasStagedGpsRangeCoverageConflict(
        localDb: SQLiteDatabase,
        handle: GpsDeletionHandle,
        rangeId: String,
    ): Boolean {
        val range = handle.database.rawQuery(
            "SELECT row_json FROM import_rows WHERE table_name='location_deletion_ranges' " +
                "AND primary_key=? LIMIT 1",
            arrayOf(jsonArray(listOf(rangeId))),
        ).use { rows ->
            if (!rows.moveToFirst()) return false
            @Suppress("UNCHECKED_CAST")
            jsonValue(JSONObject(rows.getString(0)).getJSONObject("values")) as? Map<String, Any?>
                ?: invalidRevisionGraph()
        }
        return hasUnprovenLocalGpsPoints(localDb, range) ||
            backupGpsPointsWithoutRangeParent(handle, range).isNotEmpty()
    }

internal fun BackupImportStore.localGpsPointsWithoutRangeParent(
        db: SQLiteDatabase,
        range: Map<String, Any?>,
    ): List<String> {
        val start = revisionInteger(range["start_ms"])
        val end = revisionInteger(range["end_ms"])
        val revisionId = revisionString(range["revision_id"])
        val jobId = nullableRevisionString(range["job_id"])
        val jobFilter = if (jobId == null) "" else " AND s.job_id=?"
        val args = buildList {
            add(start.toString())
            add(end.toString())
            jobId?.let(::add)
            add(revisionId)
        }.toTypedArray()
        return db.rawQuery(
            "SELECT p.id FROM location_points p JOIN sessions s ON s.id=p.session_id " +
                "JOIN entity_heads h ON h.kind='location' AND h.entity_id=p.id " +
                "WHERE p.measured_ms>=? AND p.measured_ms<?$jobFilter AND NOT EXISTS(" +
                "SELECT 1 FROM revision_parents rp WHERE rp.revision_id=h.revision_id AND rp.parent_id=?) " +
                "ORDER BY p.id",
            args,
        ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
    }

internal fun BackupImportStore.applyGpsRangeDeletionChoices(
        db: SQLiteDatabase,
        parentsByPoint: Map<String, Set<String>>,
    ): Long {
        var deletedPoints = 0L
        parentsByPoint.toSortedMap().forEach { (pointId, rangeRevisionIds) ->
            val localPoint = db.rawQuery(
                "SELECT p.session_id,p.measured_ms,s.job_id,h.revision_id,r.changed_ms,r.deleted " +
                    "FROM location_points p JOIN sessions s ON s.id=p.session_id " +
                    "JOIN entity_heads h ON h.kind='location' AND h.entity_id=p.id " +
                    "JOIN entity_revisions r ON r.id=h.revision_id WHERE p.id=?",
                arrayOf(pointId),
            ).use { rows ->
                if (!rows.moveToFirst() || rows.getLong(5) != 0L) {
                    throw GpsDeletionFailure("PREVIEW_STALE", "GPS deletion coverage changed after preview.")
                }
                arrayOf(
                    rows.getString(0),
                    rows.getLong(1).toString(),
                    rows.getString(2),
                    rows.getString(3),
                    rows.getLong(4).toString(),
                )
            }
            var deletedAt = localPoint[4].toLong()
            rangeRevisionIds.forEach { rangeRevisionId ->
                val rangeDeletedAt = db.rawQuery(
                    "SELECT deleted_ms FROM location_deletion_ranges d WHERE d.revision_id=? " +
                        "AND d.start_ms<=? AND d.end_ms>? AND (d.job_id IS NULL OR d.job_id=?)",
                    arrayOf(rangeRevisionId, localPoint[1], localPoint[1], localPoint[2]),
                ).use { rows -> if (rows.moveToFirst()) rows.getLong(0) else null }
                if (rangeDeletedAt == null) {
                    throw GpsDeletionFailure("PREVIEW_STALE", "GPS deletion coverage changed after preview.")
                }
                deletedAt = maxOf(deletedAt, rangeDeletedAt)
            }
            RevisionStore.record(
                db,
                "location",
                pointId,
                localPoint[2],
                mapOf("id" to pointId, "session_id" to localPoint[0]),
                deletedAt,
                deleted = true,
                origin = "GPS_RANGE_DELETE",
                additionalParents = rangeRevisionIds,
            )
            if (db.delete("location_points", "id=?", arrayOf(pointId)) != 1) {
                throw GpsDeletionFailure("PREVIEW_STALE", "GPS deletion coverage changed after preview.")
            }
            deletedPoints++
        }
        return deletedPoints
    }
