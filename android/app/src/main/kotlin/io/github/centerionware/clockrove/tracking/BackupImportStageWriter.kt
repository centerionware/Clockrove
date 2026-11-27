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
internal typealias StageWriterFailure = BackupImportStore.Failure
internal typealias StageWriterStarted = BackupImportStore.Started
internal typealias StageWriterPreview = BackupImportStore.Preview
internal typealias StageWriterConflictPage = BackupImportStore.ConflictPage
internal typealias StageWriterApplied = BackupImportStore.Applied
internal typealias StageWriterExpected = BackupImportStore.Expected
internal typealias StageWriterHandle = BackupImportStore.Handle
internal typealias StageWriterNormalizedRow = BackupImportStore.NormalizedRow
internal typealias StageWriterApplyRow = BackupImportStore.ApplyRow
internal typealias StageWriterForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias StageWriterHeadAdvance = BackupImportStore.HeadAdvance
internal typealias StageWriterDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias StageWriterPlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias StageWriterPlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias StageWriterOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias StageWriterRevisionContent = BackupImportStore.RevisionContent
internal typealias StageWriterRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias StageWriterValidationColumn = BackupImportStore.ValidationColumn
internal typealias StageWriterBlobField = BackupImportStore.BlobField
internal typealias StageWriterLocalRow = BackupImportStore.LocalRow
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


internal fun BackupImportStore.createStageTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE import_rows(ordinal INTEGER PRIMARY KEY,table_name TEXT NOT NULL,primary_key TEXT NOT NULL,row_json TEXT NOT NULL,session_start_ms INTEGER,session_end_ms INTEGER,UNIQUE(table_name,primary_key))")
        db.execSQL("CREATE INDEX import_session_bounds ON import_rows(table_name,session_start_ms,session_end_ms)")
        db.execSQL("CREATE TABLE import_blobs(blob_id TEXT PRIMARY KEY,table_name TEXT NOT NULL,row_ordinal INTEGER NOT NULL,column_name TEXT NOT NULL,size_bytes INTEGER NOT NULL,received_bytes INTEGER NOT NULL,file_offset INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX import_blob_row ON import_blobs(row_ordinal,column_name)")
        db.execSQL("CREATE TABLE import_conflicts(ordinal INTEGER PRIMARY KEY,conflict_id TEXT NOT NULL UNIQUE,row_ordinal INTEGER NOT NULL UNIQUE,selected_choice TEXT,conflict_json TEXT NOT NULL)")
    }

internal fun BackupImportStore.normalizeRecord(handle: StageWriterHandle, input: Any?): StageWriterNormalizedRow {
        val record = input as? Map<*, *> ?: throw StageWriterFailure("INVALID_ARCHIVE", "A backup record is invalid.")
        if (record.keys != setOf("type", "table", "values", "blobs") || record["type"] != "row") {
            throw StageWriterFailure("INVALID_ARCHIVE", "A backup record has an unsupported format.")
        }
        val table = record["table"] as? String ?: throw StageWriterFailure("INVALID_ARCHIVE", "A backup table is invalid.")
        val spec = handle.catalog[table] ?: throw StageWriterFailure("TABLE_NOT_ALLOWED", "The backup contains an unsupported table.")
        val rawValues = record["values"] as? Map<*, *> ?: throw StageWriterFailure("INVALID_ARCHIVE", "A backup row is invalid.")
        if (rawValues.keys.any { it !is String }) throw StageWriterFailure("INVALID_ARCHIVE", "A backup column is invalid.")
        val values = rawValues.entries.associate { (key, value) ->
            key as String to normalizeScalar(value)
        }
        val rawBlobs = record["blobs"] as? List<*> ?: throw StageWriterFailure("INVALID_ARCHIVE", "A backup media list is invalid.")
        val blobs = rawBlobs.map { raw ->
            val descriptor = raw as? Map<*, *> ?: throw StageWriterFailure("INVALID_ARCHIVE", "A backup media reference is invalid.")
            if (descriptor.keys != setOf("id", "column", "sizeBytes")) {
                throw StageWriterFailure("INVALID_ARCHIVE", "A backup media reference has an unsupported format.")
            }
            val id = descriptor["id"] as? String ?: throw StageWriterFailure("INVALID_ARCHIVE", "A backup media ID is invalid.")
            val column = descriptor["column"] as? String ?: throw StageWriterFailure("INVALID_ARCHIVE", "A backup media column is invalid.")
            val size = integer(descriptor["sizeBytes"], "media size")
            val maximumSize = maximumBlobBytes(table, column)
            if (!blobIdPattern.matches(id) || column !in spec.blobColumns || maximumSize == null ||
                size !in 1L..maximumSize) {
                throw StageWriterFailure("INVALID_ARCHIVE", "A backup media reference is invalid.")
            }
            StageWriterBlobField(id, column, size)
        }
        if (blobs.map { it.column }.toSet().size != blobs.size) {
            throw StageWriterFailure("INVALID_ARCHIVE", "A backup row repeats a media column.")
        }
        val blobColumnsWithData = blobs.map { it.column }.toSet()
        val requiredValueColumns = spec.valueColumns + (spec.blobColumns - blobColumnsWithData)
        if (values.keys != requiredValueColumns ||
            blobs.any { it.column in values && values[it.column] != null } ||
            spec.primaryKey.any { it !in values || values[it] == null }) {
            throw StageWriterFailure("INVALID_ARCHIVE", "A backup row does not match its table schema.")
        }
        val valuesJson = JSONObject()
        values.toSortedMap().forEach { (key, value) -> valuesJson.put(key, value ?: JSONObject.NULL) }
        val blobJson = JSONArray()
        blobs.forEach { blob ->
            blobJson.put(JSONObject().put("id", blob.id).put("column", blob.column).put("sizeBytes", blob.sizeBytes))
        }
        val rowJson = JSONObject().put("type", "row").put("table", table)
            .put("values", valuesJson).put("blobs", blobJson).toString()
        val rowBytes = rowJson.toByteArray(StandardCharsets.UTF_8).size
        if (rowBytes !in 1..BackupExportStore.MAX_ROW_BYTES) {
            throw StageWriterFailure("ROW_LIMIT_EXCEEDED", "A backup record exceeds the supported size.")
        }
        val keyJson = JSONArray().apply {
            spec.primaryKey.forEach { put(values[it]) }
        }.toString()
        val start = if (table == "sessions") integer(values["start_ms"], "session start") else null
        val end = if (table == "sessions") {
            if (values["end_ms"] == null) null else integer(values["end_ms"], "session end")
        } else null
        if (table == "sessions" && (requireNotNull(start) < 0 || (end != null && end <= start))) {
            throw StageWriterFailure("INVALID_ARCHIVE", "A backup session interval is invalid.")
        }
        return StageWriterNormalizedRow(table, values, keyJson, rowJson, rowBytes, blobs, start, end)
    }

internal fun BackupImportStore.normalizeScalar(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long -> value
        is Double -> if (value.isNaN() || value.isInfinite()) {
            throw StageWriterFailure("INVALID_ARCHIVE", "A backup number is invalid.")
        } else value
        is Short -> value.toInt()
        is Byte -> value.toInt()
        else -> throw StageWriterFailure("INVALID_ARCHIVE", "A backup cell has an unsupported value.")
    }

internal fun BackupImportStore.maximumBlobBytes(table: String, column: String): Long? = when {
        table == "invoices" && column == "pdf" -> InvoiceBlobs.MAX_DOCUMENT_BYTES.toLong()
        table == "invoice_copies" && column == "pdf" -> InvoiceBlobs.MAX_DOCUMENT_BYTES.toLong()
        table == "quote_pdfs" && column == "bytes" -> InvoiceBlobs.MAX_DOCUMENT_BYTES.toLong()
        table == "logo_assets" && column == "bytes" -> ImageLimits.NORMALIZED_BYTES.toLong()
        table == "task_attachments" && column == "data" -> ImageLimits.NORMALIZED_BYTES.toLong()
        else -> null
    }

internal fun BackupImportStore.putScalar(content: ContentValues, column: String, value: Any?) {
        when (value) {
            null -> content.putNull(column)
            is String -> content.put(column, value)
            is Boolean -> content.put(column, if (value) 1 else 0)
            is Byte -> content.put(column, value.toInt())
            is Short -> content.put(column, value.toInt())
            is Int -> content.put(column, value)
            is Long -> content.put(column, value)
            is Float -> content.put(column, value.toDouble())
            is Double -> content.put(column, value)
            else -> throw StageWriterFailure("INVALID_ARCHIVE", "A backup cell has an unsupported value.")
        }
    }

internal fun BackupImportStore.stagedBlobs(handle: StageWriterHandle, rowOrdinal: Long): Map<String, LongArray> =
        handle.database.rawQuery(
            "SELECT column_name,file_offset,size_bytes FROM import_blobs WHERE row_ordinal=?",
            arrayOf(rowOrdinal.toString()),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), longArrayOf(cursor.getLong(1), cursor.getLong(2)))
            }
        }

internal fun BackupImportStore.applyContentValues(
        handle: StageWriterHandle,
        row: StageWriterApplyRow,
        existingSiteRegistrationKey: String? = null,
): ContentValues {
        val values = row.values.toMutableMap()
        if (row.table == "automation_policies") {
            values["enabled"] = 0
            values["auto_start"] = 0
            values["facts_since_ms"] = 0L
        }
        if (row.table == "work_sites") {
            values["enabled"] = 0
            values["registration_key"] = existingSiteRegistrationKey ?: UUID.randomUUID().toString()
        }
        val content = ContentValues()
        values.forEach { (column, value) -> putScalar(content, column, value) }
        row.blobs.forEach { (column, source) ->
            val maximumSize = maximumBlobBytes(row.table, column)
            if (maximumSize == null || source[1] !in 1L..maximumSize || source[1] > Int.MAX_VALUE) {
                throw StageWriterFailure("APPLY_LIMIT_EXCEEDED", "A backup media item is too large to restore.")
            }
            val bytes = ByteArray(source[1].toInt())
            RandomAccessFile(handle.mediaFile, "r").use { input ->
                input.seek(source[0])
                input.readFully(bytes)
            }
            content.put(column, bytes)
        }
        return content
    }

internal fun BackupImportStore.beginImportCore(
        db: SQLiteDatabase,
        archiveFormatVersion: Int,
        schemaVersion: Int,
        archiveDigest: String,
        recordCount: Long,
        mediaCount: Long,
        mediaBytes: Long,
    ): StageWriterStarted {
        reapExpired()
        ensureDirectory()
        retryPendingCleanup()
        if (active != null) throw StageWriterFailure("IMPORT_BUSY", "A backup import is already staged.")
        if (archiveFormatVersion !in 1..BackupExportStore.ARCHIVE_FORMAT_VERSION ||
            schemaVersion != BackupExportStore.CURRENT_SCHEMA_VERSION || db.version != schemaVersion) {
            throw StageWriterFailure("SCHEMA_UNSUPPORTED", "This backup version is not supported.")
        }
        if (!digestPattern.matches(archiveDigest) || recordCount !in 0L..BackupExportStore.MAX_RECORDS ||
            mediaCount !in 0L..BackupExportStore.MAX_MEDIA_COUNT.toLong() ||
            mediaBytes !in 0L..BackupExportStore.MAX_SNAPSHOT_BYTES) {
            throw StageWriterFailure("INVALID_ARCHIVE", "The backup metadata is invalid.")
        }

        val catalog = catalogProvider(db)
        val id = UUID.randomUUID().toString()
        val secret = ByteArray(32).also(random::nextBytes)
        val databaseFile = File(directory, FILE_PREFIX + id + ".db")
        val mediaFile = File(directory, FILE_PREFIX + id + "-media.bin")
        var stagedDb: SQLiteDatabase? = null
        try {
            if (!databaseFile.createNewFile() || !mediaFile.createNewFile()) {
                throw StageWriterFailure("STORAGE_UNAVAILABLE", "The backup import could not be staged.")
            }
            stagedDb = SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            stagedDb.setForeignKeyConstraintsEnabled(false)
            stagedDb.setMaximumSize(MAX_STAGE_DATABASE_BYTES)
            createStageTables(stagedDb)
            val handle = StageWriterHandle(
                id, stagedDb, databaseFile, mediaFile, catalog,
                StageWriterExpected(archiveFormatVersion, schemaVersion, archiveDigest, recordCount, mediaCount, mediaBytes),
                secret, lastAccessElapsedMillis = elapsedClock(),
            )
            active = handle
            scheduleExpiry(handle)
            return StageWriterStarted(
                id, archiveFormatVersion, schemaVersion, MAX_PAGE_BYTES,
                MAX_BLOB_CHUNK_BYTES, idleTtlMillis,
            )
        } catch (failure: StageWriterFailure) {
            if (active?.id == id) {
                active = null
                cancelExpiry()
            }
            stagedDb?.close()
            secret.fill(0)
            if (!deleteArtifacts(id)) scheduleCleanupRetry()
            throw failure
        } catch (_: Exception) {
            if (active?.id == id) {
                active = null
                cancelExpiry()
            }
            stagedDb?.close()
            secret.fill(0)
            if (!deleteArtifacts(id)) scheduleCleanupRetry()
            throw StageWriterFailure("STORAGE_UNAVAILABLE", "The backup import could not be staged.")
        }
    }

internal fun BackupImportStore.appendPageCore(
        importId: String,
        firstOrdinal: Long,
        records: List<*>,
        encodedRequestBytes: Int,
    ): Long {
        val handle = requireHandle(importId)
        if (handle.preview != null) throw StageWriterFailure("IMPORT_FINALIZED", "The backup import is already finalized.")
        if (encodedRequestBytes !in 1..MAX_REQUEST_BYTES || records.isEmpty() || records.size > MAX_ROW_COUNT_PER_PAGE) {
            throw StageWriterFailure("PAGE_LIMIT_EXCEEDED", "The import page exceeds the supported size.")
        }
        if (firstOrdinal != handle.recordCount || handle.recordCount + records.size > handle.expected.recordCount) {
            throw StageWriterFailure("INVALID_PAGE_SEQUENCE", "The backup records are not contiguous.")
        }

        val normalized = records.map { normalizeRecord(handle, it) }
        val pageRowBytes = normalized.fold(0L) { total, row -> Math.addExact(total, row.rowBytes.toLong() + Int.SIZE_BYTES) }
        val pageMediaCount = normalized.sumOf { it.blobFields.size.toLong() }
        val pageMediaBytes = normalized.flatMap { it.blobFields }.fold(0L) { total, blob ->
            Math.addExact(total, blob.sizeBytes)
        }
        val newRawBytes = Math.addExact(handle.rawBytes, Math.addExact(pageRowBytes, pageMediaBytes))
        if (newRawBytes > BackupExportStore.MAX_SNAPSHOT_BYTES ||
            handle.mediaCount + pageMediaCount > handle.expected.mediaCount ||
            handle.allocatedMediaBytes + pageMediaBytes > handle.expected.mediaBytes) {
            throw StageWriterFailure("SNAPSHOT_LIMIT_EXCEEDED", "The backup exceeds supported staging limits.")
        }

        val oldMediaLength = handle.allocatedMediaBytes
        var nextMediaOffset = oldMediaLength
        var ordinal = handle.recordCount
        var transactionStarted = false
        try {
            handle.database.beginTransaction()
            transactionStarted = true
            normalized.forEach { row ->
                val rowValues = ContentValues().apply {
                    put("ordinal", ordinal)
                    put("table_name", row.table)
                    put("primary_key", row.primaryKeyJson)
                    put("row_json", row.rowJson)
                    put("session_start_ms", row.sessionStartMillis)
                    put("session_end_ms", row.sessionEndMillis)
                }
                try {
                    handle.database.insertOrThrow("import_rows", null, rowValues)
                } catch (_: SQLiteConstraintException) {
                    throw StageWriterFailure("DUPLICATE_RECORD", "The backup contains duplicate row identifiers.")
                }
                row.blobFields.forEach { blob ->
                    val blobValues = ContentValues().apply {
                        put("blob_id", blob.id)
                        put("table_name", row.table)
                        put("row_ordinal", ordinal)
                        put("column_name", blob.column)
                        put("size_bytes", blob.sizeBytes)
                        put("received_bytes", 0L)
                        put("file_offset", nextMediaOffset)
                    }
                    try {
                        handle.database.insertOrThrow("import_blobs", null, blobValues)
                    } catch (_: SQLiteConstraintException) {
                        throw StageWriterFailure("DUPLICATE_BLOB", "The backup contains duplicate media identifiers.")
                    }
                    nextMediaOffset = Math.addExact(nextMediaOffset, blob.sizeBytes)
                }
                ordinal++
            }
            RandomAccessFile(handle.mediaFile, "rw").use { it.setLength(nextMediaOffset) }
            if (File(handle.database.path).length() > MAX_STAGE_DATABASE_BYTES ||
                stagedPhysicalBytes(handle) > MAX_STAGE_PHYSICAL_BYTES) {
                throw StageWriterFailure("SNAPSHOT_LIMIT_EXCEEDED", "The backup staging database exceeds its limit.")
            }
            handle.database.setTransactionSuccessful()
            handle.database.endTransaction()
            transactionStarted = false
        } catch (failure: StageWriterFailure) {
            if (transactionStarted) handle.database.endTransaction()
            RandomAccessFile(handle.mediaFile, "rw").use { it.setLength(oldMediaLength) }
            throw failure
        } catch (_: Exception) {
            if (transactionStarted) handle.database.endTransaction()
            try { RandomAccessFile(handle.mediaFile, "rw").use { it.setLength(oldMediaLength) } } catch (_: Exception) { }
            throw StageWriterFailure("STORAGE_UNAVAILABLE", "A backup record could not be staged.")
        }
        handle.recordCount += records.size
        handle.mediaCount += pageMediaCount
        handle.allocatedMediaBytes = nextMediaOffset
        handle.rawBytes = newRawBytes
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
        return handle.recordCount
    }

internal fun BackupImportStore.writeBlobCore(
        importId: String,
        blobId: String,
        offset: Long,
        bytes: ByteArray,
        encodedRequestBytes: Int,
    ): Long {
        val handle = requireHandle(importId)
        if (handle.preview != null) throw StageWriterFailure("IMPORT_FINALIZED", "The backup import is already finalized.")
        if (!blobIdPattern.matches(blobId) || offset < 0 || bytes.size > MAX_BLOB_CHUNK_BYTES ||
            encodedRequestBytes !in 1..MAX_REQUEST_BYTES) {
            throw StageWriterFailure("INVALID_BLOB", "The media chunk is invalid.")
        }
        val blob = handle.database.rawQuery(
            "SELECT file_offset,size_bytes,received_bytes FROM import_blobs WHERE blob_id=?",
            arrayOf(blobId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw StageWriterFailure("INVALID_BLOB", "The media handle is not available.")
            longArrayOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
        }
        val fileOffset = blob[0]
        val expected = blob[1]
        val received = blob[2]
        if (offset != received || bytes.isEmpty() || received + bytes.size > expected) {
            throw StageWriterFailure("INVALID_BLOB_OFFSET", "The media offset is not valid.")
        }
        try {
            RandomAccessFile(handle.mediaFile, "rw").use { output ->
                output.seek(fileOffset + offset)
                output.write(bytes)
            }
            val update = ContentValues().apply { put("received_bytes", received + bytes.size) }
            handle.database.update("import_blobs", update, "blob_id=?", arrayOf(blobId))
        } catch (_: Exception) {
            throw StageWriterFailure("STORAGE_UNAVAILABLE", "The media chunk could not be staged.")
        }
        handle.receivedMediaBytes += bytes.size
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
        return received + bytes.size
    }
