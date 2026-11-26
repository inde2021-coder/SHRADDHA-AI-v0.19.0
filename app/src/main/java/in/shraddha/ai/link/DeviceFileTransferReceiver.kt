package `in`.shraddha.ai.link

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Receives approved PC-to-phone transfers into app-private storage. */
object DeviceFileTransferReceiver {
    data class Manifest(val peerId: String, val transferId: String, val fileName: String,
        val sizeBytes: Long, val chunkCount: Int, val sha256: String)
    data class ChunkResult(val index: Int, val ok: Boolean, val complete: Boolean = false,
        val savedPath: String? = null, val error: String? = null)
    private data class Active(val manifest: Manifest, val temp: File,
        val received: BooleanArray, val lock: Any = Any())
    private val active = ConcurrentHashMap<String, Active>()

    fun parseManifest(peerId: String, message: JSONObject): Manifest? {
        val id = message.optString("transferId")
        val size = message.optLong("sizeBytes", -1L)
        val count = message.optInt("chunkCount", -1)
        val hash = message.optString("sha256").lowercase()
        val rawName = message.optString("fileName")
        val name = DeviceFileTransferProtocol.safeDisplayName(rawName)
        if (!DeviceFileTransferProtocol.isValidManifest(id, size, count, hash) ||
            peerId.isBlank() || rawName.isBlank()) return null
        return Manifest(peerId, id, name, size, count, hash)
    }

    /** Call only after the user approves the incoming transfer prompt. */
    @Synchronized fun accept(context: Context, manifest: Manifest): Boolean {
        if (active.containsKey(manifest.transferId)) return false
        val directory = File(context.filesDir, "ReceivedFiles")
        if (!directory.exists() && !directory.mkdirs()) return false
        if (!directory.isDirectory || directory.usableSpace < manifest.sizeBytes) return false
        val temp = File(directory, ".${manifest.transferId}.part")
        if (temp.exists() || temp.isDirectory) return false
        return try {
            RandomAccessFile(temp, "rw").use { it.setLength(manifest.sizeBytes) }
            active[manifest.transferId] = Active(manifest, temp, BooleanArray(manifest.chunkCount))
            true
        } catch (_: Exception) { temp.delete(); false }
    }

    fun receiveChunk(context: Context, peerId: String, message: JSONObject): ChunkResult {
        val transferId = message.optString("transferId")
        val index = message.optInt("index", -1)
        val state = active[transferId] ?: return ChunkResult(index, false, error = "No approved transfer is active.")
        if (!DeviceFileTransferProtocol.isExpectedPeerMessage(state.manifest.peerId, peerId, state.manifest.transferId, transferId))
            return ChunkResult(index, false, error = "Unexpected sender or transfer ID.")
        val expected = DeviceFileTransferProtocol.expectedChunkBytes(state.manifest.sizeBytes, state.manifest.chunkCount, index)
            ?: return ChunkResult(index, false, error = "Invalid chunk index.")
        val bytes = try { Base64.decode(message.optString("payload"), Base64.DEFAULT) }
            catch (_: IllegalArgumentException) { return ChunkResult(index, false, error = "Malformed chunk encoding.") }
        if (bytes.size != expected) { bytes.fill(0); return ChunkResult(index, false, error = "Chunk length does not match manifest.") }

        synchronized(state.lock) {
            try {
                if (state.received[index]) return ChunkResult(index, true)
                RandomAccessFile(state.temp, "rw").use { file ->
                    file.seek(index.toLong() * DeviceFileTransferProtocol.CHUNK_BYTES)
                    file.write(bytes)
                    file.fd.sync()
                }
                state.received[index] = true
                if (state.received.all { it }) {
                    if (!sha256(state.temp).equals(state.manifest.sha256, ignoreCase = true)) {
                        active.remove(transferId); state.temp.delete()
                        return ChunkResult(index, false, error = "Final SHA-256 mismatch; incomplete file discarded.")
                    }
                    val destination = uniqueDestination(File(context.filesDir, "ReceivedFiles"), state.manifest.fileName)
                    if (!state.temp.renameTo(destination))
                        return ChunkResult(index, false, error = "Verified file could not be published; transfer retained for retry.")
                    active.remove(transferId)
                    return ChunkResult(index, true, complete = true, savedPath = destination.absolutePath)
                }
                return ChunkResult(index, true)
            } catch (error: Exception) {
                return ChunkResult(index, false, error = error.message ?: "Could not write received chunk.")
            } finally { bytes.fill(0) }
        }
    }

    fun listReceived(context: Context): List<File> {
        val directory = File(context.filesDir, "ReceivedFiles")
        return directory.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "csv" -> "text/csv"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }

    @Synchronized fun reject(transferId: String): Boolean {
        val state = active.remove(transferId) ?: return false
        return state.temp.delete() || !state.temp.exists()
    }

    private fun uniqueDestination(directory: File, displayName: String): File {
        val safe = DeviceFileTransferProtocol.safeDisplayName(displayName)
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        val base = safe.substring(0, dot).take(100)
        val extension = safe.substring(dot).take(20)
        var suffix = 1
        do {
            candidate = File(directory, "${base} ($suffix)$extension")
            suffix++
        } while (candidate.exists() && suffix < 10000)
        require(!candidate.exists()) { "Could not allocate a unique destination filename." }
        return candidate
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            try { while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) } }
            finally { buffer.fill(0) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
