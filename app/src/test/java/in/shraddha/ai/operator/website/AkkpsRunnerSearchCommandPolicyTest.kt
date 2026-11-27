package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerSearchCommandPolicyTest {
    @Test fun parsesExplicitEnglishSearchCommands() {
        assertEquals("Ram Kumar", AkkpsRunnerSearchCommandPolicy.parse("search for Ram Kumar"))
        assertEquals("Plot 123", AkkpsRunnerSearchCommandPolicy.parse("find Plot 123"))
    }

    @Test fun parsesHindiSearchCommands() {
        assertEquals("राम", AkkpsRunnerSearchCommandPolicy.parse("खोजो राम"))
        assertEquals("श्यामपुर", AkkpsRunnerSearchCommandPolicy.parse("खोजें श्यामपुर"))
        assertEquals("किसान १२३", AkkpsRunnerSearchCommandPolicy.parse("ढूँढो किसान १२३"))
    }

    @Test fun rejectsMissingOrAmbiguousSearchIntent() {
        assertNull(AkkpsRunnerSearchCommandPolicy.parse("Ram Kumar"))
        assertNull(AkkpsRunnerSearchCommandPolicy.parse("search"))
        assertNull(AkkpsRunnerSearchCommandPolicy.parse("search submit"))
        assertNull(AkkpsRunnerSearchCommandPolicy.parse("search payment"))
    }

    @Test fun enforcesCommandAndQueryBounds() {
        assertNull(AkkpsRunnerSearchCommandPolicy.parse("search " + "x".repeat(121)))
        assertNull(AkkpsRunnerSearchCommandPolicy.parse("search " + "x".repeat(301)))
        assertNull(AkkpsRunnerSearchCommandPolicy.parse("search ram\nsubmit"))
    }
    @Test fun preservesCaseSensitiveRecordIdentifiers() {
        assertEquals("PLOT-AB12", AkkpsRunnerSearchCommandPolicy.parse("Search for PLOT-AB12"))
        assertEquals("Farmer ID: FmR-008", AkkpsRunnerSearchCommandPolicy.parse("Find Farmer ID: FmR-008"))
    }

}
