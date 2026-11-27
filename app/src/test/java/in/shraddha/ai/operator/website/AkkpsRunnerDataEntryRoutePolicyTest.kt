package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerDataEntryRoutePolicyTest {
    @Test fun allowsKnownCreateAndExplicitEditRoutes() {
        assertTrue(AkkpsRunnerDataEntryRoutePolicy.isAllowed(
            AkkpsRunnerRouteMatch(AkkpsRunnerModule.FARMERS, "/runner/farmers/new", isCreateRoute = true), false
        ))
        assertTrue(AkkpsRunnerDataEntryRoutePolicy.isAllowed(
            AkkpsRunnerRouteMatch(AkkpsRunnerModule.PLOTS, "/runner/plots/plot-1/edit", isDetailRoute = true, isEditRoute = true), false
        ))
    }

    @Test fun detailPageRequiresVerifiedEditSession() {
        val detail = AkkpsRunnerRouteMatch(AkkpsRunnerModule.FARMERS, "/runner/farmers/farmer-1", isDetailRoute = true)
        assertFalse(AkkpsRunnerDataEntryRoutePolicy.isAllowed(detail, false))
        assertTrue(AkkpsRunnerDataEntryRoutePolicy.isAllowed(detail, true))
    }

    @Test fun blocksListProfileSyncAndUnknownRoutes() {
        assertFalse(AkkpsRunnerDataEntryRoutePolicy.isAllowed(
            AkkpsRunnerRouteMatch(AkkpsRunnerModule.FARMERS, "/runner/farmers"), true
        ))
        assertFalse(AkkpsRunnerDataEntryRoutePolicy.isAllowed(
            AkkpsRunnerRouteMatch(AkkpsRunnerModule.PROFILE, "/runner/profile", isDetailRoute = true), true
        ))
        assertFalse(AkkpsRunnerDataEntryRoutePolicy.isAllowed(
            AkkpsRunnerRouteMatch(AkkpsRunnerModule.SYNC, "/runner/sync", isDetailRoute = true), true
        ))
        assertFalse(AkkpsRunnerDataEntryRoutePolicy.isAllowed(null, true))
    }
}
