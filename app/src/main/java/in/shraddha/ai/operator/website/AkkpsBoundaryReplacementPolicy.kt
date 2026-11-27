package `in`.shraddha.ai.operator.website

/** Prevents imports from silently replacing an existing boundary draft or editor geometry. */
internal object AkkpsBoundaryReplacementPolicy {
    fun requiresConfirmation(existingPointCount: Int): Boolean = existingPointCount > 0
}
