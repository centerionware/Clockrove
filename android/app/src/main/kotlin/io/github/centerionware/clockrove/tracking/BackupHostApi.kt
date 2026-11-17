package io.github.centerionware.clockrove.tracking

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec

/** Bounded export and authenticated-import staging boundary for Dart's archive flow. */
internal class BackupHostApi(
    context: Context,
    messenger: BinaryMessenger,
    private val repository: TimerRepository = TimerRepository.get(context.applicationContext),
) {
    private val channel = MethodChannel(
        messenger,
        "clockrove/backup/v1",
        io.flutter.plugin.common.StandardMethodCodec.INSTANCE,
        messenger.makeBackgroundTaskQueue(),
    )

    init {
        channel.setMethodCallHandler { call, result ->
            try {
                val response = dispatch(call)
                if (response == null) result.notImplemented() else result.success(response)
            } catch (failure: BackupExportStore.Failure) {
                result.error(failure.code, failure.message, null)
            } catch (failure: BackupImportStore.Failure) {
                result.error(failure.code, failure.message, null)
            } catch (_: IllegalArgumentException) {
                result.error("INVALID_ARGUMENT", "Invalid backup request", null)
            } catch (_: ClassCastException) {
                result.error("INVALID_ARGUMENT", "Invalid backup request", null)
            } catch (_: Exception) {
                result.error("STORAGE_UNAVAILABLE", "Backup storage is unavailable", null)
            }
        }
    }

    fun detach() {
        channel.setMethodCallHandler(null)
        try {
            repository.discardBackupOperations()
        } catch (_: Exception) {
            // Activity teardown must continue; the next backup request retries cleanup.
        }
    }

    private fun dispatch(call: MethodCall): Any? {
        val input = Input(call.arguments)
        return when (call.method) {
            "getBackupSnapshotInfo" -> repository.getBackupSnapshotInfo().let { info ->
                mapOf(
                    "archiveFormatVersion" to info.archiveFormatVersion,
                    "schemaVersion" to info.schemaVersion,
                    "stateVersion" to info.stateVersion,
                    "maxSnapshotBytes" to info.maxSnapshotBytes,
                    "maxPageBytes" to info.maxPageBytes,
                    "maxBlobChunkBytes" to info.maxBlobChunkBytes,
                    "maxRecords" to info.maxRecords,
                    "maxMediaCount" to info.maxMediaCount,
                    "idleExpiryMillis" to info.idleExpiryMillis,
                    "portableTables" to info.portableTables,
                )
            }
            "startBackupSnapshot" -> repository.startBackupSnapshot().let { started ->
                mapOf(
                    "snapshotId" to started.snapshotId,
                    "archiveFormatVersion" to started.archiveFormatVersion,
                    "schemaVersion" to started.schemaVersion,
                    "stateVersion" to started.stateVersion,
                    "capturedAtUtcMillis" to started.capturedAtUtcMillis,
                    "recordCount" to started.recordCount,
                    "mediaCount" to started.mediaCount,
                    "mediaBytes" to started.mediaBytes,
                    "openSessionCount" to started.openSessionCount,
                )
            }
            "readBackupSnapshotPage" -> repository.readBackupSnapshotPage(
                input.string("snapshotId"),
                input.nullableString("cursor"),
                input.nullableInt("maxBytes"),
            ).let { page ->
                mapOf(
                    "snapshotId" to page.snapshotId,
                    "nextCursor" to page.nextCursor,
                    "done" to page.done,
                    "records" to page.records,
                )
            }
            "readBackupBlob" -> repository.readBackupBlob(
                input.string("snapshotId"),
                input.string("blobId"),
                input.long("offset"),
                input.nullableInt("maxBytes"),
            ).let { chunk ->
                mapOf(
                    "offset" to chunk.offset,
                    "nextOffset" to chunk.nextOffset,
                    "done" to chunk.done,
                    "bytes" to chunk.bytes,
                )
            }
            "closeBackupSnapshot" -> mapOf(
                "closed" to repository.closeBackupSnapshot(input.string("snapshotId")),
            )
            "beginBackupImport" -> repository.beginBackupImport(
                input.int("archiveFormatVersion"),
                input.int("schemaVersion"),
                input.string("archiveDigest"),
                input.long("recordCount"),
                input.long("mediaCount"),
                input.long("mediaBytes"),
            ).let { started ->
                mapOf(
                    "importId" to started.importId,
                    "archiveFormatVersion" to started.archiveFormatVersion,
                    "schemaVersion" to started.schemaVersion,
                    "maxPageBytes" to started.maxPageBytes,
                    "maxBlobChunkBytes" to started.maxBlobChunkBytes,
                    "idleExpiryMillis" to started.idleExpiryMillis,
                )
            }
            "appendBackupImportPage" -> mapOf(
                "nextOrdinal" to repository.appendBackupImportPage(
                    input.string("importId"),
                    input.long("firstOrdinal"),
                    input.list("records"),
                    encodedCallBytes(call),
                ),
            )
            "writeBackupImportBlob" -> mapOf(
                "nextOffset" to repository.writeBackupImportBlob(
                    input.string("importId"),
                    input.string("blobId"),
                    input.long("offset"),
                    input.bytes("bytes"),
                    encodedCallBytes(call),
                ),
            )
            "finishBackupImport" -> repository.finishBackupImport(input.string("importId")).toWireMap()
            "readBackupImportConflictPage" -> repository.readBackupImportConflictPage(
                input.string("importId"),
                input.string("previewToken"),
                input.nullableString("cursor"),
                input.nullableInt("maxBytes"),
            ).let { page ->
                mapOf(
                    "importId" to page.importId,
                    "previewToken" to page.previewToken,
                    "nextCursor" to page.nextCursor,
                    "done" to page.done,
                    "conflicts" to page.conflicts,
                )
            }
            "resolveBackupImportConflict" -> {
                repository.resolveBackupImportConflict(
                    input.string("importId"),
                    input.string("previewToken"),
                    input.long("expectedLocalStateVersion"),
                    input.string("conflictId"),
                    input.string("choice"),
                )
                mapOf("resolved" to true)
            }
            "applyBackupImport" -> repository.applyBackupImport(
                input.string("importId"),
                input.string("previewToken"),
                input.long("expectedLocalStateVersion"),
            ).let { applied ->
                mapOf(
                    "importId" to applied.importId,
                    "stateVersion" to applied.stateVersion,
                    "insertedCount" to applied.insertedCount,
                    "skippedCount" to applied.skippedCount,
                )
            }
            "closeBackupImport" -> mapOf(
                "closed" to repository.closeBackupImport(input.string("importId")),
            )
            else -> null
        }
    }

    private fun encodedCallBytes(call: MethodCall): Int = StandardMethodCodec.INSTANCE.encodeMethodCall(call).position()

    private fun BackupImportStore.Preview.toWireMap(): Map<String, Any?> = mapOf(
        "importId" to importId,
        "previewToken" to previewToken,
        "schemaVersion" to schemaVersion,
        "localStateVersion" to localStateVersion,
        "recordCount" to recordCount,
        "mediaCount" to mediaCount,
        "mediaBytes" to mediaBytes,
        "stagedRecordBytes" to stagedRecordBytes,
        "addedCount" to addedCount,
        "changedCount" to changedCount,
        "deletedCount" to deletedCount,
        "unchangedCount" to unchangedCount,
        "unresolvedReferenceCount" to unresolvedReferenceCount,
        "timeOverlapCount" to timeOverlapCount,
        "conflictCount" to conflictCount,
        "openSessionCount" to openSessionCount,
        "localOpenSessionCount" to localOpenSessionCount,
        "applyReady" to applyReady,
    )

    private class Input(arguments: Any?) {
        private val values: Map<*, *> = when (arguments) {
            null -> emptyMap<String, Any?>()
            is Map<*, *> -> arguments
            else -> throw IllegalArgumentException("Expected a map")
        }

        fun string(key: String): String = (values[key] as? String)
            ?.also { require(it.isNotBlank() && it.length <= 128) }
            ?: throw IllegalArgumentException("Missing string")

        fun nullableString(key: String): String? {
            val raw = values[key] ?: return null
            return (raw as? String)?.also { require(it.length <= 128) }
                ?: throw IllegalArgumentException("Invalid string")
        }

        fun long(key: String): Long {
            val raw = values[key]
            require(raw is Int || raw is Long)
            return (raw as Number).toLong()
        }

        fun int(key: String): Int {
            val raw = values[key]
            require(raw is Int || raw is Long)
            val value = (raw as Number).toLong()
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return value.toInt()
        }

        fun list(key: String): List<*> = (values[key] as? List<*>)
            ?.also { require(it.isNotEmpty() && it.size <= BackupImportStore.MAX_ROW_COUNT_PER_PAGE) }
            ?: throw IllegalArgumentException("Missing list")

        fun bytes(key: String): ByteArray = (values[key] as? ByteArray)
            ?.also { require(it.size <= BackupImportStore.MAX_BLOB_CHUNK_BYTES) }
            ?: throw IllegalArgumentException("Missing bytes")

        fun nullableInt(key: String): Int? {
            val raw = values[key] ?: return null
            require(raw is Int || raw is Long)
            val value = (raw as Number).toLong()
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return value.toInt()
        }
    }
}
