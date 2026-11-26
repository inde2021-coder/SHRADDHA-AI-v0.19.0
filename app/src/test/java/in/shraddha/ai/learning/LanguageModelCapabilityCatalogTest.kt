package `in`.shraddha.ai.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageModelCapabilityCatalogTest {
    @Test fun catalogCoversAllFiveRequestedLanguageGroups() {
        assertEquals(setOf("hi", "en", "sa", "mr", "pav"), LanguageModelCapabilityCatalog.languages.map { it.code }.toSet())
    }
    @Test fun hindiAndSanskritAsrAreDownloadableButNotClaimedInstalled() {
        listOf("hi", "sa").forEach { code ->
            val item = requireNotNull(LanguageModelCapabilityCatalog.get(code))
            assertEquals(LanguageModelCapabilityCatalog.State.READY_TO_DOWNLOAD, item.asrState)
            assertEquals(LanguageModelCapabilityCatalog.State.CANDIDATE_NOT_INTEGRATED, item.ttsState)
            assertEquals(LanguageModelCapabilityCatalog.State.CANDIDATE_NOT_INTEGRATED, item.translationState)
        }
    }
    @Test fun marathiRemainsBlockedUntilModelChecksumIsPinned() {
        assertEquals(LanguageModelCapabilityCatalog.State.CHECKSUM_BLOCKED, requireNotNull(LanguageModelCapabilityCatalog.get("mr")).asrState)
    }
    @Test fun pawariNeverClaimsSupportWithoutCommunityDatasetAndReview() {
        val item = requireNotNull(LanguageModelCapabilityCatalog.get("pav"))
        assertEquals(LanguageModelCapabilityCatalog.State.COMMUNITY_DATA_REQUIRED, item.asrState)
        assertEquals(LanguageModelCapabilityCatalog.State.COMMUNITY_DATA_REQUIRED, item.ttsState)
        assertEquals(LanguageModelCapabilityCatalog.State.COMMUNITY_DATA_REQUIRED, item.translationState)
    }
    @Test fun lookupIsCaseInsensitiveAndUnknownCodesStayUnknown() {
        assertNotNull(LanguageModelCapabilityCatalog.get("HI"))
        assertNull(LanguageModelCapabilityCatalog.get("xx"))
    }
}
