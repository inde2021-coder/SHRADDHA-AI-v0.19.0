package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsWebsiteProfileTest {
    @Test fun acceptsOnlySecureAkkpsOrigins() {
        assertTrue(AkkpsWebsiteProfile.acceptsUrl("https://www.akkps.ngo"))
        assertTrue(AkkpsWebsiteProfile.acceptsUrl("https://akkps.ngo/dashboard"))
        assertTrue(AkkpsWebsiteProfile.acceptsUrl("https://www.akkps.ngo/farmers?id=1"))
        assertFalse(AkkpsWebsiteProfile.acceptsUrl("http://www.akkps.ngo"))
        assertFalse(AkkpsWebsiteProfile.acceptsUrl("https://akkps.ngo.evil.example"))
        assertFalse(AkkpsWebsiteProfile.acceptsUrl("https://evil.example/akkps.ngo"))
        assertFalse(AkkpsWebsiteProfile.acceptsUrl("https://user:pass@www.akkps.ngo"))
        assertFalse(AkkpsWebsiteProfile.acceptsUrl("https://www.akkps.ngo:8443"))
        assertFalse(AkkpsWebsiteProfile.acceptsUrl(""))
    }

    @Test fun privateDashboardActionsRemainUnknownUntilDiscovered() {
        val capabilities = AkkpsWebsiteProfile.initialCapabilities().associateBy { it.operation }
        assertTrue(capabilities.getValue(WebsiteOperation.OBSERVE_PAGE).state == CapabilityState.AVAILABLE)
        assertTrue(capabilities.getValue(WebsiteOperation.SEARCH_RECORDS).state == CapabilityState.UNKNOWN)
        assertTrue(capabilities.getValue(WebsiteOperation.CREATE_RECORD).state == CapabilityState.UNKNOWN)
        assertTrue(capabilities.getValue(WebsiteOperation.EXPORT_REPORT).state == CapabilityState.UNAVAILABLE)
        assertTrue(capabilities.getValue(WebsiteOperation.DOWNLOAD_DOCUMENT).state == CapabilityState.UNAVAILABLE)
        assertTrue(capabilities.getValue(WebsiteOperation.DELETE_RECORD).state == CapabilityState.UNAVAILABLE)
        assertTrue(capabilities.getValue(WebsiteOperation.PAYMENT).state == CapabilityState.UNAVAILABLE)
    }

    @Test fun destructiveAndPaymentOperationsNeedHighImpactConfirmation() {
        assertTrue(AkkpsWebsiteProfile.requiresHighImpactConfirmation(WebsiteOperation.DELETE_RECORD))
        assertTrue(AkkpsWebsiteProfile.requiresHighImpactConfirmation(WebsiteOperation.PAYMENT))
        assertFalse(AkkpsWebsiteProfile.requiresHighImpactConfirmation(WebsiteOperation.VIEW_RECORD))
        assertFalse(AkkpsWebsiteProfile.requiresHighImpactConfirmation(WebsiteOperation.SEARCH_RECORDS))
    }

    @Test fun successRequiresVerifiedPostcondition() {
        assertFalse(AkkpsWebsiteProfile.canReportSuccess(WebsiteOperation.SAVE_RECORD, true, false))
        assertFalse(AkkpsWebsiteProfile.canReportSuccess(WebsiteOperation.SAVE_RECORD, false, true))
        assertTrue(AkkpsWebsiteProfile.canReportSuccess(WebsiteOperation.SAVE_RECORD, true, true))
        assertFalse(AkkpsWebsiteProfile.canReportSuccess(WebsiteOperation.NAVIGATE, true, true))
    }
    @Test fun runnerNavigationRejectsRestrictedAndTraversalPaths() {
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/admin/users"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/coordinator/plots"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/runner/../admin"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/runner/%2e%2e/admin"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/runner/%252e%252e/admin"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/runner%2f..%2fadmin"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/runner\\\\..\\\\admin"))
    }

    @Test fun runnerNavigationAllowsLoginAndOnlyApprovedRunnerModules() {
        assertTrue(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/"))
        assertTrue(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/login"))
        assertTrue(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/auth/otp"))
        assertTrue(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/auth/verify"))
        assertTrue(AkkpsWebsiteProfile.allowsRunnerNavigation("https://akkps.ngo/runner/plots?page=2"))
        assertTrue(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/runner/awd-reports"))
        assertTrue(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/plots/new"))
        assertTrue(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/awd-reports/new"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/administrator-help"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("https://www.akkps.ngo/dashboard"))
        assertFalse(AkkpsWebsiteProfile.allowsRunnerNavigation("http://www.akkps.ngo/runner"))
    }

}
