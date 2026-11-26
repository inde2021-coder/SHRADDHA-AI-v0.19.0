package `in`.shraddha.ai.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurriculumReviewRegistryTest {
    @Test fun everyCorePackHasAnExplicitHumanReviewRequirement() {
        val ids = listOf(
            "curriculum_gita_chapter_18_v1",
            "curriculum_gita_shloka_mantra_katha_v1",
            "curriculum_baby_care_0_2_v1",
            "curriculum_kids_foundation_3_10_v1",
            "curriculum_abacus_eight_levels_v1",
            "curriculum_mind_activities_18_v1",
            "curriculum_five_language_learning_v1",
            "curriculum_core_life_skills_hi_en_v1",
            "curriculum_advanced_practical_skills_v1"
        )
        ids.forEach { id ->
            val requirement = CurriculumReviewRegistry.forPack(id)
            assertNotNull("Missing review requirement for $id", requirement)
            assertTrue(requirement!!.requiredReviewers.isNotEmpty())
            assertFalse("No pack may be marked approved without a human sign-off", requirement.productionApproved)
        }
    }

    @Test fun pawariRequiresLocalCommunityReview() {
        val requirement = CurriculumReviewRegistry.forPack("curriculum_five_language_learning_v1")!!
        assertEquals(CurriculumReviewRegistry.Status.PENDING_COMMUNITY_REVIEW, requirement.status)
    }
}
