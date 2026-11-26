package `in`.shraddha.ai.future

import android.content.Context
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.ProviderKeyStore
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.GeminiTextClient
import com.Lia.assistant.voice.MultiProviderChatClient
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.forge.SseParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sole provider dispatch boundary for Main Brain orchestrated work.
 * Provider credentials remain in their existing encrypted stores; this class never logs them.
 */
object MainBrainProviderGateway {
    private val providerOrder = listOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")
    val supportedProviders: Set<String> = providerOrder.toSet()

    fun configuredProviders(context: Context, preferredProvider: String? = null): List<String> {
        val preferred = (preferredProvider
            ?: NovaPreferences.getString(context, NovaPreferences.Keys.AI_PROVIDER, "GEMINI"))
            .uppercase()
        require(preferred in supportedProviders) { "Unsupported preferred AI provider: $preferred" }
        // Keep fallback order stable across devices and runtime implementations.
        // Cloud-only keys are valid routes; provider secrets remain on the HTTPS backend.
        val cloudProviders = VoiceGatewaySettings.configuredProviders(context).orEmpty()
        return (listOf(preferred) + providerOrder).distinct().filter { provider ->
            val localConfigured = if (provider == "GEMINI") ApiKeyStore.getKey(context).isNotBlank()
                else ProviderKeyStore.has(context, provider)
            localConfigured || provider in cloudProviders
        }.take(5)
    }

    /** Secure realtime Gemini Live session token; raw provider credentials never enter the UI. */
    suspend fun fetchLiveVoiceToken(context: Context): String {
        OnlineAiConsent.requireAllowed(context)
        return VoiceGatewaySettings.fetchEphemeralToken(context.applicationContext)
    }

    data class StreamingTextResult(val httpCode: Int, val text: String, val finishReason: String?)

    /** Streaming Gemini generation used by Website Forge; API keys and endpoint policy stay here. */
    fun streamGemini(
        context: Context,
        systemPrompt: String,
        onChunk: (String) -> Unit
    ): StreamingTextResult {
        if (!OnlineAiConsent.isAllowed(context)) {
            return StreamingTextResult(403, "Online AI is disabled by the user.", null)
        }
        val apiKey = ApiKeyStore.getKey(context.applicationContext)
        require(apiKey.isNotBlank()) { "GEMINI API key is missing." }
        val model = kotlinx.coroutines.runBlocking { GeminiTextClient.resolveModel(apiKey) }
        val modelEndpoint = if (model.startsWith("models/")) model else "models/$model"
        val url = "https://generativelanguage.googleapis.com/v1beta/$modelEndpoint:streamGenerateContent?alt=sse"
        val parts = JSONArray().put(JSONObject().put("text", systemPrompt.take(32_000)))
        val content = JSONObject().put("role", "user").put("parts", parts)
        val generationConfig = JSONObject()
        if (model.contains("2.5")) generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", generationConfig)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url)
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()
        GeminiTextClient.internalHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return StreamingTextResult(response.code, "", null)
            val input = response.body?.byteStream()
                ?: return StreamingTextResult(response.code, "", null)
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val accumulated = StringBuilder()
            var finishReason: String? = null
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("data:")) {
                    val data = trimmed.substring(5).trim()
                    SseParser.extractFinishReason(data)?.let { finishReason = it }
                    SseParser.extractText(data)?.takeIf { it.isNotEmpty() }?.let { chunk ->
                        accumulated.append(chunk)
                        onChunk(chunk)
                    }
                    SseParser.extractError(data)?.let { error(it) }
                }
                line = reader.readLine()
            }
            return StreamingTextResult(response.code, accumulated.toString(), finishReason)
        }
    }

    fun reply(
        context: Context,
        providerName: String,
        systemInstruction: String,
        history: List<ChatTurn>
    ): ChatReplyResult {
        if (!OnlineAiConsent.isAllowed(context)) {
            return ChatReplyResult.Error("Online AI is disabled. Your content was not sent to a provider.")
        }
        val provider = providerName.uppercase()
        if (provider !in supportedProviders) return ChatReplyResult.Error("Unsupported AI provider: $provider")
        if (history.isEmpty()) return ChatReplyResult.Error("No conversation history provided.")
        val boundedHistory = history.takeLast(40).map { it.copy(text = it.text.take(8_000)) }
        // Hybrid routing: use the authenticated HTTPS agent when this provider is configured there.
        // A failed configured cloud route is surfaced; it is not silently retried against a
        // different provider or billed to a second local API key.
        val historyPrompt = boundedHistory.joinToString("\n\n") { turn ->
            (if (turn.isUser) "User: " else "Assistant: ") + turn.text
        }.take(12_000)
        val cloud = runCatching {
            VoiceGatewaySettings.executeAgent(context, provider, "Main Brain", systemInstruction.take(8_000), historyPrompt)
        }.getOrElse { return ChatReplyResult.Error("Cloud " + provider + " agent failed: " + (it.message ?: "network error")) }
        if (cloud != null) {
            val usage = if (cloud.inputTokens != null && cloud.outputTokens != null && !cloud.model.isNullOrBlank()) {
                runCatching { com.Lia.assistant.voice.ProviderTokenUsage(provider, cloud.model, cloud.inputTokens, cloud.outputTokens) }.getOrNull()
            } else null
            return ChatReplyResult.Success(cloud.text, usage)
        }
        // Direct device API keys are an explicit fallback only when the gateway has no key for this provider.
        return if (provider == "GEMINI") {
            val key = runCatching { ApiKeyStore.getKey(context) }.getOrElse {
                return ChatReplyResult.Error("Could not read the encrypted GEMINI API key: " + it.message)
            }
            if (key.isBlank()) ChatReplyResult.Error("GEMINI API key is missing.")
            else GeminiTextClient.reply(key, systemInstruction.take(8_000), boundedHistory)
        } else {
            MultiProviderChatClient.reply(context, provider, systemInstruction.take(8_000), boundedHistory)
        }
    }

    fun complete(context: Context, providerName: String, systemInstruction: String, prompt: String): String {
        OnlineAiConsent.requireAllowed(context)
        VoiceGatewaySettings.executeAgent(context, providerName, "Assistant", systemInstruction, prompt)?.let { return it.text }
        return when (val result = reply(context, providerName, systemInstruction, listOf(ChatTurn(true, prompt.take(8_000))))) {
            is ChatReplyResult.Success -> result.text
            is ChatReplyResult.Error -> error("$providerName provider error: ${result.message}")
        }
    }
    
    /** Returns provider text and any token counters explicitly reported by the provider. */
    fun completeWithUsage(
        context: Context,
        providerName: String,
        systemInstruction: String,
        prompt: String
    ): Pair<String, com.Lia.assistant.voice.ProviderTokenUsage?> {
        OnlineAiConsent.requireAllowed(context)
        VoiceGatewaySettings.executeAgent(context, providerName, "Main Brain", systemInstruction, prompt)?.let { cloud ->
            val usage = if (cloud.inputTokens != null && cloud.outputTokens != null && !cloud.model.isNullOrBlank()) {
                com.Lia.assistant.voice.ProviderTokenUsage(providerName.uppercase(), cloud.model, cloud.inputTokens, cloud.outputTokens)
            } else {
                CouncilCostBudget.stopIfUsageUnknown(context, providerName, cloud.model)
                null
            }
            return cloud.text to usage
        }
        return when (val result = reply(context, providerName, systemInstruction, listOf(ChatTurn(true, prompt.take(8_000))))) {
            is ChatReplyResult.Success -> result.text to result.tokenUsage
            is ChatReplyResult.Error -> error(providerName + " provider error: " + result.message)
        }
    }

}
