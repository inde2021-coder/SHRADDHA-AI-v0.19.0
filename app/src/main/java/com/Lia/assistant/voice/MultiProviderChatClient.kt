package com.Lia.assistant.voice

import android.content.Context
import com.Lia.assistant.data.ProviderKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Text chat adapters for non-Gemini providers.
 * Secrets stay in the encrypted local vault and are never logged.
 */
object MultiProviderChatClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    private data class Config(val endpoint: String, val model: String, val anthropic: Boolean = false)

    private fun config(provider: String): Config? = when (provider.uppercase()) {
        "OPENAI" -> Config("https://api.openai.com/v1/chat/completions", "gpt-4o-mini")
        "GROQ" -> Config("https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile")
        "PERPLEXITY" -> Config("https://api.perplexity.ai/chat/completions", "sonar")
        "CLAUDE" -> Config("https://api.anthropic.com/v1/messages", "claude-3-5-sonnet-latest", true)
        else -> null
    }

    fun reply(
        context: Context,
        provider: String,
        systemInstruction: String,
        history: List<ChatTurn>
    ): ChatReplyResult {
        val selected = provider.uppercase()
        val cfg = config(selected) ?: return ChatReplyResult.Error("Unsupported AI provider.")
        val apiKey = runCatching { ProviderKeyStore.get(context, selected) }
            .getOrElse { return ChatReplyResult.Error("Could not read the encrypted $selected API key: ${it.message}") }
        if (apiKey.isBlank()) return ChatReplyResult.Error("$selected API key is missing. Add it in Settings.")
        if (history.isEmpty()) return ChatReplyResult.Error("No conversation history provided.")

        val body = if (cfg.anthropic) {
            val messages = JSONArray()
            history.forEach { turn ->
                messages.put(JSONObject().put("role", if (turn.isUser) "user" else "assistant")
                    .put("content", turn.text))
            }
            JSONObject().put("model", cfg.model).put("max_tokens", 2048)
                .put("system", systemInstruction).put("messages", messages)
        } else {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
            history.forEach { turn ->
                messages.put(JSONObject().put("role", if (turn.isUser) "user" else "assistant")
                    .put("content", turn.text))
            }
            JSONObject().put("model", cfg.model).put("messages", messages)
                .put("temperature", 0.7).put("max_tokens", 2048)
        }

        val builder = Request.Builder().url(cfg.endpoint)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
        if (cfg.anthropic) {
            builder.header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
        } else {
            builder.header("Authorization", "Bearer $apiKey")
        }

        return try {
            http.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        val json = JSONObject(raw)
                        json.optJSONObject("error")?.optString("message")
                            ?: json.optString("message").takeIf { it.isNotBlank() }
                    }.getOrNull()
                    return ChatReplyResult.Error(message ?: "$selected request failed (HTTP ${response.code}).")
                }
                val json = JSONObject(raw)
                val text = if (cfg.anthropic) {
                    val content = json.optJSONArray("content") ?: JSONArray()
                    (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }
                        .filter { !it.isNullOrBlank() }.joinToString("\n")
                } else {
                    json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                        ?.optString("content").orEmpty()
                }
                if (text.isBlank()) ChatReplyResult.Error("$selected returned an empty response.")
                else ChatReplyResult.Success(text)
            }
        } catch (_: IOException) {
            ChatReplyResult.Error("Couldn't reach $selected. Check the internet connection and try again.")
        } catch (e: Exception) {
            ChatReplyResult.Error("$selected request failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
