package com.Lia.assistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Authenticated client for the backend's Edesy inbound-call readiness and history APIs. */
object EdesyCallAgentClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun status(baseUrl: String, gatewayToken: String): JSONObject =
        get(baseUrl, gatewayToken, "/api/telephony/edesy/status")

    suspend fun calls(baseUrl: String, gatewayToken: String): JSONObject =
        get(baseUrl, gatewayToken, "/api/telephony/edesy/calls")

    suspend fun preflight(baseUrl: String, gatewayToken: String): JSONObject =
        post(baseUrl, gatewayToken, "/api/telephony/edesy/preflight",
            JSONObject().put("sample_rate", 16000).put("seconds", 2))

    private suspend fun get(baseUrl: String, token: String, path: String): JSONObject =
        execute(Request.Builder().url(endpoint(baseUrl, path))
            .header("Authorization", "Bearer ${validatedToken(token)}").get().build())

    private suspend fun post(baseUrl: String, token: String, path: String, body: JSONObject): JSONObject =
        execute(Request.Builder().url(endpoint(baseUrl, path))
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())

    private fun validatedToken(token: String): String {
        val clean = token.trim()
        require(clean.length in 20..512 && clean == token) {
            "Backend gateway token must contain 20-512 characters without surrounding whitespace."
        }
        return clean
    }

    private fun endpoint(baseUrl: String, path: String): String {
        require(baseUrl.length <= 512) { "Backend URL is too long." }
        val parsed = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Backend URL is invalid.")
        require(parsed.scheme == "https" && parsed.host.isNotBlank() &&
            parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.query == null && parsed.fragment == null) {
            "Phone Call Agent requires an HTTPS backend URL without credentials, query parameters or fragments."
        }
        return parsed.newBuilder().addPathSegments(path.trimStart('/')).build().toString()
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(raw).optString("error", "Backend request failed (${response.code}).")
                }.getOrDefault("Backend request failed (${response.code}).")
                throw IllegalStateException(message.take(240))
            }
            JSONObject(raw)
        }
    }
}
