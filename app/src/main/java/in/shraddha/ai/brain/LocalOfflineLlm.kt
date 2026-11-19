package `in`.shraddha.ai.brain

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/** Local text inference. No network client, telemetry, or cloud fallback is used here. */
object LocalOfflineLlm {
    private val lock = Any()
    private var loadedPath: String? = null
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun generate(context: Context, modelPath: String, prompt: String): String = synchronized(lock) {
        require(prompt.isNotBlank() && prompt.length <= 6000) { "Prompt must contain 1-6000 characters" }
        val model = File(modelPath).canonicalFile
        require(model.isFile && model.extension.equals("litertlm", ignoreCase = true)) { "Select a valid local .litertlm model file" }
        require(model.length() >= 10_000_000L) { "Model file is too small to be valid" }

        if (loadedPath != model.absolutePath || engine == null || conversation == null) {
            closeLocked()
            val created = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath))
            try {
                created.initialize()
                val chat = created.createConversation(ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are Shraddha AI, a helpful bilingual Hindi and English assistant. " +
                        "You run fully offline on this device. Be accurate, concise, admit uncertainty, " +
                        "and never claim to have performed an action."
                    )
                ))
                engine = created
                conversation = chat
                loadedPath = model.absolutePath
            } catch (error: Throwable) {
                runCatching { created.close() }
                throw error
            }
        }
        val response = requireNotNull(conversation).sendMessage(prompt)
        response.text.trim().ifBlank { "Local model ने कोई उत्तर नहीं दिया।" }
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        loadedPath = null
    }
}
