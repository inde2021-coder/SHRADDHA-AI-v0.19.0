package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerEditCommandPolicyTest {
    @Test fun acceptsExplicitEnglishEditCommands() {
        assertTrue(AkkpsRunnerEditCommandPolicy.isExplicit("edit"))
        assertTrue(AkkpsRunnerEditCommandPolicy.isExplicit("edit record"))
        assertTrue(AkkpsRunnerEditCommandPolicy.isExplicit("update"))
    }

    @Test fun acceptsExplicitHindiEditCommands() {
        assertTrue(AkkpsRunnerEditCommandPolicy.isExplicit("संपादित करें"))
        assertTrue(AkkpsRunnerEditCommandPolicy.isExplicit("रिकॉर्ड बदलें"))
        assertTrue(AkkpsRunnerEditCommandPolicy.isExplicit("रिकॉर्ड संपादित करें"))
    }

    @Test fun rejectsCommandsThatCouldMeanDataEntryOrSubmission() {
        assertFalse(AkkpsRunnerEditCommandPolicy.isExplicit("edit farmer 123"))
        assertFalse(AkkpsRunnerEditCommandPolicy.isExplicit("edit and submit"))
        assertFalse(AkkpsRunnerEditCommandPolicy.isExplicit("delete record"))
        assertFalse(AkkpsRunnerEditCommandPolicy.isExplicit(" "))
    }

    @Test fun rejectsMultilineAndOversizedCommands() {
        assertFalse(AkkpsRunnerEditCommandPolicy.isExplicit("edit\nrecord"))
        assertFalse(AkkpsRunnerEditCommandPolicy.isExplicit("e".repeat(121)))
    }
}
