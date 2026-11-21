package `in`.shraddha.ai.link

/** Validates the bounded JPEG frame envelope shared by Android and the relay. */
internal object DeviceFrameValidation {
    const val MAX_JPEG_BYTES = 2_500_000

    fun isValidJpeg(bytes: ByteArray): Boolean =
        bytes.size in 4..MAX_JPEG_BYTES &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte()
}
