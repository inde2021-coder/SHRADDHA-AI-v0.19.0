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
    @Test fun rejectsValuesThatAreOnlySubstringsOfLongerWords() {
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "Farmer Name=Ramesh", listOf("Farmer Name" to "Ram")
        ))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "Village=Rampur", listOf("Village" to "pur")
        ))
    }

    @Test fun acceptsWholeLiteralTokensWithPunctuationBoundaries() {
        assertTrue(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "Address=Ward 4, Near School; Phone=9876543210",
            listOf("Address" to "Ward 4, Near School", "Phone" to "9876543210")
        ))
        assertTrue(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "किसान का नाम: राम कुमार।", listOf("नाम" to "राम कुमार")
        ))
    }

    @Test fun boundsAssignmentCountFieldNamesAndValues() {
        val tooMany = (1..101).map { "field$it" to "value$it" }
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            (1..101).joinToString(" ") { "value$it" }, tooMany
        ))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "value", listOf("x".repeat(161) to "value")
        ))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "value", listOf("field" to "x".repeat(501))
        ))
    }

    @Test fun boundsMissingFieldLabels() {
        assertTrue(AkkpsAiMappingPolicy.hasValidMissingItems(listOf("Farmer Name", "Village")))
        assertFalse(AkkpsAiMappingPolicy.hasValidMissingItems(listOf("x".repeat(121))))
        assertFalse(AkkpsAiMappingPolicy.hasValidMissingItems((1..31).map { "field$it" }))
        assertFalse(AkkpsAiMappingPolicy.hasValidMissingItems(listOf("")))
    }

}
