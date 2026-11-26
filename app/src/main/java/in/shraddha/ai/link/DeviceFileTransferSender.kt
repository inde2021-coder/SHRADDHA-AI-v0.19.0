package `in`.shraddha.ai.link

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Durable, bounded-memory staging for user-approved Android-to-PC file transfers. */
object DeviceFileTransferSender {
    data class Prepared(
        val file: File,
        val transferId: String,
        val fileName: String,
        val sizeBytes: Long,
        val chunkCount: Int,
        val sha256: String,
        val peerId: String = "",
        val sentChunks: Int = 0
    )

    fun prepare(resolver: ContentResolver, uri: Uri, storageDir: File, displayName: String?): Prepared {
        require(storageDir.exists() || storageDir.mkdirs()) { "Outgoing transfer storage is unavailable." }
        require(storageDir.isDirectory) { "Outgoing transfer storage path is invalid." }
        val temp = File(storageDir, "shraddha-send-${UUID.randomUUID()}.part")
        try {
            val input = resolver.openInputStream(uri) ?: error("Selected document could not be opened.")
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    try {
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            require(total <= DeviceFileTransferProtocol.MAX_FILE_BYTES) { "File exceeds 100 MB transfer limit." }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    } finally { buffer.fill(0) }
                }
            }
            val size = temp.length()
            val chunks = DeviceFileTransferProtocol.chunkCount(size) ?: error("Empty or oversized files cannot be sent.")
            val digest = MessageDigest.getInstance("SHA-256")
            temp.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                try {
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                } finally { buffer.fill(0) }
            }
            val name = DeviceFileTransferProtocol.safeDisplayName(displayName.orEmpty())
            val prepared = Prepared(temp, UUID.randomUUID().toString().replace("-", ""), name, size, chunks,
                digest.digest().joinToString("") { "%02x".format(it) })
            persist(prepared)
            return prepared
        } catch (error: Exception) {
            temp.delete()
            metadataFile(temp).delete()
            throw error
        }
    }

    @Synchronized fun bindPeer(prepared: Prepared, peerId: String): Prepared {
        require(peerId.isNotBlank() && peerId.length <= 128) { "Invalid receiving PC." }
        return prepared.copy(peerId = peerId).also(::persist)
    }

    @Synchronized fun updateProgress(prepared: Prepared, sentChunks: Int): Prepared {
        require(sentChunks in 0..prepared.chunkCount) { "Invalid transfer progress." }
        val previous = runCatching {
            Properties().apply { FileInputStream(metadataFile(prepared.file)).use { load(it) } }
                .getProperty("sentChunks", "0").toInt()
        }.getOrDefault(prepared.sentChunks).coerceIn(0, prepared.chunkCount)
        return prepared.copy(sentChunks = maxOf(sentChunks, prepared.sentChunks, previous)).also(::persist)
    }

    fun pending(storageDir: File): List<Prepared> {
        if (!storageDir.isDirectory) return emptyList()
        return storageDir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }
            ?.mapNotNull { staged ->
                readMetadata(staged) ?: run {
                    // A staged source that cannot pass manifest + SHA-256 recovery
                    // validation must never be offered for retry. Remove only the
                    // app-private staging artifacts (never the user's original URI).
                    runCatching { staged.delete() }
                    val metadata = metadataFile(staged)
                    runCatching { metadata.delete() }
                    runCatching { File(metadata.parentFile, metadata.name + ".tmp").delete() }
                    null
                }
            }?.sortedByDescending { it.file.lastModified() }.orEmpty()
    }

    private fun persist(prepared: Prepared) {
        require(prepared.file.isFile && prepared.file.length() == prepared.sizeBytes) { "Staged file is missing or changed." }
        val props = Properties().apply {
            setProperty("transferId", prepared.transferId)
            setProperty("fileName", prepared.fileName)
            setProperty("sizeBytes", prepared.sizeBytes.toString())
            setProperty("chunkCount", prepared.chunkCount.toString())
            setProperty("sha256", prepared.sha256)
            setProperty("peerId", prepared.peerId)
            setProperty("sentChunks", prepared.sentChunks.toString())
        }
        val target = metadataFile(prepared.file)
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { output -> props.store(output, "Shraddha AI outgoing transfer"); output.fd.sync() }
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: Exception) {
            temp.delete()
            throw IllegalStateException("Could not atomically commit transfer recovery record.", error)
        }
    }

    private fun readMetadata(file: File): Prepared? = runCatching {
        val meta = metadataFile(file)
        require(meta.isFile && !file.isSymbolicLink())
        val props = Properties().apply { FileInputStream(meta).use { load(it) } }
        val size = props.getProperty("sizeBytes").toLong()
        val count = props.getProperty("chunkCount").toInt()
        val id = props.getProperty("transferId")
        val hash = props.getProperty("sha256")
        require(file.length() == size && DeviceFileTransferProtocol.isValidManifest(id, size, count, hash))
        require(sha256(file).equals(hash, ignoreCase = true)) { "Staged source failed SHA-256 recovery verification." }
        val sentRaw = props.getProperty("sentChunks", "0").toInt()
        require(sentRaw in 0..count)
        val peer = props.getProperty("peerId", "")
        require(peer.length <= 128)
        Prepared(file, id, DeviceFileTransferProtocol.safeDisplayName(props.getProperty("fileName", "")),
            size, count, hash, peer, sentRaw)
    }.getOrNull()

    fun readChunk(prepared: Prepared, index: Int): ByteArray {
        val expected = DeviceFileTransferProtocol.expectedChunkBytes(prepared.sizeBytes, prepared.chunkCount, index)
            ?: error("Invalid outgoing chunk index.")
        require(prepared.file.isFile && prepared.file.length() == prepared.sizeBytes) { "Staged file is missing or changed." }
        val bytes = ByteArray(expected)
        java.io.RandomAccessFile(prepared.file, "r").use { file ->
            file.seek(index.toLong() * DeviceFileTransferProtocol.CHUNK_BYTES)
            file.readFully(bytes)
        }
        return bytes
    }

    @Synchronized fun cleanup(prepared: Prepared) {
        prepared.file.delete()
        metadataFile(prepared.file).delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            } finally { buffer.fill(0) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun metadataFile(file: File) = File(file.parentFile, file.name + ".meta")
    private fun File.isSymbolicLink(): Boolean = runCatching {
        val path = this.toPath()
        java.nio.file.Files.isSymbolicLink(path)
    }.getOrDefault(true)
}
