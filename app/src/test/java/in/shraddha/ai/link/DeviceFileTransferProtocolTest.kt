package `in`.shraddha.ai.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFileTransferProtocolTest {
    @Test fun chunkCountMatchesRelay48KiBBoundaries() {
        assertEquals(1, DeviceFileTransferProtocol.chunkCount(1L))
        assertEquals(1, DeviceFileTransferProtocol.chunkCount(48L * 1024L))
        assertEquals(2, DeviceFileTransferProtocol.chunkCount(48L * 1024L + 1L))
        assertEquals(2, DeviceFileTransferProtocol.chunkCount(96_000L))
        assertEquals(3, DeviceFileTransferProtocol.chunkCount(100_000L))
        assertEquals(2_134, DeviceFileTransferProtocol.chunkCount(DeviceFileTransferProtocol.MAX_FILE_BYTES))
    }

    @Test fun exactChunkBoundariesNeverCreateAnEmptyTrailingChunk() {
        val size = 48L * 1024L
        val count = DeviceFileTransferProtocol.chunkCount(size)!!
        assertEquals(1, count)
        assertEquals(48 * 1024, DeviceFileTransferProtocol.expectedChunkBytes(size, count, 0))
        assertNull(DeviceFileTransferProtocol.expectedChunkBytes(size, count, 1))
    }

    @Test fun finalChunkUsesOnlyRemainingBytesAndRejectsBadManifest() {
        val size = 100_000L
        val count = DeviceFileTransferProtocol.chunkCount(size)!!
        assertEquals(48 * 1024, DeviceFileTransferProtocol.expectedChunkBytes(size, count, 0))
        assertEquals(100_000 - 2 * 48 * 1024, DeviceFileTransferProtocol.expectedChunkBytes(size, count, 2))
        assertNull(DeviceFileTransferProtocol.expectedChunkBytes(size, count + 1, 0))
        assertNull(DeviceFileTransferProtocol.expectedChunkBytes(size, count, count))
    }

    @Test fun fileSizeOutsideSupportedRangeIsRejected() {
        assertNull(DeviceFileTransferProtocol.chunkCount(0L))
        assertNull(DeviceFileTransferProtocol.chunkCount(DeviceFileTransferProtocol.MAX_FILE_BYTES + 1L))
    }
    @Test fun validatesIncomingManifestBeforeAcceptingIt() {
        val size = DeviceFileTransferProtocol.CHUNK_BYTES.toLong() + 1L
        val count = DeviceFileTransferProtocol.chunkCount(size)!!
        val hash = "a".repeat(64)
        assertTrue(DeviceFileTransferProtocol.isValidManifest("transfer_123456", size, count, hash))
        assertFalse(DeviceFileTransferProtocol.isValidManifest("../escape12345", size, count, hash))
        assertFalse(DeviceFileTransferProtocol.isValidManifest("transfer_123456", size, count + 1, hash))
        assertFalse(DeviceFileTransferProtocol.isValidManifest("transfer_123456", size, count, "bad"))
        assertFalse(DeviceFileTransferProtocol.isValidManifest("transfer_123456", 0L, 1, hash))
        assertEquals("evil_name.txt", DeviceFileTransferProtocol.safeDisplayName("../evil/name.txt"))
        assertEquals("shared-file", DeviceFileTransferProtocol.safeDisplayName("\u0000"))
    }

}
