package io.github.centerionware.clockrove

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** CI rendering harness. These previews do not prove real launcher/device behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeVisualPreviewTest {
    @Test fun renderAndroidVisualResources() {
        val app = RuntimeEnvironment.getApplication()
        val root = System.getenv("GITHUB_WORKSPACE")?.let { File(it, "build/native-ui-preview") }
            ?: File("build/native-ui-preview")
        root.mkdirs()
        fun save(name: String, drawable: android.graphics.drawable.Drawable, width: Int, height: Int) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        save("clockrove-legacy-icon", app.getDrawable(R.drawable.ic_clockrove_legacy)!!, 256, 256)
        val adaptive = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        save("clockrove-adaptive-icon", adaptive, 256, 256)
        save("clockrove-themed-mark", adaptive.monochrome!!.apply { setTint(Color.rgb(23, 107, 85)) }, 256, 256)
        for (dark in listOf(false, true)) {
            val configuration = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val context = app.createConfigurationContext(configuration)
            save("clockrove-launch-${if (dark) "dark" else "light"}",
                context.getDrawable(R.drawable.launch_background)!!, 420, 920)
        }
    }
}
