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
internal typealias RevisionValidatorFailure = BackupImportStore.Failure
internal typealias RevisionValidatorStarted = BackupImportStore.Started
internal typealias RevisionValidatorPreview = BackupImportStore.Preview
internal typealias RevisionValidatorConflictPage = BackupImportStore.ConflictPage
internal typealias RevisionValidatorApplied = BackupImportStore.Applied
internal typealias RevisionValidatorExpected = BackupImportStore.Expected
internal typealias RevisionValidatorHandle = BackupImportStore.Handle
internal typealias RevisionValidatorNormalizedRow = BackupImportStore.NormalizedRow
internal typealias RevisionValidatorApplyRow = BackupImportStore.ApplyRow
internal typealias RevisionValidatorForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias RevisionValidatorHeadAdvance = BackupImportStore.HeadAdvance
internal typealias RevisionValidatorDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias RevisionValidatorPlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias RevisionValidatorPlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias RevisionValidatorOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias RevisionValidatorRevisionContent = BackupImportStore.RevisionContent
internal typealias RevisionValidatorRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias RevisionValidatorValidationColumn = BackupImportStore.ValidationColumn
internal typealias RevisionValidatorBlobField = BackupImportStore.BlobField
internal typealias RevisionValidatorLocalRow = BackupImportStore.LocalRow
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
private val revisionValidationTables = BackupImportStore.revisionValidationTables
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


internal fun BackupImportStore.validateRevisionGraph(localDb: SQLiteDatabase, handle: RevisionValidatorHandle) {
        val db = handle.database
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE validation_revisions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,job_id TEXT,payload TEXT,deleted INTEGER NOT NULL,origin TEXT NOT NULL)")
            db.execSQL("CREATE TABLE validation_parents(revision_id TEXT NOT NULL,parent_id TEXT NOT NULL,PRIMARY KEY(revision_id,parent_id))")
            db.execSQL("CREATE INDEX validation_parent_child ON validation_parents(revision_id)")
            db.execSQL("CREATE INDEX validation_parent_parent ON validation_parents(parent_id)")
            db.execSQL("CREATE TABLE validation_heads(kind TEXT NOT NULL,entity_id TEXT NOT NULL,revision_id TEXT NOT NULL,PRIMARY KEY(kind,entity_id))")
            db.execSQL("CREATE TABLE validation_ranges(id TEXT PRIMARY KEY,job_id TEXT,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,revision_id TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE INDEX validation_range_scope_bounds ON validation_ranges(job_id,start_ms,end_ms)")
            db.execSQL("CREATE TABLE validation_points(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,job_id TEXT NOT NULL,measured_ms INTEGER NOT NULL,revision_id TEXT NOT NULL)")
            db.execSQL("CREATE INDEX validation_point_time ON validation_points(measured_ms,job_id)")
            db.execSQL("CREATE TABLE validation_reference_keys(parent_table TEXT NOT NULL,columns_json TEXT NOT NULL,values_json TEXT NOT NULL,PRIMARY KEY(parent_table,columns_json,values_json))")

            forEachImportedValues(handle, "entity_revisions") { values ->
                val deleted = revisionInteger(values["deleted"])
                if (deleted !in 0L..1L) invalidRevisionGraph()
                val kind = revisionString(values["kind"])
                val entityId = revisionString(values["entity_id"])
                if (kind !in supportedRevisionKinds) invalidRevisionGraph()
                values["payload"]?.let(::validateRevisionObjectPayload)
                if (kind == "location" && values["payload"] != null) {
                    validateLocationRevisionPayload(values["payload"], entityId)
                }
                db.execSQL(
                    "INSERT INTO validation_revisions VALUES(?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        revisionString(values["id"]), kind,
                        entityId, nullableRevisionString(values["job_id"]),
                        values["payload"]?.toString(),
                        deleted, revisionString(values["origin"]),
                    ),
                )
            }
            forEachImportedValues(handle, "revision_parents") { values ->
                db.execSQL(
                    "INSERT INTO validation_parents VALUES(?,?)",
                    arrayOf(revisionString(values["revision_id"]), revisionString(values["parent_id"])),
                )
            }
            forEachImportedValues(handle, "entity_heads") { values ->
                db.execSQL(
                    "INSERT INTO validation_heads VALUES(?,?,?)",
                    arrayOf(
                        revisionString(values["kind"]), revisionString(values["entity_id"]),
                        revisionString(values["revision_id"]),
                    ),
                )
            }
            forEachImportedValues(handle, "job_deletions") { values ->
                val jobId = revisionString(values["id"])
                val deletionRevision = revisionString(values["revision_id"])
                val revisionMatches = db.rawQuery(
                    "SELECT kind,entity_id,deleted FROM validation_revisions WHERE id=?",
                    arrayOf(deletionRevision),
                ).use { rows ->
                    rows.moveToFirst() && rows.getString(0) == "job" && rows.getString(1) == jobId &&
                        rows.getLong(2) == 1L
                }
                val headMatches = scalarLong(
                    db,
                    "SELECT COUNT(*) FROM validation_heads WHERE kind='job' AND entity_id=? AND revision_id=?",
                    arrayOf(jobId, deletionRevision),
                ) == 1L
                if (!revisionMatches || !headMatches || stagedIdentifierExists(handle, "jobs", jobId)) {
                    invalidReference()
                }
            }
            forEachImportedValues(handle, "location_deletion_ranges") { values ->
                val start = revisionInteger(values["start_ms"])
                val end = revisionInteger(values["end_ms"])
                if (start < 0L || end <= start) invalidReference()
                db.execSQL(
                    "INSERT INTO validation_ranges VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(
                        revisionString(values["id"]), nullableRevisionString(values["job_id"]),
                        start, end, revisionString(values["revision_id"]),
                    ),
                )
            }

            val missingLinkCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM validation_parents p " +
                    "LEFT JOIN validation_revisions child ON child.id=p.revision_id " +
                    "LEFT JOIN validation_revisions parent ON parent.id=p.parent_id " +
                    "WHERE child.id IS NULL OR parent.id IS NULL",
            )
            if (missingLinkCount != 0L) invalidRevisionGraph()

            val invalidHeadCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM validation_heads h " +
                    "LEFT JOIN validation_revisions r ON r.id=h.revision_id " +
                    "WHERE r.id IS NULL OR r.kind<>h.kind OR r.entity_id<>h.entity_id",
            )
            val missingEntityHeadCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM validation_revisions r WHERE NOT EXISTS(" +
                    "SELECT 1 FROM validation_heads h WHERE h.kind=r.kind AND h.entity_id=r.entity_id)",
            )
            if (invalidHeadCount != 0L || missingEntityHeadCount != 0L) invalidRevisionGraph()

            // Normal ancestry remains within one logical entity. GPS point tombstones
            // may also name the range revision whose deletion coverage caused them.
            val invalidParentCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM validation_parents p " +
                    "JOIN validation_revisions child ON child.id=p.revision_id " +
                    "JOIN validation_revisions parent ON parent.id=p.parent_id " +
                    "WHERE NOT ((child.kind=parent.kind AND child.entity_id=parent.entity_id) OR " +
                    "(child.kind='location' AND child.deleted=1 AND child.origin='GPS_RANGE_DELETE' " +
                    "AND child.job_id IS NOT NULL AND parent.kind='location_deletion_range' " +
                    "AND parent.deleted=0 AND (parent.job_id IS NULL OR parent.job_id=child.job_id)))",
            )
            if (invalidParentCount != 0L) invalidRevisionGraph()

            val invalidRangeCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM validation_ranges d LEFT JOIN validation_revisions r " +
                    "ON r.id=d.revision_id WHERE r.id IS NULL OR r.kind<>'location_deletion_range' " +
                    "OR r.entity_id<>d.id OR r.deleted<>0 OR r.origin<>'GPS_RANGE_DELETE' " +
                    "OR r.job_id IS NOT d.job_id",
            )
            val missingRangeRowCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM validation_revisions r WHERE r.kind='location_deletion_range' " +
                    "AND NOT EXISTS(SELECT 1 FROM validation_ranges d WHERE d.revision_id=r.id)",
            )
            if (invalidRangeCount != 0L || missingRangeRowCount != 0L) invalidReference()

            forEachImportedValues(handle, "location_points") { values ->
                val pointId = revisionString(values["id"])
                val pointRevision = db.rawQuery(
                    "SELECT h.revision_id,r.deleted FROM validation_heads h JOIN validation_revisions r ON r.id=h.revision_id " +
                        "WHERE h.kind='location' AND h.entity_id=?",
                    arrayOf(pointId),
                ).use { rows ->
                    if (!rows.moveToFirst()) invalidReference()
                    rows.getString(0) to rows.getLong(1)
                }
                if (pointRevision.second != 0L) invalidReference()
                val sessionId = revisionString(values["session_id"])
                val jobId = stagedSessionJobId(localDb, handle, sessionId) ?: invalidReference()
                db.execSQL(
                    "INSERT INTO validation_points VALUES(?,?,?,?,?)",
                    arrayOf(
                        pointId, sessionId, jobId, revisionInteger(values["measured_ms"]), pointRevision.first,
                    ),
                )
            }
            val invalidRangeHeadCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM validation_ranges d LEFT JOIN validation_heads h " +
                    "ON h.kind='location_deletion_range' AND h.entity_id=d.id " +
                    "WHERE h.revision_id IS NULL OR h.revision_id<>d.revision_id",
            )
            if (invalidRangeHeadCount != 0L) invalidReference()
            forEachImportedValues(handle, "location_deletion_ranges") { values ->
                val jobId = nullableRevisionString(values["job_id"])
                if (jobId != null &&
                    !stagedIdentifierExists(handle, "jobs", jobId) &&
                    !localIdentifierExists(localDb, "jobs", jobId) &&
                    !stagedIdentifierExists(handle, "job_deletions", jobId) &&
                    !localIdentifierExists(localDb, "job_deletions", jobId)) {
                    invalidReference()
                }
            }

            validatePortableRows(localDb, handle)
            validateRevisionedDataCoverage(localDb, handle)
            validatePortableReferences(localDb, handle)
            validateInvoiceClaimRules(localDb, handle)

            validateRevisionAcyclicity(db)
            if (File(db.path).length() > MAX_STAGE_DATABASE_BYTES ||
                stagedPhysicalBytes(handle) > MAX_STAGE_PHYSICAL_BYTES) {
                throw RevisionValidatorFailure("SNAPSHOT_LIMIT_EXCEEDED", "The backup staging database exceeds its limit.")
            }
            db.setTransactionSuccessful()
        } catch (failure: RevisionValidatorFailure) {
            throw failure
        } catch (_: Exception) {
            invalidRevisionGraph()
        } finally {
            db.endTransaction()
        }
    }

internal fun BackupImportStore.validateRevisionAcyclicity(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE validation_revision_pending(revision_id TEXT PRIMARY KEY,remaining_parents INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX validation_revision_pending_count ON validation_revision_pending(remaining_parents)")
        db.execSQL("CREATE TABLE validation_revision_ready(revision_id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE validation_revision_batch(revision_id TEXT PRIMARY KEY)")
        db.execSQL(
            "INSERT INTO validation_revision_pending " +
                "SELECT r.id,COUNT(p.parent_id) FROM validation_revisions r " +
                "LEFT JOIN validation_parents p ON p.revision_id=r.id GROUP BY r.id",
        )
        db.execSQL(
            "INSERT INTO validation_revision_ready " +
                "SELECT revision_id FROM validation_revision_pending WHERE remaining_parents=0",
        )
        while (true) {
            db.execSQL("DELETE FROM validation_revision_batch")
            db.execSQL(
                "INSERT INTO validation_revision_batch " +
                    "SELECT revision_id FROM validation_revision_ready ORDER BY revision_id LIMIT $REVISION_VALIDATION_BATCH_SIZE",
            )
            if (scalarLong(db, "SELECT COUNT(*) FROM validation_revision_batch") == 0L) break
            db.execSQL(
                "DELETE FROM validation_revision_ready WHERE revision_id IN " +
                    "(SELECT revision_id FROM validation_revision_batch)",
            )
            db.execSQL(
                "DELETE FROM validation_revision_pending WHERE revision_id IN " +
                    "(SELECT revision_id FROM validation_revision_batch)",
            )
            db.execSQL(
                "UPDATE validation_revision_pending SET remaining_parents=remaining_parents-(" +
                    "SELECT COUNT(*) FROM validation_parents p JOIN validation_revision_batch b " +
                    "ON b.revision_id=p.parent_id WHERE p.revision_id=validation_revision_pending.revision_id) " +
                    "WHERE EXISTS(SELECT 1 FROM validation_parents p JOIN validation_revision_batch b " +
                    "ON b.revision_id=p.parent_id WHERE p.revision_id=validation_revision_pending.revision_id)",
            )
            db.execSQL(
                "INSERT OR IGNORE INTO validation_revision_ready " +
                    "SELECT revision_id FROM validation_revision_pending WHERE remaining_parents=0",
            )
        }
        if (scalarLong(db, "SELECT COUNT(*) FROM validation_revision_pending") != 0L) {
            invalidRevisionGraph()
        }
    }

internal fun BackupImportStore.clearRevisionValidation(handle: RevisionValidatorHandle) {
        val db = handle.database
        try {
            db.beginTransaction()
            try {
                revisionValidationTables.forEach { table ->
                    db.execSQL("DROP TABLE IF EXISTS ${quote(table)}")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            handle.revisionGraphValidated = false
        } catch (_: Exception) {
            handle.revisionGraphValidated = false
            active = null
            destroyHandle(handle)
            throw RevisionValidatorFailure("STORAGE_UNAVAILABLE", "The backup preview could not be finalized.")
        }
    }

internal fun BackupImportStore.forEachImportedValues(handle: RevisionValidatorHandle, table: String, action: (Map<String, Any?>) -> Unit) {
        handle.database.rawQuery(
            "SELECT row_json FROM import_rows WHERE table_name=? ORDER BY ordinal",
            arrayOf(table),
        ).use { rows ->
            while (rows.moveToNext()) {
                val values = jsonValue(JSONObject(rows.getString(0)).getJSONObject("values")) as? Map<*, *>
                    ?: invalidRevisionGraph()
                @Suppress("UNCHECKED_CAST")
                action(values as Map<String, Any?>)
            }
        }
    }

internal fun BackupImportStore.revisionString(value: Any?): String = (value as? String)
        ?.takeIf { it.isNotEmpty() } ?: invalidRevisionGraph()

internal fun BackupImportStore.nullableRevisionString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> invalidRevisionGraph()
    }

internal fun BackupImportStore.revisionInteger(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> invalidRevisionGraph()
    }

internal fun BackupImportStore.validateRevisionObjectPayload(payload: Any?) {
        val jsonText = payload as? String ?: invalidRevisionGraph()
        if (!jsonText.trimStart().startsWith("{")) invalidRevisionGraph()
        try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            invalidRevisionGraph()
        }
    }

internal fun BackupImportStore.validateLocationRevisionPayload(payload: Any?, entityId: String) {
        val jsonText = payload as? String ?: invalidRevisionGraph()
        val json = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            invalidRevisionGraph()
        }
        val keys = mutableSetOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) keys.add(iterator.next())
        if (keys != setOf("id", "session_id") ||
            json.opt("id") !is String || json.optString("id") != entityId ||
            json.opt("session_id") !is String || json.optString("session_id").isBlank()) {
            invalidRevisionGraph()
        }
    }

internal fun BackupImportStore.validateRevisionedDataCoverage(localDb: SQLiteDatabase, handle: RevisionValidatorHandle) {
        revisionDataTables.forEach { (kind, table) ->
            handle.database.rawQuery(
                "SELECT row_json FROM import_rows WHERE table_name=? ORDER BY ordinal",
                arrayOf(table),
            ).use { rows ->
                while (rows.moveToNext()) {
                    val row = JSONObject(rows.getString(0))
                    @Suppress("UNCHECKED_CAST")
                    val values = jsonValue(row.getJSONObject("values")) as? Map<String, Any?>
                        ?: invalidRevisionGraph()
                    val entityId = revisionEntityIdForDataRow(table, values) ?: invalidRevisionGraph()
                    val revisionId = handle.database.rawQuery(
                        "SELECT revision_id FROM validation_heads WHERE kind=? AND entity_id=?",
                        arrayOf(kind, entityId),
                    ).use { head -> if (head.moveToFirst()) head.getString(0) else null }
                        ?: invalidRevisionGraph()
                    val revision = revisionContent(
                        handle.database, "validation_revisions", revisionId,
                    ) ?: invalidRevisionGraph()
                    val payloadValues = revisionPayloadValuesForDataRow(
                        localDb, handle, kind, values,
                    ) ?: invalidRevisionGraph()
                    // Retained rows can be soft-deleted (actual costs keep deleted_ms) while
                    // still being exported. Their head must be the matching tombstone, not
                    // a live revision, or every archive with a removed cost would fail.
                    val rowDeleted = values["deleted_ms"] is Number
                    val revisionStateValid = if (rowDeleted) {
                        revision.deleted == 1L && revision.payload != null
                    } else {
                        validLiveRevision(revision, kind, entityId)
                    }
                    if (!revisionStateValid ||
                        !revisionJobMatchesData(
                            localDb, handle, kind, revision.jobId, values, payloadValues, preferStaged = true,
                        ) ||
                        !revisionPayloadMatchesRow(
                            revision.payload,
                            revisionDataColumns.getValue(kind),
                            payloadValues,
                            optionalRevisionPayloadKeys(kind),
                        )) {
                        invalidRevisionGraph()
                    }
                }
            }
        }
    }

internal fun BackupImportStore.validatePortableRows(localDb: SQLiteDatabase, handle: RevisionValidatorHandle) {
        val db = handle.database
        val tables = handle.catalog.keys.sorted()
        val columnsByTable = tables.associateWith { table -> validationColumns(localDb, table) }
        var allTablesCreated = false
        try {
            tables.forEach { table ->
                val createSql = localDb.rawQuery(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name=? AND sql IS NOT NULL",
                    arrayOf(table),
                ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
                    ?: throw RevisionValidatorFailure("SCHEMA_UNSUPPORTED", "A portable table schema is unavailable.")
                db.execSQL(createSql)
            }
            allTablesCreated = true
            db.rawQuery("SELECT ordinal,table_name,row_json FROM import_rows ORDER BY ordinal", null).use { rows ->
                while (rows.moveToNext()) {
                    val ordinal = rows.getLong(0)
                    val table = rows.getString(1)
                    val spec = handle.catalog[table]
                        ?: throw RevisionValidatorFailure("TABLE_NOT_ALLOWED", "The backup contains an unsupported table.")
                    val row = JSONObject(rows.getString(2))
                    val values = jsonValue(row.getJSONObject("values")) as? Map<String, Any?>
                        ?: invalidArchiveRow()
                    val blobs = stagedBlobs(handle, ordinal)
                    val columns = columnsByTable.getValue(table)
                    val normalizedValues = validatePortableValueTypes(table, spec, columns, values)
                    val content = validationContentValues(table, spec, columns, normalizedValues, blobs, ordinal)
                    try {
                        db.insertOrThrow(table, null, content)
                    } catch (_: SQLiteConstraintException) {
                        invalidArchiveRow()
                    } catch (_: Exception) {
                        invalidArchiveRow()
                    }

                    val where = spec.primaryKey.joinToString(" AND ") { quote(it) + "=?" }
                    val args = spec.primaryKey.map { normalizedValues.getValue(it).toString() }.toTypedArray()
                    val stagedValues = db.rawQuery(
                        "SELECT ${spec.valueColumns.joinToString(",") { quote(it) }} FROM ${quote(table)} WHERE $where",
                        args,
                    ).use { stagedRow ->
                        if (!stagedRow.moveToFirst()) invalidArchiveRow()
                        spec.valueColumns.associateWith { column ->
                            cursorValue(stagedRow, stagedRow.getColumnIndexOrThrow(column))
                        }
                    }
                    if (canonicalValues(normalizedValues, spec.valueColumns) != canonicalValues(stagedValues, spec.valueColumns)) {
                        invalidArchiveRow()
                    }
                    if (db.delete(table, where, args) != 1) invalidArchiveRow()
                }
            }
            if (File(db.path).length() > MAX_STAGE_DATABASE_BYTES ||
                stagedPhysicalBytes(handle) > MAX_STAGE_PHYSICAL_BYTES) {
                throw RevisionValidatorFailure("SNAPSHOT_LIMIT_EXCEEDED", "The backup staging database exceeds its limit.")
            }
        } finally {
            if (allTablesCreated) {
                tables.asReversed().forEach { table -> db.execSQL("DROP TABLE IF EXISTS ${quote(table)}") }
            }
        }
    }

internal fun BackupImportStore.validationColumns(db: SQLiteDatabase, table: String): List<RevisionValidatorValidationColumn> =
        db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    add(RevisionValidatorValidationColumn(
                        name = rows.getString(1),
                        declaredType = rows.getString(2).orEmpty().uppercase(),
                        notNull = rows.getInt(3) != 0,
                        defaultSql = if (rows.isNull(4)) null else rows.getString(4),
                    ))
                }
            }
        }

internal fun BackupImportStore.validatePortableValueTypes(
        table: String,
        spec: BackupExportStore.ImportTable,
        columns: List<RevisionValidatorValidationColumn>,
        values: Map<String, Any?>,
    ): Map<String, Any?> {
        val byName = columns.associateBy { it.name }
        val normalized = values.toMutableMap()
        for (column in spec.valueColumns) {
            val value = values[column] ?: continue
            val declaredType = byName[column]?.declaredType.orEmpty()
            val realColumn = "REAL" in declaredType || "FLOA" in declaredType || "DOUB" in declaredType
            val valid = when {
                "INT" in declaredType -> value is Int || value is Long
                "CHAR" in declaredType || "CLOB" in declaredType || "TEXT" in declaredType -> value is String
                realColumn -> value is Number && value.toDouble().isFinite()
                "BLOB" in declaredType -> false
                else -> true
            }
            if (!valid) invalidArchiveRow()
            if (realColumn) normalized[column] = (value as Number).toDouble()
        }
        return normalized
    }

internal fun BackupImportStore.validationContentValues(
        table: String,
        spec: BackupExportStore.ImportTable,
        columns: List<RevisionValidatorValidationColumn>,
        values: Map<String, Any?>,
        blobs: Map<String, LongArray>,
        ordinal: Long,
    ): ContentValues {
        val content = ContentValues()
        values.forEach { (column, value) -> putValidationValue(content, column, value) }
        for ((column, location) in blobs) {
            val size = location[1]
            if (size < 0L || (table == "task_attachments" && size !in 1L..ImageLimits.NORMALIZED_BYTES.toLong())) {
                invalidArchiveRow()
            }
            content.put(column, ByteArray(if (size == 0L) 0 else 1))
        }
        val represented = values.keys + blobs.keys
        columns.filter { it.name !in represented }.forEach { column ->
            if (!column.notNull || column.defaultSql?.trim()?.equals("NULL", ignoreCase = true) == false) return@forEach
            val placeholder: Any = when {
                table == "automation_policies" && column.name == "facts_since_ms" -> 0L
                table == "work_sites" && column.name == "registration_key" -> "preview-validation-$ordinal"
                else -> throw RevisionValidatorFailure(
                    "SCHEMA_UNSUPPORTED",
                    "A required device-local field has no safe backup validation default.",
                )
            }
            putValidationValue(content, column.name, placeholder)
        }
        return content
    }

internal fun BackupImportStore.putValidationValue(content: ContentValues, column: String, value: Any?) {
        when (value) {
            null -> content.putNull(column)
            is String -> content.put(column, value)
            is Int -> content.put(column, value)
            is Long -> content.put(column, value)
            is Double -> content.put(column, value)
            is Boolean -> content.put(column, value)
            else -> invalidArchiveRow()
        }
    }

internal fun BackupImportStore.validatePortableReferences(localDb: SQLiteDatabase, handle: RevisionValidatorHandle) {
        val populatedSecondaryKeys = mutableSetOf<Pair<String, List<String>>>()
        for (table in handle.catalog.keys.sorted()) {
            val foreignKeys = foreignKeySpecs(localDb, table, handle.catalog)
            for (foreignKey in foreignKeys) {
                val parentTableSpec = handle.catalog[foreignKey.parentTable]
                if (parentTableSpec != null && foreignKey.parentColumns.toSet() != parentTableSpec.primaryKey.toSet() &&
                    populatedSecondaryKeys.add(foreignKey.parentTable to foreignKey.parentColumns)) {
                    forEachImportedValues(handle, foreignKey.parentTable) { values ->
                        val keyValues = foreignKey.parentColumns.map { values[it] }
                        if (keyValues.any { it == null }) return@forEachImportedValues
                        val columnsJson = JSONArray(foreignKey.parentColumns).toString()
                        val valuesJson = jsonArray(keyValues)
                        try {
                            handle.database.execSQL(
                                "INSERT INTO validation_reference_keys VALUES(?,?,?)",
                                arrayOf(foreignKey.parentTable, columnsJson, valuesJson),
                            )
                        } catch (_: Exception) {
                            // Duplicate values cannot satisfy a declared unique FK target.
                            invalidReference()
                        }
                    }
                }
                forEachImportedValues(handle, table) { childValues ->
                    val referenceValues = foreignKey.childColumns.map { childValues[it] }
                    if (referenceValues.any { it == null }) return@forEachImportedValues
                    if (!stagedForeignKeyExists(handle, foreignKey, referenceValues) &&
                        !localForeignKeyExists(localDb, foreignKey, referenceValues)) {
                        invalidReference()
                    }
                }
            }
        }
    }

internal fun BackupImportStore.validateInvoiceClaimRules(localDb: SQLiteDatabase, handle: RevisionValidatorHandle) {
        val db = handle.database
        db.execSQL("CREATE TABLE validation_invoice_jobs(id TEXT PRIMARY KEY,job_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE validation_time_claims(id TEXT PRIMARY KEY,job_id TEXT NOT NULL,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,released_ms INTEGER)")
        db.execSQL("CREATE INDEX validation_time_claim_scope ON validation_time_claims(job_id,start_ms,end_ms)")
        forEachImportedValues(handle, "invoices") { values ->
            db.execSQL(
                "INSERT INTO validation_invoice_jobs VALUES(?,?)",
                arrayOf(revisionString(values["id"]), revisionString(values["job_id"])),
            )
        }
        listOf("invoice_task_claims", "invoice_time_claims").forEach { table ->
            forEachImportedValues(handle, table) { values ->
                val claimJob = revisionString(values["job_id"])
                val invoiceId = revisionString(values["invoice_id"])
                val importedInvoiceJob = db.rawQuery(
                    "SELECT job_id FROM validation_invoice_jobs WHERE id=?",
                    arrayOf(invoiceId),
                ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
                val invoiceJob = importedInvoiceJob ?: localDb.rawQuery(
                    "SELECT job_id FROM invoices WHERE id=?",
                    arrayOf(invoiceId),
                ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
                if (invoiceJob != claimJob) invalidReference()
                if (table == "invoice_time_claims") {
                    val released = values["released_ms"]?.let(::revisionInteger)
                    db.execSQL(
                        "INSERT INTO validation_time_claims VALUES(?,?,?,?,?)",
                        arrayOf<Any?>(
                            revisionString(values["id"]), claimJob,
                            revisionInteger(values["start_ms"]), revisionInteger(values["end_ms"]), released,
                        ),
                    )
                }
            }
        }
        val stagedOverlap = scalarLong(
            db,
            "SELECT COUNT(*) FROM validation_time_claims a JOIN validation_time_claims b " +
                "ON a.job_id=b.job_id AND a.id<b.id AND a.start_ms<b.end_ms AND a.end_ms>b.start_ms " +
                "WHERE a.released_ms IS NULL AND b.released_ms IS NULL",
        )
        if (stagedOverlap != 0L) invalidReference()
        db.rawQuery(
            "SELECT id,job_id,start_ms,end_ms FROM validation_time_claims WHERE released_ms IS NULL",
            null,
        ).use { incomingClaims ->
            while (incomingClaims.moveToNext()) {
                val id = incomingClaims.getString(0)
                val jobId = incomingClaims.getString(1)
                val start = incomingClaims.getLong(2)
                val end = incomingClaims.getLong(3)
                val overlapsLocal = localDb.rawQuery(
                    "SELECT 1 FROM invoice_time_claims WHERE job_id=? AND id<>? AND released_ms IS NULL " +
                        "AND start_ms<? AND end_ms>? LIMIT 1",
                    arrayOf(jobId, id, end.toString(), start.toString()),
                ).use { it.moveToFirst() }
                if (overlapsLocal) invalidReference()
            }
        }
    }

internal fun BackupImportStore.foreignKeySpecs(
        db: SQLiteDatabase,
        table: String,
        catalog: Map<String, BackupExportStore.ImportTable>,
    ): List<RevisionValidatorForeignKeySpec> {
        data class Column(val sequence: Int, val child: String, val parent: String?)
        data class Group(val parentTable: String, val columns: MutableList<Column>)

        val groups = linkedMapOf<Int, Group>()
        db.rawQuery("PRAGMA foreign_key_list(${quote(table)})", null).use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getInt(0)
                val parentTable = rows.getString(2)
                val group = groups.getOrPut(id) { Group(parentTable, mutableListOf()) }
                group.columns.add(Column(rows.getInt(1), rows.getString(3), if (rows.isNull(4)) null else rows.getString(4)))
            }
        }
        return groups.values.map { group ->
            val ordered = group.columns.sortedBy { it.sequence }
            val parentColumns = if (ordered.all { it.parent == null }) {
                catalog[group.parentTable]?.primaryKey ?: tablePrimaryKey(db, group.parentTable)
            } else {
                if (ordered.any { it.parent == null }) invalidReference()
                ordered.map { requireNotNull(it.parent) }
            }
            if (parentColumns.size != ordered.size || parentColumns.isEmpty()) invalidReference()
            RevisionValidatorForeignKeySpec(group.parentTable, ordered.map { it.child }, parentColumns)
        }
    }

internal fun BackupImportStore.tablePrimaryKey(db: SQLiteDatabase, table: String): List<String> {
        val columns = mutableListOf<Pair<Int, String>>()
        db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { rows ->
            while (rows.moveToNext()) {
                val order = rows.getInt(5)
                if (order > 0) columns.add(order to rows.getString(1))
            }
        }
        return columns.sortedBy { it.first }.map { it.second }
    }

internal fun BackupImportStore.stagedForeignKeyExists(
        handle: RevisionValidatorHandle,
        foreignKey: RevisionValidatorForeignKeySpec,
        values: List<Any?>,
    ): Boolean {
        val parentSpec = handle.catalog[foreignKey.parentTable] ?: return false
        if (foreignKey.parentColumns.toSet() == parentSpec.primaryKey.toSet()) {
            val orderedValues = parentSpec.primaryKey.map { parentColumn ->
                values[foreignKey.parentColumns.indexOf(parentColumn)]
            }
            val key = jsonArray(orderedValues)
            return handle.database.rawQuery(
                "SELECT 1 FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
                arrayOf(foreignKey.parentTable, key),
            ).use { it.moveToFirst() }
        }
        return handle.database.rawQuery(
            "SELECT 1 FROM validation_reference_keys WHERE parent_table=? AND columns_json=? AND values_json=? LIMIT 1",
            arrayOf(
                foreignKey.parentTable,
                JSONArray(foreignKey.parentColumns).toString(),
                jsonArray(values),
            ),
        ).use { it.moveToFirst() }
    }

internal fun BackupImportStore.localForeignKeyExists(
        db: SQLiteDatabase,
        foreignKey: RevisionValidatorForeignKeySpec,
        values: List<Any?>,
    ): Boolean {
        if (values.any { it is ByteArray }) return false
        val where = foreignKey.parentColumns.joinToString(" AND ") { quote(it) + "=?" }
        val args = values.map { it.toString() }.toTypedArray()
        return try {
            db.rawQuery(
                "SELECT 1 FROM ${quote(foreignKey.parentTable)} WHERE $where LIMIT 1",
                args,
            ).use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

internal fun BackupImportStore.validateAppliedState(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA foreign_key_check", null).use { rows ->
            if (rows.moveToFirst()) throw RevisionValidatorFailure("APPLY_FAILED", "The backup could not be applied atomically.")
        }
        val liveJobsWithDeletionFence = scalarLong(
            db,
            "SELECT COUNT(*) FROM jobs j JOIN job_deletions d ON d.id=j.id",
        )
        val invalidJobDeletionFences = scalarLong(
            db,
            "SELECT COUNT(*) FROM job_deletions d " +
                "LEFT JOIN entity_revisions r ON r.id=d.revision_id " +
                "LEFT JOIN entity_heads h ON h.kind='job' AND h.entity_id=d.id " +
                "WHERE r.id IS NULL OR r.kind<>'job' OR r.entity_id<>d.id OR r.deleted<>1 " +
                "OR h.revision_id IS NOT d.revision_id",
        )
        if (liveJobsWithDeletionFence != 0L || invalidJobDeletionFences != 0L) {
            throw RevisionValidatorFailure("APPLY_FAILED", "The backup could not be applied atomically.")
        }
        val invalidRangeHeads = scalarLong(
            db,
            "SELECT COUNT(*) FROM location_deletion_ranges d LEFT JOIN entity_heads h " +
                "ON h.kind='location_deletion_range' AND h.entity_id=d.id AND h.revision_id=d.revision_id " +
                "WHERE h.entity_id IS NULL",
        )
        val orphanedRangeRevisions = scalarLong(
            db,
            "SELECT COUNT(*) FROM entity_revisions r WHERE r.kind='location_deletion_range' AND NOT EXISTS(" +
                "SELECT 1 FROM location_deletion_ranges d WHERE d.id=r.entity_id AND d.revision_id=r.id)",
        )
        val livePointHeadsWithoutPoints = scalarLong(
            db,
            "SELECT COUNT(*) FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind='location' AND r.deleted=0 AND NOT EXISTS(" +
                "SELECT 1 FROM location_points p WHERE p.id=h.entity_id)",
        )
        val pointsWithoutLiveHeads = scalarLong(
            db,
            "SELECT COUNT(*) FROM location_points p LEFT JOIN entity_heads h " +
                "ON h.kind='location' AND h.entity_id=p.id " +
                "LEFT JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.revision_id IS NULL OR r.deleted<>0",
        )
        val coveredPointsWithoutCausalParent = scalarLong(
            db,
            "SELECT COUNT(*) FROM location_points p JOIN sessions s ON s.id=p.session_id " +
                "JOIN entity_heads h ON h.kind='location' AND h.entity_id=p.id " +
                "JOIN location_deletion_ranges d ON p.measured_ms>=d.start_ms AND p.measured_ms<d.end_ms " +
                "AND (d.job_id IS NULL OR d.job_id=s.job_id) " +
                "WHERE NOT EXISTS(SELECT 1 FROM revision_parents rp " +
                "WHERE rp.revision_id=h.revision_id AND rp.parent_id=d.revision_id)",
        )
        if (invalidRangeHeads != 0L || orphanedRangeRevisions != 0L ||
            livePointHeadsWithoutPoints != 0L || pointsWithoutLiveHeads != 0L ||
            coveredPointsWithoutCausalParent != 0L) {
            throw RevisionValidatorFailure("APPLY_FAILED", "The backup could not be applied atomically.")
        }
        validateAppliedRevisionGraph(db)
    }

internal fun BackupImportStore.validateAppliedRevisionGraph(db: SQLiteDatabase) {
        revisionValidationTables.forEach { table -> db.execSQL("DROP TABLE IF EXISTS ${quote(table)}") }
        try {
            db.execSQL(
                "CREATE TABLE validation_revisions(" +
                    "id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,job_id TEXT," +
                    "deleted INTEGER NOT NULL,origin TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE validation_parents(" +
                    "revision_id TEXT NOT NULL,parent_id TEXT NOT NULL,PRIMARY KEY(revision_id,parent_id))",
            )
            db.execSQL(
                "INSERT INTO validation_revisions SELECT id,kind,entity_id,job_id,deleted,origin FROM entity_revisions",
            )
            db.execSQL("INSERT INTO validation_parents SELECT revision_id,parent_id FROM revision_parents")
            val invalidHeadCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM entity_heads h LEFT JOIN entity_revisions r ON r.id=h.revision_id " +
                    "WHERE r.id IS NULL OR r.kind<>h.kind OR r.entity_id<>h.entity_id",
            )
            val missingEntityHeadCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM entity_revisions r WHERE NOT EXISTS(" +
                    "SELECT 1 FROM entity_heads h WHERE h.kind=r.kind AND h.entity_id=r.entity_id)",
            )
            val invalidParentCount = scalarLong(
                db,
                "SELECT COUNT(*) FROM revision_parents p " +
                    "LEFT JOIN entity_revisions child ON child.id=p.revision_id " +
                    "LEFT JOIN entity_revisions parent ON parent.id=p.parent_id " +
                    "WHERE child.id IS NULL OR parent.id IS NULL OR NOT ((child.kind=parent.kind AND child.entity_id=parent.entity_id) OR " +
                    "(child.kind='location' AND child.deleted=1 AND child.origin='GPS_RANGE_DELETE' " +
                    "AND child.job_id IS NOT NULL AND parent.kind='location_deletion_range' " +
                    "AND parent.deleted=0 AND (parent.job_id IS NULL OR parent.job_id=child.job_id)))",
            )
            if (invalidHeadCount != 0L || missingEntityHeadCount != 0L || invalidParentCount != 0L) {
                appliedGraphInvalid()
            }
            validateRevisionAcyclicity(db)
        } catch (failure: RevisionValidatorFailure) {
            if (failure.code == "APPLY_FAILED") throw failure
            appliedGraphInvalid()
        } catch (_: Exception) {
            appliedGraphInvalid()
        } finally {
            revisionValidationTables.forEach { table ->
                try { db.execSQL("DROP TABLE IF EXISTS ${quote(table)}") } catch (_: Exception) { }
            }
        }
    }
