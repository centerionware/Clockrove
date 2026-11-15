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
internal class BackupImportStore(
    context: Context,
    private val catalogProvider: (SQLiteDatabase) -> Map<String, BackupExportStore.ImportTable>,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val deleteFile: (File) -> Boolean = { it.delete() },
    private val idleTtlMillis: Long = BackupExportStore.IDLE_EXPIRY_MILLIS,
    private val scheduler: BackupScheduler = ExecutorBackupScheduler,
) {
    data class Started(
        val importId: String,
        val archiveFormatVersion: Int,
        val schemaVersion: Int,
        val maxPageBytes: Int,
        val maxBlobChunkBytes: Int,
        val idleExpiryMillis: Long,
    )

    data class Preview(
        val importId: String,
        val previewToken: String,
        val schemaVersion: Int,
        val localStateVersion: Long,
        val recordCount: Long,
        val mediaCount: Long,
        val mediaBytes: Long,
        val addedCount: Long,
        val unchangedCount: Long,
        val conflictCount: Long,
        val openSessionCount: Long,
        val localOpenSessionCount: Long,
        val applyReady: Boolean,
    )

    data class ConflictPage(
        val importId: String,
        val previewToken: String,
        val nextCursor: String?,
        val done: Boolean,
        val conflicts: List<Map<String, Any?>>,
    )

    class Failure(val code: String, message: String) : IllegalStateException(message)

    private data class Expected(
        val archiveFormatVersion: Int,
        val schemaVersion: Int,
        val archiveDigest: String,
        val recordCount: Long,
        val mediaCount: Long,
        val mediaBytes: Long,
    )

    private data class Handle(
        val id: String,
        val database: SQLiteDatabase,
        val databaseFile: File,
        val mediaFile: File,
        val catalog: Map<String, BackupExportStore.ImportTable>,
        val expected: Expected,
        val secret: ByteArray,
        var recordCount: Long = 0,
        var mediaCount: Long = 0,
        var allocatedMediaBytes: Long = 0,
        var receivedMediaBytes: Long = 0,
        var rawBytes: Long = 0,
        var lastAccessElapsedMillis: Long,
        var preview: Preview? = null,
    )

    private data class NormalizedRow(
        val table: String,
        val values: Map<String, Any?>,
        val primaryKeyJson: String,
        val rowJson: String,
        val rowBytes: Int,
        val blobFields: List<BlobField>,
        val sessionStartMillis: Long?,
        val sessionEndMillis: Long?,
    )

    private data class BlobField(val id: String, val column: String, val sizeBytes: Long)

    companion object {
        const val MAX_REQUEST_BYTES = BackupExportStore.MAX_PAGE_BYTES
        const val MAX_PAGE_BYTES = MAX_REQUEST_BYTES - 1024
        const val MAX_BLOB_CHUNK_BYTES = 255 * 1024
        const val MAX_ROW_COUNT_PER_PAGE = 512
        const val MAX_CONFLICT_PAGE_BYTES = 256 * 1024
        private const val MIN_CONFLICT_PAGE_BYTES = 1024
        private const val STORAGE_CHUNK_BYTES = 32 * 1024
        private const val MAX_STAGE_DATABASE_BYTES = 96L * 1024L * 1024L
        private const val MAX_STAGE_PHYSICAL_BYTES = 128L * 1024L * 1024L
        private const val FILE_PREFIX = "clockrove-import-"
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val blobIdPattern = Regex("[A-Za-z0-9_-]{43}")
        private val digestPattern = Regex("(?:[0-9a-f]{64}|[A-Za-z0-9_-]{43})")
        private val immutableTables = setOf(
            "entity_revisions", "revision_parents", "job_deletions", "location_points",
            "location_deletion_ranges", "task_comment_events", "invoices", "invoice_copies",
            "invoice_payments", "invoice_payment_events",
            "invoice_payment_reversals", "invoice_voids", "quote_revisions", "quote_decisions",
            "quote_pdfs", "quote_invoice_links", "quotes", "quote_scope_lines", "quote_scope_revisions",
        )
        private val mergeOnlyTables = setOf("app_settings", "automation_policies", "entity_heads", "quote_counter")
        private val summaryColumns = setOf(
            "id", "name", "job_name", "customer_name", "number", "title", "start_ms", "end_ms",
            "occurred_ms", "amount_minor", "currency", "kind", "category",
        )
        private val uniqueColumns = mapOf(
            "invoices" to listOf(listOf("number")),
            "quotes" to listOf(listOf("number")),
            "quote_pdfs" to listOf(listOf("quote_id", "revision")),
            "location_deletion_ranges" to listOf(listOf("revision_id")),
        )
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir, "clockrove-backup-imports")
    private val random = SecureRandom()
    private val cleanupPending = mutableSetOf<String>()
    private val pendingFiles = mutableSetOf<File>()
    private var active: Handle? = null
    private var expiryTask: BackupScheduledTask? = null
    private var cleanupTask: BackupScheduledTask? = null

    init {
        require(idleTtlMillis > 0)
        try {
            ensureDirectory()
            retryPendingCleanup()
        } catch (_: Exception) {
            // Backup storage errors must not prevent ordinary timekeeping.
        }
    }

    @Synchronized
    fun begin(
        db: SQLiteDatabase,
        archiveFormatVersion: Int,
        schemaVersion: Int,
        archiveDigest: String,
        recordCount: Long,
        mediaCount: Long,
        mediaBytes: Long,
    ): Started {
        reapExpired()
        ensureDirectory()
        retryPendingCleanup()
        if (active != null) throw Failure("IMPORT_BUSY", "A backup import is already staged.")
        if (archiveFormatVersion != BackupExportStore.ARCHIVE_FORMAT_VERSION ||
            schemaVersion != BackupExportStore.CURRENT_SCHEMA_VERSION || db.version != schemaVersion) {
            throw Failure("SCHEMA_UNSUPPORTED", "This backup version is not supported.")
        }
        if (!digestPattern.matches(archiveDigest) || recordCount !in 0L..BackupExportStore.MAX_RECORDS ||
            mediaCount !in 0L..BackupExportStore.MAX_MEDIA_COUNT.toLong() ||
            mediaBytes !in 0L..BackupExportStore.MAX_SNAPSHOT_BYTES) {
            throw Failure("INVALID_ARCHIVE", "The backup metadata is invalid.")
        }

        val catalog = catalogProvider(db)
        val id = UUID.randomUUID().toString()
        val secret = ByteArray(32).also(random::nextBytes)
        val databaseFile = File(directory, FILE_PREFIX + id + ".db")
        val mediaFile = File(directory, FILE_PREFIX + id + "-media.bin")
        var stagedDb: SQLiteDatabase? = null
        try {
            if (!databaseFile.createNewFile() || !mediaFile.createNewFile()) {
                throw Failure("STORAGE_UNAVAILABLE", "The backup import could not be staged.")
            }
            stagedDb = SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            stagedDb.setMaximumSize(MAX_STAGE_DATABASE_BYTES)
            createStageTables(stagedDb)
            val handle = Handle(
                id, stagedDb, databaseFile, mediaFile, catalog,
                Expected(archiveFormatVersion, schemaVersion, archiveDigest, recordCount, mediaCount, mediaBytes),
                secret, lastAccessElapsedMillis = elapsedClock(),
            )
            active = handle
            scheduleExpiry(handle)
            return Started(
                id, archiveFormatVersion, schemaVersion, MAX_PAGE_BYTES,
                MAX_BLOB_CHUNK_BYTES, idleTtlMillis,
            )
        } catch (failure: Failure) {
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
            throw Failure("STORAGE_UNAVAILABLE", "The backup import could not be staged.")
        }
    }

    /** Adds a contiguous bounded page of canonical rows; table and column names are compiled. */
    @Synchronized
    fun appendPage(
        importId: String,
        firstOrdinal: Long,
        records: List<*>,
        encodedRequestBytes: Int,
    ): Long {
        val handle = requireHandle(importId)
        if (handle.preview != null) throw Failure("IMPORT_FINALIZED", "The backup import is already finalized.")
        if (encodedRequestBytes !in 1..MAX_REQUEST_BYTES || records.isEmpty() || records.size > MAX_ROW_COUNT_PER_PAGE) {
            throw Failure("PAGE_LIMIT_EXCEEDED", "The import page exceeds the supported size.")
        }
        if (firstOrdinal != handle.recordCount || handle.recordCount + records.size > handle.expected.recordCount) {
            throw Failure("INVALID_PAGE_SEQUENCE", "The backup records are not contiguous.")
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
            throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup exceeds supported staging limits.")
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
                    throw Failure("DUPLICATE_RECORD", "The backup contains duplicate row identifiers.")
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
                        throw Failure("DUPLICATE_BLOB", "The backup contains duplicate media identifiers.")
                    }
                    nextMediaOffset = Math.addExact(nextMediaOffset, blob.sizeBytes)
                }
                ordinal++
            }
            RandomAccessFile(handle.mediaFile, "rw").use { it.setLength(nextMediaOffset) }
            if (File(handle.database.path).length() > MAX_STAGE_DATABASE_BYTES ||
                stagedPhysicalBytes(handle) > MAX_STAGE_PHYSICAL_BYTES) {
                throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup staging database exceeds its limit.")
            }
            handle.database.setTransactionSuccessful()
            handle.database.endTransaction()
            transactionStarted = false
        } catch (failure: Failure) {
            if (transactionStarted) handle.database.endTransaction()
            RandomAccessFile(handle.mediaFile, "rw").use { it.setLength(oldMediaLength) }
            throw failure
        } catch (_: Exception) {
            if (transactionStarted) handle.database.endTransaction()
            try { RandomAccessFile(handle.mediaFile, "rw").use { it.setLength(oldMediaLength) } } catch (_: Exception) { }
            throw Failure("STORAGE_UNAVAILABLE", "A backup record could not be staged.")
        }
        handle.recordCount += records.size
        handle.mediaCount += pageMediaCount
        handle.allocatedMediaBytes = nextMediaOffset
        handle.rawBytes = newRawBytes
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
        return handle.recordCount
    }

    /** Writes a sequential chunk to an opaque media reference declared by an imported row. */
    @Synchronized
    fun writeBlob(
        importId: String,
        blobId: String,
        offset: Long,
        bytes: ByteArray,
        encodedRequestBytes: Int,
    ): Long {
        val handle = requireHandle(importId)
        if (handle.preview != null) throw Failure("IMPORT_FINALIZED", "The backup import is already finalized.")
        if (!blobIdPattern.matches(blobId) || offset < 0 || bytes.size > MAX_BLOB_CHUNK_BYTES ||
            encodedRequestBytes !in 1..MAX_REQUEST_BYTES) {
            throw Failure("INVALID_BLOB", "The media chunk is invalid.")
        }
        val blob = handle.database.rawQuery(
            "SELECT file_offset,size_bytes,received_bytes FROM import_blobs WHERE blob_id=?",
            arrayOf(blobId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw Failure("INVALID_BLOB", "The media handle is not available.")
            longArrayOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
        }
        val fileOffset = blob[0]
        val expected = blob[1]
        val received = blob[2]
        if (offset != received || bytes.isEmpty() || received + bytes.size > expected) {
            throw Failure("INVALID_BLOB_OFFSET", "The media offset is not valid.")
        }
        try {
            RandomAccessFile(handle.mediaFile, "rw").use { output ->
                output.seek(fileOffset + offset)
                output.write(bytes)
            }
            val update = ContentValues().apply { put("received_bytes", received + bytes.size) }
            handle.database.update("import_blobs", update, "blob_id=?", arrayOf(blobId))
        } catch (_: Exception) {
            throw Failure("STORAGE_UNAVAILABLE", "The media chunk could not be staged.")
        }
        handle.receivedMediaBytes += bytes.size
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
        return received + bytes.size
    }

    @Synchronized
    fun finish(db: SQLiteDatabase, importId: String): Preview {
        val handle = requireHandle(importId)
        handle.preview?.let { return it }
        if (handle.recordCount != handle.expected.recordCount ||
            handle.mediaCount != handle.expected.mediaCount ||
            handle.allocatedMediaBytes != handle.expected.mediaBytes ||
            handle.receivedMediaBytes != handle.expected.mediaBytes) {
            throw Failure("INCOMPLETE_ARCHIVE", "The backup records or media are incomplete.")
        }
        val incompleteBlobs = scalarLong(handle.database,
            "SELECT COUNT(*) FROM import_blobs WHERE received_bytes<>size_bytes")
        if (incompleteBlobs != 0L) throw Failure("INCOMPLETE_ARCHIVE", "The backup media are incomplete.")
        RandomAccessFile(handle.mediaFile, "rw").use { it.fd.sync() }
        val preview = buildPreview(db, handle)
        handle.preview = preview
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
        return preview
    }

    @Synchronized
    fun readConflictPage(
        db: SQLiteDatabase,
        importId: String,
        previewToken: String,
        cursor: String?,
        requestedMaxBytes: Int?,
    ): ConflictPage {
        val handle = requireHandle(importId)
        val preview = handle.preview ?: throw Failure("PREVIEW_NOT_READY", "The import preview is not ready.")
        if (!MessageDigest.isEqual(preview.previewToken.toByteArray(), previewToken.toByteArray())) {
            throw Failure("INVALID_PREVIEW_TOKEN", "The import preview token is invalid.")
        }
        if (stateVersion(db) != preview.localStateVersion) {
            throw Failure("PREVIEW_STALE", "Local data changed after this preview; create a new preview.")
        }
        val maxBytes = requestedMaxBytes ?: MAX_CONFLICT_PAGE_BYTES
        if (maxBytes !in MIN_CONFLICT_PAGE_BYTES..MAX_CONFLICT_PAGE_BYTES) {
            throw Failure("INVALID_ARGUMENT", "The conflict page size is invalid.")
        }
        val startIndex = if (cursor == null) 0 else decodeCursor(handle, preview, cursor)
        val total = preview.conflictCount.toInt()
        val conflicts = mutableListOf<Map<String, Any?>>()
        var nextIndex = startIndex
        handle.database.rawQuery(
            "SELECT ordinal,conflict_json FROM import_conflicts WHERE ordinal>=? ORDER BY ordinal",
            arrayOf(startIndex.toString()),
        ).use { rows ->
            while (rows.moveToNext()) {
                val candidate = parseConflict(rows.getString(1))
                conflicts.add(candidate)
                val candidateNext = rows.getInt(0) + 1
                val candidatePage = ConflictPage(
                    importId, preview.previewToken,
                    if (candidateNext < total) encodeCursor(handle, preview, candidateNext) else null,
                    candidateNext >= total, conflicts,
                )
                if (encodedEnvelopeSize(candidatePage.toWireMap()) > maxBytes) {
                    conflicts.removeAt(conflicts.lastIndex)
                    if (conflicts.isEmpty()) throw Failure("PAGE_LIMIT_TOO_SMALL", "One conflict exceeds the requested page size.")
                    break
                }
                nextIndex = candidateNext
            }
        }
        val done = nextIndex >= total
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
        return ConflictPage(
            importId, preview.previewToken,
            if (done) null else encodeCursor(handle, preview, nextIndex),
            done, conflicts,
        )
    }

    @Synchronized
    fun close(importId: String): Boolean {
        val handle = active
        if (handle != null && handle.id == importId) {
            active = null
            cancelExpiry()
            handle.secret.fill(0)
            handle.database.close()
            if (!deleteArtifacts(handle.id)) {
                scheduleCleanupRetry()
                throw Failure("CLEANUP_FAILED", "The staged backup could not be removed.")
            }
            return true
        }
        if (importId in cleanupPending) {
            retryPendingCleanup()
            return true
        }
        return false
    }

    @Synchronized
    fun closeAll() {
        val handle = active
        active = null
        cancelExpiry()
        if (handle != null) {
            handle.secret.fill(0)
            handle.database.close()
            if (!deleteArtifacts(handle.id)) {
                scheduleCleanupRetry()
                throw Failure("CLEANUP_FAILED", "The staged backup could not be removed.")
            }
        }
        retryPendingCleanup()
    }

    private fun createStageTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE import_rows(ordinal INTEGER PRIMARY KEY,table_name TEXT NOT NULL,primary_key TEXT NOT NULL,row_json TEXT NOT NULL,session_start_ms INTEGER,session_end_ms INTEGER,UNIQUE(table_name,primary_key))")
        db.execSQL("CREATE INDEX import_session_bounds ON import_rows(table_name,session_start_ms,session_end_ms)")
        db.execSQL("CREATE TABLE import_blobs(blob_id TEXT PRIMARY KEY,table_name TEXT NOT NULL,row_ordinal INTEGER NOT NULL,column_name TEXT NOT NULL,size_bytes INTEGER NOT NULL,received_bytes INTEGER NOT NULL,file_offset INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX import_blob_row ON import_blobs(row_ordinal,column_name)")
        db.execSQL("CREATE TABLE import_conflicts(ordinal INTEGER PRIMARY KEY,conflict_id TEXT NOT NULL UNIQUE,conflict_json TEXT NOT NULL)")
    }

    private fun normalizeRecord(handle: Handle, input: Any?): NormalizedRow {
        val record = input as? Map<*, *> ?: throw Failure("INVALID_ARCHIVE", "A backup record is invalid.")
        if (record.keys != setOf("type", "table", "values", "blobs") || record["type"] != "row") {
            throw Failure("INVALID_ARCHIVE", "A backup record has an unsupported format.")
        }
        val table = record["table"] as? String ?: throw Failure("INVALID_ARCHIVE", "A backup table is invalid.")
        val spec = handle.catalog[table] ?: throw Failure("TABLE_NOT_ALLOWED", "The backup contains an unsupported table.")
        val rawValues = record["values"] as? Map<*, *> ?: throw Failure("INVALID_ARCHIVE", "A backup row is invalid.")
        if (rawValues.keys.any { it !is String }) throw Failure("INVALID_ARCHIVE", "A backup column is invalid.")
        val values = rawValues.entries.associate { (key, value) ->
            key as String to normalizeScalar(value)
        }
        val rawBlobs = record["blobs"] as? List<*> ?: throw Failure("INVALID_ARCHIVE", "A backup media list is invalid.")
        val blobs = rawBlobs.map { raw ->
            val descriptor = raw as? Map<*, *> ?: throw Failure("INVALID_ARCHIVE", "A backup media reference is invalid.")
            if (descriptor.keys != setOf("id", "column", "sizeBytes")) {
                throw Failure("INVALID_ARCHIVE", "A backup media reference has an unsupported format.")
            }
            val id = descriptor["id"] as? String ?: throw Failure("INVALID_ARCHIVE", "A backup media ID is invalid.")
            val column = descriptor["column"] as? String ?: throw Failure("INVALID_ARCHIVE", "A backup media column is invalid.")
            val size = integer(descriptor["sizeBytes"], "media size")
            if (!blobIdPattern.matches(id) || column !in spec.blobColumns || size < 0) {
                throw Failure("INVALID_ARCHIVE", "A backup media reference is invalid.")
            }
            BlobField(id, column, size)
        }
        if (blobs.map { it.column }.toSet().size != blobs.size) {
            throw Failure("INVALID_ARCHIVE", "A backup row repeats a media column.")
        }
        val blobColumnsWithData = blobs.map { it.column }.toSet()
        val requiredValueColumns = spec.valueColumns + (spec.blobColumns - blobColumnsWithData)
        if (values.keys != requiredValueColumns ||
            blobs.any { it.column in values && values[it.column] != null } ||
            spec.primaryKey.any { it !in values || values[it] == null }) {
            throw Failure("INVALID_ARCHIVE", "A backup row does not match its table schema.")
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
            throw Failure("ROW_LIMIT_EXCEEDED", "A backup record exceeds the supported size.")
        }
        val keyJson = JSONArray().apply {
            spec.primaryKey.forEach { put(values[it]) }
        }.toString()
        val start = if (table == "sessions") integer(values["start_ms"], "session start") else null
        val end = if (table == "sessions") {
            if (values["end_ms"] == null) null else integer(values["end_ms"], "session end")
        } else null
        if (table == "sessions" && (requireNotNull(start) < 0 || (end != null && end <= start))) {
            throw Failure("INVALID_ARCHIVE", "A backup session interval is invalid.")
        }
        return NormalizedRow(table, values, keyJson, rowJson, rowBytes, blobs, start, end)
    }

    private fun normalizeScalar(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long -> value
        is Double -> if (value.isNaN() || value.isInfinite()) {
            throw Failure("INVALID_ARCHIVE", "A backup number is invalid.")
        } else value
        is Short -> value.toInt()
        is Byte -> value.toInt()
        else -> throw Failure("INVALID_ARCHIVE", "A backup cell has an unsupported value.")
    }

    private fun buildPreview(db: SQLiteDatabase, handle: Handle): Preview {
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
                        val spec = handle.catalog.getValue(table)
                        val blobFields = stagedBlobs(handle, ordinal)
                        val local = findLocalRow(db, table, spec, values)
                        val sessionStart = if (rows.isNull(4)) null else rows.getLong(4)
                        val sessionEnd = if (rows.isNull(5)) null else rows.getLong(5)
                        if (table == "sessions" && sessionEnd == null) {
                            openSessions++
                            conflictOrdinal = addConflict(
                                handle, conflictOrdinal, ordinal, table, keyJson, "OPEN_SESSION",
                                listOf("OMIT_OPEN_SESSION", "CLOSE_OPEN_SESSION"), "OMIT_OPEN_SESSION",
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
                                    listOf("KEEP_LOCAL", "OMIT_BACKUP_SESSION", "ADJUST_BACKUP_INTERVAL"),
                                    "KEEP_LOCAL", localIdentifier = overlappingId,
                                    localSummary = mapOf("identifier" to overlappingId),
                                    backupSummary = safeSummary(values),
                                )
                                continue
                            }
                        }
                        if (local != null) {
                            if (sameRecord(db, handle, table, spec, ordinal, values, blobFields, local)) {
                                unchanged++
                            } else {
                                val immutable = table in immutableTables
                                conflictOrdinal = addConflict(
                                    handle, conflictOrdinal, ordinal, table, keyJson,
                                    if (immutable) "IMMUTABLE_RECORD_DIFFERS" else "EXISTING_RECORD_DIFFERS",
                                    when {
                                        immutable -> listOf("KEEP_LOCAL", "OMIT_BACKUP_RECORD")
                                        table in mergeOnlyTables -> listOf("KEEP_LOCAL", "MERGE")
                                        else -> listOf("KEEP_LOCAL", "TAKE_BACKUP")
                                    },
                                    "KEEP_LOCAL",
                                    localSummary = safeSummary(local.values),
                                    backupSummary = safeSummary(values),
                                )
                            }
                            continue
                        }
                        val uniqueConflict = findUniqueConflict(db, table, spec, values)
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
                handle.database.setTransactionSuccessful()
            } finally {
                handle.database.endTransaction()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val token = previewToken(handle, localVersion, conflictOrdinal)
        return Preview(
            handle.id, token, handle.expected.schemaVersion, localVersion,
            handle.recordCount, handle.mediaCount, handle.expected.mediaBytes,
            added, unchanged, conflictOrdinal, openSessions, localOpenSessions, false,
        )
    }

    private data class LocalRow(val values: Map<String, Any?>, val blobSizes: Map<String, Long?>)

    private fun findLocalRow(
        db: SQLiteDatabase,
        table: String,
        spec: BackupExportStore.ImportTable,
        values: Map<String, Any?>,
    ): LocalRow? {
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
            LocalRow(rowValues, sizes)
        }
    }

    private fun sameRecord(
        db: SQLiteDatabase,
        handle: Handle,
        table: String,
        spec: BackupExportStore.ImportTable,
        rowOrdinal: Long,
        values: Map<String, Any?>,
        staged: Map<String, LongArray>,
        local: LocalRow,
    ): Boolean {
        if (canonicalValues(values, spec.valueColumns) != canonicalValues(local.values, spec.valueColumns)) return false
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

    private fun stagedBlobs(handle: Handle, rowOrdinal: Long): Map<String, LongArray> =
        handle.database.rawQuery(
            "SELECT column_name,file_offset,size_bytes FROM import_blobs WHERE row_ordinal=?",
            arrayOf(rowOrdinal.toString()),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), longArrayOf(cursor.getLong(1), cursor.getLong(2)))
            }
        }

    private fun findUniqueConflict(
        db: SQLiteDatabase,
        table: String,
        spec: BackupExportStore.ImportTable,
        values: Map<String, Any?>,
    ): String? {
        uniqueColumns[table].orEmpty().forEach { columns ->
            if (columns.any { values[it] == null }) return@forEach
            val where = columns.joinToString(" AND ") { quote(it) + "=?" }
            val args = columns.map { values.getValue(it).toString() }.toTypedArray()
            val existing = db.rawQuery("SELECT " + spec.primaryKey.joinToString(",") { quote(it) } +
                " FROM " + quote(table) + " WHERE " + where + " LIMIT 1", args).use { cursor ->
                if (!cursor.moveToFirst()) null else (0 until cursor.columnCount).joinToString("|") { cursor.getString(it) }
            }
            if (existing != null) return existing
        }
        return null
    }

    private fun findOverlappingSessionId(
        db: SQLiteDatabase,
        handle: Handle,
        ordinal: Long,
        start: Long,
        end: Long,
        sessionId: String,
    ): String? {
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

    private fun addConflict(
        handle: Handle,
        ordinal: Long,
        rowOrdinal: Long,
        table: String,
        keyJson: String,
        reason: String,
        choices: List<String>,
        defaultChoice: String,
        localIdentifier: String? = null,
        localSummary: Map<String, Any?> = emptyMap(),
        backupSummary: Map<String, Any?> = emptyMap(),
    ): Long {
        val conflictId = conflictId(handle, rowOrdinal, table, keyJson, reason)
        val conflict = JSONObject()
            .put("conflictId", conflictId)
            .put("kind", reason)
            .put("table", table)
            .put("primaryKey", JSONArray(keyJson))
            .put("localIdentifier", localIdentifier ?: JSONObject.NULL)
            .put("localSummary", safeSummaryObject(localSummary))
            .put("backupSummary", safeSummaryObject(backupSummary))
            .put("allowedChoices", JSONArray(choices))
            .put("defaultChoice", defaultChoice)
        handle.database.insertOrThrow("import_conflicts", null, ContentValues().apply {
            put("ordinal", ordinal)
            put("conflict_id", conflictId)
            put("conflict_json", conflict.toString())
        })
        return ordinal + 1
    }

    private fun safeSummary(values: Map<String, Any?>): Map<String, Any?> =
        summaryColumns.sorted().mapNotNull { column ->
            val value = values[column] ?: return@mapNotNull null
            val safe = when (value) {
                is String -> value.take(160)
                is Number, is Boolean -> value
                else -> return@mapNotNull null
            }
            column to safe
        }.toMap()

    private fun safeSummaryObject(values: Map<String, Any?>): JSONObject = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) ->
            if (key in summaryColumns || key == "identifier") put(key, value ?: JSONObject.NULL)
        }
    }

    private fun canonicalValues(values: Map<String, Any?>, columns: Set<String>): String {
        val json = JSONObject()
        columns.sorted().forEach { column -> json.put(column, values[column] ?: JSONObject.NULL) }
        return json.toString()
    }

    private fun digestFile(file: File, offset: Long, size: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            var remaining = size
            val buffer = ByteArray(STORAGE_CHUNK_BYTES)
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                input.readFully(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        return digest.digest()
    }

    private fun digestDatabaseBlob(
        db: SQLiteDatabase,
        table: String,
        column: String,
        spec: BackupExportStore.ImportTable,
        values: Map<String, Any?>,
        size: Long,
    ): ByteArray {
        val where = spec.primaryKey.joinToString(" AND ") { quote(it) + "=?" }
        val keyArgs = spec.primaryKey.map { values.getValue(it).toString() }
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while (offset < size) {
            val chunkSize = minOf(STORAGE_CHUNK_BYTES.toLong(), size - offset).toInt()
            val args = arrayOf((offset + 1L).toString(), chunkSize.toString()) + keyArgs.toTypedArray()
            val chunk = db.rawQuery(
                "SELECT substr(" + quote(column) + ",?,?) FROM " + quote(table) + " WHERE " + where,
                args,
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) {
                    throw Failure("STORAGE_UNAVAILABLE", "A local media item could not be read.")
                }
                cursor.getBlob(0)
            }
            if (chunk.isEmpty() || chunk.size > chunkSize) {
                throw Failure("STORAGE_UNAVAILABLE", "A local media item could not be read.")
            }
            digest.update(chunk)
            offset += chunk.size
        }
        return digest.digest()
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
        Cursor.FIELD_TYPE_BLOB -> throw Failure("SCHEMA_UNSUPPORTED", "A portable field is not chunked media.")
        else -> throw Failure("SCHEMA_UNSUPPORTED", "A portable field has an unsupported value.")
    }

    private fun jsonValue(value: Any?): Any? = when {
        value == null || value === JSONObject.NULL -> null
        value is JSONObject -> {
            val result = LinkedHashMap<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = jsonValue(value.get(key))
            }
            result
        }
        value is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        value is String || value is Number || value is Boolean -> value
        else -> throw Failure("INVALID_ARCHIVE", "A staged import record is invalid.")
    }

    private fun parseConflict(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return jsonValue(JSONObject(json)) as? Map<String, Any?>
            ?: throw Failure("STORAGE_UNAVAILABLE", "A staged conflict is invalid.")
    }

    private fun previewToken(handle: Handle, localVersion: Long, conflictCount: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("preview\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(handle.id.toByteArray(StandardCharsets.UTF_8))
        mac.update(handle.expected.archiveDigest.toByteArray(StandardCharsets.UTF_8))
        mac.update(ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(localVersion).putLong(conflictCount).array())
        return Base64.encodeToString(mac.doFinal(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun conflictId(handle: Handle, rowOrdinal: Long, table: String, key: String, reason: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("conflict\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(handle.id.toByteArray(StandardCharsets.UTF_8))
        mac.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(rowOrdinal).array())
        mac.update(table.toByteArray(StandardCharsets.UTF_8))
        mac.update(key.toByteArray(StandardCharsets.UTF_8))
        mac.update(reason.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(mac.doFinal(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun encodeCursor(handle: Handle, preview: Preview, index: Int): String {
        val position = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(index).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("conflict-cursor\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(preview.previewToken.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(position + mac.doFinal(position), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun decodeCursor(handle: Handle, preview: Preview, cursor: String): Int {
        val token = try {
            Base64.decode(cursor, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            throw Failure("INVALID_CURSOR", "The conflict page cursor is invalid.")
        }
        if (token.size != Int.SIZE_BYTES + 32) throw Failure("INVALID_CURSOR", "The conflict page cursor is invalid.")
        val position = token.copyOfRange(0, Int.SIZE_BYTES)
        val signature = token.copyOfRange(Int.SIZE_BYTES, token.size)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("conflict-cursor\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(preview.previewToken.toByteArray(StandardCharsets.UTF_8))
        if (!MessageDigest.isEqual(signature, mac.doFinal(position))) {
            throw Failure("INVALID_CURSOR", "The conflict page cursor is invalid.")
        }
        val index = ByteBuffer.wrap(position).order(ByteOrder.BIG_ENDIAN).int
        if (index < 0 || index.toLong() > preview.conflictCount) throw Failure("INVALID_CURSOR", "The conflict page cursor is invalid.")
        return index
    }

    private fun encodedEnvelopeSize(value: Any): Int =
        StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(value).position()

    private fun ConflictPage.toWireMap(): Map<String, Any?> = mapOf(
        "importId" to importId,
        "previewToken" to previewToken,
        "nextCursor" to nextCursor,
        "done" to done,
        "conflicts" to conflicts,
    )

    private fun requireHandle(importId: String): Handle {
        val handle = active
        if (handle == null || handle.id != importId) {
            throw Failure("IMPORT_UNAVAILABLE", "The staged backup is no longer available.")
        }
        val now = elapsedClock()
        if (now >= handle.lastAccessElapsedMillis && now - handle.lastAccessElapsedMillis >= idleTtlMillis) {
            active = null
            destroyHandle(handle)
            throw Failure("IMPORT_EXPIRED", "The staged backup expired.")
        }
        return handle
    }

    private fun reapExpired() {
        val handle = active ?: return
        val now = elapsedClock()
        if (now >= handle.lastAccessElapsedMillis && now - handle.lastAccessElapsedMillis >= idleTtlMillis) {
            active = null
            cancelExpiry()
            destroyHandle(handle)
        }
    }

    private fun destroyHandle(handle: Handle) {
        cancelExpiry()
        handle.secret.fill(0)
        try { handle.database.close() } catch (_: Exception) { }
        if (!deleteArtifacts(handle.id)) scheduleCleanupRetry()
    }

    private fun deleteArtifacts(importId: String): Boolean {
        if (!idPattern.matches(importId)) return true
        cleanupPending.add(importId)
        var success = true
        listOf(
            File(directory, FILE_PREFIX + importId + ".db"),
            File(directory, FILE_PREFIX + importId + ".db-journal"),
            File(directory, FILE_PREFIX + importId + ".db-wal"),
            File(directory, FILE_PREFIX + importId + ".db-shm"),
            File(directory, FILE_PREFIX + importId + "-media.bin"),
        ).forEach { file ->
            if (file.exists()) {
                val deleted = try { deleteFile(file) } catch (_: Exception) { false }
                if (!deleted && file.exists()) success = false
            }
        }
        if (success) cleanupPending.remove(importId)
        return success
    }

    private fun retryPendingCleanup() {
        val files = directory.listFiles()
            ?: throw Failure("CLEANUP_FAILED", "An unfinished backup import could not be removed.")
        val pattern = Regex("^" + FILE_PREFIX + "([0-9a-f-]{36})(?:\\.db(?:-journal)?|-media\\.bin)$")
        files.filter { it.name.startsWith(FILE_PREFIX) }.forEach { file ->
            val id = pattern.matchEntire(file.name)?.groupValues?.get(1)
            if (id != null && id == active?.id) return@forEach
            if (id != null && idPattern.matches(id)) cleanupPending.add(id) else pendingFiles.add(file)
        }
        cleanupPending.toList().forEach(::deleteArtifacts)
        pendingFiles.toList().forEach { file ->
            val deleted = try { !file.exists() || deleteFile(file) } catch (_: Exception) { false }
            if (deleted || !file.exists()) pendingFiles.remove(file)
        }
        if (cleanupPending.isNotEmpty() || pendingFiles.isNotEmpty()) {
            throw Failure("CLEANUP_FAILED", "An unfinished backup import could not be removed.")
        }
        cleanupTask?.cancel()
        cleanupTask = null
    }

    private fun scheduleExpiry(handle: Handle) {
        expiryTask?.cancel()
        val idle = (elapsedClock() - handle.lastAccessElapsedMillis).coerceAtLeast(0L)
        expiryTask = scheduler.schedule((idleTtlMillis - idle).coerceAtLeast(1L)) { expireIfIdle(handle.id) }
    }

    @Synchronized
    private fun expireIfIdle(importId: String) {
        val handle = active
        if (handle == null || handle.id != importId) return
        expiryTask = null
        val now = elapsedClock()
        if (now >= handle.lastAccessElapsedMillis && now - handle.lastAccessElapsedMillis >= idleTtlMillis) {
            active = null
            destroyHandle(handle)
        } else {
            scheduleExpiry(handle)
        }
    }

    private fun cancelExpiry() {
        expiryTask?.cancel()
        expiryTask = null
    }

    private fun scheduleCleanupRetry() {
        cleanupTask?.cancel()
        cleanupTask = scheduler.schedule(TimeUnit.MINUTES.toMillis(1)) {
            synchronized(this) {
                cleanupTask = null
                try {
                    ensureDirectory()
                    retryPendingCleanup()
                } catch (_: Exception) {
                    scheduleCleanupRetry()
                }
            }
        }
    }

    private fun ensureDirectory() {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw Failure("STORAGE_UNAVAILABLE", "Private backup import storage is unavailable.")
        }
    }

    private fun stagedPhysicalBytes(handle: Handle): Long = listOf(
        File(handle.database.path),
        File(handle.database.path + "-journal"),
        File(handle.database.path + "-wal"),
        File(handle.database.path + "-shm"),
        handle.mediaFile,
    ).fold(0L) { total, file -> Math.addExact(total, file.length()) }

    private fun stateVersion(db: SQLiteDatabase): Long = scalarLong(db, "SELECT version FROM state WHERE id=1")

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use {
        if (!it.moveToFirst()) throw Failure("STORAGE_UNAVAILABLE", "The local backup state is unavailable.")
        it.getLong(0)
    }

    private fun integer(value: Any?, label: String): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw Failure("INVALID_ARCHIVE", "A backup $label is invalid.")
    }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
