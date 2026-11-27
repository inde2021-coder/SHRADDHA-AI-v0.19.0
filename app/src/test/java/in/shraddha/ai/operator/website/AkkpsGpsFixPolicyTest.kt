package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Test

class AkkpsGpsFixPolicyTest {
    @Test fun acceptsFirstGoodFixAndNormalWalkingFix() {
        assertEquals(AkkpsGpsFixPolicy.Decision.ACCEPT, AkkpsGpsFixPolicy.evaluate(4f, null, null))
        assertEquals(AkkpsGpsFixPolicy.Decision.ACCEPT, AkkpsGpsFixPolicy.evaluate(8f, 3.0, 2.0))
    }

    @Test fun rejectsPoorOrInvalidAccuracy() {
        assertEquals(AkkpsGpsFixPolicy.Decision.INACCURATE, AkkpsGpsFixPolicy.evaluate(16f, null, null))
        assertEquals(AkkpsGpsFixPolicy.Decision.INACCURATE, AkkpsGpsFixPolicy.evaluate(Float.NaN, null, null))
        assertEquals(AkkpsGpsFixPolicy.Decision.INACCURATE, AkkpsGpsFixPolicy.evaluate(-1f, null, null))
    }

    @Test fun rejectsNearDuplicateAndImplausibleJump() {
        assertEquals(AkkpsGpsFixPolicy.Decision.TOO_CLOSE, AkkpsGpsFixPolicy.evaluate(5f, 1.5, 2.0))
        assertEquals(AkkpsGpsFixPolicy.Decision.IMPLAUSIBLE_JUMP, AkkpsGpsFixPolicy.evaluate(5f, 20.0, 1.0))
    }

    @Test fun rejectsInvalidElapsedTimeAndDistance() {
        assertEquals(AkkpsGpsFixPolicy.Decision.INVALID_TIME, AkkpsGpsFixPolicy.evaluate(5f, 3.0, 0.0))
        assertEquals(AkkpsGpsFixPolicy.Decision.INVALID_TIME, AkkpsGpsFixPolicy.evaluate(5f, 3.0, null))
        assertEquals(AkkpsGpsFixPolicy.Decision.IMPLAUSIBLE_JUMP, AkkpsGpsFixPolicy.evaluate(5f, Double.NaN, 1.0))
    }
}
