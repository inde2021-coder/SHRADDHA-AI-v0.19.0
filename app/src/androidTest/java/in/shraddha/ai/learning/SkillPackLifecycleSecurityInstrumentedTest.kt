package `in`.shraddha.ai.learning

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SkillPackLifecycleSecurityInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun unpinnedPublisherIsRejectedBeforeAnyNetworkRequest() {
        val manager = SkillPackLifecycleManager(context)
        val manifest = SkillPackLifecycleManager.Manifest(
            packId = "signed-pack",
            version = "1.0.0",
            title = "Signed pack",
            contentUrl = "https://packs.example.org/content.md",
            sourceUrl = "https://packs.example.org/source",
            licenceId = "CC-BY-4.0",
            sha256 = "a".repeat(64),
            contentType = "text/markdown",
            sizeBytes = 100,
            publisherKeyId = "official-key",
            signatureBase64 = "A".repeat(100)
        )
        var rejected = false
        try {
            manager.downloadAndQuarantine(manifest, emptyMap())
        } catch (_: SecurityException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun unsignedUserImportCannotUseAutomaticActivation() {
        val manager = SkillPackLifecycleManager(context)
        val id = "unsigned-" + UUID.randomUUID().toString().take(8)
        val source = File(context.cacheDir, "$id.md").apply { writeText("Unverified user notes") }
        try {
            val staged = manager.importForReview(id, "1.0.0", "Unsigned notes", "CC0-1.0", "user selected", source, true)
            var rejected = false
            try {
                manager.approveAndActivate(
                    staged,
                    SkillPackLifecycleManager.Approval(true, true, true, true, true),
                    automaticLearningEnabled = true
                )
            } catch (_: SecurityException) {
                rejected = true
            }
            assertTrue(rejected)
            assertNull(manager.activePackFile(id))
        } finally {
            source.delete()
        }
    }
    @Test fun modifiedQuarantinedBytesCannotBeActivated() {
        val manager = SkillPackLifecycleManager(context)
        val id = "tampered-" + UUID.randomUUID().toString().take(8)
        val source = File(context.cacheDir, "$id.md").apply { writeText("# reviewed content") }
        try {
            val staged = manager.importForReview(id, "1.0.0", "Tamper fixture", "CC0-1.0", "user selected", source, true)
            staged.file.writeText("# changed after review")
            var rejected = false
            try {
                manager.approveAndActivate(
                    staged,
                    SkillPackLifecycleManager.Approval(true, true, true, true, true),
                    automaticLearningEnabled = false
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertNull(manager.activePackFile(id))
        } finally {
            source.delete()
        }
    }

}
