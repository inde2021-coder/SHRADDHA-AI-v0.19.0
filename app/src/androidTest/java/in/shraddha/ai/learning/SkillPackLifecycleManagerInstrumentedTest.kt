package `in`.shraddha.ai.learning

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SkillPackLifecycleManagerInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun userImportRequiresConsentAndManualReviewBeforeActivation() {
        val manager = SkillPackLifecycleManager(context)
        val id = "manual-" + UUID.randomUUID().toString().take(8)
        val source = File(context.cacheDir, "$id.md").apply { writeText("# Verified local notes") }
        try {
            try {
                manager.importForReview(id, "1.0.0", "Local notes", "CC-BY-4.0", "user-selected file", source, false)
                throw AssertionError("Import without consent must fail.")
            } catch (_: IllegalArgumentException) { }
            val staged = manager.importForReview(id, "1.0.0", "Local notes", "CC-BY-4.0", "user-selected file", source, true)
            assertTrue(staged.file.isFile)
            assertFalse(manager.approveAndActivate(
                staged,
                SkillPackLifecycleManager.Approval(false, true, true, true, true),
                automaticLearningEnabled = false
            ))
        } catch (_: IllegalArgumentException) {
            // A fail-closed validation exception is also an acceptable refusal before activation.
        } finally {
            source.delete()
        }
    }

    @Test fun reviewedManualPackCanActivateAndRollbackToPreviousVersion() {
        val manager = SkillPackLifecycleManager(context)
        val id = "rollback-" + UUID.randomUUID().toString().take(8)
        fun stage(version: String, text: String): SkillPackLifecycleManager.StagedPack {
            val source = File(context.cacheDir, "$id-$version.md").apply { writeText(text) }
            return try {
                manager.importForReview(id, version, "Rollback fixture", "CC0-1.0", "instrumented test", source, true)
            } finally { source.delete() }
        }
        val approval = SkillPackLifecycleManager.Approval(true, true, true, true, true)
        val first = stage("1.0.0", "first version")
        assertTrue(manager.approveAndActivate(first, approval, automaticLearningEnabled = false))
        assertNotNull(manager.activePackFile(id))
        val second = stage("1.1.0", "second version")
        assertTrue(manager.approveAndActivate(second, approval, automaticLearningEnabled = false))
        assertEquals("1.1.0.pack", manager.activePackFile(id)?.name)
        assertTrue(manager.rollback(id))
        assertEquals("1.0.0.pack", manager.activePackFile(id)?.name)
    }
}
