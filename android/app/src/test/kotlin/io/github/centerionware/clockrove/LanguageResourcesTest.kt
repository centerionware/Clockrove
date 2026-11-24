package io.github.centerionware.clockrove

import android.content.res.Configuration
import io.github.centerionware.clockrove.widget.formatDueDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun widgetTaskCopyFollowsTheSavedLanguageNotThePhoneLanguage() {
        val app = RuntimeEnvironment.getApplication()
        val device = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val preferences = languagePreferences(device)
        try {
            assertEquals("Due soon · all jobs", device.getString(R.string.widget_due_all))
            assertTrue(preferences.edit().putString("language", "de").commit())
            val german = localizedContext(device)
            assertEquals("Bald fällig · alle Aufträge", german.getString(R.string.widget_due_all))
            assertEquals("Bald fällig · Synthetic job", german.getString(R.string.widget_due_job, "Synthetic job"))
            assertEquals("Heute", german.getString(R.string.widget_today))
        } finally {
            preferences.edit().remove("language").commit()
        }
    }

    @Test
    fun widgetDueDatesUseTheSavedLanguageDateFormat() {
        val spanish = formatDueDate("2026-11-29", Locale.forLanguageTag("es"))
        val english = formatDueDate("2026-11-29", Locale.US)
        assertNotEquals("2026-11-29", spanish)
        assertNotEquals("2026-11-29", english)
        assertTrue(spanish.contains("2026"))
        assertNotEquals(english, spanish)
        assertEquals("not-a-date", formatDueDate("not-a-date", Locale.US))
    }
}
