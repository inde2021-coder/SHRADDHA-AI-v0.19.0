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

/**
 * The sole provider dispatch boundary for Main Brain orchestrated work.
 * Provider credentials remain in their existing encrypted stores; this class never logs them.
 */
object MainBrainProviderGateway {
    val supportedProviders: Set<String> =
        setOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")

    fun configuredProviders(context: Context, preferredProvider: String? = null): List<String> {
        val preferred = (preferredProvider
            ?: NovaPreferences.getString(context, NovaPreferences.Keys.AI_PROVIDER, "GEMINI"))
            .uppercase()
        require(preferred in supportedProviders) { "Unsupported preferred AI provider: $preferred" }
        return (listOf(preferred) + supportedProviders).distinct().filter { provider ->
            runCatching {
                if (provider == "GEMINI") ApiKeyStore.getKey(context).isNotBlank()
                else ProviderKeyStore.has(context, provider)
            }.getOrDefault(false)
        }.take(5)
    }

    /** Secure realtime Gemini Live session token; raw provider credentials never enter the UI. */
    suspend fun fetchLiveVoiceToken(context: Context): String =
        VoiceGatewaySettings.fetchEphemeralToken(context.applicationContext)

    fun reply(
        context: Context,
        providerName: String,
        systemInstruction: String,
        history: List<ChatTurn>
    ): ChatReplyResult {
        val provider = providerName.uppercase()
        if (provider !in supportedProviders) return ChatReplyResult.Error("Unsupported AI provider: $provider")
        if (history.isEmpty()) return ChatReplyResult.Error("No conversation history provided.")
        val boundedHistory = history.takeLast(40).map { it.copy(text = it.text.take(8_000)) }
        return if (provider == "GEMINI") {
            val key = runCatching { ApiKeyStore.getKey(context) }.getOrElse {
                return ChatReplyResult.Error("Could not read the encrypted GEMINI API key: ${it.message}")
            }
            if (key.isBlank()) ChatReplyResult.Error("GEMINI API key is missing.")
            else GeminiTextClient.reply(key, systemInstruction.take(8_000), boundedHistory)
        } else {
            MultiProviderChatClient.reply(context, provider, systemInstruction.take(8_000), boundedHistory)
        }
    }

    fun complete(context: Context, providerName: String, systemInstruction: String, prompt: String): String =
        when (val result = reply(context, providerName, systemInstruction, listOf(ChatTurn(true, prompt.take(8_000))))) {
            is ChatReplyResult.Success -> result.text
            is ChatReplyResult.Error -> error("$providerName provider error: ${result.message}")
        }
}
