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
    @Test fun rejectsValueThatAppearsOnlyAsSubstringOfAnotherWordOrNumber() {
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "किसान का नाम=रामेश्वर", listOf("किसान का नाम" to "राम")
        ))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "मोबाइल नंबर=9876543210", listOf("मोबाइल नंबर" to "987")
        ))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "कुल भूमि क्षेत्र=12.50", listOf("कुल भूमि क्षेत्र" to "2.5")
        ))
    }

    @Test fun acceptsWholeValueTokensBesideSeparatorsAndHindiPunctuation() {
        assertTrue(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "नाम: राम, गाँव=बरखेड़ा; मोबाइल=9876543210",
            listOf("नाम" to "राम", "गाँव" to "बरखेड़ा", "मोबाइल" to "9876543210")
        ))
    }

    @Test fun removesZeroWidthCharactersButKeepsTokenBoundaries() {
        assertTrue(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "नाम=रा\u200Bम", listOf("नाम" to "राम")
        ))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "नाम=रामेश", listOf("नाम" to "राम")
        ))
    }

    @Test fun rejectsMoreThanOneHundredAiAssignments() {
        val values = (1..101).map { "field$it" to "value$it" }
        val command = values.joinToString(";") { "${it.first}=${it.second}" }
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(command, values))
    }

}
