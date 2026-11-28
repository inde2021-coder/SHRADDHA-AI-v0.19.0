package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

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

    @Test fun parsesCreatePrefixBeforeFieldAssignments() {
        val inline = AkkpsRunnerCommandParser.parse("create farmer Farmer Name=Ram Kumar; Village=Rampur")
        assertTrue(inline.issues.toString(), inline.issues.isEmpty())
        assertEquals(listOf("farmerName", "village"), inline.assignments.map { it.first })

        val separate = AkkpsRunnerCommandParser.parse("new farmer; Farmer Name=Ram Kumar; Village=Rampur")
        assertTrue(separate.issues.toString(), separate.issues.isEmpty())
        assertEquals(listOf("farmerName", "village"), separate.assignments.map { it.first })
    }

    @Test fun trailingSubmitAfterLastValueIsDetectedAndRemovedFromValue() {
        val parsed = AkkpsRunnerCommandParser.parse("create farmer Farmer Name=Ram Kumar; Village=Rampur submit")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals(listOf("Ram Kumar", "Rampur"), parsed.assignments.map { it.second })
        assertTrue(parsed.submit)
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
    @Test fun normalizesFullWidthEnglishAndUnicodeDuplicateAliases() {
        val fullWidth = AkkpsRunnerCommandParser.parse("Ｆａｒｍｅｒ Ｎａｍｅ=Ram; Ｖｉｌｌａｇｅ=Rampur")
        assertTrue(fullWidth.issues.toString(), fullWidth.issues.isEmpty())
        assertEquals(listOf("farmerName", "village"), fullWidth.assignments.map { it.first })

        val duplicate = AkkpsRunnerCommandParser.parse("Farmer Name=Ram; Ｆａｒｍｅｒ Ｎａｍｅ=Shyam")
        assertTrue(duplicate.assignments.isEmpty())
        assertTrue(duplicate.issues.isNotEmpty())
    }

    @Test fun aliasMatchingIsIndependentOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val parsed = AkkpsRunnerCommandParser.parse("PHONE=9876543210")
            assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
            assertEquals("phoneNumber", parsed.assignments.single().first)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun normalizesFullWidthEnglishAliasesBeforeMapping() {
        val parsed = AkkpsRunnerCommandParser.parse("Ｆａｒｍｅｒ Ｎａｍｅ=Ram; Ｓｕｒｖｅｙ Ｎｕｍｂｅｒ=123")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals(listOf("farmerName", "surveyNumber"), parsed.assignments.map { it.first })
    }

    @Test fun duplicateUnicodeCompatibilityAliasesCannotOverwrite() {
        val parsed = AkkpsRunnerCommandParser.parse("Farmer Name=Ram; Ｆａｒｍｅｒ Ｎａｍｅ=Shyam")
        assertTrue(parsed.assignments.isEmpty())
        assertTrue(parsed.issues.isNotEmpty())
    }

    @Test fun normalizesHindiCompatibilityWhitespaceForAliasLookup() {
        val parsed = AkkpsRunnerCommandParser.parse("खसरा   नंबर=123")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals("surveyNumber", parsed.assignments.single().first)
    }

    @Test fun mapsGenderAliasesToFarmerGenderForRadioGroupEntry() {
        val parsed = AkkpsRunnerCommandParser.parse("Gender=Female")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals("farmerGender", parsed.assignments.single().first)
        assertEquals("Female", parsed.assignments.single().second)
    }

}
