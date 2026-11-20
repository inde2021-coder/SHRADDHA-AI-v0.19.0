package `in`.shraddha.ai.future

import android.content.Context
import `in`.shraddha.ai.brain.SecureMemoryVault

/**
 * Opt-in user-curated knowledge graph persisted in its own Android-Keystore encrypted slot.
 * It is local-only; no inferred sensitive traits and no automatic cloud upload.
 */
class PersistentKnowledgeGraph(context: Context) {
    private val vault = SecureMemoryVault(context.applicationContext)
    private val slot = "knowledge_graph_v1"

    fun save(graph: KnowledgeGraph): Result<Unit> = runCatching {
        vault.saveSlot(slot, KnowledgeGraphCodec.encode(graph)).getOrThrow()
    }

    fun load(): Result<KnowledgeGraph> = runCatching {
        val payload = vault.loadSlot(slot).getOrThrow()
        if (payload.isBlank()) KnowledgeGraph() else KnowledgeGraphCodec.decode(payload)
    }

    fun clear(): Boolean = vault.clearSlot(slot)
}
