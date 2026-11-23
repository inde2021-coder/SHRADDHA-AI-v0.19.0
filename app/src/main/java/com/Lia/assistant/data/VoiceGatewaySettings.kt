package com.Lia.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores gateway URL and shared gateway credential encrypted with Android Keystore. */
object VoiceGatewaySettings {
    data class Config(val baseUrl: String, val gatewayToken: String)
    private const val PREFS = "shraddha_voice_gateway"
    private const val PAYLOAD = "config_ciphertext"
    private const val IV = "config_iv"
    private const val ALIAS = "shraddha_voice_gateway_aes_v1"
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(cipherText: String, ivText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun get(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(PAYLOAD, null) ?: return Config("", "")
        val iv = prefs.getString(IV, null) ?: return Config("", "")
        return runCatching {
            val json = JSONObject(decrypt(encrypted, iv))
            Config(json.optString("baseUrl"), json.optString("gatewayToken"))
        }.getOrElse { Config("", "") }
    }

    fun isConfigured(context: Context): Boolean {
        val config = get(context)
        return config.baseUrl.isNotBlank() && config.gatewayToken.isNotBlank()
    }

    fun save(context: Context, baseUrl: String, gatewayToken: String): Result<Unit> = runCatching {
        val url = baseUrl.trim().trimEnd('/')
        require(url.startsWith("https://", true)) { "Secure HTTPS backend URL required" }
        require(url.length <= 500 && !url.contains(" ")) { "Backend URL is invalid" }
        val uri = java.net.URI(url)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Backend URL must be a base HTTPS URL without credentials, query or fragment" }
        val secret = gatewayToken.trim()
        require(secret.length in 16..512) { "Gateway token must contain 16-512 characters" }
        val (payload, iv) = encrypt(JSONObject().put("baseUrl", url).put("gatewayToken", secret).toString())
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PAYLOAD, payload).putString(IV, iv).commit()) {
            "Could not persist the encrypted Voice Gateway settings."
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Requests a one-use, short-lived Gemini Live token. Must be called off the main thread. */
    fun fetchEphemeralToken(context: Context): String {
        val config = get(context)
        require(config.baseUrl.isNotBlank() && config.gatewayToken.isNotBlank()) {
            "Set the secure HTTPS backend URL and gateway token in Settings."
        }
        val body = JSONObject().put("purpose", "voice").toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/ai/gemini-live-token")
            .header("Authorization", "Bearer ${config.gatewayToken}")
            .post(body).build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "Gemini Live token request failed (HTTP ${response.code})" })
            }
            val token = JSONObject(raw).optString("token")
            require(token.startsWith("auth_tokens/")) { "Backend returned an invalid Gemini Live token." }
            return token
        }
    }
}
