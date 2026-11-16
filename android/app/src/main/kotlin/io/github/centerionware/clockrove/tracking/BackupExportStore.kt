package io.github.centerionware.clockrove.tracking

import android.content.Context
import android.database.Cursor
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
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal fun interface BackupScheduledTask {
    fun cancel()
}

internal fun interface BackupScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): BackupScheduledTask
}

internal object ExecutorBackupScheduler : BackupScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "clockrove-backup-cleanup").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun schedule(delayMillis: Long, action: () -> Unit): BackupScheduledTask {
        val future = executor.schedule({ action() }, delayMillis, TimeUnit.MILLISECONDS)
        return BackupScheduledTask { future.cancel(false) }
    }
}

/** Detached, bounded export view. No caller-controlled path or SQL reaches this class. */
internal class BackupExportStore(
    context: Context,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val deleteFile: (File) -> Boolean = { it.delete() },
    private val idleTtlMillis: Long = IDLE_EXPIRY_MILLIS,
    private val scheduler: BackupScheduler = ExecutorBackupScheduler,
) {
    data class Info(
        val archiveFormatVersion: Int,
        val schemaVersion: Int,
        val stateVersion: Long,
        val maxSnapshotBytes: Long,
        val maxPageBytes: Int,
        val maxBlobChunkBytes: Int,
        val maxRecords: Long,
        val maxMediaCount: Int,
        val idleExpiryMillis: Long,
        val portableTables: List<String>,
    )

    data class Started(
        val snapshotId: String,
        val archiveFormatVersion: Int,
        val schemaVersion: Int,
        val stateVersion: Long,
        val capturedAtUtcMillis: Long,
        val recordCount: Long,
        val mediaCount: Long,
        val mediaBytes: Long,
        val openSessionCount: Long,
    )

    data class Page(
        val snapshotId: String,
        val nextCursor: String?,
        val done: Boolean,
        val records: List<Map<String, Any?>>,
    )

    data class BlobChunk(
        val offset: Long,
        val nextOffset: Long,
        val done: Boolean,
        val bytes: ByteArray,
    )

    data class ImportTable(
        val valueColumns: Set<String>,
        val blobColumns: Set<String>,
        val primaryKey: List<String>,
    )

    class Failure(val code: String, message: String) : IllegalStateException(message)

    private data class BlobField(val column: String, val keyColumn: String)
    private data class Dataset(
        val table: String,
        val excludedColumns: Set<String> = emptySet(),
        val blobFields: List<BlobField> = emptyList(),
    )
    private data class ResolvedDataset(
        val spec: Dataset,
        val columns: List<String>,
        val primaryKey: List<String>,
    )
    private data class BlobLocation(val offset: Long, val sizeBytes: Long, val sha256: ByteArray)
    private data class Preflight(
        val datasets: List<ResolvedDataset>,
        val recordCount: Long,
        val mediaCount: Long,
        val mediaBytes: Long,
        val bodyBytes: Long,
    )
    private data class Handle(
        val id: String,
        val rowsFile: File,
        val mediaFile: File,
        val rowOffsets: LongArray,
        val blobs: Map<String, BlobLocation>,
        val secret: ByteArray,
        val metadata: Started,
        var lastAccessElapsedMillis: Long,
    )

    companion object {
        const val ARCHIVE_FORMAT_VERSION = 1
        const val CURRENT_SCHEMA_VERSION = 16
        const val MAX_SNAPSHOT_BYTES = 60L * 1024L * 1024L
        const val MAX_PAGE_BYTES = 256 * 1024
        const val MIN_PAGE_BYTES = 1024
        const val MAX_BLOB_CHUNK_BYTES = 256 * 1024
        const val MAX_ROW_BYTES = 128 * 1024
        const val MAX_RECORDS = 500_000L
        const val MAX_MEDIA_COUNT = 10_000
        const val IDLE_EXPIRY_MILLIS = 15L * 60L * 1000L
        private const val STORAGE_BLOB_CHUNK_BYTES = 64 * 1024
        private const val MAX_RECORDS_PER_PAGE = 512
        private const val FILE_PREFIX = "clockrove-backup-"
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val datasets = listOf(
            Dataset("actual_costs"),
            Dataset("app_settings"),
            Dataset("automation_policies", setOf("armed", "suppressed", "last_presence", "facts_since_ms", "manual_barrier_ms")),
            Dataset("billing_tax_settings"),
            Dataset("card_money"),
            Dataset("customers"),
            Dataset("entity_heads"),
            Dataset("entity_revisions"),
            Dataset("invoice_accounting_settings"),
            Dataset("invoice_billing_defaults"),
            Dataset("invoice_copies", blobFields = listOf(BlobField("pdf", "id"))),
            Dataset("invoice_coverage"),
            Dataset("invoice_payment_events"),
            Dataset("invoice_payment_reversals"),
            Dataset("invoice_payments"),
            Dataset("invoice_task_claims"),
            Dataset("invoice_time_claims"),
            Dataset("invoice_voids"),
            Dataset("invoices", blobFields = listOf(BlobField("pdf", "id"))),
            Dataset("job_deletions"),
            Dataset("job_invoice_billing_policy"),
            Dataset("job_location_recording"),
            Dataset("job_money"),
            Dataset("job_tax_rates"),
            Dataset("jobs"),
            Dataset("kanban_cards"),
            Dataset("kanban_columns"),
            Dataset("location_deletion_ranges"),
            Dataset("location_points"),
            Dataset("location_recording_settings"),
            Dataset("logo_assets", blobFields = listOf(BlobField("bytes", "id"))),
            Dataset("payment_destinations"),
            Dataset("quote_counter"),
            Dataset("quote_decisions"),
            Dataset("quote_drafts"),
            Dataset("quote_invoice_links"),
            Dataset("quote_pdfs", blobFields = listOf(BlobField("bytes", "id"))),
            Dataset("quote_revisions"),
            Dataset("quote_scope_lines"),
            Dataset("quote_scope_revisions"),
            Dataset("quotes"),
            Dataset("revision_parents"),
            Dataset("sessions"),
            Dataset("settings_money"),
            Dataset("task_attachments", blobFields = listOf(BlobField("data", "id"))),
            Dataset("task_comment_events"),
            Dataset("task_comments"),
            Dataset("task_quantities"),
            Dataset("task_templates"),
            Dataset("template_money"),
            Dataset("template_quantities"),
            Dataset("work_sites", setOf("registration_key", "presence", "observed_ms")),
        )
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir, "clockrove-backup-snapshots")
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
            cleanupStaleArtifacts()
        } catch (_: Exception) {
            // Snapshot storage failure must not prevent timer/database use.
        }
    }

    @Synchronized
    fun info(db: SQLiteDatabase): Info {
        reapExpired()
        ensureDirectory()
        retryPendingCleanup()
        return Info(
            archiveFormatVersion = ARCHIVE_FORMAT_VERSION,
            schemaVersion = db.version,
            stateVersion = stateVersion(db),
            maxSnapshotBytes = MAX_SNAPSHOT_BYTES,
            maxPageBytes = MAX_PAGE_BYTES,
            maxBlobChunkBytes = MAX_BLOB_CHUNK_BYTES,
            maxRecords = MAX_RECORDS,
            maxMediaCount = MAX_MEDIA_COUNT,
            idleExpiryMillis = idleTtlMillis,
            portableTables = datasets.map { it.table },
        )
    }

    /** Catalog for validating an import; every identifier comes from this compiled allowlist. */
    @Synchronized
    internal fun importCatalog(db: SQLiteDatabase): Map<String, ImportTable> {
        requireSupportedSchema(db)
        return datasets.associate { spec ->
            val resolved = resolveDataset(db, spec)
            spec.table to ImportTable(
                valueColumns = resolved.columns.toSet(),
                blobColumns = spec.blobFields.map { it.column }.toSet(),
                primaryKey = resolved.primaryKey,
            )
        }
    }

    /** Caller holds TimerRepository's synchronized writer boundary. */
    @Synchronized
    fun start(db: SQLiteDatabase): Started {
        reapExpired()
        ensureDirectory()
        retryPendingCleanup()
        if (active != null) throw Failure("SNAPSHOT_BUSY", "A backup snapshot is already open.")
        requireSupportedSchema(db)

        val id = UUID.randomUUID().toString()
        val secret = ByteArray(32).also(random::nextBytes)
        val rowsFile = File(directory, FILE_PREFIX + id + "-rows.bin")
        val mediaFile = File(directory, FILE_PREFIX + id + "-media.bin")
        try {
            if (!rowsFile.createNewFile() || !mediaFile.createNewFile()) {
                secret.fill(0)
                if (!deleteArtifacts(id)) scheduleCleanupRetry()
                throw Failure("STORAGE_UNAVAILABLE", "The backup snapshot could not be created.")
            }
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            secret.fill(0)
            if (!deleteArtifacts(id)) scheduleCleanupRetry()
            throw Failure("STORAGE_UNAVAILABLE", "The backup snapshot could not be created.")
        }

        var transactionStarted = false
        try {
            db.beginTransactionNonExclusive()
            transactionStarted = true
            val capturedAt = wallClock()
            val version = stateVersion(db)
            val openSessions = scalarLong(db, "SELECT COUNT(*) FROM sessions WHERE end_ms IS NULL")
            val resolved = datasets.map { resolveDataset(db, it) }
            val preflight = preflight(db, resolved, id, secret)
            if (preflight.recordCount > MAX_RECORDS || preflight.mediaCount > MAX_MEDIA_COUNT ||
                preflight.bodyBytes > MAX_SNAPSHOT_BYTES) {
                throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup exceeds the supported size or count.")
            }
            val copied = copySnapshot(db, preflight, id, secret, rowsFile, mediaFile)
            if (rowsFile.length() + mediaFile.length() > MAX_SNAPSHOT_BYTES) {
                throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup exceeds the supported size.")
            }
            val metadata = Started(
                snapshotId = id,
                archiveFormatVersion = ARCHIVE_FORMAT_VERSION,
                schemaVersion = db.version,
                stateVersion = version,
                capturedAtUtcMillis = capturedAt,
                recordCount = preflight.recordCount,
                mediaCount = preflight.mediaCount,
                mediaBytes = preflight.mediaBytes,
                openSessionCount = openSessions,
            )
            db.setTransactionSuccessful()
            db.endTransaction()
            transactionStarted = false
            active = Handle(id, rowsFile, mediaFile, copied.first, copied.second, secret, metadata, elapsedClock())
            scheduleExpiry(requireNotNull(active))
            return metadata
        } catch (failure: Failure) {
            secret.fill(0)
            if (!deleteArtifacts(id)) scheduleCleanupRetry()
            throw failure
        } catch (_: Exception) {
            secret.fill(0)
            if (!deleteArtifacts(id)) scheduleCleanupRetry()
            throw Failure("STORAGE_UNAVAILABLE", "The backup snapshot could not be read.")
        } finally {
            if (transactionStarted) db.endTransaction()
        }
    }

    @Synchronized
    fun readPage(snapshotId: String, cursor: String?, requestedMaxBytes: Int?): Page {
        val handle = requireHandle(snapshotId)
        val maxBytes = requestedMaxBytes ?: MAX_PAGE_BYTES
        require(maxBytes in MIN_PAGE_BYTES..MAX_PAGE_BYTES) { "INVALID_ARGUMENT" }
        val startIndex = if (cursor == null) 0 else decodeCursor(handle, cursor)
        val records = mutableListOf<Map<String, Any?>>()
        var nextIndex = startIndex
        val total = handle.rowOffsets.size
        RandomAccessFile(handle.rowsFile, "r").use { input ->
            while (nextIndex < total && records.size < MAX_RECORDS_PER_PAGE) {
                // Attach the native snapshot digest before measuring the wire
                // page, so its descriptor bytes count against maxBytes.
                val candidate = readRecord(input, handle.rowOffsets[nextIndex], handle.blobs)
                records.add(candidate)
                val candidateNext = nextIndex + 1
                val candidatePage = Page(
                    snapshotId = handle.id,
                    nextCursor = if (candidateNext < total) encodeCursor(handle, candidateNext) else null,
                    done = candidateNext >= total,
                    records = records,
                )
                if (encodedEnvelopeSize(candidatePage.toWireMap()) > maxBytes) {
                    records.removeAt(records.lastIndex)
                    if (records.isEmpty()) throw Failure("PAGE_LIMIT_TOO_SMALL", "The requested page limit is too small.")
                    break
                }
                nextIndex = candidateNext
            }
        }
        val done = nextIndex >= total
        return Page(
            snapshotId = handle.id,
            nextCursor = if (done) null else encodeCursor(handle, nextIndex),
            done = done,
            records = records,
        )
    }

    @Synchronized
    fun readBlob(snapshotId: String, blobId: String, offset: Long, requestedMaxBytes: Int?): BlobChunk {
        val handle = requireHandle(snapshotId)
        require(offset >= 0) { "INVALID_ARGUMENT" }
        val maxBytes = requestedMaxBytes ?: MAX_BLOB_CHUNK_BYTES
        require(maxBytes in 1..MAX_BLOB_CHUNK_BYTES) { "INVALID_ARGUMENT" }
        val location = handle.blobs[blobId] ?: throw Failure("INVALID_BLOB", "The media handle is not available.")
        if (offset > location.sizeBytes) throw Failure("INVALID_BLOB_OFFSET", "The media offset is not valid.")
        val count = minOf(maxBytes.toLong(), location.sizeBytes - offset).toInt()
        val bytes = ByteArray(count)
        if (count > 0) {
            RandomAccessFile(handle.mediaFile, "r").use { input ->
                input.seek(location.offset + offset)
                input.readFully(bytes)
            }
        }
        val next = offset + count
        return BlobChunk(offset, next, next >= location.sizeBytes, bytes)
    }

    @Synchronized
    fun close(snapshotId: String): Boolean {
        val handle = active
        if (handle != null && handle.id == snapshotId) {
            active = null
            cancelExpiry()
            handle.secret.fill(0)
            if (!deleteArtifacts(handle.id)) {
                scheduleCleanupRetry()
                throw Failure("CLEANUP_FAILED", "The temporary backup snapshot could not be removed.")
            }
            return true
        }
        if (snapshotId in cleanupPending) {
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
            if (!deleteArtifacts(handle.id)) {
                scheduleCleanupRetry()
                throw Failure("CLEANUP_FAILED", "The temporary backup snapshot could not be removed.")
            }
        }
        retryPendingCleanup()
    }

    private fun preflight(
        db: SQLiteDatabase,
        resolved: List<ResolvedDataset>,
        snapshotId: String,
        secret: ByteArray,
    ): Preflight {
        var recordCount = 0L
        var mediaCount = 0L
        var mediaBytes = 0L
        var bodyBytes = 0L
        var rowOrdinal = 0L

        resolved.forEach { dataset ->
            scanLengths(db, dataset) { scalarBytes, sizes ->
                if (scalarBytes > MAX_ROW_BYTES) {
                    throw Failure("ROW_LIMIT_EXCEEDED", "A backup record exceeds the supported size.")
                }
                recordCount = Math.addExact(recordCount, 1L)
                rowOrdinal++
                if (recordCount > MAX_RECORDS) {
                    throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup contains too many records.")
                }
                sizes.forEach { size ->
                    if (size != null) {
                        mediaCount = Math.addExact(mediaCount, 1L)
                        mediaBytes = Math.addExact(mediaBytes, size)
                        if (mediaCount > MAX_MEDIA_COUNT || mediaBytes > MAX_SNAPSHOT_BYTES) {
                            throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup exceeds the supported size or count.")
                        }
                    }
                }
            }
        }

        rowOrdinal = 0L
        resolved.forEach { dataset ->
            scanRows(db, dataset) { cursor, sizes ->
                val row = encodeRecord(dataset, cursor, sizes, snapshotId, secret, rowOrdinal)
                val payload = row.toString().toByteArray(StandardCharsets.UTF_8)
                if (payload.size > MAX_ROW_BYTES) {
                    throw Failure("ROW_LIMIT_EXCEEDED", "A backup record exceeds the supported size.")
                }
                bodyBytes = Math.addExact(bodyBytes, payload.size.toLong() + Int.SIZE_BYTES)
                if (bodyBytes + mediaBytes > MAX_SNAPSHOT_BYTES) {
                    throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup exceeds the supported size.")
                }
                rowOrdinal++
            }
        }
        if (rowOrdinal != recordCount) throw Failure("STORAGE_UNAVAILABLE", "The backup snapshot changed while it was read.")
        return Preflight(resolved, recordCount, mediaCount, mediaBytes, Math.addExact(bodyBytes, mediaBytes))
    }

    private fun copySnapshot(
        db: SQLiteDatabase,
        preflight: Preflight,
        snapshotId: String,
        secret: ByteArray,
        rowsFile: File,
        mediaFile: File,
    ): Pair<LongArray, Map<String, BlobLocation>> {
        val offsets = ArrayList<Long>(preflight.recordCount.toInt())
        val blobs = HashMap<String, BlobLocation>(preflight.mediaCount.toInt().coerceAtLeast(1))
        var rowOrdinal = 0L
        var mediaOffset = 0L
        RandomAccessFile(rowsFile, "rw").use { rows ->
            RandomAccessFile(mediaFile, "rw").use { media ->
                preflight.datasets.forEach { dataset ->
                    scanRows(db, dataset) { cursor, sizes ->
                        val record = encodeRecord(dataset, cursor, sizes, snapshotId, secret, rowOrdinal)
                        val payload = record.toString().toByteArray(StandardCharsets.UTF_8)
                        if (payload.size > MAX_ROW_BYTES) {
                            throw Failure("ROW_LIMIT_EXCEEDED", "A backup record exceeds the supported size.")
                        }
                        offsets.add(rows.filePointer)
                        rows.writeInt(payload.size)
                        rows.write(payload)
                        dataset.spec.blobFields.forEachIndexed { index, field ->
                            val size = sizes[index] ?: return@forEachIndexed
                            val blobId = blobId(secret, snapshotId, rowOrdinal, field.column)
                            val rowValues = record.getJSONObject("values")
                            val rawKey = rowValues.get(field.keyColumn)
                            if (rawKey === JSONObject.NULL) throw Failure("STORAGE_UNAVAILABLE", "A media key is unavailable.")
                            val sha256 = copyBlob(db, dataset.spec.table, field, rawKey.toString(), size, media)
                            val location = BlobLocation(mediaOffset, size, sha256)
                            if (blobs.put(blobId, location) != null) {
                                throw Failure("STORAGE_UNAVAILABLE", "The backup contains duplicate media.")
                            }
                            mediaOffset = Math.addExact(mediaOffset, size)
                            if (rows.length() + media.length() > MAX_SNAPSHOT_BYTES) {
                                throw Failure("SNAPSHOT_LIMIT_EXCEEDED", "The backup exceeds the supported size.")
                            }
                        }
                        rowOrdinal++
                    }
                }
                rows.fd.sync()
                media.fd.sync()
            }
        }
        if (rowOrdinal != preflight.recordCount || mediaOffset != preflight.mediaBytes ||
            blobs.size.toLong() != preflight.mediaCount) {
            throw Failure("STORAGE_UNAVAILABLE", "The backup snapshot changed while it was copied.")
        }
        return offsets.toLongArray() to blobs
    }

    private fun scanLengths(db: SQLiteDatabase, dataset: ResolvedDataset, consume: (Long, List<Long?>) -> Unit) {
        val scalarExpr = dataset.columns.joinToString(" + ") {
            "COALESCE(length(CAST(" + quote(it) + " AS BLOB)),0)"
        }.ifEmpty { "0" }
        val blobExpr = dataset.spec.blobFields.mapIndexed { index, field ->
            "length(" + quote(field.column) + ") AS __blob_size_" + index
        }
        val query = "SELECT (" + scalarExpr + ") AS __scalar_bytes" +
            (if (blobExpr.isEmpty()) "" else "," + blobExpr.joinToString(",")) +
            " FROM " + quote(dataset.spec.table) + " ORDER BY " + orderBy(dataset.primaryKey)
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val sizes = dataset.spec.blobFields.indices.map { index ->
                    val column = cursor.getColumnIndexOrThrow("__blob_size_" + index)
                    if (cursor.isNull(column)) null else cursor.getLong(column)
                }
                if (sizes.any { it != null && it < 0 }) throw Failure("STORAGE_UNAVAILABLE", "The backup contains invalid media data.")
                consume(cursor.getLong(cursor.getColumnIndexOrThrow("__scalar_bytes")), sizes)
            }
        }
    }

    private fun scanRows(db: SQLiteDatabase, dataset: ResolvedDataset, consume: (Cursor, List<Long?>) -> Unit) {
        val selected = dataset.columns.map(::quote).toMutableList()
        dataset.spec.blobFields.forEachIndexed { index, field ->
            selected.add("length(" + quote(field.column) + ") AS __blob_size_" + index)
        }
        val query = "SELECT " + selected.joinToString(",") + " FROM " + quote(dataset.spec.table) +
            " ORDER BY " + orderBy(dataset.primaryKey)
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val sizes = dataset.spec.blobFields.indices.map { index ->
                    val column = cursor.getColumnIndexOrThrow("__blob_size_" + index)
                    if (cursor.isNull(column)) null else cursor.getLong(column)
                }
                consume(cursor, sizes)
            }
        }
    }

    private fun resolveDataset(db: SQLiteDatabase, spec: Dataset): ResolvedDataset {
        val all = mutableListOf<String>()
        val primary = mutableListOf<Pair<Int, String>>()
        val declaredBlobColumns = spec.blobFields.map { it.column }.toSet()
        db.rawQuery("PRAGMA table_info(" + quote(spec.table) + ")", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type")).orEmpty().uppercase()
                val pkOrder = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                all.add(name)
                if (pkOrder > 0) primary.add(pkOrder to name)
                if ("BLOB" in type && name !in declaredBlobColumns) {
                    throw Failure("SCHEMA_UNSUPPORTED", "The backup contains an unsupported binary column.")
                }
            }
        }
        if (all.isEmpty()) throw Failure("SCHEMA_UNSUPPORTED", "A required portable table is unavailable.")
        if (!all.containsAll(spec.excludedColumns) || !all.containsAll(declaredBlobColumns)) {
            throw Failure("SCHEMA_UNSUPPORTED", "A portable table has an unsupported schema.")
        }
        spec.blobFields.forEach { field ->
            if (field.keyColumn !in all || field.keyColumn !in primary.map { it.second }) {
                throw Failure("SCHEMA_UNSUPPORTED", "A media table has an unsupported key.")
            }
        }
        val pk = primary.sortedBy { it.first }.map { it.second }
        if (pk.isEmpty()) throw Failure("SCHEMA_UNSUPPORTED", "A portable table has no stable key.")
        return ResolvedDataset(
            spec,
            all.filterNot { it in spec.excludedColumns || it in declaredBlobColumns },
            pk,
        )
    }

    private fun encodeRecord(
        dataset: ResolvedDataset,
        cursor: Cursor,
        sizes: List<Long?>,
        snapshotId: String,
        secret: ByteArray,
        rowOrdinal: Long,
    ): JSONObject {
        val values = JSONObject()
        dataset.columns.forEach { name ->
            values.put(name, cursorValue(cursor, cursor.getColumnIndexOrThrow(name)) ?: JSONObject.NULL)
        }
        val descriptors = JSONArray()
        dataset.spec.blobFields.forEachIndexed { index, field ->
            val size = sizes[index]
            if (size == null) {
                values.put(field.column, JSONObject.NULL)
            } else {
                descriptors.put(JSONObject()
                    .put("id", blobId(secret, snapshotId, rowOrdinal, field.column))
                    .put("column", field.column)
                    .put("sizeBytes", size))
            }
        }
        return JSONObject()
            .put("type", "row")
            .put("table", dataset.spec.table)
            .put("values", values)
            .put("blobs", descriptors)
    }

    private fun copyBlob(
        db: SQLiteDatabase,
        table: String,
        field: BlobField,
        key: String,
        size: Long,
        target: RandomAccessFile,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while (offset < size) {
            val chunkSize = minOf(STORAGE_BLOB_CHUNK_BYTES.toLong(), size - offset).toInt()
            val start = offset + 1L
            val chunk = db.rawQuery(
                "SELECT substr(" + quote(field.column) + ",?,?) FROM " + quote(table) +
                    " WHERE " + quote(field.keyColumn) + "=?",
                arrayOf(start.toString(), chunkSize.toString(), key),
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) {
                    throw Failure("STORAGE_UNAVAILABLE", "A media item could not be read.")
                }
                cursor.getBlob(0)
            }
            if (chunk.isEmpty() || chunk.size > chunkSize || offset + chunk.size > size) {
                throw Failure("STORAGE_UNAVAILABLE", "A media item could not be read.")
            }
            target.write(chunk)
            digest.update(chunk)
            offset += chunk.size
        }
        if (offset != size) throw Failure("STORAGE_UNAVAILABLE", "A media item could not be read.")
        return digest.digest()
    }

    private fun readRecord(
        file: RandomAccessFile,
        offset: Long,
        blobs: Map<String, BlobLocation>,
    ): Map<String, Any?> {
        file.seek(offset)
        val length = file.readInt()
        if (length !in 1..MAX_ROW_BYTES) throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
        val bytes = ByteArray(length)
        file.readFully(bytes)
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        val decoded = jsonValue(json) as? Map<*, *>
            ?: throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
        val record = LinkedHashMap<String, Any?>()
        decoded.forEach { (key, value) ->
            if (key !is String) throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
            record[key] = value
        }
        val descriptors = record["blobs"] as? List<*>
            ?: throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
        record["blobs"] = descriptors.map { rawDescriptor ->
            val decodedDescriptor = rawDescriptor as? Map<*, *>
                ?: throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
            val descriptor = LinkedHashMap<String, Any?>()
            decodedDescriptor.forEach { (key, value) ->
                if (key !is String) throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
                descriptor[key] = value
            }
            val blobId = descriptor["id"] as? String
                ?: throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
            val location = blobs[blobId]
                ?: throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
            descriptor["sha256"] = Base64.encodeToString(location.sha256, Base64.NO_WRAP)
            descriptor
        }
        return record
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
        else -> throw Failure("STORAGE_UNAVAILABLE", "The staged backup record is invalid.")
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
        Cursor.FIELD_TYPE_BLOB -> throw Failure("SCHEMA_UNSUPPORTED", "A portable binary column is not chunked.")
        else -> throw Failure("SCHEMA_UNSUPPORTED", "A portable field has an unsupported value.")
    }

    private fun requireHandle(snapshotId: String): Handle {
        val handle = active
        if (handle == null || handle.id != snapshotId) {
            throw Failure("SNAPSHOT_UNAVAILABLE", "The backup snapshot is no longer available.")
        }
        val now = elapsedClock()
        if (now >= handle.lastAccessElapsedMillis && now - handle.lastAccessElapsedMillis >= idleTtlMillis) {
            active = null
            destroyHandle(handle)
            throw Failure("SNAPSHOT_EXPIRED", "The backup snapshot expired.")
        }
        handle.lastAccessElapsedMillis = now
        scheduleExpiry(handle)
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
        if (!deleteArtifacts(handle.id)) scheduleCleanupRetry()
    }

    private fun deleteArtifacts(id: String): Boolean {
        if (!idPattern.matches(id)) return true
        cleanupPending.add(id)
        var success = true
        listOf(
            File(directory, FILE_PREFIX + id + "-rows.bin"),
            File(directory, FILE_PREFIX + id + "-media.bin"),
        ).forEach { file ->
            if (file.exists()) {
                val deleted = try { deleteFile(file) } catch (_: Exception) { false }
                if (!deleted && file.exists()) success = false
            }
        }
        if (success) cleanupPending.remove(id)
        return success
    }

    private fun cleanupStaleArtifacts() {
        retryPendingCleanup()
    }

    private fun retryPendingCleanup() {
        val files = directory.listFiles()
            ?: throw Failure("CLEANUP_FAILED", "An unfinished backup snapshot could not be removed.")
        val pattern = Regex("^" + FILE_PREFIX + "([0-9a-f-]{36})-(rows|media)\\.bin$")
        files.filter { it.name.startsWith(FILE_PREFIX) }.forEach { file ->
            val match = pattern.matchEntire(file.name)
            val id = match?.groupValues?.get(1)
            if (id != null && id == active?.id) return@forEach
            if (id != null && idPattern.matches(id)) cleanupPending.add(id)
            else pendingFiles.add(file)
        }
        cleanupPending.toList().forEach { id ->
            deleteArtifacts(id)
        }
        pendingFiles.toList().forEach { file ->
            val deleted = try { !file.exists() || deleteFile(file) } catch (_: Exception) { false }
            if (deleted || !file.exists()) pendingFiles.remove(file)
        }
        if (cleanupPending.isNotEmpty() || pendingFiles.isNotEmpty()) {
            throw Failure("CLEANUP_FAILED", "An unfinished backup snapshot could not be removed.")
        }
        cleanupTask?.cancel()
        cleanupTask = null
    }

    private fun scheduleExpiry(handle: Handle) {
        expiryTask?.cancel()
        expiryTask = null
        val idle = (elapsedClock() - handle.lastAccessElapsedMillis).coerceAtLeast(0L)
        val delay = (idleTtlMillis - idle).coerceAtLeast(1L)
        try {
            expiryTask = scheduler.schedule(delay) { expireIfIdle(handle.id) }
        } catch (_: Exception) {
            if (active?.id == handle.id) active = null
            cancelExpiry()
            handle.secret.fill(0)
            // Leave failed deletions pending for the next store interaction. The
            // scheduler just failed, so don't depend on it for another cleanup task.
            deleteArtifacts(handle.id)
            throw Failure("STORAGE_UNAVAILABLE", "The backup snapshot expiry could not be scheduled.")
        }
    }

    @Synchronized
    private fun expireIfIdle(snapshotId: String) {
        val handle = active
        if (handle == null || handle.id != snapshotId) return
        expiryTask = null
        val now = elapsedClock()
        if (now >= handle.lastAccessElapsedMillis &&
            now - handle.lastAccessElapsedMillis >= idleTtlMillis) {
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
        cleanupTask = scheduler.schedule(60L * 1000L) {
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
            throw Failure("STORAGE_UNAVAILABLE", "Private backup storage is unavailable.")
        }
    }

    private fun requireSupportedSchema(db: SQLiteDatabase) {
        if (db.version != CURRENT_SCHEMA_VERSION) {
            throw Failure("SCHEMA_UNSUPPORTED", "This database schema is not supported for backup.")
        }
    }

    private fun stateVersion(db: SQLiteDatabase): Long = scalarLong(db, "SELECT version FROM state WHERE id=1")

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use {
        if (!it.moveToFirst()) throw Failure("STORAGE_UNAVAILABLE", "The backup state is unavailable.")
        it.getLong(0)
    }

    private fun blobId(secret: ByteArray, snapshotId: String, row: Long, column: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update("blob\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(snapshotId.toByteArray(StandardCharsets.UTF_8))
        mac.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(row).array())
        mac.update(column.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(mac.doFinal(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun encodeCursor(handle: Handle, nextIndex: Int): String {
        val position = ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(nextIndex.toLong()).array()
        val signature = cursorSignature(handle, position)
        return Base64.encodeToString(position + signature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun decodeCursor(handle: Handle, cursor: String): Int {
        val token = try {
            Base64.decode(cursor, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            throw Failure("INVALID_CURSOR", "The backup page cursor is invalid.")
        }
        if (token.size != Long.SIZE_BYTES + 32) throw Failure("INVALID_CURSOR", "The backup page cursor is invalid.")
        val position = token.copyOfRange(0, Long.SIZE_BYTES)
        val signature = token.copyOfRange(Long.SIZE_BYTES, token.size)
        if (!MessageDigest.isEqual(signature, cursorSignature(handle, position))) {
            throw Failure("INVALID_CURSOR", "The backup page cursor is invalid.")
        }
        val index = ByteBuffer.wrap(position).order(ByteOrder.BIG_ENDIAN).long
        if (index < 0 || index > handle.rowOffsets.size) {
            throw Failure("INVALID_CURSOR", "The backup page cursor is invalid.")
        }
        return index.toInt()
    }

    private fun cursorSignature(handle: Handle, position: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("cursor\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(handle.id.toByteArray(StandardCharsets.UTF_8))
        return mac.doFinal(position)
    }

    private fun encodedEnvelopeSize(value: Any): Int =
        StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(value).position()

    private fun orderBy(primaryKey: List<String>): String = primaryKey.joinToString(",") { quote(it) }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun Page.toWireMap(): Map<String, Any?> = mapOf(
        "snapshotId" to snapshotId,
        "nextCursor" to nextCursor,
        "done" to done,
        "records" to records,
    )
}
