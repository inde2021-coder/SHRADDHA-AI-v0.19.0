package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsEditFormVerificationPolicyTest {
    private val route = AkkpsRunnerRouteMatch(
        AkkpsRunnerModule.FARMERS,
        "/runner/farmers/farmer-1",
        isDetailRoute = true
    )

    @Test fun authorizesOnlyVisibleFormWithSaveOrSubmitAction() {
        assertTrue(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.FARMERS, route, 1, 8, listOf("Save", "Cancel")
        ))
        assertTrue(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.FARMERS, route, 1, 8, listOf("सबमिट करें")
        ))
    }

    @Test fun rejectsWrongModuleRouteAndMissingFormEvidence() {
        assertFalse(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.PLOTS, route, 1, 8, listOf("Save")
        ))
        assertFalse(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.FARMERS, null, 1, 8, listOf("Save")
        ))
        assertFalse(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.FARMERS, route, 0, 8, listOf("Save")
        ))
        assertFalse(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.FARMERS, route, 1, 0, listOf("Save")
        ))
    }

    @Test fun rejectsFormsWithoutCommitActionOrWithOversizedCounts() {
        assertFalse(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.FARMERS, route, 1, 8, listOf("Edit", "Cancel")
        ))
        assertFalse(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.FARMERS, route, 21, 8, listOf("Save")
        ))
        assertFalse(AkkpsEditFormVerificationPolicy.isVerified(
            AkkpsRunnerModule.FARMERS, route, 1, 101, listOf("Save")
        ))
    }
}
