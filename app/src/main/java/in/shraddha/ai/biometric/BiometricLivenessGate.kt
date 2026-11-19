package `in`.shraddha.ai.biometric

/** Active challenge-response gate; this is basic PAD, not a certified anti-spoofing system. */
class BiometricLivenessGate(sequence: List<Step> = listOf(Step.BLINK, Step.TURN_LEFT, Step.TURN_RIGHT).shuffled()) {
    enum class Step { BLINK, TURN_LEFT, TURN_RIGHT }
    private val steps = sequence.toList()
    private var index = 0
    private var blinkClosed = false
    val isComplete: Boolean get() = index >= steps.size
    val currentStep: Step? get() = steps.getOrNull(index)

    init { require(steps.size == 3 && steps.toSet() == Step.values().toSet()) }

    @Synchronized
    fun observe(faceCount: Int, leftEyeOpen: Float, rightEyeOpen: Float, yawDegrees: Float): Boolean {
        if (isComplete || faceCount != 1) return isComplete
        val eyeOpen = minOf(leftEyeOpen, rightEyeOpen)
        val passed = when (currentStep) {
            Step.BLINK -> { if (eyeOpen < 0.25f) blinkClosed = true; blinkClosed && eyeOpen > 0.65f }
            Step.TURN_LEFT -> yawDegrees < -18f
            Step.TURN_RIGHT -> yawDegrees > 18f
            null -> true
        }
        if (passed) { index++; if (currentStep == Step.BLINK) blinkClosed = false }
        return isComplete
    }

    fun prompt(): String = when (currentStep) {
        Step.BLINK -> "Live check: blink naturally, then open both eyes"
        Step.TURN_LEFT -> "Live check: slowly turn your face to your left"
        Step.TURN_RIGHT -> "Live check: slowly turn your face to your right"
        null -> "Live challenge complete. Tap capture to continue."
    }
}