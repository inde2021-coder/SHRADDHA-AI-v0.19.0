package com.Lia.assistant.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ChatTurn(
    val isUser: Boolean,
    val text: String
)

data class ProviderTokenUsage(val provider: String, val model: String, val inputTokens: Long, val outputTokens: Long) {
    init { require(provider.isNotBlank() && model.isNotBlank()); require(inputTokens >= 0L && outputTokens >= 0L) }
}

sealed class ChatReplyResult {
    data class Success(val text: String, val tokenUsage: ProviderTokenUsage? = null) : ChatReplyResult()
    data class Error(val message: String) : ChatReplyResult()
}

object GeminiTextClient {

    val internalHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Cache model discovery per credential fingerprint; never expose API keys in URLs.
    private val resolvedModels = ConcurrentHashMap<String, String>()

    private fun credentialFingerprint(apiKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    suspend fun resolveModel(apiKey: String): String {
        val cacheKey = credentialFingerprint(apiKey)
        resolvedModels[cacheKey]?.let { return it }

        val url = "https://generativelanguage.googleapis.com/v1beta/models"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .get()
            .build()

        try {
            internalHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val fallback = "models/gemini-3.8-flash"
                    resolvedModels[cacheKey] = fallback
                    return fallback
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val models = json.optJSONArray("models") ?: JSONArray()

                var flashCandidate: String? = null
                var bestVersionRank = -1

                for (i in 0 until models.length()) {
                    val modelObj = models.getJSONObject(i)
                    val name = modelObj.optString("name", "")
                    val methods = modelObj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    var canGenerate = false
                    for (m in 0 until methods.length()) {
                        if (methods.getString(m) == "generateContent") {
                            canGenerate = true
                            break
                        }
                    }

                    if (!canGenerate) continue
                    val lowerName = name.lowercase()
                    if (!lowerName.contains("flash") ||
                        lowerName.contains("live") ||
                        lowerName.contains("image") ||
                        lowerName.contains("tts") ||
                        lowerName.contains("native-audio")
                    ) continue

                    // New projects may not be entitled to Gemini 2.5 generation even
                    // when the catalog lists it. Prefer current 3.x Flash models and
                    // rank by major/minor version (e.g. 3.8 > 3.5 > 3.1).
                    val version = Regex("gemini-(\\d+)\\.(\\d+)").find(lowerName) ?: continue
                    val major = version.groupValues[1].toIntOrNull() ?: continue
                    val minor = version.groupValues[2].toIntOrNull() ?: continue
                    if (major < 3) continue
                    val rank = major * 100 + minor
                    val previewPenalty = if (lowerName.contains("preview")) 0 else 1
                    val weightedRank = rank * 10 + previewPenalty
                    if (weightedRank > bestVersionRank) {
                        bestVersionRank = weightedRank
                        flashCandidate = name
                    }
                }

                val chosen = flashCandidate ?: "models/gemini-3.8-flash"
                resolvedModels[cacheKey] = chosen
                return chosen
            }
        } catch (_: Exception) {
            val fallback = "models/gemini-3.8-flash"
            resolvedModels[cacheKey] = fallback
            return fallback
        }
    }

    fun reply(
        apiKey: String,
        systemInstruction: String,
        history: List<ChatTurn>
    ): ChatReplyResult {
        if (apiKey.isBlank()) {
            return ChatReplyResult.Error("Gemini API key is missing. Please add it in Settings.")
        }
        if (history.isEmpty()) {
            return ChatReplyResult.Error("No conversation history provided.")
        }
        if (history.size > 40 || history.any { it.text.length > 8_000 }) {
            return ChatReplyResult.Error("Conversation context is too large. Keep it to 40 turns with 8,000 characters per turn.")
        }
        if (systemInstruction.length > 8_000) {
            return ChatReplyResult.Error("System instructions exceed the 8,000-character safety limit.")
        }

        val modelName = runCatching {
            kotlinx.coroutines.runBlocking { resolveModel(apiKey) }
        }.getOrDefault("models/gemini-3.8-flash")

        val modelEndpoint = if (modelName.startsWith("models/")) modelName else "models/$modelName"
        val url = "https://generativelanguage.googleapis.com/v1beta/$modelEndpoint:generateContent"

        val root = JSONObject()

        // System Instruction
        val sysInstructionObj = JSONObject()
        val sysParts = JSONArray()
        sysParts.put(JSONObject().apply { put("text", systemInstruction) })
        sysInstructionObj.put("parts", sysParts)
        root.put("systemInstruction", sysInstructionObj)

        // Contents
        val contentsArray = JSONArray()
        for (turn in history) {
            val contentObj = JSONObject()
            contentObj.put("role", if (turn.isUser) "user" else "model")
            val partsArray = JSONArray()
            partsArray.put(JSONObject().apply { put("text", turn.text) })
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
        }
        root.put("contents", contentsArray)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = root.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()

        try {
            internalHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    val reason = runCatching {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message", "")
                    }.getOrNull() ?: "HTTP error $code"
                    return ChatReplyResult.Error(reason)
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return ChatReplyResult.Error("The model returned an empty response.")
                }

                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "")
                    return if (text.isNotBlank()) {
                        val usage = json.optJSONObject("usageMetadata")?.let { metadata ->
                            val input = metadata.optLong("promptTokenCount", -1L)
                            val output = metadata.optLong("candidatesTokenCount", -1L)
                            if (input >= 0L && output >= 0L) ProviderTokenUsage("GEMINI", modelName.removePrefix("models/"), input, output) else null
                        }
                        ChatReplyResult.Success(text, usage)
                    } else {
                        ChatReplyResult.Error("The model returned an empty reply.")
                    }
                }

                return ChatReplyResult.Error("The response was filtered or unavailable.")
            }
        } catch (e: IOException) {
            return ChatReplyResult.Error("Couldn't reach Gemini. Please check your internet connection.")
        } catch (e: Exception) {
            return ChatReplyResult.Error("Failed to communicate with Gemini: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
