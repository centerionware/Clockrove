package io.github.centerionware.clockrove.invoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import io.github.centerionware.clockrove.tracking.LogoStore
import io.github.centerionware.clockrove.tracking.ImageLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Bounded private copy. Re-encoding strips source EXIF/GPS/filename metadata. */
internal object LogoImages {
    fun normalize(context: Context, input: InputStream): LogoStore.Asset {
        val directory = File(context.cacheDir, "image-imports").apply {
            require(isDirectory || mkdirs()) { "IMAGE_IMPORT_UNAVAILABLE" }
        }
        val source = File.createTempFile("source-", ".image", directory)
        try {
            source.outputStream().buffered().use { output ->
                val buffer = ByteArray(16_384)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= ImageLimits.SOURCE_BYTES) { "LOGO_TOO_LARGE" }
                    output.write(buffer, 0, read)
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            require(supportsSourceBounds(bounds.outMimeType, bounds.outWidth, bounds.outHeight)) { "INVALID_LOGO" }
            val orientation = try {
                ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: java.io.IOException) { ExifInterface.ORIENTATION_NORMAL }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
            }
            var bitmap = requireNotNull(BitmapFactory.decodeFile(source.absolutePath, options)) { "INVALID_LOGO" }
            try {
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                }
                if (!matrix.isIdentity) {
                    val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (transformed !== bitmap) { bitmap.recycle(); bitmap = transformed }
                }
                val scale = minOf(1.0, 1024.0 / bitmap.width, 1024.0 / bitmap.height)
                if (scale < 1) {
                    val reduced = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                    if (reduced !== bitmap) { bitmap.recycle(); bitmap = reduced }
                }
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                require(output.size() <= ImageLimits.NORMALIZED_BYTES) { "LOGO_TOO_LARGE" }
                val canonical = output.toByteArray()
                return LogoStore.Asset(LogoStore.hash(canonical), bitmap.width, bitmap.height, canonical)
            } finally {
                bitmap.recycle()
            }
        } finally {
            source.delete()
            if (directory.listFiles().isNullOrEmpty()) directory.delete()
        }
    }
    fun remember(context: Context, asset: LogoStore.Asset) {
        val directory = File(context.cacheDir, "logo-drafts").apply { mkdirs() }
        val existing = directory.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
        existing.forEachIndexed { index, file -> if (index >= 9 || System.currentTimeMillis() - file.lastModified() > 86400000) file.delete() }
        val temporary = File.createTempFile("copy-", ".png", directory)
        try {
            temporary.writeBytes(asset.bytes)
            val destination = File(directory, "${asset.id}.png")
            if (!temporary.renameTo(destination)) { if (!destination.exists()) error("LOGO_COPY_UNAVAILABLE") }
        } finally { temporary.delete() }
    }
    fun recall(context: Context, token: String): LogoStore.Asset? {
        require(token.matches(Regex("[a-f0-9]{64}"))) { "INVALID_LOGO" }
        val file = File(File(context.cacheDir, "logo-drafts"), "$token.png")
        if (!file.isFile || file.length() !in 1..ImageLimits.NORMALIZED_BYTES.toLong()) return null
        val bytes = try { file.readBytes() } catch (_: java.io.IOException) { return null }
        if (LogoStore.hash(bytes) != token) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/png" || bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
        return LogoStore.Asset(token, bounds.outWidth, bounds.outHeight, bytes)
    }

    internal fun supportsSourceBounds(mime: String?, width: Int, height: Int): Boolean =
        mime in setOf("image/png", "image/jpeg", "image/webp") &&
            width in 1..ImageLimits.SOURCE_DIMENSION &&
            height in 1..ImageLimits.SOURCE_DIMENSION &&
            width.toLong() * height <= ImageLimits.SOURCE_PIXELS
}
