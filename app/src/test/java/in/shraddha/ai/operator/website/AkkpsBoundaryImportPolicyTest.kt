package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsBoundaryImportPolicyTest {
    @Test fun emptyEditorDoesNotNeedReplacementConfirmation() {
        assertFalse(AkkpsBoundaryImportPolicy.requiresReplacementConfirmation(0))
    }

    @Test fun existingPointsAlwaysRequireExplicitReplacementConfirmation() {
        assertTrue(AkkpsBoundaryImportPolicy.requiresReplacementConfirmation(1))
        assertTrue(AkkpsBoundaryImportPolicy.requiresReplacementConfirmation(500))
    }

    @Test fun rejectsNegativePointCounts() {
        assertTrue(runCatching {
            AkkpsBoundaryImportPolicy.requiresReplacementConfirmation(-1)
        }.isFailure)
    }
}
