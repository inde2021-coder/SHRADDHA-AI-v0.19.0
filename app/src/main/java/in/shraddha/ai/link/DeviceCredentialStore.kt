package `in`.shraddha.ai.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
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
        require(sessionId.isNotBlank() && deviceId.isNotBlank() && token.length >= 24)
        require(baseUrl.startsWith("https://"))
        val plaintext = JSONObject()
            .put("sessionId", sessionId)
            .put("deviceId", deviceId)
            .put("deviceToken", token)
            .put("baseUrl", baseUrl.trim().trimEnd('/'))
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val saved = preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("payload", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        plaintext.fill(0)
        check(saved) { "Unable to persist encrypted device credentials" }
    }

    @Synchronized fun load(): Credentials? {
        val ivText = preferences.getString("iv", null) ?: return null
        val payloadText = preferences.getString("payload", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            val clear = cipher.doFinal(Base64.decode(payloadText, Base64.NO_WRAP))
            try {
                val json = JSONObject(clear.toString(Charsets.UTF_8))
                Credentials(json.getString("sessionId"), json.getString("deviceId"),
                    json.getString("deviceToken"), json.getString("baseUrl"))
            } finally { clear.fill(0) }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized fun clear() {
        preferences.edit().clear().commit()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
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
