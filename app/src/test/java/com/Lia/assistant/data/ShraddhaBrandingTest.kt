package com.Lia.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShraddhaBrandingTest {
    @Test
    fun publicAssistantIdentityIsShraddhaAi() {
        assertEquals("Shraddha AI", AssistantBrand.NAME)
        assertEquals("Shraddha AI", AssistantBrand.FULL_NAME)
    }
}
