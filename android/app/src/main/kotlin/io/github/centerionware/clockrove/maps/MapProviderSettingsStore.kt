package io.github.centerionware.clockrove.maps

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Stores provider configuration and credentials as one authenticated ciphertext. */
internal class MapProviderSettingsStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    internal class Settings(
        val provider: String,
        val urlTemplate: String,
        val attribution: String,
        val attributionUrl: String,
        val authHeader: String,
        val authSecret: String,
    ) {
        fun toPublicMap() = mapOf<String, Any?>(
            "provider" to provider,
            "urlTemplate" to urlTemplate,
            "attribution" to attribution,
            "attributionUrl" to attributionUrl,
            "authHeader" to authHeader,
            "hasCredential" to authSecret.isNotEmpty(),
        )

        fun tileCredentialMap(): Map<String, String>? =
            if (provider == CUSTOM_PROVIDER && authSecret.isNotEmpty()) {
                mapOf("authHeader" to authHeader, "authSecret" to authSecret)
            } else {
                null
            }

        fun toJson() = JSONObject(
            mapOf(
                "provider" to provider,
                "urlTemplate" to urlTemplate,
                "attribution" to attribution,
                "attributionUrl" to attributionUrl,
                "authHeader" to authHeader,
                "authSecret" to authSecret,
            ),
        ).toString()

        companion object {
            fun fromMap(value: Map<*, *>): Settings = Settings(
                provider = value["provider"] as? String
                    ?: throw IllegalArgumentException("INVALID_MAP_SETTINGS"),
                urlTemplate = value["urlTemplate"] as? String ?: DEFAULT_URL,
                attribution = value["attribution"] as? String ?: DEFAULT_ATTRIBUTION,
                attributionUrl = value["attributionUrl"] as? String ?: DEFAULT_ATTRIBUTION_URL,
                authHeader = value["authHeader"] as? String ?: DEFAULT_AUTH_HEADER,
                authSecret = "",
            )

            fun fromJson(json: String): Settings {
                val value = JSONObject(json)
                return Settings(
                    provider = value.optString("provider", DEFAULT_PROVIDER),
                    urlTemplate = value.optString("urlTemplate", DEFAULT_URL),
                    attribution = value.optString("attribution", DEFAULT_ATTRIBUTION),
                    attributionUrl = value.optString("attributionUrl", DEFAULT_ATTRIBUTION_URL),
                    authHeader = value.optString("authHeader", DEFAULT_AUTH_HEADER),
                    authSecret = value.optString("authSecret", ""),
                )
            }
        }
    }

    @Synchronized
    fun read(): Settings {
        val stored = preferences.getString(SETTINGS, null) ?: return defaultSettings()
        return try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            require(packed.size >= 2 + IV_BYTES + TAG_BYTES && packed[0].toInt() == FORMAT_VERSION)
            val ivLength = packed[1].toInt() and 0xff
            require(ivLength == IV_BYTES && packed.size > 2 + ivLength + TAG_BYTES)
            val iv = packed.copyOfRange(2, 2 + ivLength)
            val ciphertext = packed.copyOfRange(2 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad())
            val settings = Settings.fromJson(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            validate(settings)
            settings
        } catch (_: Exception) {
            // Never include ciphertext, credentials, or an exception that could
            // contain them in the platform-channel error text.
            throw IllegalStateException("MAP_SETTINGS_UNAVAILABLE")
        }
    }

    @Synchronized
    fun save(value: Map<*, *>): Settings {
        val candidate = Settings.fromMap(value)
        if (candidate.provider == DEFAULT_PROVIDER) return reset()
        val removeCredential = value["removeCredential"] as? Boolean ?: false
        val suppliedSecret = value["authSecret"] as? String
        val existingSecret = if (removeCredential || !suppliedSecret.isNullOrEmpty()) {
            ""
        } else {
            read().authSecret
        }
        val settings = Settings(
            candidate.provider,
            candidate.urlTemplate,
            candidate.attribution,
            candidate.attributionUrl,
            candidate.authHeader,
            when {
                removeCredential -> ""
                !suppliedSecret.isNullOrEmpty() -> suppliedSecret
                else -> existingSecret
            },
        )
        validate(settings)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad())
        val ciphertext = cipher.doFinal(settings.toJson().toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
            .put(FORMAT_VERSION.toByte())
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        check(preferences.edit().putString(SETTINGS, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "MAP_SETTINGS_UNAVAILABLE"
        }
        return settings
    }

    @Synchronized
    fun reset(): Settings {
        check(preferences.edit().remove(SETTINGS).commit()) { "MAP_SETTINGS_UNAVAILABLE" }
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        return defaultSettings()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun aad() = "${app.packageName}:clockrove-map-provider:v1".toByteArray(Charsets.UTF_8)

    private fun validate(settings: Settings) {
        require(settings.provider == DEFAULT_PROVIDER || settings.provider == CUSTOM_PROVIDER) {
            "INVALID_MAP_SETTINGS"
        }
        require(settings.urlTemplate.length <= MAX_URL_LENGTH && settings.attribution.length <= MAX_ATTRIBUTION_LENGTH &&
            settings.attributionUrl.length <= MAX_ATTRIBUTION_URL_LENGTH && settings.authHeader.length <= MAX_HEADER_LENGTH &&
            settings.authSecret.length <= MAX_SECRET_LENGTH && !settings.authSecret.contains('\r') && !settings.authSecret.contains('\n')) {
            "INVALID_MAP_SETTINGS"
        }
        if (settings.provider == CUSTOM_PROVIDER) {
            require(settings.attribution.isNotBlank() && validTileUrl(settings.urlTemplate) &&
                (settings.attributionUrl.isBlank() || validHttpsUrl(settings.attributionUrl)) &&
                (settings.authSecret.isBlank() || validHeader(settings.authHeader))) {
                "INVALID_MAP_SETTINGS"
            }
        }
    }

    private fun validTileUrl(template: String): Boolean {
        return try {
            if (!template.contains("{z}") || !template.contains("{x}") || !template.contains("{y}")) return false
            val replaced = template.replace("{z}", "1").replace("{x}", "1").replace("{y}", "1")
            if (replaced.contains('{') || replaced.contains('}')) return false
            val uri = URI(replaced)
            if (!validHttpsUri(uri)) return false
            val queryNames = uri.rawQuery.orEmpty().split('&').mapNotNull {
                it.substringBefore('=', "").takeIf { name -> name.isNotBlank() }
                    ?.let { name -> URLDecoder.decode(name, Charsets.UTF_8.name()).lowercase() }
            }
            queryNames.none { it in SENSITIVE_QUERY_NAMES }
        } catch (_: Exception) { false }
    }

    private fun validHttpsUrl(value: String): Boolean = try { validHttpsUri(URI(value)) } catch (_: Exception) { false }

    private fun validHttpsUri(uri: URI) = uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null

    private fun validHeader(value: String): Boolean = HEADER_NAME.matches(value) &&
        value.lowercase() !in RESERVED_HEADERS

    private fun defaultSettings() = Settings(
        DEFAULT_PROVIDER,
        DEFAULT_URL,
        DEFAULT_ATTRIBUTION,
        DEFAULT_ATTRIBUTION_URL,
        DEFAULT_AUTH_HEADER,
        "",
    )

    companion object {
        const val DEFAULT_PROVIDER = "OSM"
        const val CUSTOM_PROVIDER = "CUSTOM"
        const val DEFAULT_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val DEFAULT_ATTRIBUTION = "© OpenStreetMap contributors"
        const val DEFAULT_ATTRIBUTION_URL = "https://www.openstreetmap.org/copyright"
        const val DEFAULT_AUTH_HEADER = "Authorization"
        private const val PREFERENCES = "clockrove-map-provider"
        private const val SETTINGS = "encrypted-settings-v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "clockrove.map-provider.settings.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val MAX_URL_LENGTH = 2048
        private const val MAX_ATTRIBUTION_LENGTH = 300
        private const val MAX_ATTRIBUTION_URL_LENGTH = 2048
        private const val MAX_HEADER_LENGTH = 64
        private const val MAX_SECRET_LENGTH = 4096
        private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
        private val RESERVED_HEADERS = setOf("user-agent", "host", "cookie", "connection", "content-length", "accept", "referer", "origin", "proxy-authorization")
        private val SENSITIVE_QUERY_NAMES = setOf(
            "key", "api_key", "apikey", "x_api_key", "x-api-key",
            "accesskey", "access_key", "accesstoken", "access-token", "access_token",
            "client_key", "client_secret", "clientkey", "clientsecret",
            "key_id", "keyid", "map_key", "mapkey", "private_key", "privatekey",
            "secret_key", "secretkey", "subscription-key", "subscription_key", "subscriptionkey",
            "token", "auth", "authorization", "secret", "credential", "signature", "sig",
        )
    }
}
