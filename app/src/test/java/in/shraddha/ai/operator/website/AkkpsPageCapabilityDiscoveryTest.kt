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
            navigationLabels = listOf("Farmers", "Plots")
        ).associateBy { it.operation }

        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.SEARCH_RECORDS).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.VIEW_RECORD).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.CREATE_RECORD).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.SAVE_RECORD).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.SUBMIT_FORM).state)
        assertEquals(CapabilityState.AVAILABLE, result.getValue(WebsiteOperation.UPLOAD_DOCUMENT).state)
        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.DELETE_RECORD).state)
        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.PAYMENT).state)
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
        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.DELETE_RECORD).state)
    }

    @Test fun createAndDetailRoutesAreExplicitEvidence() {
        val create = AkkpsPageCapabilityDiscovery.discover(
            0, 0, emptyList(), emptyList(), emptyList(), emptyList(), isCreateRoute = true
        )
        val detail = AkkpsPageCapabilityDiscovery.discover(
            0, 0, emptyList(), emptyList(), emptyList(), emptyList(), isDetailRoute = true
        )
        assertEquals(CapabilityState.AVAILABLE, create.first { it.operation == WebsiteOperation.CREATE_RECORD }.state)
        assertEquals(CapabilityState.AVAILABLE, detail.first { it.operation == WebsiteOperation.VIEW_RECORD }.state)
    }

    @Test fun deleteAndPaymentRemainHighImpactOperations() {
        assertTrue(AkkpsWebsiteProfile.requiresHighImpactConfirmation(WebsiteOperation.DELETE_RECORD))
        assertTrue(AkkpsWebsiteProfile.requiresHighImpactConfirmation(WebsiteOperation.PAYMENT))
    }
    @Test fun observedDeleteAndPaymentButtonsDoNotExpandRunnerScope() {
        val result = AkkpsPageCapabilityDiscovery.discover(
            formCount = 1,
            tableCount = 1,
            fieldTypes = listOf("text"),
            fieldLabels = listOf("Farmer Name"),
            actionLabels = listOf("Delete record", "Make payment", "Export report", "Download"),
            navigationLabels = listOf("Farmers")
        ).associateBy { it.operation }
        listOf(
            WebsiteOperation.DELETE_RECORD,
            WebsiteOperation.PAYMENT,
            WebsiteOperation.EXPORT_REPORT,
            WebsiteOperation.DOWNLOAD_DOCUMENT
        ).forEach { operation ->
            assertEquals(CapabilityState.UNAVAILABLE, result.getValue(operation).state)
        }
    }

    @Test fun doesNotInferSearchSaveOrSubmitFromNavigationLabelsOrButtonsWithoutForm() {
        val result = AkkpsPageCapabilityDiscovery.discover(
            formCount = 0,
            tableCount = 0,
            fieldTypes = emptyList(),
            fieldLabels = emptyList(),
            actionLabels = listOf("Save", "Submit"),
            navigationLabels = listOf("Search", "Find records")
        ).associateBy { it.operation }
        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.SEARCH_RECORDS).state)
        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.SAVE_RECORD).state)
        assertEquals(CapabilityState.UNKNOWN, result.getValue(WebsiteOperation.SUBMIT_FORM).state)
    }

}
