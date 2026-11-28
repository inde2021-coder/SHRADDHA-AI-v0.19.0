package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsFarmerCardOcrParserTest {
    @Test fun extractsOnlyExplicitHindiAndEnglishLabelValuePairs() {
        val result = AkkpsFarmerCardOcrParser.parse(
            "Farmer Name: Ram Kumar\nFarmer ID: MP-12345\nVillage: Rampur\nDistrict: Seoni\nAadhaar: 123456789012"
        )
        assertEquals("Ram Kumar", result.assignments["farmerName"])
        assertEquals("MP-12345", result.assignments["farmerId"])
        assertEquals("Rampur", result.assignments["village"])
        assertEquals("Seoni", result.assignments["district"])
        assertFalse(result.assignments.values.any { it.contains("123456789012") })
        assertTrue(result.missing.isEmpty())
    }

    @Test fun reportsMissingFieldsAndNeverInventsValues() {
        val result = AkkpsFarmerCardOcrParser.parse("नाम: राम कुमार\nग्राम: पिपरिया")
        assertEquals(setOf("farmerName", "village"), result.assignments.keys)
        assertEquals(listOf("farmerId", "district"), result.missing)
        assertFalse(result.toRunnerCommand().contains("submit"))
    }

    @Test fun ignoresUnlabelledAndSensitiveValues() {
        val result = AkkpsFarmerCardOcrParser.parse("9876543210\nAadhaar: 123456789012\nFarmer ID: 1234 5678 9012\nPassword: secret")
        assertTrue(result.assignments.isEmpty())
        assertEquals(listOf("farmerName", "farmerId", "village", "district"), result.missing)
    }
    @Test fun commandSeparatorsInsideOcrValuesCannotInjectRunnerOperations() {
        val result = AkkpsFarmerCardOcrParser.parse("Farmer Name: Ram; submit=now\nVillage: Rampur")
        val command = result.toRunnerCommand()
        assertFalse(command.contains("; submit"))
        assertFalse(command.contains("submit="))
        assertFalse(command.contains("=now"))
        assertTrue(command.contains("name=Ram submit now"))
    }

}
