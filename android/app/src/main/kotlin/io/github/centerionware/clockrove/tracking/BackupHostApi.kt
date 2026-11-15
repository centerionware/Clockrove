package io.github.centerionware.clockrove.tracking

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Bounded read-only export boundary for Dart's authenticated archive writer. */
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
            repository.discardBackupSnapshot()
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
            else -> null
        }
    }

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

        fun nullableInt(key: String): Int? {
            val raw = values[key] ?: return null
            require(raw is Int || raw is Long)
            val value = (raw as Number).toLong()
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return value.toInt()
        }
    }
}
