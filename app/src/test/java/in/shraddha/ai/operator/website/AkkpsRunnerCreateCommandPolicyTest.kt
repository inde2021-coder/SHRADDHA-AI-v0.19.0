package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerCreateCommandPolicyTest {
    @Test fun resolvesEnglishCreateCommands() {
        assertEquals(AkkpsRunnerModule.FARMERS, AkkpsRunnerCreateCommandPolicy.resolve("create farmer"))
        assertEquals(AkkpsRunnerModule.PLOTS, AkkpsRunnerCreateCommandPolicy.resolve("new plot"))
        assertEquals(AkkpsRunnerModule.AWD_REPORTS, AkkpsRunnerCreateCommandPolicy.resolve("add AWD report"))
    }

    @Test fun resolvesHindiCreateCommands() {
        assertEquals(AkkpsRunnerModule.FARMERS, AkkpsRunnerCreateCommandPolicy.resolve("नया किसान"))
        assertEquals(AkkpsRunnerModule.PLOTS, AkkpsRunnerCreateCommandPolicy.resolve("खेत बनाएं"))
        assertEquals(AkkpsRunnerModule.AWD_REPORTS, AkkpsRunnerCreateCommandPolicy.resolve("रिपोर्ट जोड़ें"))
    }

    @Test fun rejectsAmbiguousOrHighImpactCreateCommands() {
        assertNull(AkkpsRunnerCreateCommandPolicy.resolve("create farmer and plot"))
        assertNull(AkkpsRunnerCreateCommandPolicy.resolve("create payment record"))
        assertEquals(AkkpsRunnerModule.FARMERS, AkkpsRunnerCreateCommandPolicy.resolve("create farmer submit"))
        assertEquals(AkkpsRunnerModule.PLOTS, AkkpsRunnerCreateCommandPolicy.resolve("new plot save"))
        assertNull(AkkpsRunnerCreateCommandPolicy.resolve("farmer"))
    }

    @Test fun acceptsLongerExplicitDataEntryCommandsWithinRunnerLimit() {
        val command = "create farmer " + "name=Ram; village=Rampur; ".repeat(20)
        assertEquals(AkkpsRunnerModule.FARMERS, AkkpsRunnerCreateCommandPolicy.resolve(command))
        assertNull(AkkpsRunnerCreateCommandPolicy.resolve("create farmer " + "x".repeat(3001)))
    }

    @Test fun onlyKnownRunnerCreateRoutesAreReturned() {
        assertEquals("/runner/farmers/new", AkkpsRunnerCreateCommandPolicy.route(AkkpsRunnerModule.FARMERS))
        assertEquals("/runner/plots/new", AkkpsRunnerCreateCommandPolicy.route(AkkpsRunnerModule.PLOTS))
        assertEquals("/awd-reports/new", AkkpsRunnerCreateCommandPolicy.route(AkkpsRunnerModule.AWD_REPORTS))
        assertNull(AkkpsRunnerCreateCommandPolicy.route(AkkpsRunnerModule.PROFILE))
    }
}
