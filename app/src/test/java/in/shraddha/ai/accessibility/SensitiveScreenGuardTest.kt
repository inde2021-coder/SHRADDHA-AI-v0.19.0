package `in`.shraddha.ai.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveScreenGuardTest {
    @Test fun blocksTapInsideSensitiveField() {
        val bounds = listOf(SensitiveBounds(100, 200, 300, 260))
        assertTrue(SensitiveScreenGuard.blocksGesture(bounds, 150f, 220f, 150f, 220f))
        assertFalse(SensitiveScreenGuard.blocksGesture(bounds, 350f, 220f, 350f, 220f))
    }

    @Test fun blocksSwipeThatCrossesSensitiveFieldEvenWhenEndpointsAreOutside() {
        val bounds = listOf(SensitiveBounds(100, 200, 300, 260))
        assertTrue(SensitiveScreenGuard.blocksGesture(bounds, 20f, 230f, 380f, 230f))
        assertTrue(SensitiveScreenGuard.blocksGesture(listOf(SensitiveBounds(100, 200, 101, 260)), 20f, 230f, 380f, 230f))
        assertFalse(SensitiveScreenGuard.blocksGesture(bounds, 20f, 100f, 380f, 100f))
    }

    @Test fun malformedCoordinatesFailClosed() {
        assertTrue(SensitiveScreenGuard.blocksGesture(emptyList(), Float.NaN, 0f, 1f, 1f))
        assertTrue(SensitiveScreenGuard.blocksGesture(List(501) { SensitiveBounds(0, 0, 1, 1) }, 0f, 0f, 1f, 1f))
    }
}
