package `in`.shraddha.ai.future

import android.content.Context
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.ProviderKeyStore
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.GeminiTextClient
import com.Lia.assistant.voice.MultiProviderChatClient

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

    fun complete(context: Context, providerName: String, systemInstruction: String, prompt: String): String {
        val provider = providerName.uppercase()
        require(provider in supportedProviders) { "Unsupported AI provider: $provider" }
        val turns = listOf(ChatTurn(true, prompt.take(8_000)))
        val response = if (provider == "GEMINI") {
            val key = ApiKeyStore.getKey(context)
            require(key.isNotBlank()) { "GEMINI API key is missing." }
            GeminiTextClient.reply(key, systemInstruction.take(8_000), turns)
        } else {
            MultiProviderChatClient.reply(context, provider, systemInstruction, turns)
        }
        return when (response) {
            is ChatReplyResult.Success -> response.text
            is ChatReplyResult.Error -> error("$provider provider error: ${response.message}")
        }
    }
}
