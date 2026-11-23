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
    @Test fun validatesInternationalE164Numbers() {
        assertTrue(MessageApprovalPolicy.isValidE164("+919876543210"))
        assertTrue(MessageApprovalPolicy.isValidE164("+12025550123"))
        assertFalse(MessageApprovalPolicy.isValidE164("9876543210"))
        assertFalse(MessageApprovalPolicy.isValidE164("+01234567890"))
        assertFalse(MessageApprovalPolicy.isValidE164("+123"))
        assertFalse(MessageApprovalPolicy.isValidE164("+919876543210x"))
    }

}
