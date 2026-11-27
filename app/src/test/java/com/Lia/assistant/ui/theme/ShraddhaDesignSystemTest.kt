package com.Lia.assistant.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.Lia.assistant.data.AssistantBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShraddhaDesignSystemTest {
    @Test fun canonicalBrandIsShraddhaAi() {
        assertEquals("Shraddha AI", AssistantBrand.NAME)
        assertEquals("Shraddha AI", AssistantBrand.FULL_NAME)
    }

    @Test fun defaultDarkPaletteUsesPremiumDarkBackgroundAndDistinctAccents() {
        assertEquals(0xFF090D16.toInt(), DarkNovaColors.background.toArgb())
        assertEquals(0xFFB69CFF.toInt(), DarkNovaColors.accent.toArgb())
        assertNotEquals(DarkNovaColors.accent, DarkNovaColors.accentSecondary)
    }
}
