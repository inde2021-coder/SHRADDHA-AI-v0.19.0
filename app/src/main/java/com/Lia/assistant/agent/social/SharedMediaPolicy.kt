package com.Lia.assistant.agent.social

/** Validation shared by the Android share receiver and its unit tests. */
internal object SharedMediaPolicy {
    const val MAX_FILE_BYTES = 50L * 1024L * 1024L
    const val MAX_ITEMS = 10
    const val MAX_BATCH_BYTES = 100L * 1024L * 1024L

    fun supports(mimeType: String?): Boolean {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    fun extensionFor(mimeType: String): String {
        return when (mimeType.substringBefore(';').trim().lowercase()) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "video/mp4" -> ".mp4"
            "video/webm" -> ".webm"
            "video/quicktime" -> ".mov"
            else -> if (mimeType.startsWith("image/", ignoreCase = true)) ".img" else ".video"
        }
    }

    fun isAllowedSize(bytes: Long): Boolean = bytes in 1..MAX_FILE_BYTES

    fun isAllowedBatchSize(bytes: Long): Boolean = bytes in 1..MAX_BATCH_BYTES
}
