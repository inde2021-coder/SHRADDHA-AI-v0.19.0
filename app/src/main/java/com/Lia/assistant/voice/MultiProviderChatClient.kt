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

    private data class Config(
        val endpoint: String,
        val model: String,
        val anthropic: Boolean = false,
        val perplexityAgent: Boolean = false
    )

    private fun config(provider: String): Config? = when (provider.uppercase()) {
        "OPENAI" -> Config("https://api.openai.com/v1/chat/completions", "gpt-4o-mini")
        // Groq retired llama-3.3-70b-versatile for developer-tier traffic in August 2026.
        "GROQ" -> Config("https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b")
        "PERPLEXITY" -> Config("https://api.perplexity.ai/v1/agent", "", perplexityAgent = true)
        "CLAUDE" -> Config("https://api.anthropic.com/v1/messages", "claude-sonnet-5", true)
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

        val boundedHistory = history.takeLast(40).map { turn ->
            turn.copy(text = turn.text.take(8_000))
        }
        val boundedSystemInstruction = systemInstruction.take(8_000)

        val body = when {
            cfg.perplexityAgent -> buildPerplexityAgentBody(boundedSystemInstruction, boundedHistory)
            cfg.anthropic -> {
                val messages = JSONArray()
                boundedHistory.forEach { turn ->
                    messages.put(JSONObject().put("role", if (turn.isUser) "user" else "assistant").put("content", turn.text))
                }
                JSONObject().put("model", cfg.model).put("max_tokens", 2048)
                    .put("system", boundedSystemInstruction).put("messages", messages)
            }
            else -> {
                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", boundedSystemInstruction))
                boundedHistory.forEach { turn ->
                    messages.put(JSONObject().put("role", if (turn.isUser) "user" else "assistant").put("content", turn.text))
                }
                JSONObject().put("model", cfg.model).put("messages", messages)
                    .put("temperature", 0.7).put("max_tokens", 2048)
            }
        }

        val builder = Request.Builder().url(cfg.endpoint)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
        if (cfg.anthropic) {
            builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
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
                if (cfg.perplexityAgent) {
                    parsePerplexityAgentResponse(json)
                } else {
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
            }
        } catch (_: IOException) {
            ChatReplyResult.Error("Couldn't reach $selected. Check the internet connection and try again.")
        } catch (e: Exception) {
            ChatReplyResult.Error("$selected request failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun buildPerplexityAgentBody(systemInstruction: String, history: List<ChatTurn>): JSONObject {
        val input = JSONArray()
        history.forEach { turn ->
            input.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", if (turn.isUser) "user" else "assistant")
                    .put("content", turn.text)
            )
        }
        return JSONObject()
            // Current Agent API uses effort levels (preset is the older field name).
            .put("effort", "fast")
            .put("instructions", systemInstruction)
            .put("input", input)
            .put("max_output_tokens", 2048)
    }

    internal fun parsePerplexityAgentResponse(json: JSONObject): ChatReplyResult {
        val status = json.optString("status", "")
        if (status == "failed" || status == "cancelled") {
            val error = json.optJSONObject("error")
            return ChatReplyResult.Error(
                error?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Perplexity Agent request $status."
            )
        }

        val output = json.optJSONArray("output") ?: JSONArray()
        val answer = StringBuilder()
        val sources = linkedMapOf<String, String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") {
                            val text = part.optString("text")
                            if (text.isNotBlank()) {
                                if (answer.isNotEmpty()) answer.append("\n")
                                answer.append(text)
                            }
                        }
                    }
                }
                "search_results" -> {
                    val results = item.optJSONArray("results") ?: JSONArray()
                    for (j in 0 until results.length()) {
                        val result = results.optJSONObject(j) ?: continue
                        val url = result.optString("url").trim()
                        val title = result.optString("title").trim().ifBlank { url }
                        if (url.startsWith("https://") || url.startsWith("http://")) {
                            if (!sources.containsKey(url)) sources[url] = title
                        }
                    }
                }
                "message" -> {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        val annotations = part.optJSONArray("annotations") ?: JSONArray()
                        for (k in 0 until annotations.length()) {
                            val annotation = annotations.optJSONObject(k) ?: continue
                            val url = annotation.optString("url").trim()
                            val title = annotation.optString("title").trim().ifBlank { url }
                            if (url.startsWith("https://") || url.startsWith("http://")) {
                                if (!sources.containsKey(url)) sources[url] = title
                            }
                        }
                    }
                }
            }
        }

        val text = answer.toString().trim()
        if (text.isBlank()) {
            val error = json.optJSONObject("error")?.optString("message")
            return ChatReplyResult.Error(error?.takeIf { it.isNotBlank() } ?: "Perplexity returned no answer text (status: ${status.ifBlank { "unknown" }}).")
        }
        val sourceFooter = sources.entries.take(5).joinToString("\n") { (url, title) -> "• $title — $url" }
        return ChatReplyResult.Success(if (sourceFooter.isBlank()) text else "$text\n\nSources:\n$sourceFooter")
    }
}
