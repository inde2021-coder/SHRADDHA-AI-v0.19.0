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
        assertTrue("Baby-care lesson should be found locally", babyHits.any { it.documentId == "curriculum_baby_care_0_2_v1" })
        assertTrue("Gita lesson should be found locally", gitaHits.any { it.documentId == "curriculum_gita_shloka_mantra_katha_v1" })
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
