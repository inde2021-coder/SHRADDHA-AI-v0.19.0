package `in`.shraddha.ai.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceFileTransferSenderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun readsChunksAtExactOffsetsIncludingShortFinalChunk() {
        val source = temporaryFolder.newFile("source.bin")
        val bytes = ByteArray(DeviceFileTransferProtocol.CHUNK_BYTES + 37) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val prepared = DeviceFileTransferSender.Prepared(
            file = source,
            transferId = "transfer_123456",
            fileName = "source.bin",
            sizeBytes = bytes.size.toLong(),
            chunkCount = 2,
            sha256 = "a".repeat(64)
        )

        assertArrayEquals(bytes.copyOfRange(0, DeviceFileTransferProtocol.CHUNK_BYTES),
            DeviceFileTransferSender.readChunk(prepared, 0))
        assertArrayEquals(bytes.copyOfRange(DeviceFileTransferProtocol.CHUNK_BYTES, bytes.size),
            DeviceFileTransferSender.readChunk(prepared, 1))
    }

    @Test fun rejectsOutOfRangeChunkAndDeletesStagedFileOnCleanup() {
        val source = temporaryFolder.newFile("staged.bin")
        source.writeBytes(byteArrayOf(1, 2, 3))
        val prepared = DeviceFileTransferSender.Prepared(
            file = source, transferId = "transfer_123456", fileName = "staged.bin",
            sizeBytes = 3, chunkCount = 1, sha256 = "b".repeat(64)
        )

        val rejected = runCatching { DeviceFileTransferSender.readChunk(prepared, 1) }
        assertTrue(rejected.isFailure)
        DeviceFileTransferSender.cleanup(prepared)
        assertFalse(source.exists())
    }
}
