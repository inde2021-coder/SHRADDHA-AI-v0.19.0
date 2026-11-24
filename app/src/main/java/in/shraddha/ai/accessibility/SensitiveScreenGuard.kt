package `in`.shraddha.ai.accessibility

/** Screen-coordinate bounds for controls whose contents/actions are sensitive. */
internal data class SensitiveBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Reject remote touch gestures that would enter or cross a sensitive control. */
internal object SensitiveScreenGuard {
    fun blocksGesture(bounds: List<SensitiveBounds>, x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return true
        if (bounds.size > 500) return true
        return bounds.any { rect ->
            if (rect.right <= rect.left || rect.bottom <= rect.top) return@any false
            // Sample the complete path, not only endpoints, so a swipe cannot cross a PIN field.
            (0..32).any { step ->
                val t = step / 32f
                val x = x1 + (x2 - x1) * t
                val y = y1 + (y2 - y1) * t
                x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
            }
        }
    }
}
