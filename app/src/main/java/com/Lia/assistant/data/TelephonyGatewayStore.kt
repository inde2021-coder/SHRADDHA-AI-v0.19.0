package com.Lia.assistant.data

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import java.net.URI

/** Stores the backend gateway URL and bearer token encrypted with the Android Keystore vault. */
object TelephonyGatewayStore {
    data class Config(val baseUrl: String, val token: String, val readError: String? = null)

    private const val BASE_URL = "telephony_gateway_base_url"
    private const val TOKEN = "telephony_gateway_token"

    fun get(context: Context): Config {
        val store = EncryptedFeatureStore(context.applicationContext)
        return runCatching {
            Config(
                baseUrl = store.get(BASE_URL).getOrThrow().trim()
                    .ifBlank { VoiceGatewaySettings.get(context).baseUrl.ifBlank { VoiceGatewaySettings.DEFAULT_BASE_URL } },
                token = store.get(TOKEN).getOrThrow().trim()
                    .ifBlank { VoiceGatewaySettings.get(context).gatewayToken }
            )
        }.getOrElse { error ->
            Config("", "", "Could not decrypt the saved telephony backend settings: ${error.localizedMessage ?: "secure storage unavailable"}. Existing encrypted data was preserved.")
        }
    }

    fun baseUrl(context: Context): String = get(context).baseUrl
    fun token(context: Context): String = get(context).token

    fun isConfigured(context: Context): Boolean {
        val config = get(context)
        return config.readError == null && config.baseUrl.isNotBlank() && config.token.length >= 20
    }

    fun save(context: Context, baseUrl: String, token: String) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(cleanUrl) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid backend URL.")
        require(uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Backend URL must be HTTPS and must not contain credentials, query parameters or fragments."
        }
        val cleanToken = token.trim()
        require(cleanToken.length in 20..512) { "Gateway token must contain 20-512 characters." }
        val store = EncryptedFeatureStore(context.applicationContext)
        store.putAll(mapOf(BASE_URL to cleanUrl, TOKEN to cleanToken)).getOrThrow()
    }

    fun clear(context: Context) {
        val store = EncryptedFeatureStore(context.applicationContext)
        store.putAllAndRemove(emptyMap(), setOf(BASE_URL, TOKEN)).getOrThrow()
    }
}
