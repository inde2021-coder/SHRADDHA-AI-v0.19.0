package `in`.shraddha.ai.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillPackMetadataRecoveryTest {
    @Test fun restoresLastMetadataWhenReplacementWasInterrupted() {
        val directory = Files.createTempDirectory("skill-pack-metadata").toFile()
        try {
            val target = File(directory, "pack.json")
            val backup = File(directory, "pack.json.bak").apply { writeText("previous-valid-metadata") }

            recoverSkillPackMetadataBackup(target, backup)

            assertTrue(target.isFile)
            assertEquals("previous-valid-metadata", target.readText())
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun removesBackupOnlyWhenCommittedTargetAlreadyExists() {
        val directory = Files.createTempDirectory("skill-pack-metadata").toFile()
        try {
            val target = File(directory, "pack.json").apply { writeText("current-valid-metadata") }
            val backup = File(directory, "pack.json.bak").apply { writeText("previous-valid-metadata") }

            recoverSkillPackMetadataBackup(target, backup)

            assertEquals("current-valid-metadata", target.readText())
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun missingBackupDoesNotChangeTarget() {
        val directory = Files.createTempDirectory("skill-pack-metadata").toFile()
        try {
            val target = File(directory, "pack.json").apply { writeText("current-valid-metadata") }
            recoverSkillPackMetadataBackup(target, File(directory, "pack.json.bak"))
            assertEquals("current-valid-metadata", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
