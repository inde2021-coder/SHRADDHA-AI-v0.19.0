package `in`.shraddha.ai.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingCurriculumInstallerTest {
    @Test fun curriculumCatalogHasUniqueIdsAndValidOfflineAssets() {
        val catalog = TrainingCurriculumInstaller.catalog
        assertEquals(27, catalog.size)
        assertEquals(catalog.size, catalog.map { it.id }.distinct().size)
        assertTrue(catalog.all { it.id.startsWith("curriculum_") })
        assertTrue(catalog.all { it.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) })
        assertTrue(catalog.all { it.assetPath.startsWith("training/") && (it.assetPath.endsWith(".md") || it.assetPath.endsWith(".json")) })
        assertEquals(18, catalog.count { it.id.startsWith("curriculum_gita_chapter_") })
    }

    @Test fun onlyManifestIdsAreRecognisedAsBundledTraining() {
        assertTrue(TrainingCurriculumInstaller.isBundledPackId("curriculum_core_life_skills_hi_en_v1"))
        assertTrue(!TrainingCurriculumInstaller.isBundledPackId("curriculum_user_private_document"))
        assertTrue(!TrainingCurriculumInstaller.isBundledPackId("knowledge_private_document"))
    }

    @Test fun catalogIncludesEveryRequestedLearningArea() {
        val ids = TrainingCurriculumInstaller.catalog.map { it.id }.toSet()
        assertTrue("Core ten domains", "curriculum_core_life_skills_hi_en_v1" in ids)
        assertTrue("Gita and mantras", "curriculum_gita_shloka_mantra_katha_v1" in ids)
        assertTrue("Baby care", "curriculum_baby_care_0_2_v1" in ids)
        assertTrue("Kids learning", "curriculum_kids_foundation_3_10_v1" in ids)
        assertTrue("Eight-level abacus", "curriculum_abacus_eight_levels_v1" in ids)
        assertTrue("Eighteen mind activities", "curriculum_mind_activities_18_v1" in ids)
        assertTrue("Five languages", "curriculum_five_language_learning_v1" in ids)
        assertTrue("Full training catalogue", "curriculum_full_catalog_v1" in ids)
        assertTrue("Advanced practical skills", "curriculum_advanced_practical_skills_v1" in ids)
    }
}
