package `in`.shraddha.ai.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageModelCapabilityCatalogTest {
    @Test fun allFiveRequestedLanguagesHaveExplicitCapabilityRows() {
        assertEquals(listOf("hi", "en", "sa", "mr", "pav"), LanguageModelCapabilityCatalog.languages.map { it.code })
        assertTrue(LanguageModelCapabilityCatalog.languages.all { it.asrCandidate.isNotBlank() && it.ttsCandidate.isNotBlank() && it.translationCandidate.isNotBlank() })
    }

    @Test fun pawariIsNotPresentedAsAnAvailableModel() {
        val pawari = LanguageModelCapabilityCatalog.get("pav")
        assertNotNull(pawari)
        assertEquals(LanguageModelCapabilityCatalog.IntegrationState.COMMUNITY_DATA_REQUIRED, pawari!!.state)
        assertTrue(pawari.asrCandidate.contains("No verified"))
        assertTrue(pawari.ttsCandidate.contains("No verified"))
    }

    @Test fun candidateModelsAreNeverMisreportedAsInstalled() {
        assertTrue(LanguageModelCapabilityCatalog.languages.all { it.state != LanguageModelCapabilityCatalog.IntegrationState.NOT_INTEGRATED || it.note.isNotBlank() })
    }
}
