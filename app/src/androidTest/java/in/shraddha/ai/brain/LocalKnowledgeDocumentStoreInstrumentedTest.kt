package `in`.shraddha.ai.brain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKnowledgeDocumentStoreInstrumentedTest {
    @Test fun approvedDocumentPersistsWithSourceCitationsAndEncryptedAtRest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalKnowledgeDocumentStore(context)
        val prefs = context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
        val id = "knowledge_test_" + System.currentTimeMillis()
        val title = "Private calibration manual $id"
        val source = "User-selected local PDF import"
        val body = "Before operation, verify telescope calibration and align the optical axis. " +
            "Record the measured deviation and repeat calibration after transport."
        try {
            assertTrue(store.importText(true, id, title, source, content = body).isSuccess)
            val reopened = LocalKnowledgeDocumentStore(context)
            val hit = reopened.search("telescope calibration", limit = 5).getOrThrow()
                .first { it.documentId == id }
            assertTrue(hit.citation.contains(title))
            assertTrue(hit.citation.contains(source))
            assertTrue(hit.excerpt.contains("calibration", ignoreCase = true))
            assertEquals(1, reopened.listDocuments().getOrThrow().count { it.id == id })

            val ciphertext = prefs.getString("knowledge_doc_$id", null) ?: error("Encrypted document record missing")
            assertFalse(ciphertext.contains(title))
            assertFalse(ciphertext.contains(body))
            assertTrue(reopened.delete(id).getOrThrow())
            assertFalse(reopened.listDocuments().getOrThrow().any { it.id == id })
        } finally {
            store.delete(id)
        }
    }

    @Test fun importRequiresExplicitConsentAndSafeSourceUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalKnowledgeDocumentStore(context)
        val id = "knowledge_policy_" + System.currentTimeMillis()
        assertTrue(store.importText(false, id, "Title", "User text", content = "approved content").isFailure)
        assertTrue(store.importText(true, id, "Title", "User text", "http://example.invalid/file",
            "approved content").isFailure)
    }

    @Test fun exactPhraseAndTitleRelevanceRankAboveScatteredTermMatches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalKnowledgeDocumentStore(context)
        val suffix = System.currentTimeMillis()
        val exactId = "knowledge_exact_$suffix"
        val broadId = "knowledge_broad_$suffix"
        try {
            assertTrue(store.importText(true, exactId, "Telescope calibration guide", "Approved manual",
                content = "Telescope calibration must be completed before every observation.").isSuccess)
            assertTrue(store.importText(true, broadId, "General observatory notes", "Approved notes",
                content = "The telescope is stored in the dome. Later, the team reviews calibration records.").isSuccess)
            val hits = store.search("telescope calibration", limit = 10).getOrThrow()
            assertTrue(hits.isNotEmpty())
            assertEquals(exactId, hits.first().documentId)
            assertTrue(hits.first().score > hits.firstOrNull { it.documentId == broadId }!!.score)
            assertTrue(hits.first().citation.contains("Telescope calibration guide"))
        } finally {
            store.delete(exactId)
            store.delete(broadId)
        }
    }
}
