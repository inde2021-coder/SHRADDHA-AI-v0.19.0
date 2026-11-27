package `in`.shraddha.ai.operator.website

/** Fail-closed policy for AI-assisted AKKPS form submission. */
internal object AkkpsAiSubmitPolicy {
    fun hasDuplicateFields(fields: List<String>): Boolean {
        val normalized = fields.map { it.trim().lowercase() }
        return normalized.any { it.isBlank() } || normalized.distinct().size != normalized.size
    }

    fun maySubmit(aiRequested: Boolean, userExplicitlyRequested: Boolean, missing: List<String>): Boolean {
        return aiRequested && userExplicitlyRequested && missing.none { it.isNotBlank() }
    }
}
