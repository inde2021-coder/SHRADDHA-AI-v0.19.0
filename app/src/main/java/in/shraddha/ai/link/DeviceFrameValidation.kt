package `in`.shraddha.ai.link

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validates and encodes the encrypted visual-frame envelope shared by Android and the relay. */
internal object DeviceFrameValidation {
    const val MAX_JPEG_BYTES = 2_500_000
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val SEQUENCE_BYTES = 8
    private const val MAX_DEVICE_ID_BYTES = 64

    data class RelayedFrame(
        val sourceDeviceId: String,
        val frameType: Int,
        val sequence: Long,
        val nonce: ByteArray,
        val ciphertext: ByteArray
    )

    fun isValidJpeg(bytes: ByteArray): Boolean =
        bytes.size in 4..MAX_JPEG_BYTES &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte()

    /** Sender-side packet: type flag, target ID, sequence, nonce and authenticated ciphertext. */
    fun encodeForRelay(
        frameType: Int,
        targetDeviceId: String,
        sequence: Long,
        nonce: ByteArray,
        ciphertext: ByteArray
    ): ByteArray {
        require(frameType in 1..2) { "Unsupported visual frame type" }
        val target = targetDeviceId.toByteArray(Charsets.UTF_8)
        require(target.isNotEmpty() && target.size <= MAX_DEVICE_ID_BYTES) { "Invalid target device ID" }
        require(sequence > 0) { "Visual frame sequence must be positive" }
        require(nonce.size == NONCE_BYTES) { "AES-GCM nonce must be 12 bytes" }
        require(ciphertext.size in GCM_TAG_BYTES..(MAX_JPEG_BYTES + GCM_TAG_BYTES)) { "Invalid encrypted frame size" }
        return ByteBuffer.allocate(2 + target.size + SEQUENCE_BYTES + NONCE_BYTES + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put((0x80 or frameType).toByte())
            .put(target.size.toByte())
            .put(target)
            .putLong(sequence)
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    /** Receiver-side packet after the relay prepends the source ID and removes the target ID. */
    fun decodeFromRelay(packet: ByteArray): RelayedFrame? = runCatching {
        require(packet.size >= 1 + 1 + SEQUENCE_BYTES + NONCE_BYTES + GCM_TAG_BYTES)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val sourceLength = buffer.get().toInt() and 0xff
        require(sourceLength in 1..MAX_DEVICE_ID_BYTES && buffer.remaining() >= sourceLength + 1 + SEQUENCE_BYTES + NONCE_BYTES + GCM_TAG_BYTES)
        val sourceBytes = ByteArray(sourceLength).also(buffer::get)
        val sourceId = sourceBytes.toString(Charsets.UTF_8)
        require(sourceId.isNotBlank() && sourceId.none { it.isISOControl() })
        val encodedType = buffer.get().toInt() and 0xff
        require(encodedType == 0x81 || encodedType == 0x82)
        val sequence = buffer.long
        require(sequence > 0)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        require(ciphertext.size in GCM_TAG_BYTES..(MAX_JPEG_BYTES + GCM_TAG_BYTES))
        RelayedFrame(sourceId, encodedType and 0x7f, sequence, nonce, ciphertext)
    }.getOrNull()
}
