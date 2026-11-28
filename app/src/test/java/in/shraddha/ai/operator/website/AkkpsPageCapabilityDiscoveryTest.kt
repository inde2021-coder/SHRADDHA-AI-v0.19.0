package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsPageCapabilityDiscoveryTest {
    @Test fun reportsOnlyCapabilitiesSupportedByVisibleEvidence() {
        val result = AkkpsPageCapabilityDiscovery.discover(
            formCount = 1,
            tableCount = 1,
            fieldTypes = listOf("text", "file"),
            fieldLabels = listOf("Farmer Name", "Search"),
            actionLabels = listOf("Create New", "Save", "Submit", "Upload Document"),
            navigationLabels = listOf("Farmers", "Plots"),
            supportedDataEntryModule = true
        ).associateBy { it.operation }

        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.SEARCH_RECORDS).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.VIEW_RECORD).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.CREATE_RECORD).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.SAVE_RECORD).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.SUBMIT_FORM).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.UPLOAD_DOCUMENT).state)
        assertEquals(CapabilityState.UNAVAILABLE, result.getValue(WebsiteOperation.DELETE_RECORD).state)
        assertEquals(CapabilityState.UNAVAILABLE, result.getValue(WebsiteOperation.PAYMENT).state)
    }

    @Test fun missingControlsRemainUnknownRatherThanUnavailable() {
        val result = AkkpsPageCapabilityDiscovery.discover(
            formCount = 0,
            tableCount = 0,
            fieldTypes = emptyList(),
            fieldLabels = emptyList(),
            actionLabels = emptyList(),
            navigationLabels = emptyList()
        ).associateBy { it.operation }

        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.CREATE_RECORD).state)
        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.UPDATE_RECORD).state)
        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.SUBMIT_FORM).state)
        assertEquals(CapabilityState.UNAVAILABLE, result.getValue(WebsiteOperation.DELETE_RECORD).state)
    }

    @Test fun createAndDetailRoutesAreExplicitEvidence() {
        val create = AkkpsPageCapabilityDiscovery.discover(
            0, 0, emptyList(), emptyList(), emptyList(), emptyList(),
            isCreateRoute = true, supportedDataEntryModule = true
        )
        val detail = AkkpsPageCapabilityDiscovery.discover(
            0, 0, emptyList(), emptyList(), emptyList(), emptyList(),
            isDetailRoute = true, supportedDataEntryModule = true
        )
        assertEquals(CapabilityState.AVAILABLE, create.first { it.operation == WebsiteOperation.CREATE_RECORD }.state)
        assertEquals(CapabilityState.AVAILABLE, detail.first { it.operation == WebsiteOperation.VIEW_RECORD }.state)
    }

    @Test fun deleteAndPaymentRemainHighImpactOperations() {
        assertTrue(AkkpsWebsiteProfile.requiresHighImpactConfirmation(WebsiteOperation.DELETE_RECORD))
        assertTrue(AkkpsWebsiteProfile.requiresHighImpactConfirmation(WebsiteOperation.PAYMENT))
    }
    @Test fun runnerScopeNeverAdvertisesDownloadsExportsDeletionOrPayments() {
        val result = AkkpsPageCapabilityDiscovery.discover(
            formCount = 1,
            tableCount = 1,
            fieldTypes = listOf("file"),
            fieldLabels = listOf("Search"),
            actionLabels = listOf("Download", "Export report", "Delete record", "Make payment"),
            navigationLabels = listOf("Farmers")
        ).associateBy { it.operation }

        listOf(
            WebsiteOperation.DOWNLOAD_DOCUMENT,
            WebsiteOperation.EXPORT_REPORT,
            WebsiteOperation.DELETE_RECORD,
            WebsiteOperation.PAYMENT
        ).forEach { operation ->
            assertEquals(CapabilityState.UNAVAILABLE, result.getValue(operation).state)
            assertTrue(result.getValue(operation).evidence.contains("data-entry scope"))
        }
    }

    @Test fun runnerScopeAllowsFarmerPlotAwdDataEntryAndSupportingLookup() {
        listOf(
            WebsiteOperation.SEARCH_RECORDS,
            WebsiteOperation.VIEW_RECORD,
            WebsiteOperation.CREATE_RECORD,
            WebsiteOperation.UPDATE_RECORD,
            WebsiteOperation.SAVE_RECORD,
            WebsiteOperation.SUBMIT_FORM,
            WebsiteOperation.UPLOAD_DOCUMENT
        ).forEach { operation ->
            assertTrue("$operation should be in Runner scope", AkkpsWebsiteProfile.isRunnerOperationAllowed(operation))
        }
    }

    @Test fun fieldOrNavigationLabelAloneDoesNotProveCreateAction() {
        val result = AkkpsPageCapabilityDiscovery.discover(
            formCount = 1,
            tableCount = 0,
            fieldTypes = listOf("text"),
            fieldLabels = listOf("Create New Farmer"),
            actionLabels = emptyList(),
            navigationLabels = listOf("Add New Plot")
        )
        assertEquals(
            CapabilityState.UNKNOWN,
            result.first { it.operation == WebsiteOperation.CREATE_RECORD }.state
        )
    }

    @Test fun explicitCreateActionLabelProvesCreateCapability() {
        val result = AkkpsPageCapabilityDiscovery.discover(
            formCount = 1,
            tableCount = 0,
            fieldTypes = emptyList(),
            fieldLabels = emptyList(),
            actionLabels = listOf("Create New Farmer"),
            navigationLabels = emptyList(),
            supportedDataEntryModule = true
        )
        assertEquals(
            CapabilityState.AVAILABLE,
            result.first { it.operation == WebsiteOperation.CREATE_RECORD }.state
        )
    }

}
