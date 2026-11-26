package `in`.shraddha.ai.brain

import android.content.Context
import java.security.MessageDigest

/**
 * Installs the authored, versioned lesson libraries bundled in the APK into the existing
 * encrypted LocalKnowledgeDocumentStore. No network request, paid API or model download occurs.
 */
object TrainingCurriculumInstaller {
    data class Pack(
        val id: String,
        val title: String,
        val version: String,
        val assetPath: String,
        val languages: Set<String>,
        val audience: String
    )

    data class InstallReport(
        val installed: Int,
        val updated: Int,
        val unchanged: Int,
        val total: Int
    )

    val catalog: List<Pack> = listOf(
        Pack("curriculum_core_life_skills_hi_en_v1", "Core Life Skills — 10 Domains", "1.0.0",
            "training/core-life-skills-hi-en-v1.md", setOf("Hindi", "English"), "General"),
        Pack("curriculum_gita_shloka_mantra_katha_v1", "Gita, Shloka, Mantra & Katha", "1.0.0",
            "training/gita-shloka-mantra-katha-hi-en-v1.md", setOf("Hindi", "English", "Sanskrit"), "All ages"),
        Pack("curriculum_baby_care_0_2_v1", "Baby Care — Ages 0–2", "1.0.0",
            "training/baby-care-hi-en-0-2.md", setOf("Hindi", "English"), "Parents and caregivers"),
        Pack("curriculum_kids_foundation_3_10_v1", "Kids Foundation — Ages 3–10", "1.0.0",
            "training/kids-foundation-hi-en-3-10.md", setOf("Hindi", "English"), "Children with guardian"),
        Pack("curriculum_abacus_eight_levels_v1", "Abacus — Eight Levels", "1.0.0",
            "training/abacus-foundation-8-levels.md", setOf("Hindi", "English"), "Learners"),
        Pack("curriculum_mind_activities_18_v1", "18 Mind Activities", "1.0.0",
            "training/mind-activities-18-hi-en.md", setOf("Hindi", "English"), "All ages"),
        Pack("curriculum_five_language_learning_v1", "Five-Language Learning", "1.0.0",
            "training/five-language-hi-en-sa-mr-pawari.md",
            setOf("Hindi", "English", "Sanskrit", "Marathi", "Pawari"), "All ages"),
        Pack("curriculum_full_catalog_v1", "Complete Training Curriculum Catalogue", "1.0.0",
            "training/full-curriculum-catalog-v1.md",
            setOf("Hindi", "English", "Sanskrit", "Marathi", "Pawari"), "All ages")
    )

    /**
     * Safe to call at chat startup. A process-local guard avoids repeated disk work.
     * Every fresh process verifies the bundled content digest and repairs missing/outdated packs.
     */
    @Synchronized
    fun install(context: Context): Result<InstallReport> = runCatching {
        if (checkedThisProcess) return@runCatching InstallReport(0, 0, catalog.size, catalog.size)
        val app = context.applicationContext
        val store = LocalKnowledgeDocumentStore(app)
        val existing = store.listDocuments().getOrThrow().associateBy { it.id }
        var installed = 0
        var updated = 0
        var unchanged = 0

        catalog.forEach { pack ->
            require(pack.id.matches(Regex("curriculum_[a-z0-9_]{3,60}")))
            require(pack.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            val content = app.assets.open(pack.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(content.isNotBlank() && content.length <= MAX_PACK_CHARS) {
                "Curriculum pack ${pack.id} is empty or exceeds the safe size limit."
            }
            require(content.startsWith("# Shraddha AI") || content.startsWith("# ")) {
                "Curriculum pack ${pack.id} has an unexpected document format."
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val sourceLabel = "Shraddha AI built-in curriculum v${pack.version}"
            val old = existing[pack.id]
            if (old != null && old.sourceLabel == sourceLabel && old.content == content) {
                unchanged++
            } else {
                store.importBundledCurriculum(
                    id = pack.id,
                    title = pack.title,
                    version = pack.version,
                    sha256 = digest,
                    content = content
                ).getOrThrow()
                if (old == null) installed++ else updated++
            }
        }

        checkedThisProcess = true
        InstallReport(installed, updated, unchanged, catalog.size)
    }

    @Volatile private var checkedThisProcess = false

    private const val MAX_PACK_CHARS = 50_000
}
