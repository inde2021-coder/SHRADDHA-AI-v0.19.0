package com.Lia.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class IndicConformerRuntimePolicyTest {
    @Test fun onlyModelsWithPinnedChecksumsCanDownload() {
        assertTrue(IndicConformerRuntime.canDownload(IndicConformerRuntime.Language.HINDI))
        assertTrue(IndicConformerRuntime.canDownload(IndicConformerRuntime.Language.SANSKRIT))
        assertFalse(IndicConformerRuntime.canDownload(IndicConformerRuntime.Language.MARATHI))
    }

    @Test fun restoresPreviousModelWhenProcessDiedAfterMovingItToBackup() {
        val directory = Files.createTempDirectory("shraddha-model-recovery").toFile()
        try {
            val destination = java.io.File(directory, "model.onnx")
            val backup = java.io.File(directory, "model.onnx.bak")
            backup.writeText("previous-verified-model")

            recoverModelBackup(destination, backup)

            assertTrue(destination.isFile)
            assertEquals("previous-verified-model", destination.readText())
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun removesStaleBackupOnlyWhenDestinationAlreadyExists() {
        val directory = Files.createTempDirectory("shraddha-model-recovery").toFile()
        try {
            val destination = java.io.File(directory, "model.onnx").apply { writeText("installed-model") }
            val backup = java.io.File(directory, "model.onnx.bak").apply { writeText("older-model") }

            recoverModelBackup(destination, backup)

            assertEquals("installed-model", destination.readText())
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
