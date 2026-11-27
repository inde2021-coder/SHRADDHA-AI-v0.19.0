package `in`.shraddha.ai.operator.website

/** Validates the exact set of mapped field keys before selecting a submit target. */
internal object AkkpsSubmitTargetPolicy {
    fun hasValidKeys(keys: List<String>): Boolean {
        val normalized = keys.map { it.trim().lowercase() }
        return normalized.isNotEmpty() && normalized.none { it.isBlank() } &&
            normalized.distinct().size == normalized.size
    }
}
