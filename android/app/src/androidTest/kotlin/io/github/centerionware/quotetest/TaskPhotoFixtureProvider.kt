package io.github.centerionware.quotetest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Read-only content URI used by the test gallery picker; contains synthetic pixels only. */
class TaskPhotoFixtureProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fixture(uri: Uri): File {
        if (uri.scheme != "content" ||
            uri.authority != "io.github.centerionware.clockrove.test.task-photo-fixtures" ||
            uri.pathSegments != listOf("task-photo-fixture.jpg")) {
            throw FileNotFoundException("Synthetic photo unavailable")
        }
        return File(requireNotNull(context).cacheDir, "task-photo-fixture.jpg")
            .canonicalFile.also {
                if (it.parentFile != requireNotNull(context).cacheDir.canonicalFile || !it.isFile) {
                    throw FileNotFoundException("Synthetic photo unavailable")
                }
            }
    }

    override fun getType(uri: Uri): String {
        fixture(uri)
        return "image/jpeg"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only synthetic photo")
        return ParcelFileDescriptor.open(fixture(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Synthetic photo is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Synthetic photo is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Synthetic photo is read-only")
}
