package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerViewCommandPolicyTest {
    @Test fun parsesExplicitEnglishViewCommands() {
        assertEquals("Farmer 123", AkkpsRunnerViewCommandPolicy.parse("view Farmer 123"))
        assertEquals("PLOT-45", AkkpsRunnerViewCommandPolicy.parse("open record PLOT-45"))
        assertEquals("Report 7", AkkpsRunnerViewCommandPolicy.parse("show record Report 7"))
    }

    @Test fun parsesHindiViewCommands() {
        assertEquals("किसान १२३", AkkpsRunnerViewCommandPolicy.parse("रिकॉर्ड खोलो किसान १२३"))
        assertEquals("राम", AkkpsRunnerViewCommandPolicy.parse("देखो राम"))
        assertEquals("खसरा ४५", AkkpsRunnerViewCommandPolicy.parse("खोलें खसरा ४५"))
    }

    @Test fun rejectsEmptyAmbiguousAndHighImpactCommands() {
        assertNull(AkkpsRunnerViewCommandPolicy.parse("view"))
        assertNull(AkkpsRunnerViewCommandPolicy.parse("open farmers"))
        assertNull(AkkpsRunnerViewCommandPolicy.parse("view payment record"))
        assertNull(AkkpsRunnerViewCommandPolicy.parse("view record submit"))
    }

    @Test fun enforcesLengthAndSingleLineBounds() {
        assertNull(AkkpsRunnerViewCommandPolicy.parse("view " + "x".repeat(101)))
        assertNull(AkkpsRunnerViewCommandPolicy.parse("view " + "x".repeat(301)))
        assertNull(AkkpsRunnerViewCommandPolicy.parse("view farmer\n123"))
    }
    @Test fun acceptsColonSeparatedRecordViewCommands() {
        assertEquals("FmR-008", AkkpsRunnerViewCommandPolicy.parse("open record: FmR-008"))
        assertEquals("Plot-AB12", AkkpsRunnerViewCommandPolicy.parse("view: Plot-AB12"))
    }

}
