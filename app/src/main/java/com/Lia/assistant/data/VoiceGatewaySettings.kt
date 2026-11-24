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
    data class AgentReply(val text: String, val provider: String, val model: String?, val inputTokens: Long?, val outputTokens: Long?)
    data class Config(val baseUrl: String, val gatewayToken: String, val readError: String? = null)
    /** Public Render origin declared by render.yaml; users only need to supply the private gateway token. */
    const val DEFAULT_BASE_URL = "https://shraddha-ai-secure-relay.onrender.com"
    private const val PREFS = "shraddha_voice_gateway"
    private const val PAYLOAD = "config_ciphertext"
    private const val IV = "config_iv"
    private const val ALIAS = "shraddha_voice_gateway_aes_v1"
    private data class ProviderCache(val baseUrl: String, val tokenHash: Int, val fetchedAt: Long, val providers: Set<String>)
    @Volatile private var providerCache: ProviderCache? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun keyForEncryption(): SecretKey {
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
        val clear = value.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyForEncryption())
            val encrypted = cipher.doFinal(clear)
            val iv = cipher.iv
            try {
                return Base64.encodeToString(encrypted, Base64.NO_WRAP) to Base64.encodeToString(iv, Base64.NO_WRAP)
            } finally {
                encrypted.fill(0)
                iv.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }

    private fun decrypt(cipherText: String, ivText: String): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingKey = keyStore.getKey(ALIAS, null) as? SecretKey
            ?: error("Voice Gateway encryption key is missing; refusing to replace it while encrypted settings still exist.")
        val iv = Base64.decode(ivText, Base64.NO_WRAP)
        val encrypted = Base64.decode(cipherText, Base64.NO_WRAP)
        return try {
            require(iv.size == 12 && encrypted.size >= 16) { "Encrypted Voice Gateway settings are invalid." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, iv))
            val clear = cipher.doFinal(encrypted)
            try {
                String(clear, Charsets.UTF_8)
            } finally {
                clear.fill(0)
            }
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    fun get(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(PAYLOAD, null)
        val iv = prefs.getString(IV, null)
        if (encrypted == null && iv == null) return Config("", "")
        if (encrypted == null || iv == null) {
            return Config("", "", "Encrypted Voice Gateway settings are incomplete. Re-enter the backend URL and token; existing data was preserved.")
        }
        return runCatching {
            val json = JSONObject(decrypt(encrypted, iv))
            Config(json.optString("baseUrl"), json.optString("gatewayToken"))
        }.getOrElse { failure ->
            Config("", "", "Could not decrypt saved Voice Gateway settings: ${failure.localizedMessage ?: failure.javaClass.simpleName}. Re-enter the settings; existing encrypted data was preserved.")
        }
    }

    fun isConfigured(context: Context): Boolean {
        val config = get(context)
        return config.baseUrl.isNotBlank() && config.gatewayToken.isNotBlank()
    }

    fun save(context: Context, baseUrl: String, gatewayToken: String): Result<Unit> = runCatching {
        providerCache = null
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
        providerCache = null
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()) {
            "Could not clear the stored Voice Gateway settings."
        }
    }


    /** Returns server-side provider keys configured on the HTTPS gateway; null means no gateway. */
    fun configuredProviders(context: Context): Set<String>? {
        val config = get(context)
        require(config.readError == null) { config.readError ?: "Gateway settings could not be read." }
        if (config.baseUrl.isBlank() || config.gatewayToken.isBlank()) return null
        val now = System.currentTimeMillis()
        val cached = providerCache
        if (cached != null && cached.baseUrl == config.baseUrl && cached.tokenHash == config.gatewayToken.hashCode() && now - cached.fetchedAt < 30_000L) {
            return cached.providers
        }
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/ai/providers")
            .header("Authorization", "Bearer " + config.gatewayToken)
            .get().build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "Cloud provider status failed (HTTP " + response.code + ")." })
            }
            val flags = JSONObject(raw).optJSONObject("providers") ?: error("Gateway provider status is malformed.")
            val providers = listOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")
                .filter { provider -> flags.optBoolean(if (provider == "CLAUDE") "anthropic" else provider.lowercase()) }
                .toSet()
            providerCache = ProviderCache(config.baseUrl, config.gatewayToken.hashCode(), now, providers)
            return providers
        }
    }

    /** Calls a server-side provider over authenticated HTTPS; null means it is not configured there. */
    fun executeAgent(
        context: Context,
        provider: String,
        role: String,
        systemInstruction: String,
        prompt: String
    ): AgentReply? {
        val config = get(context)
        require(config.readError == null) { config.readError ?: "Gateway settings could not be read." }
        if (config.baseUrl.isBlank() || config.gatewayToken.isBlank()) return null
        val normalized = provider.uppercase()
        if (normalized !in configuredProviders(context).orEmpty()) return null
        val providerId = if (normalized == "CLAUDE") "anthropic" else normalized.lowercase()
        val combinedPrompt = "You are the " + role.take(40) + " agent in Shraddha AI.\n" +
            systemInstruction.take(1800) + "\n\n" + prompt.take(10_000)
        require(combinedPrompt.length <= 12_000) { "Cloud agent prompt exceeds the gateway limit." }
        val body = JSONObject().put("provider", providerId).put("prompt", combinedPrompt)
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/chat")
            .header("Authorization", "Bearer " + config.gatewayToken)
            .post(body).build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "Cloud agent request failed (HTTP " + response.code + ")." })
            }
            val json = JSONObject(raw)
            require(json.optString("provider").equals(providerId, true)) {
                "Cloud gateway returned a different provider than the assigned agent."
            }
            val answer = json.optString("answer").takeIf { it.isNotBlank() }
                ?: error("Cloud agent returned an empty response.")
            val usage = json.optJSONObject("usage")
            val inputTokens = usage?.takeIf { it.has("inputTokens") && !it.isNull("inputTokens") }?.optLong("inputTokens")?.takeIf { it >= 0L }
            val outputTokens = usage?.takeIf { it.has("outputTokens") && !it.isNull("outputTokens") }?.optLong("outputTokens")?.takeIf { it >= 0L }
            return AgentReply(answer, normalized, json.optString("model").takeIf { it.isNotBlank() }, inputTokens, outputTokens)
        }
    }

    /** Requests a one-use, short-lived Gemini Live token. Must be called off the main thread. */
    fun fetchEphemeralToken(context: Context): String {
        val config = get(context)
        require(config.readError == null) { config.readError ?: "Voice Gateway settings could not be read." }
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
