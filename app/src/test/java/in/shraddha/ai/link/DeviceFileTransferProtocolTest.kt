package `in`.shraddha.ai.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFileTransferProtocolTest {
    @Test fun validatesIncomingManifestAndSanitizesDisplayName() {
        val size = DeviceFileTransferProtocol.CHUNK_BYTES.toLong() + 1L
        val count = DeviceFileTransferProtocol.chunkCount(size)!!
        val hash = "a".repeat(64)
        assertEquals(true, DeviceFileTransferProtocol.isValidManifest("transfer_123456", size, count, hash))
        assertEquals(false, DeviceFileTransferProtocol.isValidManifest("../escape12345", size, count, hash))
        assertEquals(false, DeviceFileTransferProtocol.isValidManifest("transfer_123456", size, count + 1, hash))
        assertEquals(false, DeviceFileTransferProtocol.isValidManifest("transfer_123456", size, count, "bad"))
        assertEquals("name.txt", DeviceFileTransferProtocol.safeDisplayName("../evil/name.txt"))
        assertEquals("shared-file", DeviceFileTransferProtocol.safeDisplayName("\\u0000"))
    }

    @Test fun bindsChunkAcksAndCancellationToExpectedPeerAndTransfer() {
        assertEquals(true, DeviceFileTransferProtocol.isExpectedPeerMessage("peer-A", "peer-A", "transfer_123456", "transfer_123456"))
        assertEquals(false, DeviceFileTransferProtocol.isExpectedPeerMessage("peer-A", "peer-B", "transfer_123456", "transfer_123456"))
        assertEquals(false, DeviceFileTransferProtocol.isExpectedPeerMessage("peer-A", "peer-A", "transfer_123456", "transfer_654321"))
        assertEquals(false, DeviceFileTransferProtocol.isExpectedPeerMessage("", "peer-A", "transfer_123456", "transfer_123456"))
    }

    @Test fun incomingChunksMustMatchTheApprovedPeer() {
        assertEquals(true, DeviceFileTransferProtocol.isExpectedPeer("paired-device-1234", "paired-device-1234"))
        assertEquals(false, DeviceFileTransferProtocol.isExpectedPeer("paired-device-1234", "other-device-5678"))
        assertEquals(false, DeviceFileTransferProtocol.isExpectedPeer("", "paired-device-1234"))
        assertEquals(false, DeviceFileTransferProtocol.isExpectedPeer("paired-device-1234", ""))
    }

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
}
