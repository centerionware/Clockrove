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
    private val wallClock: () -> Long = { System.currentTimeMillis() },
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

    data class Applied(
        val importId: String,
        val stateVersion: Long,
        val insertedCount: Long,
        val skippedCount: Long,
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
        var revisionGraphValidated: Boolean = false,
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

    private data class ApplyRow(
        val ordinal: Long,
        val table: String,
        val values: Map<String, Any?>,
        val blobs: Map<String, LongArray>,
    )

    private data class ForeignKeySpec(
        val parentTable: String,
        val childColumns: List<String>,
        val parentColumns: List<String>,
    )

    private data class HeadAdvance(
        val kind: String,
        val entityId: String,
        val expectedLocalRevision: String,
        val backupRevision: String,
    )

    private data class ValidationColumn(
        val name: String,
        val declaredType: String,
        val notNull: Boolean,
        val defaultSql: String?,
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
        private const val REVISION_VALIDATION_BATCH_SIZE = 256
        private val revisionValidationTables = listOf(
            "validation_revision_batch",
            "validation_revision_ready",
            "validation_revision_pending",
            "validation_reference_keys",
            "validation_time_claims",
            "validation_invoice_jobs",
            "validation_ranges",
            "validation_heads",
            "validation_parents",
            "validation_revisions",
        )
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
            "invoice_payment_events" to listOf(listOf("command_version", "ordinal")),
            "invoice_payment_reversals" to listOf(listOf("payment_id")),
            "invoice_voids" to listOf(listOf("replacement_id")),
            "job_deletions" to listOf(listOf("revision_id")),
            "quotes" to listOf(listOf("number")),
            "quote_pdfs" to listOf(listOf("quote_id", "revision")),
            "quote_scope_lines" to listOf(listOf("task_id")),
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
            stagedDb.setForeignKeyConstraintsEnabled(false)
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

    /** Stores one explicit conflict choice in the private staging database. */
    @Synchronized
    fun resolveConflict(
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
            if (!rows.moveToFirst()) throw Failure("CONFLICT_UNAVAILABLE", "The backup conflict is unavailable.")
            JSONObject(rows.getString(0))
        }
        val choices = conflictJson.optJSONArray("allowedChoices")
            ?: throw Failure("STORAGE_UNAVAILABLE", "A staged backup conflict is invalid.")
        if ((0 until choices.length()).none { choices.optString(it) == choice } ||
            !isSupportedChoice(conflictJson.optString("kind"), conflictJson.optString("table"), choice)) {
            throw Failure("UNSUPPORTED_CHOICE", "That backup conflict choice is not supported.")
        }
        conflictJson.put("resolved", true)
        conflictJson.put("selectedChoice", choice)
        val updated = ContentValues().apply {
            put("selected_choice", choice)
            put("conflict_json", conflictJson.toString())
        }
        if (handle.database.update("import_conflicts", updated, "conflict_id=?", arrayOf(conflictId)) != 1) {
            throw Failure("CONFLICT_UNAVAILABLE", "The backup conflict is unavailable.")
        }
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
    }

    /** Applies the previewed rows and verified no-content descendant head advances atomically. */
    @Synchronized
    fun apply(
        db: SQLiteDatabase,
        importId: String,
        previewToken: String,
        expectedLocalStateVersion: Long,
    ): Applied {
        val handle = requireHandle(importId)
        val preview = handle.preview ?: throw Failure("PREVIEW_NOT_READY", "The import preview is not ready.")
        verifyPreviewIdentity(handle, preview, previewToken, expectedLocalStateVersion)
        var inserted = 0L
        var changed = 0L
        var skipped = 0L
        var committedVersion = expectedLocalStateVersion
        try {
            db.beginTransaction()
            try {
                // The repository serializes native commands; the SQL transaction also protects
                // against another connection writing between the freshness check and commit.
                if (stateVersion(db) != expectedLocalStateVersion ||
                    expectedLocalStateVersion != preview.localStateVersion) {
                    throw Failure("PREVIEW_STALE", "Local data changed after this preview; create a new preview.")
                }
                if (scalarLong(db, "SELECT COUNT(*) FROM sessions WHERE end_ms IS NULL") != 0L) {
                    throw Failure("LOCAL_TIMER_RUNNING", "Stop the local timer before restoring a backup.")
                }
                handle.revisionGraphValidated = true
                validateRevisionGraph(db, handle)

                validateApplyChoices(handle, preview.conflictCount)
                skipped = scalarLong(
                    handle.database,
                    "SELECT COUNT(*) FROM import_conflicts WHERE selected_choice<>'MERGE_IDENTICAL_CONTENT'",
                )

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
                        if (selectedChoice != null && selectedChoice != "MERGE_IDENTICAL_CONTENT") continue
                        val row = JSONObject(rows.getString(3))
                        @Suppress("UNCHECKED_CAST")
                        val values = jsonValue(row.getJSONObject("values")) as? Map<String, Any?>
                            ?: throw Failure("STORAGE_UNAVAILABLE", "A staged backup row is invalid.")
                        val spec = handle.catalog[table]
                            ?: throw Failure("TABLE_NOT_ALLOWED", "The backup contains an unsupported table.")
                        if (selectedChoice == "MERGE_IDENTICAL_CONTENT") {
                            val localHead = findLocalRow(db, table, spec, values)
                            if (table != "entity_heads" || localHead == null ||
                                !canMergeDivergentHead(db, handle, values, localHead)) {
                                throw Failure("PREVIEW_STALE", "A divergent backup head changed after preview.")
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
                                throw Failure("PREVIEW_STALE", "A backup revision head changed after preview.")
                            }
                            continue
                        }
                        val blobs = stagedBlobs(handle, ordinal)
                        val local = findLocalRow(db, table, spec, values)
                        if (local != null) {
                            if (!sameRecord(db, handle, table, spec, ordinal, values, blobs, local)) {
                                throw Failure("PREVIEW_STALE", "A backup row changed after preview; create a new preview.")
                            }
                            skipped++
                            continue
                        }
                        if (findLocalUniqueConflict(db, table, spec, values) != null) {
                            throw Failure("PREVIEW_STALE", "A backup row now conflicts with local data; create a new preview.")
                        }
                        val sessionStart = if (rows.isNull(4)) null else rows.getLong(4)
                        val sessionEnd = if (rows.isNull(5)) null else rows.getLong(5)
                        if (table == "sessions" && sessionEnd == null) {
                            throw Failure("PREVIEW_STALE", "An open backup session needs a fresh preview and explicit omission.")
                        }
                        if (table == "sessions" && sessionStart != null && sessionEnd != null &&
                            findOverlappingSessionId(db, handle, ordinal, sessionStart, sessionEnd, values["id"].toString()) != null) {
                            throw Failure("PREVIEW_STALE", "A backup session now overlaps local time; create a new preview.")
                        }
                        if (table == "location_points" && hasUnprovenIncomingGpsCoverage(db, handle, values)) {
                            throw Failure("PREVIEW_STALE", "GPS deletion coverage changed after preview.")
                        }
                        if (table == "location_deletion_ranges" && hasUnprovenLocalGpsPoints(db, values)) {
                            throw Failure("PREVIEW_STALE", "GPS deletion coverage changed after preview.")
                        }
                    }
                }

                // A divergent head can merge only when its live payload and application row are
                // identical; all other semantic choices remain explicit keep/omit operations.
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                val tableOrder = dependencyOrder(db, handle.catalog.keys.toList())
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
                                ?: throw Failure("STORAGE_UNAVAILABLE", "A staged backup row is invalid.")
                            if (!rows.isNull(2)) {
                                if (rows.getString(3) != "MERGE_IDENTICAL_CONTENT") continue
                                if (table != "entity_heads") {
                                    throw Failure("UNSUPPORTED_CHOICE", "A backup merge choice is invalid.")
                                }
                                applyIdenticalContentHeadMerge(db, handle, values)
                                changed++
                                continue
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
                                    throw Failure("PREVIEW_STALE", "A backup revision head changed after preview.")
                                }
                                changed++
                                continue
                            }
                            val blobs = stagedBlobs(handle, ordinal)
                            val local = findLocalRow(db, table, spec, values)
                            if (local != null) {
                                if (!sameRecord(db, handle, table, spec, ordinal, values, blobs, local)) {
                                    throw Failure("PREVIEW_STALE", "A backup row changed after preview; create a new preview.")
                                }
                                continue
                            }
                            val content = applyContentValues(handle, ApplyRow(ordinal, table, values, blobs))
                            try {
                                db.insertOrThrow(table, null, content)
                            } catch (_: Exception) {
                                throw Failure("APPLY_FAILED", "The backup could not be applied atomically.")
                            }
                            inserted++
                            changed++
                        }
                    }
                }
                validateAppliedState(db)
                if (changed > 0) {
                    db.execSQL("UPDATE state SET version=version+1 WHERE id=1")
                    if (stateVersion(db) != expectedLocalStateVersion + 1L) {
                        throw Failure("APPLY_FAILED", "The backup could not be applied atomically.")
                    }
                }
                clearRevisionValidation(handle)
                db.setTransactionSuccessful()
            } finally {
                try {
                    db.endTransaction()
                } finally {
                    if (handle.revisionGraphValidated) clearRevisionValidation(handle)
                }
            }
            committedVersion = stateVersion(db)
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("APPLY_FAILED", "The backup could not be applied atomically.")
        }

        // The durable transaction has succeeded. Retire the one-shot preview even if staged-file
        // cleanup must be retried later; callers must never reapply a committed import.
        active = null
        cancelExpiry()
        handle.secret.fill(0)
        try { handle.database.close() } catch (_: Exception) { }
        if (!deleteArtifacts(handle.id)) {
            try { scheduleCleanupRetry() } catch (_: Exception) { }
        }
        handle.lastAccessElapsedMillis = elapsedClock()
        return Applied(importId, committedVersion, inserted, skipped)
    }

    private fun requireCurrentPreview(
        db: SQLiteDatabase,
        handle: Handle,
        token: String,
        expectedVersion: Long,
    ): Preview {
        val preview = handle.preview ?: throw Failure("PREVIEW_NOT_READY", "The import preview is not ready.")
        verifyPreviewIdentity(handle, preview, token, expectedVersion)
        if (stateVersion(db) != expectedVersion) {
            throw Failure("PREVIEW_STALE", "Local data changed after this preview; create a new preview.")
        }
        return preview
    }

    private fun verifyPreviewIdentity(
        handle: Handle,
        preview: Preview,
        token: String,
        expectedVersion: Long,
    ) {
        if (expectedVersion != preview.localStateVersion) {
            throw Failure("PREVIEW_STALE", "The expected local version does not match this preview.")
        }
        if (!MessageDigest.isEqual(preview.previewToken.toByteArray(), token.toByteArray())) {
            throw Failure("INVALID_PREVIEW_TOKEN", "The import preview token is invalid.")
        }
    }

    private fun validateApplyChoices(handle: Handle, expectedConflictCount: Long) {
        if (scalarLong(handle.database, "SELECT COUNT(*) FROM import_conflicts") != expectedConflictCount ||
            scalarLong(handle.database,
                "SELECT COUNT(*) FROM import_conflicts c LEFT JOIN import_rows r " +
                    "ON r.ordinal=c.row_ordinal WHERE r.ordinal IS NULL") != 0L) {
            throw Failure("STORAGE_UNAVAILABLE", "The staged backup conflicts are invalid.")
        }
        if (scalarLong(handle.database, "SELECT COUNT(*) FROM import_conflicts WHERE selected_choice IS NULL") != 0L) {
            throw Failure("UNRESOLVED_CONFLICTS", "Choose an option for every backup conflict before applying.")
        }
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
                    throw Failure("UNSUPPORTED_CHOICE", "A backup conflict contains an unsupported choice.")
                }
            }
        }
    }

    private fun isSupportedChoice(kind: String, table: String, choice: String): Boolean = when (kind) {
        "OPEN_SESSION" -> choice == "OMIT_OPEN_SESSION"
        "SESSION_OVERLAP" -> choice == "KEEP_LOCAL" || choice == "OMIT_BACKUP_SESSION"
        // A covered backup point may only be omitted while preserving local deletion history.
        // Importing a range over local points still needs coordinated causal graph rewriting.
        "GPS_DELETION_COVERAGE" -> table == "location_points" && choice == "PRESERVE_DELETION"
        "GPS_TOMBSTONE" -> table == "location_points" && choice == "PRESERVE_LOCAL_DELETION"
        "DEPENDENT_ON_CONFLICT" -> choice == "OMIT_BACKUP_RECORD"
        "UNIQUE_VALUE_IN_USE" -> choice == "KEEP_LOCAL" || choice == "OMIT_BACKUP_RECORD"
        "IMMUTABLE_RECORD_DIFFERS" -> choice == "KEEP_LOCAL" ||
            (choice == "OMIT_BACKUP_RECORD" && table !in mergeOnlyTables)
        "EXISTING_RECORD_DIFFERS" -> choice == "KEEP_LOCAL" ||
            (table == "entity_heads" && choice == "MERGE_IDENTICAL_CONTENT") ||
            (choice == "OMIT_BACKUP_RECORD" && table !in mergeOnlyTables)
        else -> false
    }

    private fun dependencyOrder(db: SQLiteDatabase, tables: List<String>): List<String> {
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

    private fun applyContentValues(handle: Handle, row: ApplyRow): ContentValues {
        val values = row.values.toMutableMap()
        if (row.table == "automation_policies") {
            values["enabled"] = 0
            values["auto_start"] = 0
            values["facts_since_ms"] = 0L
        }
        if (row.table == "work_sites") {
            values["enabled"] = 0
            values["registration_key"] = UUID.randomUUID().toString()
        }
        val content = ContentValues()
        values.forEach { (column, value) -> putScalar(content, column, value) }
        row.blobs.forEach { (column, source) ->
            val maximumSize = maximumBlobBytes(row.table, column)
            if (maximumSize == null || source[1] !in 1L..maximumSize || source[1] > Int.MAX_VALUE) {
                throw Failure("APPLY_LIMIT_EXCEEDED", "A backup media item is too large to restore.")
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

    private fun maximumBlobBytes(table: String, column: String): Long? = when {
        table == "invoices" && column == "pdf" -> InvoiceBlobs.MAX_DOCUMENT_BYTES.toLong()
        table == "invoice_copies" && column == "pdf" -> InvoiceBlobs.MAX_DOCUMENT_BYTES.toLong()
        table == "quote_pdfs" && column == "bytes" -> InvoiceBlobs.MAX_DOCUMENT_BYTES.toLong()
        table == "logo_assets" && column == "bytes" -> ImageLimits.NORMALIZED_BYTES.toLong()
        table == "task_attachments" && column == "data" -> ImageLimits.NORMALIZED_BYTES.toLong()
        else -> null
    }

    private fun putScalar(content: ContentValues, column: String, value: Any?) {
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
            else -> throw Failure("INVALID_ARCHIVE", "A backup cell has an unsupported value.")
        }
    }

    private fun validateAppliedState(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA foreign_key_check", null).use { rows ->
            if (rows.moveToFirst()) throw Failure("APPLY_FAILED", "The backup could not be applied atomically.")
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
            throw Failure("APPLY_FAILED", "The backup could not be applied atomically.")
        }
        validateAppliedRevisionGraph(db)
    }

    /** Revalidate the graph after conflict choices have removed staged rows. */
    private fun validateAppliedRevisionGraph(db: SQLiteDatabase) {
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
        } catch (failure: Failure) {
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

    private fun appliedGraphInvalid(): Nothing =
        throw Failure("APPLY_FAILED", "The backup could not be applied atomically.")
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
        db.execSQL("CREATE TABLE import_conflicts(ordinal INTEGER PRIMARY KEY,conflict_id TEXT NOT NULL UNIQUE,row_ordinal INTEGER NOT NULL UNIQUE,selected_choice TEXT,conflict_json TEXT NOT NULL)")
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
            val maximumSize = maximumBlobBytes(table, column)
            if (!blobIdPattern.matches(id) || column !in spec.blobColumns || maximumSize == null ||
                size !in 1L..maximumSize) {
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
                        if (table == "location_points" && hasUnprovenIncomingGpsCoverage(db, handle, values)) {
                            val canPreserveDeletion = canSafelyPreserveLocalGpsDeletion(db, values)
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
                            if (hasUnprovenLocalGpsPoints(db, values)) {
                                conflictOrdinal = addConflict(
                                    handle, conflictOrdinal, ordinal, table, keyJson, "GPS_DELETION_COVERAGE",
                                    emptyList(), null,
                                    localSummary = mapOf("identifier" to "Unproven local GPS points are covered"),
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
                            if (same) {
                                unchanged++
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
                                val mergeableDivergentHead = table == "entity_heads" &&
                                    revisionRelationship == "DIVERGENT" &&
                                    canMergeDivergentHead(db, handle, values, local)
                                val localGpsTombstoneCanBePreserved = table == "entity_heads" &&
                                    values["kind"] == "location" &&
                                    hasLocalLocationTombstone(db, values.getValue("entity_id").toString())
                                // M7.3 remains blocked for non-identical divergent content until a
                                // two-sided take-backup/keep-local decision can preserve both histories.
                                val unsupportedDivergentHead = table == "entity_heads" &&
                                    revisionRelationship == "DIVERGENT" && !mergeableDivergentHead &&
                                    !localGpsTombstoneCanBePreserved
                                val choices = when {
                                    immutable -> listOf("KEEP_LOCAL", "OMIT_BACKUP_RECORD")
                                    mergeableDivergentHead -> listOf("KEEP_LOCAL", "MERGE_IDENTICAL_CONTENT")
                                    unsupportedDivergentHead -> emptyList()
                                    table in mergeOnlyTables -> listOf("KEEP_LOCAL")
                                    else -> listOf("KEEP_LOCAL")
                                }
                                conflictOrdinal = addConflict(
                                    handle, conflictOrdinal, ordinal, table, keyJson,
                                    if (immutable) "IMMUTABLE_RECORD_DIFFERS" else "EXISTING_RECORD_DIFFERS",
                                    choices,
                                    if (unsupportedDivergentHead) null else "KEEP_LOCAL",
                                    localSummary = safeSummary(local.values),
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
        return Preview(
            handle.id, token, handle.expected.schemaVersion, localVersion,
            handle.recordCount, handle.mediaCount, handle.expected.mediaBytes,
            added, unchanged, conflictOrdinal, openSessions, localOpenSessions,
            applyReady = conflictOrdinal == 0L && openSessions == 0L && localOpenSessions == 0L,
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

    /** Checks revision ownership and ancestry inside the bounded staging database. */
    private fun validateRevisionGraph(localDb: SQLiteDatabase, handle: Handle) {
        val db = handle.database
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE validation_revisions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,job_id TEXT,deleted INTEGER NOT NULL,origin TEXT NOT NULL)")
            db.execSQL("CREATE TABLE validation_parents(revision_id TEXT NOT NULL,parent_id TEXT NOT NULL,PRIMARY KEY(revision_id,parent_id))")
            db.execSQL("CREATE INDEX validation_parent_child ON validation_parents(revision_id)")
            db.execSQL("CREATE INDEX validation_parent_parent ON validation_parents(parent_id)")
            db.execSQL("CREATE TABLE validation_heads(kind TEXT NOT NULL,entity_id TEXT NOT NULL,revision_id TEXT NOT NULL,PRIMARY KEY(kind,entity_id))")
            db.execSQL("CREATE TABLE validation_ranges(id TEXT PRIMARY KEY,job_id TEXT,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,revision_id TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE INDEX validation_range_scope_bounds ON validation_ranges(job_id,start_ms,end_ms)")
            db.execSQL("CREATE TABLE validation_reference_keys(parent_table TEXT NOT NULL,columns_json TEXT NOT NULL,values_json TEXT NOT NULL,PRIMARY KEY(parent_table,columns_json,values_json))")

            forEachImportedValues(handle, "entity_revisions") { values ->
                val deleted = revisionInteger(values["deleted"])
                if (deleted !in 0L..1L) invalidRevisionGraph()
                val kind = revisionString(values["kind"])
                val entityId = revisionString(values["entity_id"])
                if (kind == "location" && values["payload"] != null) {
                    validateLocationRevisionPayload(values["payload"], entityId)
                }
                db.execSQL(
                    "INSERT INTO validation_revisions VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        revisionString(values["id"]), kind,
                        entityId, nullableRevisionString(values["job_id"]),
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
                val pointRevisionDeleted = db.rawQuery(
                    "SELECT r.deleted FROM validation_heads h JOIN validation_revisions r ON r.id=h.revision_id " +
                        "WHERE h.kind='location' AND h.entity_id=?",
                    arrayOf(pointId),
                ).use { rows ->
                    if (!rows.moveToFirst()) invalidReference()
                    rows.getLong(0)
                }
                if (pointRevisionDeleted != 0L) invalidReference()
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
            validatePortableReferences(localDb, handle)
            validateInvoiceClaimRules(localDb, handle)

            validateRevisionAcyclicity(db)
            if (File(db.path).length() > MAX_STAGE_DATABASE_BYTES ||
                stagedPhysicalBytes(handle) > MAX_STAGE_PHYSICAL_BYTES) {
                throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup staging database exceeds its limit.")
            }
            db.setTransactionSuccessful()
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            invalidRevisionGraph()
        } finally {
            db.endTransaction()
        }
    }

    /** Kahn traversal is backed by SQLite so deep histories do not use JVM recursion. */
    private fun validateRevisionAcyclicity(db: SQLiteDatabase) {
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

    /** Validation projections are needed only while building the preview. */
    private fun clearRevisionValidation(handle: Handle) {
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
            throw Failure("STORAGE_UNAVAILABLE", "The backup preview could not be finalized.")
        }
    }

    private fun forEachImportedValues(handle: Handle, table: String, action: (Map<String, Any?>) -> Unit) {
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

    private fun revisionString(value: Any?): String = (value as? String)
        ?.takeIf { it.isNotEmpty() } ?: invalidRevisionGraph()

    private fun nullableRevisionString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> invalidRevisionGraph()
    }

    private fun revisionInteger(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> invalidRevisionGraph()
    }

    private fun invalidRevisionGraph(): Nothing =
        throw Failure("INVALID_REVISION_GRAPH", "The backup revision history is invalid.")

    private fun validateLocationRevisionPayload(payload: Any?, entityId: String) {
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

    private fun invalidReference(): Nothing =
        throw Failure("INVALID_REFERENCE", "The backup contains an unresolved record reference.")

    /** Exercises the installed CHECK/NOT NULL/type rules against one staged row at a time. */
    private fun validatePortableRows(localDb: SQLiteDatabase, handle: Handle) {
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
                    ?: throw Failure("SCHEMA_UNSUPPORTED", "A portable table schema is unavailable.")
                db.execSQL(createSql)
            }
            allTablesCreated = true
            db.rawQuery("SELECT ordinal,table_name,row_json FROM import_rows ORDER BY ordinal", null).use { rows ->
                while (rows.moveToNext()) {
                    val ordinal = rows.getLong(0)
                    val table = rows.getString(1)
                    val spec = handle.catalog[table]
                        ?: throw Failure("TABLE_NOT_ALLOWED", "The backup contains an unsupported table.")
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
                throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup staging database exceeds its limit.")
            }
        } finally {
            if (allTablesCreated) {
                tables.asReversed().forEach { table -> db.execSQL("DROP TABLE IF EXISTS ${quote(table)}") }
            }
        }
    }

    private fun validationColumns(db: SQLiteDatabase, table: String): List<ValidationColumn> =
        db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    add(ValidationColumn(
                        name = rows.getString(1),
                        declaredType = rows.getString(2).orEmpty().uppercase(),
                        notNull = rows.getInt(3) != 0,
                        defaultSql = if (rows.isNull(4)) null else rows.getString(4),
                    ))
                }
            }
        }

    private fun validatePortableValueTypes(
        table: String,
        spec: BackupExportStore.ImportTable,
        columns: List<ValidationColumn>,
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

    private fun validationContentValues(
        table: String,
        spec: BackupExportStore.ImportTable,
        columns: List<ValidationColumn>,
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
                else -> throw Failure(
                    "SCHEMA_UNSUPPORTED",
                    "A required device-local field has no safe backup validation default.",
                )
            }
            putValidationValue(content, column.name, placeholder)
        }
        return content
    }

    private fun putValidationValue(content: ContentValues, column: String, value: Any?) {
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

    private fun invalidArchiveRow(): Nothing =
        throw Failure("INVALID_ARCHIVE", "The backup contains an invalid record.")

    /** Uses the installed SQLite schema as the FK contract for every portable row. */
    private fun validatePortableReferences(localDb: SQLiteDatabase, handle: Handle) {
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

    /** Mirrors invoice-claim triggers which are not present in the validation schema. */
    private fun validateInvoiceClaimRules(localDb: SQLiteDatabase, handle: Handle) {
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

    private fun foreignKeySpecs(
        db: SQLiteDatabase,
        table: String,
        catalog: Map<String, BackupExportStore.ImportTable>,
    ): List<ForeignKeySpec> {
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
            ForeignKeySpec(group.parentTable, ordered.map { it.child }, parentColumns)
        }
    }

    private fun tablePrimaryKey(db: SQLiteDatabase, table: String): List<String> {
        val columns = mutableListOf<Pair<Int, String>>()
        db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { rows ->
            while (rows.moveToNext()) {
                val order = rows.getInt(5)
                if (order > 0) columns.add(order to rows.getString(1))
            }
        }
        return columns.sortedBy { it.first }.map { it.second }
    }

    private fun stagedForeignKeyExists(
        handle: Handle,
        foreignKey: ForeignKeySpec,
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

    private fun localForeignKeyExists(
        db: SQLiteDatabase,
        foreignKey: ForeignKeySpec,
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

    private fun jsonArray(values: List<Any?>): String = JSONArray().apply {
        values.forEach { put(it ?: JSONObject.NULL) }
    }.toString()

    private fun stagedIdentifierExists(handle: Handle, table: String, id: String): Boolean =
        handle.database.rawQuery(
            "SELECT 1 FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
            arrayOf(table, jsonArray(listOf(id))),
        ).use { it.moveToFirst() }

    private fun localIdentifierExists(db: SQLiteDatabase, table: String, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM ${quote(table)} WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    private fun hasUnprovenIncomingGpsCoverage(
        localDb: SQLiteDatabase,
        handle: Handle,
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
        handle.database.rawQuery(
            "SELECT revision_id FROM validation_ranges WHERE start_ms<=? AND end_ms>? " +
                "AND (job_id IS NULL OR job_id=?)",
            arrayOf(measuredAt.toString(), measuredAt.toString(), jobId),
        ).use { rows ->
            while (rows.moveToNext() && !unproven) {
                unproven = !hasDirectCausalParent(handle, pointRevision, rows.getString(0))
            }
        }
        return unproven
    }

    private fun hasLocalLocationTombstone(db: SQLiteDatabase, point: Map<String, Any?>): Boolean =
        hasLocalLocationTombstone(db, revisionString(point["id"]))

    private fun hasLocalLocationTombstone(db: SQLiteDatabase, pointId: String): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind='location' AND h.entity_id=? AND r.deleted=1 LIMIT 1",
            arrayOf(pointId),
        ).use { it.moveToFirst() }
    }

    /**
     * Omitting a covered backup point is safe only when local state already represents that
     * point as either a deleted tombstone or a live point whose head proves every local range.
     * A backup-only point cannot be omitted alone because its live revision and head would then
     * remain orphaned in the restored graph.
     */
    private fun canSafelyPreserveLocalGpsDeletion(
        db: SQLiteDatabase,
        point: Map<String, Any?>,
    ): Boolean {
        val pointId = revisionString(point["id"])
        if (!localIdentifierExists(db, "location_points", pointId)) {
            return hasLocalLocationTombstone(db, point)
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

    private fun hasDirectCausalParent(handle: Handle, childRevision: String, parentRevision: String): Boolean =
        handle.database.rawQuery(
            "SELECT 1 FROM validation_parents WHERE revision_id=? AND parent_id=? LIMIT 1",
            arrayOf(childRevision, parentRevision),
        ).use { it.moveToFirst() }

    private fun stagedSessionJobId(
        localDb: SQLiteDatabase,
        handle: Handle,
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

    private fun hasUnprovenLocalGpsPoints(db: SQLiteDatabase, range: Map<String, Any?>): Boolean {
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

    private fun stagedBlobs(handle: Handle, rowOrdinal: Long): Map<String, LongArray> =
        handle.database.rawQuery(
            "SELECT column_name,file_offset,size_bytes FROM import_blobs WHERE row_ordinal=?",
            arrayOf(rowOrdinal.toString()),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), longArrayOf(cursor.getLong(1), cursor.getLong(2)))
            }
        }

    private fun registerStagedUniqueKeys(
        handle: Handle,
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

    private fun uniqueColumnsForRow(table: String, values: Map<String, Any?>): List<List<String>> =
        buildList {
            addAll(uniqueColumns[table].orEmpty())
            // This index applies only to unreleased claims; released history may repeat a task.
            if (table == "invoice_task_claims" && values["released_ms"] == null) {
                add(listOf("task_id"))
            }
        }

    private fun findLocalUniqueConflict(
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

    private fun findOverlappingSessionId(
        db: SQLiteDatabase,
        handle: Handle,
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

    private fun addConflict(
        handle: Handle,
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
        val conflict = JSONObject()
            .put("conflictId", conflictId)
            .put("kind", reason)
            .put("table", table)
            .put("primaryKey", JSONArray(keyJson))
            .put("localIdentifier", localIdentifier ?: JSONObject.NULL)
            .put("localSummary", safeSummaryObject(localSummary))
            .put("backupSummary", safeSummaryObject(backupSummary))
            .put("allowedChoices", JSONArray(choices))
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

    /** Labels the imported head's graph position without guessing from clocks or state versions. */
    private fun revisionHeadRelationship(
        localDb: SQLiteDatabase,
        handle: Handle,
        localHead: String,
        backupHead: String,
    ): String = when {
        localHead == backupHead -> "SAME"
        isRevisionAncestor(handle.database, "validation_parents", localHead, backupHead) -> "BACKUP_DESCENDANT"
        isRevisionAncestor(localDb, "revision_parents", backupHead, localHead) -> "BACKUP_ANCESTOR"
        else -> "DIVERGENT"
    }

    /** Fast-forwards only a proven descendant whose live business row and payload are unchanged. */
    private fun canAutoAdvanceHead(
        localDb: SQLiteDatabase,
        handle: Handle,
        backupHead: Map<String, Any?>,
        localHead: LocalRow,
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

    private fun canMergeDivergentHead(
        localDb: SQLiteDatabase,
        handle: Handle,
        backupHead: Map<String, Any?>,
        localHead: LocalRow,
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

    private fun sameLiveHeadContentAndData(
        localDb: SQLiteDatabase,
        handle: Handle,
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

        val table = when (kind) {
            "job" -> "jobs"
            "customer" -> "customers"
            "session" -> "sessions"
            "site" -> "work_sites"
            "column" -> "kanban_columns"
            "card" -> "kanban_cards"
            else -> return false
        }
        val spec = handle.catalog[table] ?: return false
        if (spec.primaryKey != listOf("id")) return false
        val dataRow = handle.database.rawQuery(
            "SELECT ordinal,row_json FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
            arrayOf(table, jsonArray(listOf(entityId))),
        ).use { rows ->
            if (!rows.moveToFirst()) return false
            val values = jsonValue(JSONObject(rows.getString(1)).getJSONObject("values")) as? Map<String, Any?>
                ?: return false
            Triple(rows.getLong(0), values, stagedBlobs(handle, rows.getLong(0)))
        }
        val localData = findLocalRow(localDb, table, spec, dataRow.second) ?: return false
        return sameRecord(localDb, handle, table, spec, dataRow.first, dataRow.second, dataRow.third, localData)
    }

    private fun headAdvanceForRow(handle: Handle, rowOrdinal: Long): HeadAdvance? =
        handle.database.rawQuery(
            "SELECT kind,entity_id,expected_local_revision,backup_revision " +
                "FROM preview_head_advances WHERE row_ordinal=?",
            arrayOf(rowOrdinal.toString()),
        ).use { rows ->
            if (!rows.moveToFirst()) null else HeadAdvance(
                rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3),
            )
        }

    private fun applyIdenticalContentHeadMerge(
        db: SQLiteDatabase,
        handle: Handle,
        backupHead: Map<String, Any?>,
    ) {
        val spec = handle.catalog.getValue("entity_heads")
        val localHead = findLocalRow(db, "entity_heads", spec, backupHead)
            ?: throw Failure("PREVIEW_STALE", "A divergent backup head changed after preview.")
        if (!canMergeDivergentHead(db, handle, backupHead, localHead)) {
            throw Failure("PREVIEW_STALE", "A divergent backup head changed after preview.")
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
                throw Failure("PREVIEW_STALE", "A divergent backup head changed after preview.")
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
        if (updated != 1) throw Failure("PREVIEW_STALE", "A divergent backup head changed after preview.")
    }

    private fun isRevisionAncestor(
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

    /**
     * If a backup row depends on another staged row that already needs a choice, require an
     * explicit omission for the child whenever no local row can satisfy that reference. This
     * keeps a KEEP_LOCAL/OMIT choice on a parent from turning into a late foreign-key failure.
     */
    private fun addDependentReferenceConflicts(
        localDb: SQLiteDatabase,
        handle: Handle,
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
                        ?: throw Failure("STORAGE_UNAVAILABLE", "A staged backup row is invalid.")
                    val dependencyConflict = foreignKeysByChild.getValue(table).any { foreignKey ->
                        val referenceValues = foreignKey.childColumns.map { values[it] }
                        if (referenceValues.any { it == null } || localForeignKeyExists(localDb, foreignKey, referenceValues)) {
                            return@any false
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
                        if (hasNonConflictedStagedParent) return@any false
                        staged.rawQuery(
                            "SELECT 1 FROM preview_reference_rows p JOIN import_conflicts c " +
                                "ON c.row_ordinal=p.row_ordinal WHERE p.parent_table=? AND p.columns_json=? " +
                                "AND p.values_json=? LIMIT 1",
                            keyArgs,
                        ).use { it.moveToFirst() }
                    }
                    if (dependencyConflict) {
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
        cancelExpiry()
        val idle = (elapsedClock() - handle.lastAccessElapsedMillis).coerceAtLeast(0L)
        try {
            expiryTask = scheduler.schedule((idleTtlMillis - idle).coerceAtLeast(1L)) {
                expireIfIdle(handle.id)
            }
        } catch (_: Exception) {
            if (active?.id == handle.id) active = null
            cancelExpiry()
            handle.secret.fill(0)
            try { handle.database.close() } catch (_: Exception) { }
            if (!deleteArtifacts(handle.id)) {
                try { scheduleCleanupRetry() } catch (_: Exception) { }
            }
            throw Failure("STORAGE_UNAVAILABLE", "The staged backup could not be kept secure.")
        }
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
            try { scheduleExpiry(handle) } catch (_: Failure) { }
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

    private fun scalarLong(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long = db.rawQuery(sql, args).use {
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
