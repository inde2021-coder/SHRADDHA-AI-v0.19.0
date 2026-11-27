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

    @Test fun missingRequiredInformationAlwaysBlocksAiSubmit() {
        assertTrue(AkkpsAiMappingPolicy.canSubmit(true, 0))
        assertFalse(AkkpsAiMappingPolicy.canSubmit(true, 1))
        assertFalse(AkkpsAiMappingPolicy.canSubmit(true, 3))
        assertFalse(AkkpsAiMappingPolicy.canSubmit(false, 0))
    }

    @Test fun rejectsEmptyAssignmentsAndEmptyValues() {
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand("नाम=राम", emptyList()))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand("नाम=", listOf("नाम" to " ")))
    }
}
