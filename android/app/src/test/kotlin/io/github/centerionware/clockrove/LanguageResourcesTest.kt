package io.github.centerionware.clockrove

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class LanguageResourcesTest {
    @Test
    fun nativeResourcesFollowOverrideAndReturnToDeviceLanguageWhenCleared() {
        val app = RuntimeEnvironment.getApplication()
        val device = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val preferences = languagePreferences(device)
        try {
            assertTrue(preferences.edit().putString("language", "es").commit())
            val spanish = localizedContext(device)
            assertEquals("es", spanish.resources.configuration.locales[0].language)
            assertEquals("Tareas de Clockrove", spanish.getString(R.string.widget_tasks_label))
            assertEquals("Fichar entrada en Synthetic job", spanish.getString(R.string.widget_clock_in_job, "Synthetic job"))

            assertTrue(preferences.edit().remove("language").commit())
            assertSame(device, localizedContext(device))
            assertEquals("Clockrove Tasks", device.getString(R.string.widget_tasks_label))
        } finally {
            preferences.edit().remove("language").commit()
        }
    }
}
