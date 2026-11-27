package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerCommandParserTest {
    @Test fun mapsHindiAndEnglishAliasesToCanonicalFieldNames() {
        val command = AkkpsRunnerCommandParser.parse("खसरा नंबर=123; Total Land Area=2.5; धान की किस्म=IR64")
        assertTrue(command.issues.isEmpty())
        assertEquals(listOf("surveyNumber", "totalLandAreaAcres", "riceVariety"), command.assignments.map { it.first })
    }

    @Test fun duplicateAliasesCannotSilentlyOverwriteSameField() {
        val command = AkkpsRunnerCommandParser.parse("खसरा नंबर=123; Survey Number=456")
        assertTrue(command.assignments.isEmpty())
        assertTrue(command.issues.isNotEmpty())
    }

    @Test fun parsesFarmerAssignmentsAndSubmitWithCanonicalKeys() {
        val parsed = AkkpsRunnerCommandParser.parse("Farmer Name=Ram Kumar; Village=Rampur; Phone=9876543210; submit")
        assertEquals(listOf("farmerName", "village", "phoneNumber"), parsed.assignments.map { it.first })
        assertEquals(listOf("Ram Kumar", "Rampur", "9876543210"), parsed.assignments.map { it.second })
        assertTrue(parsed.submit)
        assertTrue(parsed.issues.isEmpty())
    }

    @Test fun preservesCommasInsideValues() {
        val parsed = AkkpsRunnerCommandParser.parse("Address=Ward 4, Near School; Village=Rampur")
        assertEquals("Ward 4, Near School", parsed.assignments.first().second)
        assertFalse(parsed.submit)
        assertTrue(parsed.issues.isEmpty())
    }

    @Test fun malformedAndDuplicateKeysStopAllAssignments() {
        val parsed = AkkpsRunnerCommandParser.parse("Name=First; Name=Second; broken; =empty; submit")
        assertTrue(parsed.assignments.isEmpty())
        assertTrue(parsed.issues.isNotEmpty())
        assertTrue(parsed.submit)
    }

    @Test fun rejectsOversizedAndEmptyCommands() {
        assertTrue(AkkpsRunnerCommandParser.parse("").issues.isNotEmpty())
        assertTrue(AkkpsRunnerCommandParser.parse("x".repeat(4001)).issues.isNotEmpty())
    }
}
