package `in`.shraddha.ai.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    @Test fun pendingTransferMetadataSurvivesReconstructionAndTracksProgress() {
        val directory = temporaryFolder.newFolder("outgoing")
        val source = java.io.File(directory, "durable.part")
        val payload = ByteArray(1024) { (it % 251).toByte() }
        source.writeBytes(payload)
        val prepared = DeviceFileTransferSender.Prepared(
            file = source,
            transferId = "transfer_123456",
            fileName = "invoice.pdf",
            sizeBytes = payload.size.toLong(),
            chunkCount = 1,
            sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }
        )
        val bound = DeviceFileTransferSender.bindPeer(prepared, "trusted-pc-01")
        DeviceFileTransferSender.updateProgress(bound, 1)

        val recovered = DeviceFileTransferSender.pending(directory).single()
        assertEquals("trusted-pc-01", recovered.peerId)
        assertEquals("invoice.pdf", recovered.fileName)
        assertEquals(1, recovered.sentChunks)
        assertEquals(payload.size.toLong(), recovered.sizeBytes)

        DeviceFileTransferSender.cleanup(recovered)
        assertTrue(DeviceFileTransferSender.pending(directory).isEmpty())
    }

    @Test fun pendingRecoveryRejectsStagedFileChangedAfterMetadataWasSaved() {
        val directory = temporaryFolder.newFolder("tampered-outgoing")
        val source = java.io.File(directory, "durable.part")
        val payload = "trusted staged bytes".toByteArray()
        source.writeBytes(payload)
        val prepared = DeviceFileTransferSender.Prepared(
            file = source, transferId = "transfer_654321", fileName = "notes.txt",
            sizeBytes = payload.size.toLong(), chunkCount = 1,
            sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }
        )
        DeviceFileTransferSender.bindPeer(prepared, "trusted-pc-02")
        source.writeText("tampered bytes")
        assertTrue(DeviceFileTransferSender.pending(directory).isEmpty())
    }

    @Test fun persistedAcknowledgedProgressNeverMovesBackward() {
        val directory = temporaryFolder.newFolder("monotonic-progress")
        val source = java.io.File(directory, "outgoing.part")
        val payload = ByteArray(DeviceFileTransferProtocol.CHUNK_BYTES * 2 + 1) { (it % 251).toByte() }
        source.writeBytes(payload)
        val prepared = DeviceFileTransferSender.Prepared(
            file = source, transferId = "transfer_765432", fileName = "progress.txt",
            sizeBytes = payload.size.toLong(), chunkCount = 3,
            sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }
        )
        val first = DeviceFileTransferSender.updateProgress(prepared, 2)
        val stale = DeviceFileTransferSender.updateProgress(prepared, 1)
        assertEquals(2, first.sentChunks)
        assertEquals(2, stale.sentChunks)
        DeviceFileTransferSender.cleanup(stale)
    }

}
