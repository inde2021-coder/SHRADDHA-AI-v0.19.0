package `in`.shraddha.ai.learning

/** Canonical 20-domain registry; a catalogue entry is not a claim that a pack is installed or verified. */
object SkillDomainCatalog {
    enum class ContentState { EXISTING_SOURCE_REVIEW_REQUIRED, CATALOG_ONLY }

    data class Domain(
        val id: String,
        val title: String,
        val state: ContentState,
        val sourceDocument: String? = null
    )

    val domains = listOf(
        Domain("conversation", "Human behaviour and communication", ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, "docs/curriculum/CORE-10-DOMAINS-PRACTICAL-LESSONS.md"),
        Domain("general-learning", "School learning, mathematics and science", ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, "docs/curriculum/CORE-10-DOMAINS-PRACTICAL-LESSONS.md"),
        Domain("india-civic", "India, culture, civic literacy and public services", ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, "docs/curriculum/CORE-10-DOMAINS-PRACTICAL-LESSONS.md"),
        Domain("personal-assistant", "Planning, goals, tasks and reminders", ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, "docs/curriculum/CORE-10-DOMAINS-PRACTICAL-LESSONS.md"),
        Domain("reasoning", "Reasoning, decisions and problem solving", ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, "docs/curriculum/CORE-10-DOMAINS-PRACTICAL-LESSONS.md"),
        Domain("technology", "Android, Windows, networking and technical literacy", ContentState.CATALOG_ONLY),
        Domain("languages", "Hindi, English, Hinglish and Indian languages", ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, "docs/curriculum/FIVE-LANGUAGE-CAPABILITY-AND-LESSON-SPEC.md"),
        Domain("productivity", "Documents, OCR, spreadsheets, reports and PDFs", ContentState.CATALOG_ONLY),
        Domain("safety", "Privacy, cybersecurity, scams and emergency awareness", ContentState.CATALOG_ONLY),
        Domain("business-agriculture", "Business, accounting, agriculture and retail", ContentState.CATALOG_ONLY),
        Domain("parenting", "Parenting and infant-care education", ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, "docs/curriculum/BABY-CARE-0-2-LESSON-LIBRARY.md"),
        Domain("child-learning", "Child learning ages 3–10", ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, "docs/curriculum/KIDS-FOUNDATION-3-10-LESSON-LIBRARY.md"),
        Domain("food", "Food, cooking and nutrition education", ContentState.CATALOG_ONLY),
        Domain("health-literacy", "Health literacy and first-aid awareness", ContentState.CATALOG_ONLY),
        Domain("finance", "Financial literacy and digital-payment safety", ContentState.CATALOG_ONLY),
        Domain("repair", "Technical repair and household equipment", ContentState.CATALOG_ONLY),
        Domain("creativity", "Writing, design and presentation", ContentState.CATALOG_ONLY),
        Domain("travel", "Travel, time and everyday planning", ContentState.CATALOG_ONLY),
        Domain("accessibility", "Accessibility and assistive workflows", ContentState.CATALOG_ONLY),
        Domain("environment", "Environment, climate and sustainability", ContentState.CATALOG_ONLY)
    )

    fun find(id: String): Domain? = domains.firstOrNull { it.id == id }
}
