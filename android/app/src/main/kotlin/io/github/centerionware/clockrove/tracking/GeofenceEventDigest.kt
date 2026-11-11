package io.github.centerionware.clockrove.tracking

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Device-local receipts, not a retained history of off-clock site/time payloads. */
internal object GeofenceEventDigest {
    private const val ALIAS = "clockrove.geofence.receipts.v1"
    @Synchronized
    fun digest(value: String): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore",
        ).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
}
