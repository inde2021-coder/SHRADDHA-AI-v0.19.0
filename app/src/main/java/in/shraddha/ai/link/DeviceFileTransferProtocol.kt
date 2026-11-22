package `in`.shraddha.ai.link

/** One source of truth for Android file-transfer chunk sizing and bounds. */
internal object DeviceFileTransferProtocol {
    const val CHUNK_BYTES = 48 * 1024
    const val MAX_FILE_BYTES = 100L * 1024L * 1024L
    const val MAX_CHUNKS = ((MAX_FILE_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()

    fun chunkCount(sizeBytes: Long): Int? {
        if (sizeBytes !in 1L..MAX_FILE_BYTES) return null
        return ((sizeBytes + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
    }

    fun expectedChunkBytes(sizeBytes: Long, chunkCount: Int, index: Int): Int? {
        val expectedCount = chunkCount(sizeBytes) ?: return null
        if (chunkCount != expectedCount || index !in 0 until chunkCount) return null
        val remaining = sizeBytes - index.toLong() * CHUNK_BYTES
        if (remaining <= 0L) return null
        return minOf(CHUNK_BYTES.toLong(), remaining).toInt()
    }
}
