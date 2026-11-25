package io.github.centerionware.clockrove.invoice

import android.content.res.Configuration
import io.github.centerionware.clockrove.languagePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class InvoicePreviewLanguageTest {
    private fun device() = RuntimeEnvironment.getApplication().let { app ->
        app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
    }

    @Test
    fun samplePreviewFollowsTheLanguageTheScreenIsShowing() {
        val device = device()
        val preferences = languagePreferences(device)
        try {
            preferences.edit().remove("language").commit()
            assertEquals("Sample Company LLC", InvoiceDocuments.sampleSnapshot(device, "MODERN", "USD", 2).company)

            // The phone stays English while the app is set to Spanish.
            assertEquals(
                "Empresa de ejemplo S. L.",
                InvoiceDocuments.sampleSnapshot(device, "MODERN", "USD", 2, "es").company,
            )

            // The UI language wins over a stale preference, so a changed language never lags behind.
            assertTrue(preferences.edit().putString("language", "es").commit())
            assertEquals(
                "Beispielunternehmen GmbH",
                InvoiceDocuments.sampleSnapshot(device, "MINIMAL", "EUR", 2, "de").company,
            )
            assertEquals(
                "Kitchen tile installation",
                InvoiceDocuments.sampleSnapshot(device, "MINIMAL", "EUR", 2, "en").job,
            )
        } finally {
            preferences.edit().remove("language").commit()
        }
    }
}
