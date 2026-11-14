package io.github.centerionware.clockrove.maps

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android Keystore and encrypted persistence on the CI emulators. */
@RunWith(AndroidJUnit4::class)
class MapProviderSettingsStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = MapProviderSettingsStore(context)

    @Test
    fun customSettingsRoundTripAsCiphertextAndResetToOsm() {
        store.reset()
        val secret = "Bearer map-provider-test-secret"
        try {
            val saved = store.save(
                mapOf(
                    "provider" to "CUSTOM",
                    "urlTemplate" to "https://tiles.example.test/{z}/{x}/{y}.png",
                    "attribution" to "Example tile provider",
                    "attributionUrl" to "https://example.test/terms",
                    "authHeader" to "Authorization",
                    "authSecret" to secret,
                ),
            )

            assertEquals("CUSTOM", saved.provider)
            val restored = store.read()
            assertEquals(saved.provider, restored.provider)
            assertEquals(saved.urlTemplate, restored.urlTemplate)
            assertEquals(saved.attribution, restored.attribution)
            assertEquals(saved.attributionUrl, restored.attributionUrl)
            assertEquals(saved.authHeader, restored.authHeader)
            assertEquals(secret, restored.authSecret)
            assertEquals(true, restored.toPublicMap()["hasCredential"])
            assertFalse(
                "Public settings contain the secret",
                restored.toPublicMap().containsKey("authSecret"),
            )
            assertEquals(secret, restored.tileCredentialMap()?.get("authSecret"))
            val encryptedWithCredential = context.getSharedPreferences(
                "clockrove-map-provider",
                Context.MODE_PRIVATE,
            ).getString("encrypted-settings-v1", null)
            assertNotNull(encryptedWithCredential)
            assertFalse(
                "Credential was stored as plaintext",
                encryptedWithCredential!!.contains(secret),
            )

            val editedWithoutReturningTheCredential = store.save(
                mapOf(
                    "provider" to "CUSTOM",
                    "urlTemplate" to saved.urlTemplate,
                    "attribution" to "Updated attribution",
                    "attributionUrl" to saved.attributionUrl,
                    "authHeader" to saved.authHeader,
                ),
            )
            assertEquals(secret, editedWithoutReturningTheCredential.authSecret)
            assertEquals("Updated attribution", store.read().attribution)

            val removed = store.save(
                mapOf(
                    "provider" to "CUSTOM",
                    "urlTemplate" to saved.urlTemplate,
                    "attribution" to "Updated attribution",
                    "attributionUrl" to saved.attributionUrl,
                    "authHeader" to saved.authHeader,
                    "removeCredential" to true,
                ),
            )
            assertEquals(false, removed.toPublicMap()["hasCredential"])
            assertNull(removed.tileCredentialMap())

            val encrypted = context.getSharedPreferences(
                "clockrove-map-provider",
                Context.MODE_PRIVATE,
            ).getString("encrypted-settings-v1", null)
            assertNotNull(encrypted)
            assertFalse("Credential was stored as plaintext", encrypted!!.contains(secret))

            val defaults = store.reset()
            assertEquals("OSM", defaults.provider)
            assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", defaults.urlTemplate)
            assertEquals("", defaults.authSecret)
        } finally {
            store.reset()
        }
    }

    @Test
    fun invalidTemplatesAndEmbeddedCredentialsAreRejected() {
        store.reset()
        try {
            val valid = mapOf<String, Any?>(
                "provider" to "CUSTOM",
                "urlTemplate" to "https://tiles.example.test/{z}/{x}/{y}.png",
                "attribution" to "Example tile provider",
            )
            val invalidUrls = listOf(
                "http://tiles.example.test/{z}/{x}/{y}.png",
                "https://user:password@tiles.example.test/{z}/{x}/{y}.png",
                "https://tiles.example.test/{z}/{x}/{y}.png?api_key=private",
                "https://tiles.example.test/{z}/{x}/{y}.png?accessToken=private",
                "https://tiles.example.test/{z}/{x}/{y}.png#fragment",
            )
            for (url in invalidUrls) {
                try {
                    store.save(valid + ("urlTemplate" to url))
                    fail("Accepted unsafe tile URL: $url")
                } catch (expected: IllegalArgumentException) {
                    // Rejected before persisting any configuration.
                }
            }
            try {
                store.save(valid + mapOf("authSecret" to "line one\nline two"))
                fail("Accepted a line break in the authentication value")
            } catch (expected: IllegalArgumentException) {
                // Header values cannot inject another HTTP header.
            }
        } finally {
            store.reset()
        }
    }
}
