package com.Lia.assistant.action

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived, one-shot approval bound to the exact channel, E.164 recipient,
 * message body and request ID shown to the user before sending.
 */
class MessageSendApproval private constructor(
    internal val channel: String,
    internal val recipient: String,
    internal val messageDigest: String,
    internal val requestId: String,
    internal val approvedAtMillis: Long
) {
    private val consumed = AtomicBoolean(false)

    internal fun consume(
        actualChannel: String,
        actualRecipient: String,
        actualMessage: String,
        actualRequestId: String,
        nowMillis: Long
    ): Boolean {
        if (!MessageApprovalPolicy.isValidE164(actualRecipient)) return false
        if (channel != actualChannel || recipient != actualRecipient || requestId != actualRequestId) return false
        if (digest(actualMessage) != messageDigest) return false
        if (approvedAtMillis <= 0L || nowMillis < approvedAtMillis || nowMillis - approvedAtMillis > MAX_AGE_MILLIS) return false
        return consumed.compareAndSet(false, true)
    }

    companion object {
        private const val MAX_AGE_MILLIS = 120_000L

        /** Call only after the UI has shown the exact recipient and full message and the user accepted. */
        fun afterExplicitConfirmation(
            channel: String,
            recipient: String,
            exactMessage: String,
            requestId: String,
            nowMillis: Long = System.currentTimeMillis()
        ): MessageSendApproval {
            require(channel == "sms" || channel == "whatsapp")
            require(MessageApprovalPolicy.isValidE164(recipient))
            require(exactMessage.isNotBlank() && exactMessage.length <= 4000)
            require(requestId.matches(Regex("^[A-Za-z0-9_-]{16,80}$")))
            require(nowMillis > 0L)
            return MessageSendApproval(channel, recipient, digest(exactMessage), requestId, nowMillis)
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
