package `in`.shraddha.ai.brain

/** Small on-device keyword retrieval for user-selected notes; no cloud upload or embeddings. */
object LocalKnowledgeBase {
    data class Hit(val text: String, val score: Int)
    fun search(notes: String, query: String, limit: Int = 4): List<Hit> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 1 }.toSet()
        if (terms.isEmpty()) return emptyList()
        return notes.split(Regex("(?<=[.!?।\\n])\\s+")).mapNotNull { sentence ->
            val words = sentence.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).toSet()
            val score = terms.count { it in words || words.any { w -> w.startsWith(it) && it.length >= 3 } }
            if (score > 0) Hit(sentence.trim(), score) else null
        }.sortedByDescending { it.score }.take(limit)
    }
}
