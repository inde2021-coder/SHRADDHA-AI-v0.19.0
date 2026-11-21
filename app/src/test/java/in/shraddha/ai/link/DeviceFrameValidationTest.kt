package `in`.shraddha.ai.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFrameValidationTest {
    @Test fun acceptsBoundedJpegWithStartOfImageMarker() {
        assertTrue(DeviceFrameValidation.isValidJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
    }

    @Test fun rejectsEmptyShortAndNonJpegFrames() {
        assertFalse(DeviceFrameValidation.isValidJpeg(byteArrayOf()))
        assertFalse(DeviceFrameValidation.isValidJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(DeviceFrameValidation.isValidJpeg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test fun rejectsOversizedJpegFrames() {
        assertFalse(DeviceFrameValidation.isValidJpeg(ByteArray(DeviceFrameValidation.MAX_JPEG_BYTES + 1)))
    }
}
