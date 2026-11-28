package io.github.centerionware.clockrove.autobackup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Encrypted-at-rest configuration for unattended backups, including the password. */
internal class AutoBackupSettingsStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    internal data class Settings(
        val enabled: Boolean,
        val folderUri: String?,
        val encrypt: Boolean,
        val password: String,
        val lastSuccessMillis: Long?,
        val lastAttemptMillis: Long?,
        val lastResult: String?,
        val lastFileName: String?,
    ) {
        fun publicMap(folderLabel: String? = null) = mapOf(
            "enabled" to enabled,
            "folderSelected" to (folderUri != null),
            "folderLabel" to folderLabel,
            "encrypt" to encrypt,
            "hasPassword" to password.isNotEmpty(),
            "lastSuccessMillis" to lastSuccessMillis,
            "lastAttemptMillis" to lastAttemptMillis,
            "lastResult" to lastResult,
            "lastFileName" to lastFileName,
        )
    }

    internal class Failure(val code: String, message: String) : Exception(message)

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
            val settings = fromJson(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8))
            validate(settings)
            settings
        } catch (_: Exception) {
            // Ciphertext, folder grants and the backup password never enter error text.
            throw Failure("SETTINGS_UNAVAILABLE", "Automatic backup settings are unavailable")
        }
    }

    @Synchronized
    fun save(
        enabled: Boolean,
        folderUri: String?,
        encrypt: Boolean,
        suppliedPassword: String?,
        clearPassword: Boolean,
    ): Settings {
        val existing = read()
        val password = when {
            !encrypt -> ""
            clearPassword -> ""
            !suppliedPassword.isNullOrEmpty() -> suppliedPassword
            else -> existing.password
        }
        val candidate = Settings(
            enabled = enabled,
            folderUri = folderUri,
            encrypt = encrypt,
            password = password,
            lastSuccessMillis = existing.lastSuccessMillis,
            lastAttemptMillis = existing.lastAttemptMillis,
            lastResult = existing.lastResult,
            lastFileName = existing.lastFileName,
        )
        validate(candidate)
        write(candidate)
        return candidate
    }

    @Synchronized
    fun setFolder(folderUri: String?): Settings {
        val existing = read()
        val candidate = existing.copy(
            folderUri = folderUri,
            enabled = existing.enabled && folderUri != null,
        )
        validate(candidate)
        write(candidate)
        return candidate
    }

    @Synchronized
    fun recordResult(success: Boolean, code: String, fileName: String?) {
        val existing = try {
            read()
        } catch (_: Failure) {
            defaultSettings()
        }
        val now = System.currentTimeMillis()
        val candidate = existing.copy(
            lastSuccessMillis = if (success) now else existing.lastSuccessMillis,
            lastAttemptMillis = now,
            lastResult = code,
            lastFileName = if (success) fileName else existing.lastFileName,
        )
        validate(candidate)
        write(candidate)
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(SETTINGS).commit()) {
            "Automatic backup settings could not be cleared"
        }
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    private fun write(settings: Settings) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad())
        val ciphertext = cipher.doFinal(
            settings.toJson().toByteArray(StandardCharsets.UTF_8),
        )
        val packed = ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
            .put(FORMAT_VERSION.toByte())
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        check(
            preferences.edit()
                .putString(SETTINGS, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit(),
        ) { "Automatic backup settings could not be saved" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
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

    private fun aad() =
        "${app.packageName}:clockrove-auto-backup:v1".toByteArray(StandardCharsets.UTF_8)

    private fun validate(settings: Settings) {
        if (settings.enabled) {
            require(settings.folderUri != null) {
                "A backup folder is required before automatic backups can be enabled"
            }
        }
        settings.folderUri?.let { raw ->
            require(raw.length <= MAX_URI_LENGTH) { "The backup folder URI is too long" }
            val uri = try { Uri.parse(raw) } catch (_: Exception) { null }
            require(
                uri != null &&
                    uri.scheme == "content" &&
                    DocumentsContract.isTreeUri(uri),
            ) { "The backup folder is not a document tree" }
        }
        if (settings.encrypt) {
            val bytes = settings.password.toByteArray(StandardCharsets.UTF_8).size
            require(bytes in MIN_PASSWORD_BYTES..MAX_PASSWORD_BYTES) {
                "The automatic backup password must be 12 to 1024 bytes"
            }
        } else {
            require(settings.password.isEmpty()) {
                "A backup password is only valid with encryption"
            }
        }
        settings.lastResult?.let {
            require(RESULT_CODE.matches(it)) { "The backup result code is invalid" }
        }
        settings.lastFileName?.let {
            require(it.length <= MAX_FILE_NAME_LENGTH) {
                "The backup file name is too long"
            }
        }
    }

    private fun fromJson(source: String): Settings {
        val value = JSONObject(source)
        return Settings(
            enabled = value.optBoolean("enabled", false),
            folderUri = value.optString("folderUri", "").ifBlank { null },
            encrypt = value.optBoolean("encrypt", false),
            password = value.optString("password", ""),
            lastSuccessMillis = value.optLong("lastSuccessMillis", 0L)
                .takeIf { it > 0L },
            lastAttemptMillis = value.optLong("lastAttemptMillis", 0L)
                .takeIf { it > 0L },
            lastResult = value.optString("lastResult", "").ifBlank { null },
            lastFileName = value.optString("lastFileName", "").ifBlank { null },
        )
    }

    private fun Settings.toJson() = JSONObject(
        mapOf(
            "enabled" to enabled,
            "folderUri" to (folderUri ?: ""),
            "encrypt" to encrypt,
            "password" to password,
            "lastSuccessMillis" to (lastSuccessMillis ?: 0L),
            "lastAttemptMillis" to (lastAttemptMillis ?: 0L),
            "lastResult" to (lastResult ?: ""),
            "lastFileName" to (lastFileName ?: ""),
        ),
    ).toString()

    private fun defaultSettings() = Settings(
        enabled = false,
        folderUri = null,
        encrypt = false,
        password = "",
        lastSuccessMillis = null,
        lastAttemptMillis = null,
        lastResult = null,
        lastFileName = null,
    )

    private companion object {
        const val PREFERENCES = "clockrove-auto-backup"
        const val SETTINGS = "encrypted-settings-v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "clockrove.auto-backup.settings.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val MAX_URI_LENGTH = 2048
        const val MIN_PASSWORD_BYTES = 12
        const val MAX_PASSWORD_BYTES = 1024
        const val MAX_FILE_NAME_LENGTH = 128
        val RESULT_CODE = Regex("[A-Z_]{1,32}")
    }
}
