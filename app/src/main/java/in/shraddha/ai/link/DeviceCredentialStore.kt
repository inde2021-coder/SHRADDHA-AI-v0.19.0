package `in`.shraddha.ai.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists pairing credentials as AES-GCM ciphertext. The AES key is non-exportable
 * and protected by Android Keystore; preferences never contain the bearer token in cleartext.
 */
internal class DeviceCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun save(sessionId: String, deviceId: String, token: String, baseUrl: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(deviceId.isNotBlank() && deviceId.length <= 64)
        require(token.length in 24..512)
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val parsedBaseUrl = normalizedBaseUrl.toHttpUrlOrNull()
        require(parsedBaseUrl != null && parsedBaseUrl.scheme == "https" && parsedBaseUrl.host.isNotBlank())
        val plaintext = JSONObject()
            .put("sessionId", sessionId)
            .put("deviceId", deviceId)
            .put("deviceToken", token)
            .put("baseUrl", normalizedBaseUrl)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            try {
                val saved = preferences.edit()
                    .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString("payload", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .commit()
                check(saved) { "Unable to persist encrypted device credentials" }
            } finally {
                ciphertext.fill(0)
                iv.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized fun load(): Credentials? = loadResult().getOrNull()

    @Synchronized fun loadResult(): Result<Credentials?> = runCatching {
        val ivText = preferences.getString("iv", null)
        val payloadText = preferences.getString("payload", null)
        if (ivText == null && payloadText == null) return@runCatching null
        require(ivText != null && payloadText != null) { "Saved device pairing data is incomplete; encrypted data was preserved." }
        require(ivText.length == 16 && payloadText.length in 24..8192) { "Saved credential encoding is invalid; encrypted data was preserved." }
        val iv = Base64.decode(ivText, Base64.NO_WRAP)
        val payload = Base64.decode(payloadText, Base64.NO_WRAP)
        try {
            require(iv.size == 12) { "Invalid credential IV; encrypted data was preserved." }
            require(payload.size >= 16) { "Invalid credential ciphertext; encrypted data was preserved." }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("Device pairing encryption key is missing; saved credentials were preserved.")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, iv))
            val clear = cipher.doFinal(payload)
            try {
                val json = JSONObject(clear.toString(Charsets.UTF_8))
                val sessionId = json.getString("sessionId")
                val deviceId = json.getString("deviceId")
                val token = json.getString("deviceToken")
                val baseUrl = json.getString("baseUrl").trim().trimEnd('/')
                val parsedUrl = baseUrl.toHttpUrlOrNull()
                require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid saved session ID" }
                require(deviceId.isNotBlank() && deviceId.length <= 64) { "Invalid saved device ID" }
                require(token.length in 24..512) { "Invalid saved device token" }
                require(parsedUrl != null && parsedUrl.scheme == "https" && parsedUrl.host.isNotBlank()) { "Invalid saved relay URL" }
                Credentials(sessionId, deviceId, token, baseUrl)
            } finally {
                clear.fill(0)
            }
        } finally {
            iv.fill(0)
            payload.fill(0)
        }
    }

    @Synchronized fun clear() {
        check(preferences.edit().clear().commit()) {
            "Could not remove saved encrypted device credentials."
        }
        // Delete the Keystore key only after the ciphertext removal is durable.
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) {
            store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    data class Credentials(val sessionId: String, val deviceId: String, val token: String, val baseUrl: String)

    private companion object {
        const val PREFS = "device_link_credentials_v1"
        const val KEY_ALIAS = "shraddha_device_link_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
