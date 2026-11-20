package `in`.shraddha.ai.future

import android.content.Context
import `in`.shraddha.ai.brain.SecureMemoryVault
import org.json.JSONArray

/**
 * Opt-in user-curated knowledge graph persisted in its own Android-Keystore encrypted slot.
 * It is local-only; no inferred sensitive traits and no automatic cloud upload.
 */
class PersistentKnowledgeGraph(context: Context) {
    private val appContext = context.applicationContext
    private val vault = SecureMemoryVault(appContext)
    private val slot = "knowledge_graph_v1"

    fun save(graph: KnowledgeGraph): Result<Unit> = runCatching {
        vault.saveSlot(slot, KnowledgeGraphCodec.encode(graph)).getOrThrow()
    }

    fun load(): Result<KnowledgeGraph> = runCatching {
        val payload = vault.loadSlot(slot).getOrThrow()
        if (payload.isNotBlank()) return@runCatching KnowledgeGraphCodec.decode(payload)
        val legacyPrefs = appContext.getSharedPreferences("shraddha_knowledge_graph", Context.MODE_PRIVATE)
        val legacy = JSONArray(legacyPrefs.getString("nodes", "[]") ?: "[]")
        val graph = KnowledgeGraph()
        require(legacy.length() <= KnowledgeGraph.MAX_NODES) { "Legacy graph exceeds the current memory limit; export it before migration." }
        for (i in 0 until legacy.length()) {
            val item = legacy.getJSONObject(i)
            graph.upsert(KnowledgeNode(item.optString("id").ifBlank { "legacy-$i" }, item.optString("label"),
                item.optString("kind", "Note"), item.optString("note"), item.optLong("updated", System.currentTimeMillis())))
        }
        if (legacy.length() > 0) {
            vault.saveSlot(slot, KnowledgeGraphCodec.encode(graph)).getOrThrow()
            check(legacyPrefs.edit().remove("nodes").commit()) { "Encrypted graph saved but legacy cleanup failed" }
        }
        graph
    }

    fun clear(): Boolean {
        val encryptedCleared = vault.clearSlot(slot)
        val legacyCleared = appContext.getSharedPreferences("shraddha_knowledge_graph", Context.MODE_PRIVATE)
            .edit().remove("nodes").commit()
        return encryptedCleared && legacyCleared
    }
}
