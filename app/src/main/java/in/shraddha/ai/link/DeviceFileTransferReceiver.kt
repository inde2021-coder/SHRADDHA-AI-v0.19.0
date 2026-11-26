package `in`.shraddha.ai.link

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Receives approved PC-to-phone transfers into app-private storage with restart recovery. */
object DeviceFileTransferReceiver {
    data class Manifest(val peerId: String, val transferId: String, val fileName: String,
        val sizeBytes: Long, val chunkCount: Int, val sha256: String)
    data class ChunkResult(val index: Int, val ok: Boolean, val complete: Boolean = false,
        val savedPath: String? = null, val error: String? = null)
    data class CompletedReceipt(val peerId: String, val transferId: String, val fileName: String,
        val sizeBytes: Long, val sha256: String, val savedPath: String)
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
        active[manifest.transferId]?.let { return it.manifest == manifest }
        loadReceipt(context, manifest.transferId)?.let { receipt ->
            return receipt.peerId == manifest.peerId && receipt.sizeBytes == manifest.sizeBytes &&
                receipt.sha256.equals(manifest.sha256, ignoreCase = true)
        }
        val directory = receivedDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) return false
        if (!directory.isDirectory || directory.usableSpace < manifest.sizeBytes) return false
        val temp = File(directory, ".${manifest.transferId}.part")
        if (temp.exists() || temp.isDirectory) return false
        return try {
            RandomAccessFile(temp, "rw").use { file -> file.setLength(manifest.sizeBytes); file.fd.sync() }
            val state = Active(manifest, temp, BooleanArray(manifest.chunkCount))
            active[manifest.transferId] = state
            if (!persistState(state)) {
                active.remove(manifest.transferId)
                temp.delete()
                metadataFile(temp).delete()
                false
            } else true
        } catch (_: Exception) {
            active.remove(manifest.transferId)
            temp.delete()
            metadataFile(temp).delete()
            false
        }
    }

    /** Rehydrates only app-private partial files whose manifest and bitmap validate. */
    @Synchronized fun restore(context: Context): List<Manifest> {
        val directory = receivedDirectory(context)
        if (!directory.isDirectory) return emptyList()
        directory.listFiles()?.filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".part") }
            ?.forEach { temp ->
                val transferId = temp.name.removePrefix(".").removeSuffix(".part")
                if (active.containsKey(transferId)) return@forEach
                runCatching {
                    require(!temp.isSymbolicLink())
                    val props = Properties().apply { FileInputStream(metadataFile(temp)).use { load(it) } }
                    val peer = props.getProperty("peerId").orEmpty()
                    val name = DeviceFileTransferProtocol.safeDisplayName(props.getProperty("fileName").orEmpty())
                    val size = props.getProperty("sizeBytes").toLong()
                    val chunks = props.getProperty("chunkCount").toInt()
                    val hash = props.getProperty("sha256").orEmpty()
                    val id = props.getProperty("transferId").orEmpty()
                    require(id == transferId && temp.length() == size &&
                        DeviceFileTransferProtocol.isValidManifest(id, size, chunks, hash) && peer.isNotBlank())
                    val received = BooleanArray(chunks)
                    val rawIndexes = props.getProperty("receivedIndexes", "")
                    if (rawIndexes.isNotBlank()) rawIndexes.split(',').forEach { raw ->
                        val index = raw.toInt()
                        require(index in 0 until chunks)
                        received[index] = true
                    }
                    val manifest = Manifest(peer, id, name, size, chunks, hash)
                    val state = Active(manifest, temp, received)
                    if (received.all { it }) {
                        if (sha256(temp).equals(hash, ignoreCase = true)) {
                            val receipt = loadReceipt(context, id)
                            val destination = receipt?.let { File(it.savedPath) } ?: uniqueDestination(directory, name)
                            require(!destination.isSymbolicLink() && destination.parentFile?.canonicalFile == directory.canonicalFile)
                            if (persistReceipt(context, CompletedReceipt(peer, id, name, size, hash, destination.absolutePath)) &&
                                (destination.isFile && destination.length() == size && sha256(destination).equals(hash, true) ||
                                    temp.renameTo(destination))) {
                                metadataFile(temp).delete()
                            } else active[id] = state
                        } else {
                            temp.delete()
                            metadataFile(temp).delete()
                        }
                    } else {
                        active[id] = state
                    }
                }
            }
        return pendingTransfers()
    }

    fun pendingTransfers(): List<Manifest> = active.values.map { it.manifest }.sortedBy { it.fileName }

    fun missingIndexes(transferId: String): List<Int> {
        val state = active[transferId] ?: return emptyList()
        synchronized(state.lock) { return state.received.indices.filterNot { state.received[it] } }
    }

    fun receiveChunk(context: Context, peerId: String, message: JSONObject): ChunkResult {
        val transferId = message.optString("transferId")
        val index = message.optInt("index", -1)
        val state = active[transferId] ?: run {
            val receipt = loadReceipt(context, transferId)
            if (receipt != null && receipt.peerId == peerId) {
                val saved = File(receipt.savedPath)
                if (saved.isFile && !saved.isSymbolicLink() && saved.length() == receipt.sizeBytes) {
                    return ChunkResult(index, true, complete = true, savedPath = saved.absolutePath)
                }
            }
            return ChunkResult(index, false, error = "No approved transfer is active.")
        }
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
                if (!persistState(state)) {
                    state.received[index] = false
                    return ChunkResult(index, false, error = "Chunk was written but its recovery record could not be saved; retry this chunk.")
                }
                if (state.received.all { it }) {
                    if (!sha256(state.temp).equals(state.manifest.sha256, ignoreCase = true)) {
                        active.remove(transferId)
                        state.temp.delete()
                        metadataFile(state.temp).delete()
                        return ChunkResult(index, false, error = "Final SHA-256 mismatch; incomplete file discarded.")
                    }
                    val directory = receivedDirectory(context)
                    val destination = uniqueDestination(directory, state.manifest.fileName)
                    val receipt = CompletedReceipt(state.manifest.peerId, transferId, state.manifest.fileName,
                        state.manifest.sizeBytes, state.manifest.sha256, destination.absolutePath)
                    if (!persistReceipt(context, receipt))
                        return ChunkResult(index, false, error = "Verified file is ready, but completion receipt could not be saved; transfer retained for recovery.")
                    if (!state.temp.renameTo(destination)) {
                        deleteReceipt(context, transferId)
                        return ChunkResult(index, false, error = "Verified file could not be published; transfer retained for retry.")
                    }
                    active.remove(transferId)
                    metadataFile(state.temp).delete()
                    return ChunkResult(index, true, complete = true, savedPath = destination.absolutePath)
                }
                return ChunkResult(index, true)
            } catch (error: Exception) {
                return ChunkResult(index, false, error = error.message ?: "Could not write received chunk.")
            } finally { bytes.fill(0) }
        }
    }

    /** Receipts bridge the crash window between publishing the file and ACKing the sender. */
    @Synchronized fun completedReceipts(context: Context, peerId: String? = null): List<CompletedReceipt> {
        val directory = receivedDirectory(context)
        return directory.listFiles()?.filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".receipt") }
            ?.mapNotNull { file ->
                val id = file.name.removePrefix(".").removeSuffix(".receipt")
                loadReceipt(context, id)?.takeIf { receipt ->
                    (peerId == null || receipt.peerId == peerId) &&
                        File(receipt.savedPath).let { saved ->
                            saved.isFile && !saved.isSymbolicLink() && saved.length() == receipt.sizeBytes
                        }
                }
            }?.sortedBy { it.transferId }.orEmpty()
    }

    /** Keep receipts after ACK attempts: a relay may drop a queued ACK during disconnect. */
    @Synchronized fun acknowledgeReceipt(context: Context, peerId: String, transferId: String): Boolean {
        val receipt = loadReceipt(context, transferId) ?: return false
        if (receipt.peerId != peerId) return false
        // Keep the receipt, but refresh its retention priority after an ACK attempt.
        receiptFile(context, transferId).setLastModified(System.currentTimeMillis())
        return true
    }

    private fun persistReceipt(context: Context, receipt: CompletedReceipt): Boolean = runCatching {
        require(receipt.transferId.matches(Regex("[A-Za-z0-9_-]{12,64}")) &&
            receipt.peerId.isNotBlank() && receipt.sizeBytes in 1L..DeviceFileTransferProtocol.MAX_FILE_BYTES &&
            receipt.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        val directory = receivedDirectory(context)
        val destination = File(receipt.savedPath)
        require(destination.parentFile?.canonicalFile == directory.canonicalFile)
        val props = Properties().apply {
            setProperty("peerId", receipt.peerId)
            setProperty("transferId", receipt.transferId)
            setProperty("fileName", receipt.fileName)
            setProperty("sizeBytes", receipt.sizeBytes.toString())
            setProperty("sha256", receipt.sha256)
            setProperty("savedPath", receipt.savedPath)
        }
        val target = receiptFile(context, receipt.transferId)
        val staging = File(directory, target.name + ".tmp")
        FileOutputStream(staging).use { output -> props.store(output, "Shraddha AI completed transfer receipt"); output.fd.sync() }
        java.nio.file.Files.move(staging.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        directory.listFiles()?.filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".receipt") }
            ?.sortedByDescending { it.lastModified() }?.drop(128)?.forEach { it.delete() }
    }.isSuccess

    private fun loadReceipt(context: Context, transferId: String): CompletedReceipt? = runCatching {
        require(transferId.matches(Regex("[A-Za-z0-9_-]{12,64}")))
        val props = Properties().apply { FileInputStream(receiptFile(context, transferId)).use { load(it) } }
        val peer = props.getProperty("peerId").orEmpty()
        val id = props.getProperty("transferId").orEmpty()
        val name = DeviceFileTransferProtocol.safeDisplayName(props.getProperty("fileName").orEmpty())
        val size = props.getProperty("sizeBytes").toLong()
        val hash = props.getProperty("sha256").orEmpty()
        val path = props.getProperty("savedPath").orEmpty()
        val file = File(path)
        require(id == transferId && peer.isNotBlank() && size in 1L..DeviceFileTransferProtocol.MAX_FILE_BYTES &&
            hash.matches(Regex("[a-fA-F0-9]{64}")) && !file.isSymbolicLink() &&
            file.parentFile?.canonicalFile == receivedDirectory(context).canonicalFile)
        CompletedReceipt(peer, id, name, size, hash, path)
    }.getOrNull()

    private fun deleteReceipt(context: Context, transferId: String): Boolean =
        receiptFile(context, transferId).delete() || !receiptFile(context, transferId).exists()

    private fun receiptFile(context: Context, transferId: String) =
        File(receivedDirectory(context), ".$transferId.receipt")

    fun listReceived(context: Context): List<File> {
        val directory = receivedDirectory(context)
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

    @Synchronized fun reject(transferId: String, expectedPeerId: String? = null): Boolean {
        val state = active[transferId] ?: return false
        if (expectedPeerId != null && state.manifest.peerId != expectedPeerId) return false
        active.remove(transferId, state)
        metadataFile(state.temp).delete()
        return state.temp.delete() || !state.temp.exists()
    }

    private fun persistState(state: Active): Boolean = runCatching {
        val props = Properties().apply {
            setProperty("transferId", state.manifest.transferId)
            setProperty("peerId", state.manifest.peerId)
            setProperty("fileName", state.manifest.fileName)
            setProperty("sizeBytes", state.manifest.sizeBytes.toString())
            setProperty("chunkCount", state.manifest.chunkCount.toString())
            setProperty("sha256", state.manifest.sha256)
            setProperty("receivedIndexes", state.received.indices.filter { state.received[it] }.joinToString(","))
        }
        val target = metadataFile(state.temp)
        val staging = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(staging).use { output -> props.store(output, "Shraddha AI incoming transfer"); output.fd.sync() }
        java.nio.file.Files.move(staging.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess

    private fun receivedDirectory(context: Context) = File(context.filesDir, "ReceivedFiles")
    private fun metadataFile(temp: File) = File(temp.parentFile, temp.name + ".meta")

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

    private fun File.isSymbolicLink(): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(this.toPath())
    }.getOrDefault(true)
}
