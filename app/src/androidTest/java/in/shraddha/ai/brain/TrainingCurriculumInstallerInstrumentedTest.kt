package `in`.shraddha.ai.brain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrainingCurriculumInstallerInstrumentedTest {
    @Test fun bundledCurriculumInstallsIntoEncryptedKnowledgeStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = TrainingCurriculumInstaller.install(context).getOrThrow()
        assertEquals(TrainingCurriculumInstaller.catalog.size, report.total)

        val documents = LocalKnowledgeDocumentStore(context).listDocuments().getOrThrow()
            .associateBy { it.id }
        TrainingCurriculumInstaller.catalog.forEach { pack ->
            val document = documents[pack.id]
            assertNotNull("Missing installed pack: ${pack.id}", document)
            assertTrue("Empty pack: ${pack.id}", document!!.content.isNotBlank())
            assertTrue("Pack integrity metadata missing: ${pack.id}", document.sourceLabel.contains("built-in curriculum"))
        }
    }

    @Test fun installedLessonsAreRetrievableOfflineByTopic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TrainingCurriculumInstaller.install(context).getOrThrow()
        val store = LocalKnowledgeDocumentStore(context)
        val babyHits = store.search("baby safe sleep firm flat surface", limit = 8).getOrThrow()
        val gitaHits = store.search("Bhagavad Gita shloka mantra Sanskrit", limit = 8).getOrThrow()
        val businessHits = store.search("cashbook receivables stock ledger business", limit = 8).getOrThrow()
        assertTrue("Baby-care lesson should be found locally", babyHits.any { it.documentId == "curriculum_baby_care_0_2_v1" })
        assertTrue("Gita corpus should be found locally", gitaHits.any { it.documentId.startsWith("curriculum_gita_chapter_") || it.documentId == "curriculum_gita_shloka_mantra_katha_v1" })
        assertTrue("Practical business lesson should be found locally", businessHits.any { it.documentId == "curriculum_advanced_practical_skills_v1" })
    }

    @Test fun allSevenHundredGitaVersesHaveSequentialCanonicalReferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = listOf(47, 72, 43, 42, 29, 47, 30, 28, 34, 42, 55, 20, 34, 27, 20, 24, 28, 78)
        var total = 0
        expected.forEachIndexed { index, count ->
            val chapter = index + 1
            val path = "training/gita/chapter-%02d-700-v1.json".format(chapter)
            val json = context.assets.open(path).bufferedReader().use { org.json.JSONObject(it.readText()) }
            assertEquals(chapter, json.getInt("chapter"))
            val verses = json.getJSONArray("verses")
            assertEquals("Chapter $chapter count", count, verses.length())
            for (i in 0 until verses.length()) {
                val verse = verses.getJSONObject(i)
                assertEquals("$chapter.${i + 1}", verse.getString("ref"))
                assertTrue(verse.getString("sanskrit").isNotBlank())
                assertTrue(verse.getString("transliteration").isNotBlank())
                assertTrue(verse.getString("wordGloss").isNotBlank())
            }
            total += verses.length()
        }
        assertEquals(700, total)
    }

    @Test fun installerIsIdempotentAfterFirstSuccessfulInstall() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TrainingCurriculumInstaller.install(context).getOrThrow()
        val second = TrainingCurriculumInstaller.install(context).getOrThrow()
        assertEquals(0, second.installed)
        assertEquals(0, second.updated)
        assertEquals(TrainingCurriculumInstaller.catalog.size, second.unchanged)
    }
}
