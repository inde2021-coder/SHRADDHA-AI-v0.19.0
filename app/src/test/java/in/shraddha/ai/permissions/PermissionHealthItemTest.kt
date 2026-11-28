package `in`.shraddha.ai.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionHealthItemTest {
    @Test fun disabledPermissionIsNotReportedAsEnabled() {
        val item = PermissionHealthItem("camera", "कैमरा", false, "बंद")
        assertFalse(item.enabled)
        assertEquals("camera", item.id)
    }
    @Test fun enabledPermissionRetainsItsFeatureIdentity() {
        val item = PermissionHealthItem("microphone", "माइक्रोफोन", true, "चालू")
        assertTrue(item.enabled)
        assertEquals("माइक्रोफोन", item.title)
    }
    @Test fun detailsAreAvailableForActionableUi() {
        val item = PermissionHealthItem("accessibility", "App control", false, "Accessibility settings खोलें")
        assertTrue(item.detail.contains("settings"))
    }
}
