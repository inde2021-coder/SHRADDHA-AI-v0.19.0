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
    @Test fun normalizesFullWidthSyntaxAndHindiAliases() {
        val parsed = AkkpsRunnerCommandParser.parse("खसरा नंबर＝１２３； किसान का नाम＝राम कुमार； ｓｕｂｍｉｔ")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals(listOf("surveyNumber", "farmerName"), parsed.assignments.map { it.first })
        assertEquals(listOf("123", "राम कुमार"), parsed.assignments.map { it.second })
        assertTrue(parsed.submit)
    }

    @Test fun ignoresZeroWidthCharactersInFieldNamesAndSubmitToken() {
        val parsed = AkkpsRunnerCommandParser.parse("Far\u200Bmer Name=Ram; sub\u200Bmit")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals("farmerName", parsed.assignments.single().first)
        assertTrue(parsed.submit)
    }

    @Test fun unicodeNormalizationDoesNotTurnMalformedDuplicateKeysIntoValidAssignments() {
        val parsed = AkkpsRunnerCommandParser.parse("Survey Number=123; ｓｕｒｖｅｙ ｎｕｍｂｅｒ=456")
        assertTrue(parsed.assignments.isEmpty())
        assertTrue(parsed.issues.isNotEmpty())
    }

    @Test fun capsAssignmentsAndNormalizedCommandExpansion() {
        val tooMany = (1..101).joinToString(";") { "field$it=value" }
        val parsed = AkkpsRunnerCommandParser.parse(tooMany)
        assertTrue(parsed.assignments.isEmpty())
        assertTrue(parsed.issues.any { it.contains("अधिकतम 100") })

        val expanded = "ﬃ".repeat(1_400)
        assertTrue(AkkpsRunnerCommandParser.parse(expanded).issues.any { it.contains("बहुत लंबी") })
    }

    @Test fun recognizesExplicitSaveAndSubmitPhrases() {
        listOf("save record", "submit now", "जमा कर दें").forEach { phrase ->
            val parsed = AkkpsRunnerCommandParser.parse("Farmer Name=Ram; $phrase")
            assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
            assertTrue(phrase, parsed.submit)
        }
    }

}
