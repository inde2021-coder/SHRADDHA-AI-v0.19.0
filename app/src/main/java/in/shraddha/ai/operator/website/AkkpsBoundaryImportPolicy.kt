package `in`.shraddha.ai.operator.website

internal object AkkpsBoundaryImportPolicy {
    fun requiresReplacementConfirmation(existingPointCount: Int): Boolean {
        require(existingPointCount >= 0)
        return existingPointCount > 0
    }
}
