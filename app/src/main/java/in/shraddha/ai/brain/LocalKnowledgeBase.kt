package `in`.shraddha.ai.brain

/**
 * On-device retrieval for user-selected notes and encrypted memory recaps.
 * This is lexical search (not embeddings or semantic AI); content is never uploaded.
 */
object LocalKnowledgeBase {
    data class Hit(
        val text: String,
        val score: Int,
        val matchedTerms: List<String> = emptyList()
    )

    private val stopWords = setOf(
        "the", "and", "for", "with", "from", "that", "this", "was", "were", "are", "you", "your",
        "में", "से", "को", "का", "की", "के", "और", "है", "हैं", "था", "थी", "थे", "पर", "एक",
        "मुझे", "मेरा", "मेरी", "मेरे", "यह", "वह", "कर", "करो", "करना", "था"
    )

    /** Returns the strongest distinct passages; limit is deliberately bounded for prompt safety. */
    fun search(notes: String, query: String, limit: Int = 4): List<Hit> {
        val safeLimit = limit.coerceIn(0, 10)
        if (safeLimit == 0 || notes.isBlank() || query.isBlank()) return emptyList()
        val terms = tokens(query).filter { it.length > 1 && it !in stopWords }.distinct()
        if (terms.isEmpty()) return emptyList()
        val normalizedQuery = query.trim().lowercase()
        val seen = hashSetOf<String>()

        return notes.split(Regex("(?<=[.!?।\\n])\\s+|\\n{2,}"))
            .mapNotNull { raw ->
                val passage = raw.trim()
                if (passage.isEmpty()) return@mapNotNull null
                val identity = passage.lowercase()
                if (!seen.add(identity)) return@mapNotNull null
                val words = tokens(passage).toSet()
                val matched = terms.filter { term ->
                    words.any { word -> word == term || (term.length >= 3 && word.startsWith(term)) || (word.length >= 4 && term.startsWith(word)) }
                }
                if (matched.isEmpty()) return@mapNotNull null
                var score = matched.fold(0) { total, term ->
                    total + if (term in words) 3 else 1
                }
                if (identity.contains(normalizedQuery)) score += 5
                // Prefer passages that cover a larger fraction of the user's query.
                score += (matched.size * 4 / terms.size)
                Hit(passage, score, matched)
            }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.matchedTerms.size })
            .take(safeLimit)
    }

    private fun tokens(value: String): List<String> =
        value.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() }
}
