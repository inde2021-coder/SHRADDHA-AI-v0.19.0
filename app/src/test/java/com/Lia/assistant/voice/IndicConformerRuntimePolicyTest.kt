package com.Lia.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicConformerRuntimePolicyTest {
    @Test fun onlyModelsWithPinnedChecksumsCanDownload() {
        assertTrue(IndicConformerRuntime.canDownload(IndicConformerRuntime.Language.HINDI))
        assertTrue(IndicConformerRuntime.canDownload(IndicConformerRuntime.Language.SANSKRIT))
        assertFalse(IndicConformerRuntime.canDownload(IndicConformerRuntime.Language.MARATHI))
    }
}
