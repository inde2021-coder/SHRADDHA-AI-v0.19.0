package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsAiMappingPolicyTest {
    @Test fun acceptsOnlyLiteralUserProvidedValues() {
        assertTrue(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "नाम=राम, गाँव=बरखेड़ा", listOf("नाम" to "राम", "गाँव" to "बरखेड़ा")
        ))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "नाम=राम", listOf("नाम" to "राम", "गाँव" to "बरखेड़ा")
        ))
    }

    @Test fun unicodeCompatibilityAndWhitespaceAreNormalized() {
        assertTrue(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "नाम = ＲＡＭ", listOf("नाम" to "ram")
        ))
    }

    @Test fun rejectsEmptyAssignmentsAndEmptyValues() {
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand("नाम=राम", emptyList()))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand("नाम=", listOf("नाम" to " ")))
    }
}
