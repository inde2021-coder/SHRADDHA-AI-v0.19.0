package com.Lia.assistant.voice

import android.content.Context
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

    suspend fun resolveModel(context: Context, apiKey: String): String {
        `in`.shraddha.ai.future.OnlineAiConsent.requireAllowed(context)
        val cacheKey = credentialFingerprint(apiKey)
        resolvedModels[cacheKey]?.let { return it }

        val url = "https://generativelanguage.googleapis.com/v1beta/models"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .get()
            .build()

        try {
            `in`.shraddha.ai.future.OnlineAiConsent.requireAllowed(context)
            internalHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // A transient catalog outage must not be cached as a permanent
                    // model decision for this credential. Use the safe fallback for
                    // this request only and retry model discovery on the next call.
                    return "models/gemini-3.8-flash"
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
            // Do not poison the per-key model cache when discovery fails due to
            // connectivity, quota, or temporary Google API availability.
            return "models/gemini-3.8-flash"
        }
    }

    fun reply(
        context: Context,
        apiKey: String,
        systemInstruction: String,
        history: List<ChatTurn>
    ): ChatReplyResult {
        if (!`in`.shraddha.ai.future.OnlineAiConsent.isAllowed(context)) {
            return ChatReplyResult.Error("Online AI is disabled. Your content was not sent to a provider.")
        }
        runCatching { `in`.shraddha.ai.future.CouncilCostBudget.ensureBudgetAllowsCall(context, "GEMINI") }
            .onFailure { return ChatReplyResult.Error(it.message ?: "Monthly API budget blocked this request.") }
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
            kotlinx.coroutines.runBlocking { resolveModel(context, apiKey) }
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

        var lastTransientError: String? = null
        for (attempt in 0..2) {
            try {
                `in`.shraddha.ai.future.OnlineAiConsent.requireAllowed(context)
                internalHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val code = response.code
                        val errorBody = response.body?.string().orEmpty()
                        val providerReason = runCatching {
                            JSONObject(errorBody).optJSONObject("error")?.optString("message", "")
                        }.getOrNull().orEmpty().ifBlank { "HTTP error $code" }
                        if (code in setOf(408, 425, 429, 500, 502, 503, 504) && attempt < 2) {
                            lastTransientError = providerReason
                            val retryAfterSeconds = response.header("Retry-After")
                                ?.toLongOrNull()?.coerceIn(0L, 8L)
                            val backoffMillis = retryAfterSeconds?.times(1_000L)
                                ?: if (attempt == 0) 1_000L else 2_500L
                            Thread.sleep(backoffMillis)
                            return@use
                        }
                        val friendly = when (code) {
                            429 -> "Gemini rate limit or quota reached (HTTP 429). Check Google AI Studio quota and billing, then try again."
                            500, 502, 503, 504 -> "Gemini is temporarily busy or unavailable (HTTP $code). Please wait briefly and retry. $providerReason"
                            else -> "Gemini request failed (HTTP $code): $providerReason"
                        }
                        return ChatReplyResult.Error(friendly)
                    }

                    val body = response.body?.string().orEmpty()
                    if (!`in`.shraddha.ai.future.OnlineAiConsent.isAllowed(context)) {
                        return ChatReplyResult.Error("Online AI consent was revoked while the request was running. The response was discarded.")
                    }
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
                        if (text.isBlank()) return ChatReplyResult.Error("The model returned an empty reply.")
                        val usage = json.optJSONObject("usageMetadata")?.let { metadata ->
                            val input = metadata.optLong("promptTokenCount", -1L)
                            val output = metadata.optLong("candidatesTokenCount", -1L)
                            if (input >= 0L && output >= 0L) ProviderTokenUsage("GEMINI", modelName.removePrefix("models/"), input, output) else null
                        }
                        if (usage == null) {
                            `in`.shraddha.ai.future.CouncilCostBudget.stopIfUsageUnknown(context, "GEMINI", modelName.removePrefix("models/"))
                        } else {
                            `in`.shraddha.ai.future.CouncilCostBudget.recordUsageAndEnforce(context, usage)
                        }
                        return ChatReplyResult.Success(text, usage)
                    }
                    return ChatReplyResult.Error("The response was filtered or unavailable.")
                }
                if (attempt < 2 && lastTransientError != null) continue
            } catch (e: IOException) {
                if (attempt < 2) {
                    lastTransientError = e.localizedMessage ?: "network error"
                    Thread.sleep(if (attempt == 0) 1_000L else 2_500L)
                    continue
                }
                return ChatReplyResult.Error("Couldn't reach Gemini after 3 attempts. Check your internet connection and try again.")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return ChatReplyResult.Error("Gemini retry was interrupted.")
            } catch (e: Exception) {
                return ChatReplyResult.Error("Failed to communicate with Gemini: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
        return ChatReplyResult.Error(
            "Gemini is still experiencing high demand or temporary server errors after 3 attempts. Please wait and try again. ${lastTransientError.orEmpty()}"
        )
    }
}
