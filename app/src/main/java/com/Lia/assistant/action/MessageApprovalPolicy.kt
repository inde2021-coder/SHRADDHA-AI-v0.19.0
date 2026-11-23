package com.Lia.assistant.action

/** Approval is valid only for the exact resolved recipient and exact message body. */
object MessageApprovalPolicy {
    fun isValidE164(value: String): Boolean = Regex("^\\+[1-9]\\d{7,14}$").matches(value)

    fun isApproved(
        actualRecipient: String,
        actualMessage: String,
        userConfirmed: Boolean,
        approvedRecipient: String,
        approvedMessage: String
    ): Boolean {
        if (!userConfirmed) return false
        if (actualRecipient.isBlank() || actualMessage.isBlank()) return false
        return approvedRecipient == actualRecipient && approvedMessage == actualMessage
    }
}
