package com.Lia.assistant.data

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import java.net.URI

/** Stores the backend gateway URL and bearer token encrypted with the Android Keystore vault. */
object TelephonyGatewayStore {
    private const val BASE_URL = "telephony_gateway_base_url"
    private const val TOKEN = "telephony_gateway_token"

    fun baseUrl(context: Context): String =
        EncryptedFeatureStore(context.applicationContext).get(BASE_URL).getOrDefault("").trim()

    fun token(context: Context): String =
        EncryptedFeatureStore(context.applicationContext).get(TOKEN).getOrDefault("").trim()

    fun isConfigured(context: Context): Boolean =
        baseUrl(context).isNotBlank() && token(context).length >= 20

    fun save(context: Context, baseUrl: String, token: String) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(cleanUrl) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid backend URL.")
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Backend URL must be HTTPS and must not contain credentials, query parameters or fragments."
        }
        val cleanToken = token.trim()
        require(cleanToken.length in 20..512) { "Gateway token must contain 20-512 characters." }
        val store = EncryptedFeatureStore(context.applicationContext)
        store.put(BASE_URL, cleanUrl).getOrThrow()
        store.put(TOKEN, cleanToken).getOrThrow()
    }

    fun clear(context: Context) {
        val store = EncryptedFeatureStore(context.applicationContext)
        store.remove(BASE_URL)
        store.remove(TOKEN)
    }
}
