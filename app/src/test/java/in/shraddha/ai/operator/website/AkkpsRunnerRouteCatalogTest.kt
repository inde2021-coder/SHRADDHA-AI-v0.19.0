package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsRunnerRouteCatalogTest {
    @Test fun exposesOnlyRunnerModulesObservedInApk() {
        assertEquals(
            listOf("HOME", "FARMERS", "PLOTS", "AWD_REPORTS", "PROFILE", "SYNC"),
            AkkpsRunnerRouteCatalog.modules().map { it.name }
        )
    }

    @Test fun matchesRunnerModuleRoutes() {
        assertEquals(AkkpsRunnerModule.HOME, AkkpsRunnerRouteCatalog.match("/runner")?.module)
        assertEquals(AkkpsRunnerModule.FARMERS, AkkpsRunnerRouteCatalog.match("/runner/farmers")?.module)
        assertEquals(AkkpsRunnerModule.PLOTS, AkkpsRunnerRouteCatalog.match("/runner/plots")?.module)
        assertEquals(AkkpsRunnerModule.AWD_REPORTS, AkkpsRunnerRouteCatalog.match("/runner/awd-reports")?.module)
        assertEquals(AkkpsRunnerModule.PROFILE, AkkpsRunnerRouteCatalog.match("/runner/profile")?.module)
        assertEquals(AkkpsRunnerModule.SYNC, AkkpsRunnerRouteCatalog.match("/runner/sync")?.module)
    }

    @Test fun identifiesCreateAndDetailRoutesWithoutAssumingMutationSucceeded() {
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/farmers/new")?.isCreateRoute == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/plots/new")?.let { it.module == AkkpsRunnerModule.PLOTS && it.isCreateRoute } == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/plots/create-boundary")?.let { it.module == AkkpsRunnerModule.PLOTS && it.isCreateRoute } == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/plots/create-land-map")?.let { it.module == AkkpsRunnerModule.PLOTS && it.isCreateRoute } == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/plots/plot-123/create-land-map")?.let { it.module == AkkpsRunnerModule.PLOTS && it.isDetailRoute } == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/awd-reports/new")?.let { it.module == AkkpsRunnerModule.AWD_REPORTS && it.isCreateRoute && !it.isDetailRoute } == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/plots/new")?.isCreateRoute == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/awd-reports/new")?.isCreateRoute == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/farmers/farmer-123")?.isDetailRoute == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/plots/plot-123")?.let { it.module == AkkpsRunnerModule.PLOTS && it.isDetailRoute } == true)
        assertTrue(AkkpsRunnerRouteCatalog.match("/runner/awd-reports/report-123")?.isDetailRoute == true)
    }

    @Test fun rejectsAdminCoordinatorAndLookalikePaths() {
        assertNull(AkkpsRunnerRouteCatalog.match("/admin/farmers"))
        assertNull(AkkpsRunnerRouteCatalog.match("/coordinator/farmers"))
        assertNull(AkkpsRunnerRouteCatalog.match("/runner-admin/farmers"))
        assertNull(AkkpsRunnerRouteCatalog.match("/runner/farmers/../admin"))
        assertNull(AkkpsRunnerRouteCatalog.match("/runner/unknown"))
    }

    @Test fun acceptsRunnerUrlButNotOtherOrigins() {
        assertEquals(
            AkkpsRunnerModule.FARMERS,
            AkkpsRunnerRouteCatalog.match("https://www.akkps.ngo/runner/farmers?status=active")?.module
        )
        assertNull(AkkpsRunnerRouteCatalog.match("https://evil.example/runner/farmers"))
    }
    @Test fun acceptsCanonicalRunnerPathsWithQueryAndTrailingSlash() {
        assertEquals(AkkpsRunnerModule.PLOTS, AkkpsRunnerRouteCatalog.match("/runner/plots/?page=2")?.module)
        assertEquals(AkkpsRunnerModule.FARMERS, AkkpsRunnerRouteCatalog.match("https://akkps.ngo/runner/farmers/new?from=dashboard")?.module)
    }

    @Test fun rejectsEncodedSeparatorsTraversalAndBackslash() {
        listOf(
            "/runner/plots%2fnew",
            "/runner/plots/%2e%2e/farmers",
            "/runner/plots/%252e%252e",
            "/runner/plots%252fnew",
            "/runner/plots\\\\new",
            "/runner//plots",
            "/runner/plots/../farmers"
        ).forEach { assertNull(it, AkkpsRunnerRouteCatalog.match(it)) }
    }

    @Test fun rejectsUntrustedOriginsAndNonHttpsAbsoluteUrls() {
        listOf(
            "http://akkps.ngo/runner/plots",
            "https://akkps.ngo.evil.example/runner/plots",
            "https://user@akkps.ngo/runner/plots",
            "https://evil.example/runner/plots"
        ).forEach { assertNull(it, AkkpsRunnerRouteCatalog.match(it)) }
    }

    @Test fun rejectsRelativeAndOversizedRoutes() {
        assertNull(AkkpsRunnerRouteCatalog.match("runner/plots"))
        assertNull(AkkpsRunnerRouteCatalog.match("/" + "x".repeat(2048)))
    }

}
