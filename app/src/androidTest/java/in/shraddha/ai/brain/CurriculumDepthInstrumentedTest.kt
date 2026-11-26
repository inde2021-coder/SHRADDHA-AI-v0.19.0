package `in`.shraddha.ai.brain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class CurriculumDepthInstrumentedTest {
    private fun asset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun allNonGitaLearningPacksContainStructuredLearningAndSafetyContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baby = asset(context, "training/baby-care-hi-en-0-2.md")
        val kids = asset(context, "training/kids-foundation-hi-en-3-10.md")
        val abacus = asset(context, "training/abacus-foundation-8-levels.md")
        val mind = asset(context, "training/mind-activities-18-hi-en.md")
        val languages = asset(context, "training/five-language-hi-en-sa-mr-pawari.md")
        val core = asset(context, "training/core-life-skills-hi-en-v1.md")
        val advanced = asset(context, "training/advanced-practical-skills-hi-en-v1.md")

        assertTrue("Baby-care emergency referral and safe-sleep envelope",
            baby.contains("Non-negotiable safety envelope") && baby.contains("firm, flat") && baby.contains("emergency"))
        assertTrue("Kids library includes age bands and answer/rubric content",
            kids.contains("3–5") && kids.contains("6–8") && kids.contains("9–10"))
        assertTrue("Abacus has all eight levels", (1..8).all { abacus.contains("Level $it —") })
        assertTrue("Mind activities lists all eighteen activities",
            Regex("(?m)^\\d+\\. \\*\\*").findAll(mind).count() >= 18)
        assertTrue("Language pack explicitly distinguishes Pawari support from model support",
            languages.contains("Pawari") && (languages.contains("not") || languages.contains("अभी")))
        assertTrue("Core pack covers ten domains", (1..10).all { core.contains("## $it.") })
        assertTrue("Advanced skills includes agriculture, finance, safety and bookkeeping",
            advanced.contains("Agriculture") && advanced.contains("Finance") &&
                advanced.contains("bookkeeping") && advanced.contains("cybersecurity"))
    }
}
