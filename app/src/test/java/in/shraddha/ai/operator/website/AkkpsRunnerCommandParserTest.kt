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

    @Test fun acceptsStandaloneHindiSubmitCommands() {
        listOf("सबमिट", "जमा करें", "जमा", "भेजो", "भेज दें").forEach { token ->
            val parsed = AkkpsRunnerCommandParser.parse("किसान का नाम=राम; $token")
            assertTrue(token, parsed.submit)
            assertTrue(AkkpsRunnerCommandParser.hasExplicitSubmitIntent("किसान का नाम=राम; $token"))
        }
    }

    @Test fun submitIntentRequiresAStandaloneExplicitToken() {
        assertTrue(AkkpsRunnerCommandParser.hasExplicitSubmitIntent("name=Ram; submit"))
        assertTrue(AkkpsRunnerCommandParser.hasExplicitSubmitIntent("कृपया रिकॉर्ड जमा करें"))
        assertFalse(AkkpsRunnerCommandParser.hasExplicitSubmitIntent("please resubmit later"))
        assertFalse(AkkpsRunnerCommandParser.hasExplicitSubmitIntent("name=Submit"))
        assertFalse(AkkpsRunnerCommandParser.hasExplicitSubmitIntent("remarks=जमा करें बाद में"))
    }

    @Test fun parsesNewlineSeparatedVoiceAssignments() {
        val parsed = AkkpsRunnerCommandParser.parse("Farmer Name=Ram Kumar\nVillage=Rampur\nsubmit")
        assertEquals(listOf("Farmer Name", "Village"), parsed.assignments.map { it.first })
        assertEquals(listOf("Ram Kumar", "Rampur"), parsed.assignments.map { it.second })
        assertTrue(parsed.submit)
    }

    @Test fun preservesCommasInsideValues() {
        val parsed = AkkpsRunnerCommandParser.parse("Address=Ward 4, Near School; Village=Rampur")
        assertEquals("Ward 4, Near School", parsed.assignments.first().second)
        assertFalse(parsed.submit)
    }

    @Test fun ignoresMalformedAndDuplicateKeysWithoutGuessing() {
        val parsed = AkkpsRunnerCommandParser.parse("Name=First; Name=Second; broken; =empty; submit")
        assertEquals(1, parsed.assignments.size)
        assertEquals("First", parsed.assignments.first().second)
        assertTrue(parsed.submit)
    }
}
