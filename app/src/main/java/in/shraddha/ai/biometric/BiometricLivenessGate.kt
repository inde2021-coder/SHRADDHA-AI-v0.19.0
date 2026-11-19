package `in`.shraddha.ai.biometric

/** Active challenge-response gate; this is basic PAD, not a certified anti-spoofing system. */
class BiometricLivenessGate(sequence: List<Step> = listOf(Step.BLINK, Step.TURN_SIDE, Step.TURN_OPPOSITE).shuffled()) {
    enum class Step { BLINK, TURN_SIDE, TURN_OPPOSITE }
    private val steps = sequence.toList()
    private var index = 0
    private var blinkClosed = false
    private var firstTurnSign = 0
    val isComplete: Boolean get() = index >= steps.size
    val currentStep: Step? get() = steps.getOrNull(index)

    init { require(steps.size == 3 && steps.toSet() == Step.values().toSet()) }

    @Synchronized
    fun observe(faceCount: Int, leftEyeOpen: Float, rightEyeOpen: Float, yawDegrees: Float): Boolean {
        if (isComplete || faceCount != 1) return isComplete
        val eyeOpen = minOf(leftEyeOpen, rightEyeOpen)
        val passed = when (currentStep) {
            Step.BLINK -> { if (eyeOpen < 0.25f) blinkClosed = true; blinkClosed && eyeOpen > 0.65f }
            Step.TURN_SIDE -> if (kotlin.math.abs(yawDegrees) > 18f) { firstTurnSign = if (yawDegrees < 0f) -1 else 1; true } else false
            Step.TURN_OPPOSITE -> firstTurnSign != 0 && (if (firstTurnSign < 0) yawDegrees > 18f else yawDegrees < -18f)
            null -> true
        }
        if (passed) { index++; if (currentStep == Step.BLINK) blinkClosed = false }
        return isComplete
    }

    fun prompt(): String = when (currentStep) {
        Step.BLINK -> "Live check: blink naturally, then open both eyes"
        Step.TURN_SIDE -> "Live check: slowly turn your head to either side"
        Step.TURN_OPPOSITE -> "Live check: slowly turn your head to the opposite side"
        null -> "Live challenge complete. Tap capture to continue."
    }
}