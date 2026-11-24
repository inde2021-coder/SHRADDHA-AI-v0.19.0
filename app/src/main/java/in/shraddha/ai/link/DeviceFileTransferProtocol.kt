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

    fun isValidManifest(transferId: String, sizeBytes: Long, declaredChunkCount: Int, sha256: String): Boolean =
        transferId.matches(Regex("^[A-Za-z0-9_-]{12,64}$")) &&
            chunkCount(sizeBytes) == declaredChunkCount &&
            sha256.matches(Regex("^[a-fA-F0-9]{64}$"))

    fun isExpectedIncomingPeer(expectedPeer: String, actualPeer: String): Boolean =
        expectedPeer.isNotBlank() && actualPeer.isNotBlank() && actualPeer == expectedPeer

    fun isExpectedPeerMessage(expectedPeer: String, actualPeer: String, expectedTransferId: String, actualTransferId: String): Boolean =
        expectedPeer.isNotBlank() && actualPeer == expectedPeer &&
            expectedTransferId.isNotBlank() && actualTransferId == expectedTransferId

    fun safeDisplayName(value: String): String {
        val cleaned = value
            .substringAfterLast('/')
            .substringAfterLast(92.toChar())
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
            .trim()
            .trimStart('.')
            .take(120)
        return cleaned.takeUnless { it.isBlank() || it.all { char -> char == '_' } } ?: "shared-file"
    }

    fun expectedChunkBytes(sizeBytes: Long, declaredChunkCount: Int, index: Int): Int? {
        val expectedCount = chunkCount(sizeBytes) ?: return null
        if (declaredChunkCount != expectedCount || index !in 0 until declaredChunkCount) return null
        val remaining = sizeBytes - index.toLong() * CHUNK_BYTES
        if (remaining <= 0L) return null
        return minOf(CHUNK_BYTES.toLong(), remaining).toInt()
    }
}
