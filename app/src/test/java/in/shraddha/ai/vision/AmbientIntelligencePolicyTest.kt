package `in`.shraddha.ai.vision

import org.junit.Assert.*
import org.junit.Test

class AmbientIntelligencePolicyTest {
    @Test fun cloudAnalysisIsRateLimited() {
        assertFalse(AmbientIntelligencePolicy.shouldAnalyzeCloudFrame(7_999L, 0L))
        assertTrue(AmbientIntelligencePolicy.shouldAnalyzeCloudFrame(8_000L, 0L))
        assertFalse(AmbientIntelligencePolicy.shouldAnalyzeCloudFrame(100L, 200L))
    }

    @Test fun announcementsRequireChangedSceneAndCooldown() {
        val first = "A person is sitting at a desk"
        assertTrue(AmbientIntelligencePolicy.shouldAnnounce(first, "", 45_000L, 0L))
        assertFalse(AmbientIntelligencePolicy.shouldAnnounce(first, first, 90_000L, 0L))
        assertFalse(AmbientIntelligencePolicy.shouldAnnounce("A new object is visible", first, 54_999L, 10_000L))
        assertTrue(AmbientIntelligencePolicy.shouldAnnounce("A new object is visible", first, 55_000L, 10_000L))
        assertTrue(AmbientIntelligencePolicy.shouldAnnounce("First scene", "", 1_000L, 0L))
    }

    @Test fun observationIsNormalizedAndBounded() {
        assertEquals("door open", AmbientIntelligencePolicy.cleanObservation("  door  \n open  "))
        assertEquals(1_200, AmbientIntelligencePolicy.cleanObservation("x".repeat(2_000)).length)
        assertFalse(AmbientIntelligencePolicy.shouldAnnounce("  ", "", 60_000L, 0L))
    }

    @Test fun scenePromptHasPrivacyAndUncertaintyGuardrails() {
        val prompt = AmbientIntelligencePolicy.buildScenePrompt("Shraddha AI", "Earlier: table")
        assertTrue(prompt.contains("Do not identify people"))
        assertTrue(prompt.contains("Never diagnose emotions"))
        assertTrue(prompt.contains("Do not invent sound"))
        assertTrue(prompt.contains("Earlier: table"))
    }
    @Test fun cloudUploadRequiresPermissionSeparateConsentAndSecureBackend() {
        assertFalse(AmbientIntelligencePolicy.mayAnalyzeCloudFrame(false, true, true, 8_000L, 0L))
        assertFalse(AmbientIntelligencePolicy.mayAnalyzeCloudFrame(true, false, true, 8_000L, 0L))
        assertFalse(AmbientIntelligencePolicy.mayAnalyzeCloudFrame(true, true, false, 8_000L, 0L))
        assertTrue(AmbientIntelligencePolicy.mayAnalyzeCloudFrame(true, true, true, 8_000L, 0L))
    }

    @Test fun revokingCloudConsentBlocksNextFrameWhileLocalAnalysisCanContinue() {
        assertTrue(AmbientIntelligencePolicy.shouldAnalyzeCloudFrame(8_000L, 0L))
        assertFalse(AmbientIntelligencePolicy.mayAnalyzeCloudFrame(true, false, true, 8_000L, 0L))
    }

}
