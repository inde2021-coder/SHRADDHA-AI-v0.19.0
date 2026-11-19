package `in`.shraddha.ai.biometric

import kotlin.math.abs

/** Active challenge-response heuristic only. This is NOT certified presentation-attack detection. */
class BiometricLivenessGate(
    sequence: List<Step> = listOf(
        listOf(Step.BLINK, Step.TURN_SIDE, Step.TURN_OPPOSITE),
        listOf(Step.TURN_SIDE, Step.BLINK, Step.TURN_OPPOSITE),
        listOf(Step.TURN_SIDE, Step.TURN_OPPOSITE, Step.BLINK)
    ).random(),
    private val startedAtMs: Long = monotonicNow()
) {
    enum class Step { BLINK, TURN_SIDE, TURN_OPPOSITE }

    companion object {
        private const val MAX_CHALLENGE_MS = 45_000L
        private const val REQUIRED_STABLE_FRAMES = 2
        private fun monotonicNow(): Long = System.nanoTime() / 1_000_000L
    }

    private val steps = sequence.toList()
    private var index = 0
    private var blinkClosed = false
    private var firstTurnSign = 0
    private var stableFrames = 0
    private var expired = false

    val isComplete: Boolean get() = index >= steps.size
    val isExpired: Boolean get() = expired
    val currentStep: Step? get() = steps.getOrNull(index)

    init {
        require(steps.size == 3 && steps.toSet() == Step.values().toSet())
        require(steps.indexOf(Step.TURN_SIDE) < steps.indexOf(Step.TURN_OPPOSITE))
    }

    @Synchronized
    fun observe(faceCount: Int, leftEyeOpen: Float, rightEyeOpen: Float, yawDegrees: Float,
                nowMs: Long = monotonicNow()): Boolean {
        if (isComplete) return true
        if (expired || nowMs < startedAtMs || nowMs - startedAtMs > MAX_CHALLENGE_MS) {
            expired = true
            return false
        }
        if (faceCount != 1 || !leftEyeOpen.isFinite() || !rightEyeOpen.isFinite() || !yawDegrees.isFinite()) {
            stableFrames = 0
            if (currentStep == Step.BLINK) blinkClosed = false
            return false
        }
        val eyeOpen = minOf(leftEyeOpen, rightEyeOpen)
        val passed = when (currentStep) {
            Step.BLINK -> {
                if (eyeOpen < 0.25f) {
                    blinkClosed = true
                    stableFrames = 0
                    false
                } else if (blinkClosed && eyeOpen > 0.65f) {
                    stableFrames++
                    stableFrames >= REQUIRED_STABLE_FRAMES
                } else {
                    stableFrames = 0
                    false
                }
            }
            Step.TURN_SIDE -> {
                if (abs(yawDegrees) > 18f) {
                    stableFrames++
                    if (stableFrames >= REQUIRED_STABLE_FRAMES) {
                        firstTurnSign = if (yawDegrees < 0f) -1 else 1
                        true
                    } else false
                } else {
                    stableFrames = 0
                    false
                }
            }
            Step.TURN_OPPOSITE -> {
                val opposite = firstTurnSign != 0 && (if (firstTurnSign < 0) yawDegrees > 18f else yawDegrees < -18f)
                if (opposite) {
                    stableFrames++
                    stableFrames >= REQUIRED_STABLE_FRAMES
                } else {
                    stableFrames = 0
                    false
                }
            }
            null -> true
        }
        if (passed) {
            index++
            stableFrames = 0
            if (steps.getOrNull(index - 1) == Step.BLINK) blinkClosed = false
        }
        return isComplete
    }

    fun prompt(): String = when {
        isExpired -> "Live check expired. Restart the check and try again."
        currentStep == Step.BLINK -> "Live check: blink naturally, then keep both eyes open"
        currentStep == Step.TURN_SIDE -> "Live check: slowly turn your head to either side and hold"
        currentStep == Step.TURN_OPPOSITE -> "Live check: slowly turn your head to the opposite side and hold"
        else -> "Basic live challenge complete. This is not certified anti-spoofing."
    }
}
