package io.github.centerionware.clockrove.invoice

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Temporary, grant-only destination for one camera-intent capture. */
class TaskCaptureProvider : ContentProvider() {
    override fun onCreate() = true

    private fun capture(uri: Uri): File {
        val parts = uri.pathSegments
        val name = parts.getOrNull(1).orEmpty()
        if (uri.scheme != "content" || uri.authority != "${requireNotNull(context).packageName}.task-capture" ||
            parts.size != 2 || parts[0] != "capture" || !name.matches(Regex("capture-[A-Za-z0-9_-]+\\.jpg"))) {
            throw FileNotFoundException("Capture unavailable")
        }
        val directory = File(requireNotNull(context).cacheDir, "task-captures")
        return File(directory, name).canonicalFile.also {
            if (it.parentFile != directory.canonicalFile || !it.isFile) throw FileNotFoundException("Capture unavailable")
        }
    }

    override fun getType(uri: Uri): String { capture(uri); return "image/jpeg" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = capture(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } }.toTypedArray<Any?>())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = capture(uri)
        val flags = when (mode) {
            "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("Write access required")
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Write through the camera output stream")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Write through the camera output stream")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Write through the camera output stream")
}
