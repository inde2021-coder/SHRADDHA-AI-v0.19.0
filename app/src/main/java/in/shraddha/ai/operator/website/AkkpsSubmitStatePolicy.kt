package `in`.shraddha.ai.operator.website

/** Fail-closed submit state recovery across Activity/process recreation. */
internal object AkkpsSubmitStatePolicy {
    const val VERIFYING = "VERIFYING"
    const val REVIEW_REQUIRED = "REVIEW_REQUIRED"

    fun requiresManualReview(storedState: String?): Boolean =
        storedState == VERIFYING || storedState == REVIEW_REQUIRED
}
