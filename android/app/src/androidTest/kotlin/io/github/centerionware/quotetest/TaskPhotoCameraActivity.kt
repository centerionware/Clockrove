package io.github.centerionware.quotetest

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/** Test camera for exercising the real ACTION_IMAGE_CAPTURE and URI grant path. */
class TaskPhotoCameraActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val output = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
            requireNotNull(output)
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(32, 128, 96))
                requireNotNull(contentResolver.openOutputStream(output, "w")).use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                }
            } finally {
                bitmap.recycle()
            }
            setResult(RESULT_OK, Intent())
        } catch (_: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
