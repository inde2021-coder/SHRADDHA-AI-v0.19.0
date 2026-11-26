package `in`.shraddha.ai.learning

/** Tracks required human sign-off. Authored or source-checked content is not expert-approved. */
object CurriculumReviewRegistry {
    enum class Status { PENDING_SPECIALIST_REVIEW, PENDING_COMMUNITY_REVIEW, PENDING_EDUCATOR_REVIEW, SOURCE_STRUCTURE_CHECKED_REVIEW_PENDING }

    data class Requirement(
        val packPrefix: String,
        val requiredReviewers: List<String>,
        val status: Status,
        val scope: String
    ) {
        val productionApproved: Boolean get() = false
        val statusLabel: String get() = when (status) {
            Status.PENDING_SPECIALIST_REVIEW -> "विशेषज्ञ समीक्षा लंबित"
            Status.PENDING_COMMUNITY_REVIEW -> "स्थानीय समुदाय समीक्षा लंबित"
            Status.PENDING_EDUCATOR_REVIEW -> "शिक्षक समीक्षा लंबित"
            Status.SOURCE_STRUCTURE_CHECKED_REVIEW_PENDING -> "स्रोत/संरचना जाँची; विशेषज्ञ समीक्षा बाकी"
        }
    }

    private val requirements = listOf(
        Requirement("curriculum_gita_chapter_", listOf("Sanskrit scholar", "critical-edition text reviewer"), Status.SOURCE_STRUCTURE_CHECKED_REVIEW_PENDING, "700 canonical Sanskrit verses, transliteration, word glosses, edition variants and teaching explanations"),
        Requirement("curriculum_gita_shloka_mantra_katha_", listOf("Sanskrit scholar", "tradition-aware religious studies reviewer"), Status.PENDING_SPECIALIST_REVIEW, "Mantra source attribution, Sanskrit grammar, pronunciation and interpretive context"),
        Requirement("curriculum_baby_care_", listOf("Indian paediatrician", "child-health nurse"), Status.PENDING_SPECIALIST_REVIEW, "Infant safety, feeding, sleep, development and emergency referral language"),
        Requirement("curriculum_kids_foundation_", listOf("foundational-stage educator", "child safeguarding reviewer", "accessibility educator"), Status.PENDING_EDUCATOR_REVIEW, "Age appropriateness, answer keys, inclusion and child-safety controls"),
        Requirement("curriculum_abacus_", listOf("qualified abacus educator", "numeracy educator"), Status.PENDING_EDUCATOR_REVIEW, "Soroban configuration, bead technique, algorithms and level progression"),
        Requirement("curriculum_mind_activities_", listOf("educational psychologist or child-development specialist", "special educator"), Status.PENDING_EDUCATOR_REVIEW, "Age suitability, accessibility and removal of unsupported cognitive-benefit claims"),
        Requirement("curriculum_five_language_", listOf("Hindi, English, Marathi and Sanskrit language educators", "Pawari fluent speakers from each target locality"), Status.PENDING_COMMUNITY_REVIEW, "Grammar, regional variety, pronunciation, translation and transliteration"),
        Requirement("curriculum_core_life_skills_", listOf("domain educators for each included subject", "Hindi/English proofreader"), Status.PENDING_SPECIALIST_REVIEW, "Ten core domains, examples, safety boundaries and factual accuracy"),
        Requirement("curriculum_advanced_practical_skills_", listOf("agriculture extension specialist", "accountant/GST professional", "food-safety educator", "cybersecurity educator"), Status.PENDING_SPECIALIST_REVIEW, "Regulated and changing practical advice, calculations, local context and safety"),
        Requirement("curriculum_full_catalog_", listOf("curriculum owner", "content QA reviewer"), Status.PENDING_EDUCATOR_REVIEW, "Catalogue coverage, links, age labels and version consistency")
    )

    fun forPack(packId: String): Requirement? = requirements.firstOrNull { packId.startsWith(it.packPrefix) }
    fun allRequirements(): List<Requirement> = requirements.toList()
}
