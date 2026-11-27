package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerNavigationCommandPolicyTest {
    @Test fun resolvesExplicitEnglishNavigationCommands() {
        assertEquals(AkkpsRunnerModule.FARMERS, AkkpsRunnerNavigationCommandPolicy.resolve("open farmers"))
        assertEquals(AkkpsRunnerModule.PLOTS, AkkpsRunnerNavigationCommandPolicy.resolve("go to plots"))
        assertEquals(AkkpsRunnerModule.AWD_REPORTS, AkkpsRunnerNavigationCommandPolicy.resolve("show AWD reports"))
    }

    @Test fun resolvesExplicitHindiNavigationCommands() {
        assertEquals(AkkpsRunnerModule.FARMERS, AkkpsRunnerNavigationCommandPolicy.resolve("किसान सूची खोलो"))
        assertEquals(AkkpsRunnerModule.PLOTS, AkkpsRunnerNavigationCommandPolicy.resolve("खेत दिखाओ"))
        assertEquals(AkkpsRunnerModule.HOME, AkkpsRunnerNavigationCommandPolicy.resolve("डैशबोर्ड खोलें"))
    }

    @Test fun requiresExplicitNavigationVerb() {
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("farmers"))
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("plots list"))
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("किसान"))
    }

    @Test fun ambiguousOrUnknownNavigationIsRejected() {
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("open farmer reports"))
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("open admin"))
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("open plots and farmers"))
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("open " + "x".repeat(400)))
    }

    @Test fun routesStayInsideKnownRunnerModulePaths() {
        assertEquals("/runner/farmers", AkkpsRunnerNavigationCommandPolicy.route(AkkpsRunnerModule.FARMERS))
        assertEquals("/runner/plots", AkkpsRunnerNavigationCommandPolicy.route(AkkpsRunnerModule.PLOTS))
        assertEquals("/runner/awd-reports", AkkpsRunnerNavigationCommandPolicy.route(AkkpsRunnerModule.AWD_REPORTS))
    }
    @Test fun doesNotExposeProfileOrSyncThroughDataEntryCommandNavigation() {
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("open profile"))
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("open sync"))
        assertNull(AkkpsRunnerNavigationCommandPolicy.resolve("डेटा सिंक खोलो"))
    }

}
