package `in`.shraddha.ai.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCameraCommandPolicyTest {
    @Test fun legacyNullActionStillStartsForExistingCallers() {
        assertEquals(RemoteCameraCommandPolicy.Command.START, RemoteCameraCommandPolicy.resolve(null))
    }
    @Test fun explicitStartAndStopAreRecognized() {
        assertEquals(RemoteCameraCommandPolicy.Command.START, RemoteCameraCommandPolicy.resolve(RemoteCameraService.ACTION_START))
        assertEquals(RemoteCameraCommandPolicy.Command.STOP, RemoteCameraCommandPolicy.resolve(RemoteCameraService.ACTION_STOP))
    }
    @Test fun unknownActionIsRejected() {
        assertEquals(RemoteCameraCommandPolicy.Command.REJECT, RemoteCameraCommandPolicy.resolve("unexpected"))
    }
}
