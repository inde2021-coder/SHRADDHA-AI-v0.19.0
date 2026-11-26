package `in`.shraddha.ai.link

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class DeviceFileTransferRecoveryInstrumentedTest {
    @Test
    fun approvedIncomingTransferRestoresBitmapAndMissingChunkList() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transferId = UUID.randomUUID().toString().replace("-", "")
        val peerId = "instrumented-test-pc"
        val payload = ByteArray(DeviceFileTransferProtocol.CHUNK_BYTES + 3) { (it % 239).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val manifest = DeviceFileTransferReceiver.Manifest(
            peerId, transferId, "recovery-test.bin", payload.size.toLong(), 2, digest
        )

        try {
            assertTrue(DeviceFileTransferReceiver.accept(context, manifest))
            val firstChunk = payload.copyOfRange(0, DeviceFileTransferProtocol.CHUNK_BYTES)
            val result = DeviceFileTransferReceiver.receiveChunk(context, peerId,
                JSONObject().put("transferId", transferId).put("index", 0)
                    .put("payload", Base64.encodeToString(firstChunk, Base64.NO_WRAP)))
            firstChunk.fill(0)
            assertTrue(result.ok)
            assertTrue(!result.complete)

            val activeField = DeviceFileTransferReceiver::class.java.getDeclaredField("active")
            activeField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (activeField.get(DeviceFileTransferReceiver) as MutableMap<String, Any>).clear()

            val restored = DeviceFileTransferReceiver.restore(context)
            assertEquals(1, restored.count { it.transferId == transferId })
            assertEquals(listOf(1), DeviceFileTransferReceiver.missingIndexes(transferId))
        } finally {
            DeviceFileTransferReceiver.reject(transferId)
        }
    }
    @Test
    fun completedReceiveRetainsReceiptUntilCompletionAckAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transferId = UUID.randomUUID().toString().replace("-", "")
        val peerId = "instrumented-test-pc"
        val payload = "complete-before-ack".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val manifest = DeviceFileTransferReceiver.Manifest(
            peerId, transferId, "completion-recovery.txt", payload.size.toLong(), 1, digest
        )
        var savedFile: java.io.File? = null
        try {
            assertTrue(DeviceFileTransferReceiver.accept(context, manifest))
            val result = DeviceFileTransferReceiver.receiveChunk(context, peerId,
                JSONObject().put("transferId", transferId).put("index", 0)
                    .put("payload", Base64.encodeToString(payload, Base64.NO_WRAP)))
            assertTrue(result.ok)
            assertTrue(result.complete)
            savedFile = java.io.File(result.savedPath!!)
            assertTrue(savedFile.isFile)

            // Simulate process death after publishing the file but before the final ACK.
            val activeField = DeviceFileTransferReceiver::class.java.getDeclaredField("active")
            activeField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (activeField.get(DeviceFileTransferReceiver) as MutableMap<String, Any>).clear()

            val receipts = DeviceFileTransferReceiver.completedReceipts(context, peerId)
            assertEquals(1, receipts.count { it.transferId == transferId })
            assertTrue(DeviceFileTransferReceiver.acknowledgeReceipt(context, peerId, transferId))
            // Keep the receipt so a relay-dropped ACK can be repeated after another reconnect.
            assertEquals(1, DeviceFileTransferReceiver.completedReceipts(context, peerId)
                .count { it.transferId == transferId })
            val duplicate = DeviceFileTransferReceiver.receiveChunk(context, peerId,
                JSONObject().put("transferId", transferId).put("index", 0)
                    .put("payload", Base64.encodeToString(payload, Base64.NO_WRAP)))
            assertTrue(duplicate.ok)
            assertTrue(duplicate.complete)
        } finally {
            DeviceFileTransferReceiver.reject(transferId)
            savedFile?.delete()
            context.filesDir.resolve("ReceivedFiles/.$transferId.receipt").delete()
        }
    }

}
