package io.github.centerionware.clockrove.invoice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogoImagesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(23,107,85))
        return ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 95, it)); bitmap.recycle() }.toByteArray()
    }
    @Test fun scalesPrivatelyWithoutChangingAspectAndNormalizesAllAcceptedFormatsToPng() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)) {
            val asset = LogoImages.normalize(context, ByteArrayInputStream(image(2048,512,format)))
            assertEquals(1024, asset.width); assertEquals(256, asset.height); assertTrue(asset.bytes.size <= 5_000_000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(asset.bytes,0,asset.bytes.size,bounds); assertEquals("image/png", bounds.outMimeType)
            LogoImages.remember(context, asset); assertArrayEquals(asset.bytes, LogoImages.recall(context, asset.id)!!.bytes)
        }
    }
    @Test fun jpegOrientationIsAppliedAndSourceExifGpsMetadataIsRemoved() {
        val file = File.createTempFile("synthetic-logo-exif", ".jpg", context.cacheDir)
        try {
            file.writeBytes(image(80,40,Bitmap.CompressFormat.JPEG))
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "4/1,5/1,6/1"); setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E"); saveAttributes()
            }
            assertNotNull(ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            val asset = file.inputStream().use { LogoImages.normalize(context, it) }
            assertEquals(40,asset.width); assertEquals(80,asset.height)
            var offset = 8; val types = mutableListOf<String>()
            while (offset + 12 <= asset.bytes.size) { val length = ByteBuffer.wrap(asset.bytes,offset,4).int; types.add(String(asset.bytes,offset+4,4,Charsets.US_ASCII)); offset += length + 12 }
            assertFalse(types.any { it in setOf("eXIf","tEXt","iTXt","zTXt") }); assertTrue(types.contains("IEND"))
        } finally { file.delete() }
    }
    @Test fun invalidTypesHugeInputAndMaliciousPixelBoundsAreRejectedBeforeFullDecode() {
        val hugeBounds = image(1,1).copyOf()
        ByteBuffer.wrap(hugeBounds).putInt(16,32768).putInt(20,32768)
        val crc = CRC32().apply { update(hugeBounds,12,17) }.value.toInt(); ByteBuffer.wrap(hugeBounds).putInt(29,crc)
        for (bytes in listOf("<svg></svg>".toByteArray(), hugeBounds)) {
            try { LogoImages.normalize(context, ByteArrayInputStream(bytes)); fail("Unsafe image accepted") } catch (_: IllegalArgumentException) {}
        }
        val oversized = object : InputStream() {
            private var remaining = 50_000_001
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length, remaining)
                java.util.Arrays.fill(target, offset, offset + count, 0.toByte())
                remaining -= count
                return count
            }
        }
        try { LogoImages.normalize(context, oversized); fail("Oversized source accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun sourceBoundsAllowModernHighResolutionPhotosWithinTheNewResourceCap() {
        assertTrue(LogoImages.supportsSourceBounds("image/jpeg", 16_384, 12_288))
        assertTrue(LogoImages.supportsSourceBounds("image/jpeg", 20_000, 12_000))
        assertFalse(LogoImages.supportsSourceBounds("image/jpeg", 20_000, 13_000))
        assertFalse(LogoImages.supportsSourceBounds("image/jpeg", 32_769, 100))
        assertFalse(LogoImages.supportsSourceBounds("image/svg+xml", 100, 100))
    }
    @Test fun corruptedOrMissingPrivateDraftDoesNotBecomeACompanyLogo() {
        val asset = LogoImages.normalize(context, ByteArrayInputStream(image(2,1)))
        LogoImages.remember(context,asset)
        File(File(context.cacheDir,"logo-drafts"),"${asset.id}.png").writeBytes(byteArrayOf(1,2,3))
        assertNull(LogoImages.recall(context,asset.id)); assertNull(LogoImages.recall(context,"0".repeat(64)))
        try { LogoImages.recall(context,"../../not-an-image"); fail("Path accepted") } catch (_: IllegalArgumentException) {}
    }
}
