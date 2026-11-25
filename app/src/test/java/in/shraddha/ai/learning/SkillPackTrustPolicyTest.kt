package `in`.shraddha.ai.learning

import org.junit.Assert.*
import org.junit.Test

class SkillPackTrustPolicyTest {
    private fun candidate() = SkillPackTrustPolicy.Candidate(
        packId = "india.general-knowledge",
        version = "1.2.0",
        sourceUrl = "https://example.org/packs/general-knowledge.json",
        sourceKind = SkillPackTrustPolicy.SourceKind.OFFICIAL,
        licenceId = "CC-BY-4.0",
        sha256 = "a".repeat(64),
        signedManifestVerified = true,
        contentSafetyPassed = true,
        factualValidationPassed = true,
        regressionTestsPassed = true,
        duplicateCheckPassed = true
    )

    @Test fun verifiedAllowListedPackCanAutoActivate() {
        assertEquals(
            SkillPackTrustPolicy.Decision.AUTO_ACTIVATE,
            SkillPackTrustPolicy.evaluate(candidate(), true).decision
        )
    }

    @Test fun paidOrAutomaticSettingDoesNotBypassVerificationGates() {
        val unverified = candidate().copy(factualValidationPassed = false)
        assertEquals(
            SkillPackTrustPolicy.Decision.QUEUE_FOR_REVIEW,
            SkillPackTrustPolicy.evaluate(unverified, true).decision
        )
    }

    @Test fun unknownSourceNeverAutoActivates() {
        val unknown = candidate().copy(sourceKind = SkillPackTrustPolicy.SourceKind.UNKNOWN)
        assertEquals(
            SkillPackTrustPolicy.Decision.QUEUE_FOR_REVIEW,
            SkillPackTrustPolicy.evaluate(unknown, true).decision
        )
    }

    @Test fun userImportRequiresExplicitConsent() {
        val imported = candidate().copy(sourceKind = SkillPackTrustPolicy.SourceKind.USER_IMPORT)
        assertEquals(
            SkillPackTrustPolicy.Decision.QUEUE_FOR_REVIEW,
            SkillPackTrustPolicy.evaluate(imported, true).decision
        )
        assertEquals(
            SkillPackTrustPolicy.Decision.AUTO_ACTIVATE,
            SkillPackTrustPolicy.evaluate(imported.copy(userImportConsent = true), true).decision
        )
    }

    @Test fun malformedMetadataIsRejected() {
        val malformed = candidate().copy(sha256 = "not-a-digest")
        assertEquals(
            SkillPackTrustPolicy.Decision.REJECT,
            SkillPackTrustPolicy.evaluate(malformed, true).decision
        )
    }

    @Test fun disabledAutomaticLearningQueuesForReview() {
        assertEquals(
            SkillPackTrustPolicy.Decision.QUEUE_FOR_REVIEW,
            SkillPackTrustPolicy.evaluate(candidate(), false).decision
        )
    }
}
