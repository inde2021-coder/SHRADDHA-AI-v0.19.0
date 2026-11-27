package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsRunnerCommandParserTest {
    @Test fun parsesAssignmentsAndSubmitInOneCommand() {
        val parsed = AkkpsRunnerCommandParser.parse("Farmer Name=Ram Kumar; Village=Rampur; Phone=9876543210; submit")
        assertEquals(listOf("Farmer Name", "Village", "Phone"), parsed.assignments.map { it.first })
        assertEquals(listOf("Ram Kumar", "Rampur", "9876543210"), parsed.assignments.map { it.second })
        assertTrue(parsed.submit)
    }

    @Test fun preservesCommasInsideValues() {
        val parsed = AkkpsRunnerCommandParser.parse("Address=Ward 4, Near School; Village=Rampur")
        assertEquals("Ward 4, Near School", parsed.assignments.first().second)
        assertFalse(parsed.submit)
    }

    @Test fun reportsDuplicateKeysInsteadOfSilentlyChoosingOneValue() {
        val parsed = AkkpsRunnerCommandParser.parse("Name=First; Name=Second; broken; =empty; submit")
        assertEquals(2, parsed.assignments.size)
        assertTrue(parsed.hasDuplicateKeys)
        assertTrue(parsed.submit)
    }

    @Test fun recognizesHindiSubmitCommands() {
        listOf("सबमिट", "जमा", "जमा करें", "भेजें", "भेज दें").forEach { token ->
            assertTrue(AkkpsRunnerCommandParser.parse("Name=Ram; $token").submit)
        }
    }

    @Test fun duplicateDetectionUsesUnicodeNormalizationAndIgnoresCase() {
        val parsed = AkkpsRunnerCommandParser.parse("Plot ID=12; plot id=13")
        assertTrue(parsed.hasDuplicateKeys)
    }

    @Test fun duplicateDetectionMatchesPunctuationNormalizedBrowserLabels() {
        val parsed = AkkpsRunnerCommandParser.parse("Plot-ID=12; plot id=13")
        assertTrue(parsed.hasDuplicateKeys)
    }
}
