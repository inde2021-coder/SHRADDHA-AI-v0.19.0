package `in`.shraddha.ai.operator.website

internal object AkkpsGpsWalkSessionPolicy {
    enum class Decision { START_NEW, RESUME, CONFIRM_REPLACE }

    fun decide(pointCount: Int, sessionInitialized: Boolean): Decision {
        require(pointCount >= 0)
        return when {
            sessionInitialized -> Decision.RESUME
            pointCount > 0 -> Decision.CONFIRM_REPLACE
            else -> Decision.START_NEW
        }
    }
}
