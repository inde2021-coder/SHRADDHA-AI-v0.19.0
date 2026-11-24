package `in`.shraddha.ai.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualEngineCapabilityTest {
    @Test fun installedArCoreEnablesOptionalMapping() {
        val capability = VisualEngineCapability.fromArCoreStatus("SUPPORTED_INSTALLED")
        assertEquals(VisualEngineCapability.AR_READY, capability)
        assertTrue(capability.arMappingAvailable)
    }

    @Test fun supportedButMissingArCoreCanOfferOptionalInstall() {
        assertTrue(VisualEngineCapability.fromArCoreStatus("SUPPORTED_NOT_INSTALLED").arMappingAvailable)
        assertTrue(VisualEngineCapability.fromArCoreStatus("SUPPORTED_APK_TOO_OLD").arMappingAvailable)
    }

    @Test fun unsupportedDeviceKeepsCoreVisualLensIndependent() {
        val capability = VisualEngineCapability.fromArCoreStatus("UNSUPPORTED_DEVICE_NOT_CAPABLE")
        assertEquals(VisualEngineCapability.AR_UNSUPPORTED, capability)
        assertFalse(capability.arMappingAvailable)
        assertTrue(capability.userMessage.contains("Visual Lens"))
        assertTrue(capability.userMessage.contains("ARCore के बिना"))
    }

    @Test fun unknownStatusFailsClosedForArButDoesNotDisableVisualLens() {
        val capability = VisualEngineCapability.fromArCoreStatus("UNKNOWN_CHECKING")
        assertEquals(VisualEngineCapability.AR_STATUS_UNKNOWN, capability)
        assertFalse(capability.arMappingAvailable)
        assertTrue(capability.userMessage.contains("Visual Lens"))
    }
}
