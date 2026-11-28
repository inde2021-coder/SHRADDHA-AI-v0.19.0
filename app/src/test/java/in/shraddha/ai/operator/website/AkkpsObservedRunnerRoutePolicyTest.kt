package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsObservedRunnerRoutePolicyTest {
    private val current = "https://www.akkps.ngo/runner/farmers"

    @Test fun acceptsOnlyObservedFirstLevelRunnerLinks() {
        val route = AkkpsObservedRunnerRoutePolicy.validate("AWD Reports", "/runner/awd-reports", current)
        assertEquals("/runner/awd-reports", route?.path)
        assertNull(AkkpsObservedRunnerRoutePolicy.validate("Farmer record", "/runner/farmers/123", current))
        assertNull(AkkpsObservedRunnerRoutePolicy.validate("Admin", "/runner/admin", current))
    }

    @Test fun rejectsExternalOriginsAndUnsafeUrlParts() {
        assertNull(AkkpsObservedRunnerRoutePolicy.validate("External", "https://evil.example/runner/farmers", current))
        assertNull(AkkpsObservedRunnerRoutePolicy.validate("Traversal", "/runner/../admin", current))
        assertNull(AkkpsObservedRunnerRoutePolicy.validate("Encoded", "/runner/%2e%2e", current))
        assertNull(AkkpsObservedRunnerRoutePolicy.validate("Query", "/runner/farmers?next=/admin", current))
    }

    @Test fun resolvesOnlyUniqueObservedLabels() {
        val routes = listOf(
            AkkpsObservedRunnerRoute("Farmers", "/runner/farmers"),
            AkkpsObservedRunnerRoute("AWD Reports", "/runner/awd-reports")
        )
        assertEquals("/runner/awd-reports", AkkpsObservedRunnerRoutePolicy.resolveCommand("open AWD Reports", routes)?.path)
        assertNull(AkkpsObservedRunnerRoutePolicy.resolveCommand("open profile", routes))
        assertNull(AkkpsObservedRunnerRoutePolicy.resolveCommand("open reports", routes + routes.last().copy(path="/runner/reports")))
    }
}
