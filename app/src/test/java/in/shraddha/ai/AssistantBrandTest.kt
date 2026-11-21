package com.Lia.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantBrandTest {
    @Test
    fun appIdentityIsShraddhaAiOnly() {
        assertEquals("Shraddha AI", AssistantBrand.NAME)
        assertEquals("Shraddha AI", AssistantBrand.FULL_NAME)
    }
}
