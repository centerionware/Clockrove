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
internal typealias LifecycleWireFailure = BackupImportStore.Failure
internal typealias LifecycleWireStarted = BackupImportStore.Started
internal typealias LifecycleWirePreview = BackupImportStore.Preview
internal typealias LifecycleWireConflictPage = BackupImportStore.ConflictPage
internal typealias LifecycleWireApplied = BackupImportStore.Applied
internal typealias LifecycleWireExpected = BackupImportStore.Expected
internal typealias LifecycleWireHandle = BackupImportStore.Handle
internal typealias LifecycleWireNormalizedRow = BackupImportStore.NormalizedRow
internal typealias LifecycleWireApplyRow = BackupImportStore.ApplyRow
internal typealias LifecycleWireForeignKeySpec = BackupImportStore.ForeignKeySpec
internal typealias LifecycleWireHeadAdvance = BackupImportStore.HeadAdvance
internal typealias LifecycleWireDivergentEntityData = BackupImportStore.DivergentEntityData
internal typealias LifecycleWirePlannedDivergentMerge = BackupImportStore.PlannedDivergentMerge
internal typealias LifecycleWirePlannedCustomerDeletionResolution = BackupImportStore.PlannedCustomerDeletionResolution
internal typealias LifecycleWireOmittedBackupGraph = BackupImportStore.OmittedBackupGraph
internal typealias LifecycleWireRevisionContent = BackupImportStore.RevisionContent
internal typealias LifecycleWireRevisionTombstoneRow = BackupImportStore.RevisionTombstoneRow
internal typealias LifecycleWireValidationColumn = BackupImportStore.ValidationColumn
internal typealias LifecycleWireBlobField = BackupImportStore.BlobField
internal typealias LifecycleWireLocalRow = BackupImportStore.LocalRow
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


internal fun BackupImportStore.requireCurrentPreview(
        db: SQLiteDatabase,
        handle: LifecycleWireHandle,
        token: String,
        expectedVersion: Long,
    ): LifecycleWirePreview {
        val preview = handle.preview ?: throw LifecycleWireFailure("PREVIEW_NOT_READY", "The import preview is not ready.")
        verifyPreviewIdentity(handle, preview, token, expectedVersion)
        if (stateVersion(db) != expectedVersion) {
            throw LifecycleWireFailure("PREVIEW_STALE", "Local data changed after this preview; create a new preview.")
        }
        return preview
    }

internal fun BackupImportStore.verifyPreviewIdentity(
        handle: LifecycleWireHandle,
        preview: LifecycleWirePreview,
        token: String,
        expectedVersion: Long,
    ) {
        if (expectedVersion != preview.localStateVersion) {
            throw LifecycleWireFailure("PREVIEW_STALE", "The expected local version does not match this preview.")
        }
        if (!MessageDigest.isEqual(preview.previewToken.toByteArray(), token.toByteArray())) {
            throw LifecycleWireFailure("INVALID_PREVIEW_TOKEN", "The import preview token is invalid.")
        }
    }

internal fun BackupImportStore.safeSummary(values: Map<String, Any?>): Map<String, Any?> =
        summaryColumns.sorted().mapNotNull { column ->
            val value = values[column] ?: return@mapNotNull null
            val safe = when (value) {
                is String -> value.take(160)
                is Number, is Boolean -> value
                else -> return@mapNotNull null
            }
            column to safe
        }.toMap()

internal fun BackupImportStore.safeSummaryObject(values: Map<String, Any?>): JSONObject = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) ->
            if (key in summaryColumns || key == "identifier") put(key, value ?: JSONObject.NULL)
        }
    }

internal fun BackupImportStore.canonicalValues(values: Map<String, Any?>, columns: Set<String>): String {
        val json = JSONObject()
        columns.sorted().forEach { column -> json.put(column, values[column] ?: JSONObject.NULL) }
        return json.toString()
    }

internal fun BackupImportStore.digestFile(file: File, offset: Long, size: Long): ByteArray {
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

internal fun BackupImportStore.digestDatabaseBlob(
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
                    throw LifecycleWireFailure("STORAGE_UNAVAILABLE", "A local media item could not be read.")
                }
                cursor.getBlob(0)
            }
            if (chunk.isEmpty() || chunk.size > chunkSize) {
                throw LifecycleWireFailure("STORAGE_UNAVAILABLE", "A local media item could not be read.")
            }
            digest.update(chunk)
            offset += chunk.size
        }
        return digest.digest()
    }

internal fun BackupImportStore.cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
        Cursor.FIELD_TYPE_BLOB -> throw LifecycleWireFailure("SCHEMA_UNSUPPORTED", "A portable field is not chunked media.")
        else -> throw LifecycleWireFailure("SCHEMA_UNSUPPORTED", "A portable field has an unsupported value.")
    }

internal fun BackupImportStore.jsonValue(value: Any?): Any? = when {
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
        else -> throw LifecycleWireFailure("INVALID_ARCHIVE", "A staged import record is invalid.")
    }

internal fun BackupImportStore.parseConflict(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return jsonValue(JSONObject(json)) as? Map<String, Any?>
            ?: throw LifecycleWireFailure("STORAGE_UNAVAILABLE", "A staged conflict is invalid.")
    }

internal fun BackupImportStore.requireHandle(importId: String): LifecycleWireHandle {
        val handle = active
        if (handle == null || handle.id != importId) {
            throw LifecycleWireFailure("IMPORT_UNAVAILABLE", "The staged backup is no longer available.")
        }
        val now = elapsedClock()
        if (now >= handle.lastAccessElapsedMillis && now - handle.lastAccessElapsedMillis >= idleTtlMillis) {
            active = null
            destroyHandle(handle)
            throw LifecycleWireFailure("IMPORT_EXPIRED", "The staged backup expired.")
        }
        return handle
    }

internal fun BackupImportStore.reapExpired() {
        val handle = active ?: return
        val now = elapsedClock()
        if (now >= handle.lastAccessElapsedMillis && now - handle.lastAccessElapsedMillis >= idleTtlMillis) {
            active = null
            cancelExpiry()
            destroyHandle(handle)
        }
    }

internal fun BackupImportStore.destroyHandle(handle: LifecycleWireHandle) {
        cancelExpiry()
        handle.wipeSecret()
        try { handle.database.close() } catch (_: Exception) { }
        if (!deleteArtifacts(handle.id)) scheduleCleanupRetry()
    }

internal fun BackupImportStore.closeHandleAndDeleteArtifacts(handle: LifecycleWireHandle): Boolean {
        handle.wipeSecret()
        val databaseClosed = try {
            closeDatabase(handle.database)
            true
        } catch (_: Exception) {
            false
        }
        val artifactsDeleted = deleteArtifacts(handle.id)
        if (!artifactsDeleted) scheduleCleanupRetry()
        return databaseClosed && artifactsDeleted
    }

internal fun BackupImportStore.deleteArtifacts(importId: String): Boolean {
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

internal fun BackupImportStore.retryPendingCleanup() {
        val files = directory.listFiles()
            ?: throw LifecycleWireFailure("CLEANUP_FAILED", "An unfinished backup import could not be removed.")
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
            throw LifecycleWireFailure("CLEANUP_FAILED", "An unfinished backup import could not be removed.")
        }
        cleanupTask?.cancel()
        cleanupTask = null
    }

internal fun BackupImportStore.scheduleExpiry(handle: LifecycleWireHandle) {
        cancelExpiry()
        val idle = (elapsedClock() - handle.lastAccessElapsedMillis).coerceAtLeast(0L)
        try {
            expiryTask = scheduler.schedule((idleTtlMillis - idle).coerceAtLeast(1L)) {
                expireIfIdle(handle.id)
            }
        } catch (_: Exception) {
            if (active?.id == handle.id) active = null
            cancelExpiry()
            handle.wipeSecret()
            try { handle.database.close() } catch (_: Exception) { }
            if (!deleteArtifacts(handle.id)) {
                try { scheduleCleanupRetry() } catch (_: Exception) { }
            }
            throw LifecycleWireFailure("STORAGE_UNAVAILABLE", "The staged backup could not be kept secure.")
        }
    }

internal fun BackupImportStore.cancelExpiry() {
        expiryTask?.cancel()
        expiryTask = null
    }

internal fun BackupImportStore.scheduleCleanupRetry() {
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

internal fun BackupImportStore.ensureDirectory() {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw LifecycleWireFailure("STORAGE_UNAVAILABLE", "Private backup import storage is unavailable.")
        }
    }

internal fun BackupImportStore.stagedPhysicalBytes(handle: LifecycleWireHandle): Long = listOf(
        File(handle.database.path),
        File(handle.database.path + "-journal"),
        File(handle.database.path + "-wal"),
        File(handle.database.path + "-shm"),
        handle.mediaFile,
    ).fold(0L) { total, file -> Math.addExact(total, file.length()) }

internal fun BackupImportStore.integer(value: Any?, label: String): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw LifecycleWireFailure("INVALID_ARCHIVE", "A backup $label is invalid.")
    }

private fun conflictPageWireMap(page: LifecycleWireConflictPage): Map<String, Any?> = mapOf(
    "importId" to page.importId,
    "previewToken" to page.previewToken,
    "nextCursor" to page.nextCursor,
    "done" to page.done,
    "conflicts" to page.conflicts,
)

internal fun BackupImportStore.readConflictPageCore(
        db: SQLiteDatabase,
        importId: String,
        previewToken: String,
        cursor: String?,
        requestedMaxBytes: Int?,
    ): LifecycleWireConflictPage {
        val handle = requireHandle(importId)
        val preview = handle.preview ?: throw LifecycleWireFailure("PREVIEW_NOT_READY", "The import preview is not ready.")
        if (!MessageDigest.isEqual(preview.previewToken.toByteArray(), previewToken.toByteArray())) {
            throw LifecycleWireFailure("INVALID_PREVIEW_TOKEN", "The import preview token is invalid.")
        }
        if (stateVersion(db) != preview.localStateVersion) {
            throw LifecycleWireFailure("PREVIEW_STALE", "Local data changed after this preview; create a new preview.")
        }
        val maxBytes = requestedMaxBytes ?: MAX_CONFLICT_PAGE_BYTES
        if (maxBytes !in MIN_CONFLICT_PAGE_BYTES..MAX_CONFLICT_PAGE_BYTES) {
            throw LifecycleWireFailure("INVALID_ARGUMENT", "The conflict page size is invalid.")
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
                val candidatePage = LifecycleWireConflictPage(
                    importId, preview.previewToken,
                    if (candidateNext < total) encodeCursor(handle, preview, candidateNext) else null,
                    candidateNext >= total, conflicts,
                )
                if (encodedEnvelopeSize(conflictPageWireMap(candidatePage)) > maxBytes) {
                    conflicts.removeAt(conflicts.lastIndex)
                    if (conflicts.isEmpty()) throw LifecycleWireFailure("PAGE_LIMIT_TOO_SMALL", "One conflict exceeds the requested page size.")
                    break
                }
                nextIndex = candidateNext
            }
        }
        val done = nextIndex >= total
        handle.lastAccessElapsedMillis = elapsedClock()
        scheduleExpiry(handle)
        return LifecycleWireConflictPage(
            importId, preview.previewToken,
            if (done) null else encodeCursor(handle, preview, nextIndex),
            done, conflicts,
        )
    }

internal fun BackupImportStore.closeImportCore(importId: String): Boolean {
        val handle = active
        if (handle != null && handle.id == importId) {
            active = null
            cancelExpiry()
            if (!closeHandleAndDeleteArtifacts(handle)) {
                throw LifecycleWireFailure("CLEANUP_FAILED", "The staged backup could not be removed.")
            }
            return true
        }
        if (importId in cleanupPending) {
            retryPendingCleanup()
            return true
        }
        return false
    }

internal fun BackupImportStore.closeAllImportsCore() {
        val handle = active
        active = null
        cancelExpiry()
        if (handle != null) {
            if (!closeHandleAndDeleteArtifacts(handle)) {
                throw LifecycleWireFailure("CLEANUP_FAILED", "The staged backup could not be removed.")
            }
        }
        retryPendingCleanup()
    }
