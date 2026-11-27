package `in`.shraddha.ai.operator.website

/** Prevents file imports from silently replacing unsaved editor geometry. */
internal object AkkpsBoundaryReplacementPolicy {
    fun requiresConfirmation(existingPointCount: Int): Boolean = existingPointCount > 0
}
