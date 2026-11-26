package `in`.shraddha.ai.learning

/**
 * Fail-closed gate for the future Skill Factory and automatic knowledge updates.
 *
 * This policy validates metadata and independent verification evidence only. It does not
 * fetch URLs, execute plugins, train model weights, or decide whether a claim is true.
 * Callers must obtain the evidence from trusted, deterministic validators.
 */
object SkillPackTrustPolicy {
    enum class Decision { AUTO_ACTIVATE, QUEUE_FOR_REVIEW, REJECT }
    enum class SourceKind { OFFICIAL, OPEN_SOURCE_MAINTAINER, USER_IMPORT, UNKNOWN }

    data class Candidate(
        val packId: String,
        val version: String,
        val sourceUrl: String,
        val sourceKind: SourceKind,
        val licenceId: String,
        val sha256: String,
        val signedManifestVerified: Boolean,
        val sourceAllowlistVerified: Boolean = false,
        val contentSafetyPassed: Boolean,
        val factualValidationPassed: Boolean,
        val regressionTestsPassed: Boolean,
        val duplicateCheckPassed: Boolean,
        val userImportConsent: Boolean = false
    )

    data class Result(val decision: Decision, val reasons: List<String>)

    fun evaluate(candidate: Candidate, automaticLearningEnabled: Boolean): Result {
        val invalid = buildList {
            if (!candidate.packId.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))) add("Invalid pack ID")
            if (!candidate.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) add("Version must use MAJOR.MINOR.PATCH")
            val sourceUri = runCatching { java.net.URI(candidate.sourceUrl) }.getOrNull()
            if (sourceUri == null || !sourceUri.scheme.equals("https", true) ||
                sourceUri.host.isNullOrBlank() || sourceUri.rawUserInfo != null ||
                sourceUri.port !in listOf(-1, 443) || candidate.sourceUrl.length > 2048
            ) add("Source must be a valid HTTPS URL without credentials or a non-standard port")
            if (candidate.licenceId.isBlank() || candidate.licenceId.length > 120) add("A recognised licence identifier is required")
            if (!candidate.sha256.matches(Regex("[a-fA-F0-9]{64}"))) add("A SHA-256 content digest is required")
        }
        if (invalid.isNotEmpty()) return Result(Decision.REJECT, invalid)

        val blockers = buildList {
            if (!automaticLearningEnabled) add("Automatic learning is disabled")
            if (candidate.sourceKind == SourceKind.UNKNOWN) add("Source is not allow-listed")
            if (candidate.sourceKind == SourceKind.USER_IMPORT && !candidate.userImportConsent) add("User import consent is missing")
            if (candidate.sourceKind != SourceKind.USER_IMPORT && !candidate.sourceAllowlistVerified) add("Source host was not verified against the trusted allow-list")
            if (candidate.sourceKind != SourceKind.USER_IMPORT && !candidate.signedManifestVerified) add("Trusted manifest signature was not verified")
            if (!candidate.contentSafetyPassed) add("Content safety validation has not passed")
            if (!candidate.factualValidationPassed) add("Independent factual validation has not passed")
            if (!candidate.regressionTestsPassed) add("Skill regression tests have not passed")
            if (!candidate.duplicateCheckPassed) add("Duplicate/collision check has not passed")
        }
        return if (blockers.isEmpty()) {
            Result(Decision.AUTO_ACTIVATE, listOf("Metadata and all independent activation gates passed"))
        } else {
            Result(Decision.QUEUE_FOR_REVIEW, blockers)
        }
    }
}
