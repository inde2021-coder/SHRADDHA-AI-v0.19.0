package `in`.shraddha.ai.accessibility

/** Screen-coordinate bounds for controls whose contents/actions are sensitive. */
internal data class SensitiveBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Reject remote touch gestures that would enter or cross a sensitive control. */
internal object SensitiveScreenGuard {
    fun blocksGesture(bounds: List<SensitiveBounds>, x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() || bounds.size > 500) return true
        return bounds.any { rect ->
            rect.right > rect.left && rect.bottom > rect.top &&
                segmentIntersectsRect(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), rect)
        }
    }

    /** Liang-Barsky clipping catches even a very narrow field crossed between gesture samples. */
    private fun segmentIntersectsRect(x1: Double, y1: Double, x2: Double, y2: Double, r: SensitiveBounds): Boolean {
        val dx = x2 - x1
        val dy = y2 - y1
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(x1 - r.left, r.right - x1, y1 - r.top, r.bottom - y1)
        var low = 0.0
        var high = 1.0
        for (i in 0..3) {
            if (p[i] == 0.0) {
                if (q[i] < 0.0) return false
            } else {
                val ratio = q[i] / p[i]
                if (p[i] < 0.0) low = maxOf(low, ratio) else high = minOf(high, ratio)
                if (low > high) return false
            }
        }
        return true
    }
}
