package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsAiMappingPolicyTest {
    @Test fun acceptsValuesLiterallyPresentInCommand() {
        assertTrue(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "Farmer name Ram Kumar; village Rampur; phone 9876543210",
            listOf("Farmer Name" to "Ram Kumar", "Village" to "Rampur", "Phone" to "9876543210")
        ))
    }

    @Test fun rejectsInventedOrModifiedFarmerValues() {
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand(
            "Farmer name Ram Kumar; village Rampur",
            listOf("Farmer Name" to "Ram Kumar", "Village" to "Jabalpur")
        ))
    }

    @Test fun rejectsEmptyAssignmentsAndBlankValues() {
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand("name Ram", emptyList()))
        assertFalse(AkkpsAiMappingPolicy.areValuesExplicitInCommand("name Ram", listOf("Name" to " ")))
    }
}
