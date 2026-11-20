package in.shraddha.ai

import com.Lia.assistant.data.AssistantBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantBrandTest {
    @Test
    fun appIdentityIsShraddhaAiOnly() {
        assertEquals("Shraddha AI", AssistantBrand.NAME)
        assertEquals("Shraddha AI", AssistantBrand.FULL_NAME)
    }
}
