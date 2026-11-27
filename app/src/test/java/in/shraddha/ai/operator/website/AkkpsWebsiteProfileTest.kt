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
        assertTrue(capabilities.getValue(WebsiteOperation.EXPORT_REPORT).state == CapabilityState.UNKNOWN)
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
    @Test fun topLevelRunnerNavigationBlocksExternalAndPrivilegedRedirects() {
        assertTrue(AkkpsWebsiteProfile.acceptsRunnerNavigation("about:blank"))
        assertTrue(AkkpsWebsiteProfile.acceptsRunnerNavigation("https://www.akkps.ngo/runner/farmers"))
        assertFalse(AkkpsWebsiteProfile.acceptsRunnerNavigation("https://evil.example/runner/farmers"))
        assertFalse(AkkpsWebsiteProfile.acceptsRunnerNavigation("https://www.akkps.ngo/admin/farmers"))
        assertFalse(AkkpsWebsiteProfile.acceptsRunnerNavigation("https://www.akkps.ngo/coordinator/plots"))
        assertFalse(AkkpsWebsiteProfile.acceptsRunnerNavigation("file:///sdcard/secret.html"))
        assertFalse(AkkpsWebsiteProfile.acceptsRunnerNavigation("intent://evil"))
    }

}
