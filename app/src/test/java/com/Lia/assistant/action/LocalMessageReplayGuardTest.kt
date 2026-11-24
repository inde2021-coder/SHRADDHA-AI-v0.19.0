package com.Lia.assistant.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMessageReplayGuardTest {
    @Test fun fingerprintsBindChannelRecipientAndExactMessage() {
        val a = LocalMessageReplayGuard.fingerprint("sms", "+919876543210", "hello")
        assertEquals(a, LocalMessageReplayGuard.fingerprint("sms", "+919876543210", "hello"))
        assertFalse(a == LocalMessageReplayGuard.fingerprint("whatsapp", "+919876543210", "hello"))
        assertFalse(a == LocalMessageReplayGuard.fingerprint("sms", "+919876543211", "hello"))
        assertFalse(a == LocalMessageReplayGuard.fingerprint("sms", "+919876543210", "Hello"))
        assertEquals(64, a.length)
    }

    @Test fun invalidRequestIdentifiersAreRejected() {
        assertFalse(LocalMessageReplayGuard.claim(android.test.mock.MockContext(), "short", "a".repeat(64)).accepted)
    }
}
