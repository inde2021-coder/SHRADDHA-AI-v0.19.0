package com.Lia.assistant.action

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageApprovalPolicyTest {
    @Test fun requiresExplicitApproval() {
        assertFalse(MessageApprovalPolicy.isApproved("+919876543210", "Hello", false, "+919876543210", "Hello"))
    }

    @Test fun acceptsOnlyExactRecipientAndMessage() {
        assertTrue(MessageApprovalPolicy.isApproved("+919876543210", "Hello", true, "+919876543210", "Hello"))
        assertFalse(MessageApprovalPolicy.isApproved("+919876543211", "Hello", true, "+919876543210", "Hello"))
        assertFalse(MessageApprovalPolicy.isApproved("+919876543210", "Hello!", true, "+919876543210", "Hello"))
    }

    @Test fun rejectsBlankApprovalData() {
        assertFalse(MessageApprovalPolicy.isApproved("", "Hello", true, "", "Hello"))
        assertFalse(MessageApprovalPolicy.isApproved("+919876543210", "", true, "+919876543210", ""))
    }
}
