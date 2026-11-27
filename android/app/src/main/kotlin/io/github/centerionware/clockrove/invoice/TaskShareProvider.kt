package io.github.centerionware.clockrove.invoice

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.centerionware.clockrove.tracking.TaskConversationStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.io.File
import java.io.FileNotFoundException

/** Grants the chooser temporary read access to stored task images by id. */
class TaskShareProvider : ContentProvider() {
    private fun attachment(uri: Uri): TaskConversationStore.Attachment {
        val parts = uri.pathSegments
        if (uri.scheme != "content" ||
            uri.authority != "${requireNotNull(context).packageName}.task-share" ||
            parts.size != 2 ||
            parts[0] != "images" ||
            !parts[1].matches(ATTACHMENT_ID)
        ) {
            throw FileNotFoundException("Task image unavailable")
        }
        return try {
            requireNotNull(TimerRepository.get(requireNotNull(context)).taskAttachment(parts[1]))
        } catch (_: Exception) {
            throw FileNotFoundException("Task image unavailable")
        }
    }

    private fun imageType(bytes: ByteArray): String =
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) "image/png"
        else "image/jpeg"

    override fun onCreate() = true

    override fun getType(uri: Uri): String = imageType(attachment(uri).bytes)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val image = attachment(uri)
        val extension = if (imageType(image.bytes) == "image/png") "png" else "jpg"
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "clockrove-task-${image.id}.$extension"
                    OpenableColumns.SIZE -> image.bytes.size.toLong()
                    else -> null
                }
            }.toTypedArray<Any?>())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val image = attachment(uri)
        val temporary = File.createTempFile(
            "task-share-",
            if (imageType(image.bytes) == "image/png") ".png" else ".jpg",
            requireNotNull(context).cacheDir,
        )
        try {
            temporary.writeBytes(image.bytes)
            return ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            temporary.delete()
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException("Read only")

    companion object {
        private val ATTACHMENT_ID = Regex("[A-Za-z0-9_-]{1,200}")

        fun uri(context: Context, attachmentId: String): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.task-share")
            .appendPath("images")
            .appendPath(attachmentId)
            .build()
    }
}
