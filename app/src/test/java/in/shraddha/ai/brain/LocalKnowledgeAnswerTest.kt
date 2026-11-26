package `in`.shraddha.ai.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalKnowledgeAnswerTest {
    private fun hit(excerpt: String, score: Double = 2.0) =
        LocalKnowledgeDocumentStore.SearchHit(
            documentId = "doc-1", title = "Solar system basics",
            sourceLabel = "Reviewed starter lesson", sourceUri = null,
            chunkIndex = 0, chunkCount = 1, excerpt = excerpt, score = score,
            citation = "[Solar system basics — Reviewed starter lesson, section 1/1]"
        )

    @Test fun returnsLocalAnswerWhenTwoMeaningfulTermsMatch() {
        val result = LocalKnowledgeAnswer.build("Explain the solar system",
            listOf(hit("The solar system contains the Sun and the objects that orbit it.")))
        assertEquals(1, result?.citations?.size)
        assert(result?.text?.contains("solar system") == true)
    }

    @Test fun rejectsWeakSingleWordOverlapForMultiTermQuestion() {
        assertNull(LocalKnowledgeAnswer.build("Explain solar system",
            listOf(hit("The Sun is a star.", score = 8.0))))
    }

    @Test fun rejectsLowScoringPassageEvenWhenTermsMatch() {
        assertNull(LocalKnowledgeAnswer.build("Explain solar system",
            listOf(hit("The solar system contains planets.", score = 0.5))))
    }

    @Test fun supportsDistinctiveSingleConceptQuestion() {
        assert(LocalKnowledgeAnswer.build("Photosynthesis",
            listOf(hit("Photosynthesis is how green plants use light to make sugars."))) != null)
    }

    @Test fun emptyOrStopwordOnlyQueriesDoNotAnswer() {
        assertNull(LocalKnowledgeAnswer.build("what is it", listOf(hit("What is it?"))))
        assertNull(LocalKnowledgeAnswer.build("", listOf(hit("anything"))))
    }
}