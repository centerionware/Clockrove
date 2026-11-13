package io.github.centerionware.quotetest

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle

/** Deterministic ACTION_OPEN_DOCUMENT handler serving a synthetic gallery image. */
class TaskPhotoGalleryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val file = java.io.File(cacheDir, "task-photo-fixture.jpg")
            val bitmap = Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(176, 96, 32))
                file.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
                }
            } finally {
                bitmap.recycle()
            }
            val uri = Uri.parse(
                "content://$packageName.task-photo-fixtures/task-photo-fixture.jpg",
            )
            setResult(
                RESULT_OK,
                Intent().setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (_: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
