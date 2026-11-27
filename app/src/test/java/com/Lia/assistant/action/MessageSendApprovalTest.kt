package com.Lia.assistant.action

import org.junit.Assert.*
import org.junit.Test

class MessageSendApprovalTest {
    private val recipient = "+14155552671"
    private val requestId = "request_1234567890"
    private val body = "Your booking is confirmed."

    @Test fun exactApprovalCanBeConsumedOnlyOnce() {
        val approval = MessageSendApproval.afterExplicitConfirmation("whatsapp", recipient, body, requestId, 10_000L)
        assertTrue(approval.consume("whatsapp", recipient, body, requestId, 11_000L))
        assertFalse(approval.consume("whatsapp", recipient, body, requestId, 11_001L))
    }

    @Test fun approvalCannotBeReusedForChangedRecipientBodyOrChannel() {
        assertFalse(MessageSendApproval.afterExplicitConfirmation("whatsapp", recipient, body, requestId, 10_000L)
            .consume("sms", recipient, body, requestId, 11_000L))
        assertFalse(MessageSendApproval.afterExplicitConfirmation("whatsapp", recipient, body, requestId, 10_000L)
            .consume("whatsapp", "+14155552672", body, requestId, 11_000L))
        assertFalse(MessageSendApproval.afterExplicitConfirmation("whatsapp", recipient, body, requestId, 10_000L)
            .consume("whatsapp", recipient, body + " changed", requestId, 11_000L))
    }

    @Test fun expiredOrFutureApprovalIsRejected() {
        assertFalse(MessageSendApproval.afterExplicitConfirmation("sms", recipient, body, requestId, 10_000L)
            .consume("sms", recipient, body, requestId, 130_001L))
        assertFalse(MessageSendApproval.afterExplicitConfirmation("sms", recipient, body, requestId, 10_000L)
            .consume("sms", recipient, body, requestId, 9_999L))
    }

    @Test fun invalidApprovalInputsAreRejected() {
        assertTrue(runCatching { MessageSendApproval.afterExplicitConfirmation("email", recipient, body, requestId) }.isFailure)
        assertTrue(runCatching { MessageSendApproval.afterExplicitConfirmation("sms", "123", body, requestId) }.isFailure)
        assertTrue(runCatching { MessageSendApproval.afterExplicitConfirmation("sms", recipient, "", requestId) }.isFailure)
    }
}
