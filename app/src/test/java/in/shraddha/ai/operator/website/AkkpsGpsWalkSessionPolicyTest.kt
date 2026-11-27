package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Test

class AkkpsGpsWalkSessionPolicyTest {
    @Test fun emptyEditorStartsNewWalk() {
        assertEquals(
            AkkpsGpsWalkSessionPolicy.Decision.START_NEW,
            AkkpsGpsWalkSessionPolicy.decide(0, false)
        )
    }

    @Test fun pausedGpsSessionResumesWithoutReplacementPrompt() {
        assertEquals(
            AkkpsGpsWalkSessionPolicy.Decision.RESUME,
            AkkpsGpsWalkSessionPolicy.decide(24, true)
        )
    }

    @Test fun importedOrSavedBoundaryRequiresExplicitReplacementConfirmation() {
        assertEquals(
            AkkpsGpsWalkSessionPolicy.Decision.CONFIRM_REPLACE,
            AkkpsGpsWalkSessionPolicy.decide(12, false)
        )
    }
}
