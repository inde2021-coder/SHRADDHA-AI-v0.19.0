package `in`.shraddha.ai.brain

/** Builds a conservative, extractive answer from user-imported local documents. */
object LocalKnowledgeAnswer {
    data class Answer(val text: String, val citations: List<String>)

    private val stopWords = setOf(
        "what", "when", "where", "which", "who", "whom", "why", "how", "the", "and", "for",
        "with", "from", "that", "this", "are", "was", "were", "is", "in", "on", "of", "to",
        "का", "की", "के", "है", "हैं", "था", "थी", "थे", "और", "में", "से", "को", "क्या",
        "कैसे", "कब", "कहाँ", "कौन", "एक", "मुझे", "बताओ", "बताइए"
    )

    /** Weak matches return null so normal provider routing can continue. */
    fun build(query: String, hits: List<LocalKnowledgeDocumentStore.SearchHit>): Answer? {
        if (query.isBlank() || hits.isEmpty()) return null
        val terms = tokens(query).filter { it.length > 1 && it !in stopWords }.toSet()
        if (terms.isEmpty()) return null
        val minimumMatches = if (terms.size >= 2) 2 else 1
        val selected = hits.asSequence()
            .filter { it.score >= 1.25 }
            .filter { hit -> terms.count { it in tokens(hit.excerpt).toSet() } >= minimumMatches }
            .distinctBy { it.documentId to it.chunkIndex }
            .take(3)
            .toList()
        if (selected.isEmpty()) return null
        val citations = selected.map { it.citation }.distinct()
        val excerpts = selected.joinToString("\n\n") { hit ->
            hit.excerpt.trim() + "\nस्रोत: " + hit.citation
        }
        return Answer(
            text = "मेरी स्थानीय Knowledge Library में यह जानकारी मिली:\n\n" + excerpts +
                "\n\nयह उत्तर आपके डिवाइस पर सेव किए गए दस्तावेज़ों से निकाला गया है। " +
                "अगर दस्तावेज़ अधूरा या पुराना है, तो इसकी पुष्टि करें।",
            citations = citations
        )
    }

    private fun tokens(value: String): List<String> =
        Regex("[\\p{L}\\p{M}\\p{N}]+")
            .findAll(value.lowercase())
            .map { it.value }
            .toList()
}