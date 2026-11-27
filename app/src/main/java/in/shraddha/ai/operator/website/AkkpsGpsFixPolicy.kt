package `in`.shraddha.ai.operator.website

/** Pure quality gate for GPS edge-walk fixes; it never manufactures coordinates. */
object AkkpsGpsFixPolicy {
    const val MAX_ACCURACY_METERS = 15f
    const val MIN_POINT_DISTANCE_METERS = 2.0
    const val MAX_WALK_SPEED_METERS_PER_SECOND = 5.5

    enum class Decision { ACCEPT, INACCURATE, TOO_CLOSE, IMPLAUSIBLE_JUMP, INVALID_TIME }

    fun evaluate(
        accuracyMeters: Float,
        distanceFromPreviousMeters: Double?,
        elapsedSecondsFromPrevious: Double?
    ): Decision {
        if (!accuracyMeters.isFinite() || accuracyMeters < 0f || accuracyMeters > MAX_ACCURACY_METERS) {
            return Decision.INACCURATE
        }
        if (distanceFromPreviousMeters == null) return Decision.ACCEPT
        if (!distanceFromPreviousMeters.isFinite() || distanceFromPreviousMeters < 0.0) return Decision.IMPLAUSIBLE_JUMP
        if (distanceFromPreviousMeters < MIN_POINT_DISTANCE_METERS) return Decision.TOO_CLOSE
        val elapsed = elapsedSecondsFromPrevious ?: return Decision.INVALID_TIME
        if (!elapsed.isFinite() || elapsed <= 0.0) return Decision.INVALID_TIME
        if (distanceFromPreviousMeters / elapsed > MAX_WALK_SPEED_METERS_PER_SECOND) return Decision.IMPLAUSIBLE_JUMP
        return Decision.ACCEPT
    }
}
