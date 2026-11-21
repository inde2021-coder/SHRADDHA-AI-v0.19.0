package `in`.shraddha.ai.biometric

/** Pure five-landmark similarity transform for SFace's 112x112 aligned crop. */
object FaceAlignmentMath {
    fun similarityTransform(source: FloatArray, target: FloatArray): FloatArray {
        require(source.size == 10 && target.size == 10) { "Exactly five 2D landmarks are required" }
        require((source.asList() + target.asList()).all { it.isFinite() }) { "Landmarks must be finite" }

        var sourceX = 0.0
        var sourceY = 0.0
        var targetX = 0.0
        var targetY = 0.0
        for (i in 0 until 5) {
            sourceX += source[i * 2]
            sourceY += source[i * 2 + 1]
            targetX += target[i * 2]
            targetY += target[i * 2 + 1]
        }
        sourceX /= 5.0
        sourceY /= 5.0
        targetX /= 5.0
        targetY /= 5.0

        var denominator = 0.0
        var scaleNumerator = 0.0
        var rotationNumerator = 0.0
        for (i in 0 until 5) {
            val x = source[i * 2] - sourceX
            val y = source[i * 2 + 1] - sourceY
            val u = target[i * 2] - targetX
            val v = target[i * 2 + 1] - targetY
            denominator += x * x + y * y
            scaleNumerator += x * u + y * v
            rotationNumerator += x * v - y * u
        }
        require(denominator > 1e-9) { "Face landmarks are degenerate" }
        val a = scaleNumerator / denominator
        val b = rotationNumerator / denominator
        val translateX = targetX - a * sourceX + b * sourceY
        val translateY = targetY - b * sourceX - a * sourceY
        return floatArrayOf(
            a.toFloat(), (-b).toFloat(), translateX.toFloat(),
            b.toFloat(), a.toFloat(), translateY.toFloat(),
            0f, 0f, 1f
        )
    }
}
