package `in`.shraddha.ai.operator.learning

/** Side-effect-free verification policy for a just-executed UI step. */
internal object WorkflowStepResultVerification {
    enum class Decision { VERIFIED, REVIEW_REQUIRED }

    fun assess(
        expectedText: String,
        expectedTextAbsentBefore: Boolean,
        beforeFingerprint: String,
        currentFingerprint: String,
        visibleText: Collection<String>
    ): Decision {
        if (beforeFingerprint.isBlank() || currentFingerprint.isBlank() ||
            beforeFingerprint == currentFingerprint) return Decision.REVIEW_REQUIRED

        val expected = expectedText.trim()
        if (expected.isBlank()) return Decision.VERIFIED

        if (!expectedTextAbsentBefore) return Decision.REVIEW_REQUIRED
        return if (visibleText.any { it.contains(expected, ignoreCase = true) }) {
            Decision.VERIFIED
        } else {
            Decision.REVIEW_REQUIRED
        }
    }
}
