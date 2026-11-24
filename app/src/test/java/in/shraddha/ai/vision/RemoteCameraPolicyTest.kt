package `in`.shraddha.ai.vision

import org.junit.Assert.*
import org.junit.Test

class RemoteCameraPolicyTest {
    @Test fun streamRequiresRuntimePermissionAndExplicitConsent() {
        val target = "paired_device_123"
        assertTrue(RemoteCameraPolicy.mayStream(true, true, target, "front"))
        assertFalse(RemoteCameraPolicy.mayStream(false, true, target, "front"))
        assertFalse(RemoteCameraPolicy.mayStream(true, false, target, "front"))
    }

    @Test fun invalidTargetsAndFacingFailClosed() {
        assertFalse(RemoteCameraPolicy.mayStream(true, true, "", "front"))
        assertFalse(RemoteCameraPolicy.mayStream(true, true, "../other", "front"))
        assertFalse(RemoteCameraPolicy.mayStream(true, true, "paired_device_123", "external"))
    }
    @Test fun stopsStreamingWhenFrameCannotBeDelivered() {
        assertFalse(RemoteCameraPolicy.shouldContinueAfterFrame(false, true, true, "device_123456", "back"))
        assertFalse(RemoteCameraPolicy.shouldContinueAfterFrame(true, false, true, "device_123456", "back"))
        assertFalse(RemoteCameraPolicy.shouldContinueAfterFrame(true, true, false, "device_123456", "back"))
        assertFalse(RemoteCameraPolicy.shouldContinueAfterFrame(true, true, true, "", "back"))
        assertTrue(RemoteCameraPolicy.shouldContinueAfterFrame(true, true, true, "device_123456", "back"))
    }

}
