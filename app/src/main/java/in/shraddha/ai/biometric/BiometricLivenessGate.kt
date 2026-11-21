package `in`.shraddha.ai.biometric

/**
 * Basic interaction challenge only. This is NOT certified presentation-attack detection.
 * A blink/head-turn challenge can be replayed or spoofed and must never be treated as proof
 * of a live human or as an authentication factor by itself.
 */
class BiometricLivenessGate {
    private enum class Stage { WAIT_OPEN, WAIT_BLINK, WAIT_TURN, WAIT_RETURN, COMPLETE }
    private var stage = Stage.WAIT_OPEN

    fun prompt(): String = when (stage) {
        Stage.WAIT_OPEN -> "Keep one face centered and look at the camera."
        Stage.WAIT_BLINK -> "Blink naturally once."
        Stage.WAIT_TURN -> "Turn your head slightly to one side."
        Stage.WAIT_RETURN -> "Return your head to the camera and look forward."
        Stage.COMPLETE -> "Basic blink, turn and return challenge completed. This is not certified anti-spoofing."
    }

    @Synchronized
    fun observe(faceCount: Int, leftEyeOpen: Float, rightEyeOpen: Float, yawDegrees: Float): Boolean {
        if (stage == Stage.COMPLETE) return true
        if (faceCount != 1 || !leftEyeOpen.isFinite() || !rightEyeOpen.isFinite() || !yawDegrees.isFinite()) return false
        when (stage) {
            Stage.WAIT_OPEN -> if (leftEyeOpen > 0.65f && rightEyeOpen > 0.65f && kotlin.math.abs(yawDegrees) < 12f) {
                stage = Stage.WAIT_BLINK
            }
            Stage.WAIT_BLINK -> if (leftEyeOpen < 0.25f && rightEyeOpen < 0.25f) {
                stage = Stage.WAIT_TURN
            }
            Stage.WAIT_TURN -> if (kotlin.math.abs(yawDegrees) in 12f..35f) {
                stage = Stage.WAIT_RETURN
            }
            Stage.WAIT_RETURN -> if (kotlin.math.abs(yawDegrees) < 10f && leftEyeOpen > 0.65f && rightEyeOpen > 0.65f) {
                stage = Stage.COMPLETE
            }
            Stage.COMPLETE -> Unit
        }
        return stage == Stage.COMPLETE
    }
}
