package `in`.shraddha.ai.link

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Stages a user-selected document in app cache so size/hash stay stable during transfer. */
object DeviceFileTransferSender {
    data class Prepared(val file: File, val transferId: String, val fileName: String,
        val sizeBytes: Long, val chunkCount: Int, val sha256: String)

    fun prepare(resolver: ContentResolver, uri: Uri, cacheDir: File, displayName: String?): Prepared {
        val temp = File(cacheDir, "shraddha-send-${UUID.randomUUID()}.part")
        try {
            val input = resolver.openInputStream(uri) ?: error("Selected document could not be opened.")
            input.use { source ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= DeviceFileTransferProtocol.MAX_FILE_BYTES) { "File exceeds 100 MB transfer limit." }
                        output.write(buffer, 0, count)
                    }
                    buffer.fill(0)
                }
            }
            val size = temp.length()
            val chunks = DeviceFileTransferProtocol.chunkCount(size) ?: error("Empty or oversized files cannot be sent.")
            val digest = MessageDigest.getInstance("SHA-256")
            temp.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                try { while (true) { val count = stream.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) } }
                finally { buffer.fill(0) }
            }
            val name = DeviceFileTransferProtocol.safeDisplayName(displayName.orEmpty())
            return Prepared(temp, UUID.randomUUID().toString().replace("-", ""), name, size, chunks,
                digest.digest().joinToString("") { "%02x".format(it) })
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    fun readChunk(prepared: Prepared, index: Int): ByteArray {
        val expected = DeviceFileTransferProtocol.expectedChunkBytes(prepared.sizeBytes, prepared.chunkCount, index)
            ?: error("Invalid outgoing chunk index.")
        val bytes = ByteArray(expected)
        java.io.RandomAccessFile(prepared.file, "r").use { file ->
            file.seek(index.toLong() * DeviceFileTransferProtocol.CHUNK_BYTES)
            file.readFully(bytes)
        }
        return bytes
    }

    fun cleanup(prepared: Prepared) { prepared.file.delete() }
}
