package `in`.shraddha.ai.brain

import org.junit.Assert.*
import org.junit.Test

class MemorySyncPolicyTest {
    @Test fun cloudSyncRequiresExplicitConsent() {
        try {
            MemorySyncPolicy.requireCloudConsent(false)
            fail("Cloud sync must require explicit consent")
        } catch (_: IllegalArgumentException) { }
        MemorySyncPolicy.requireCloudConsent(true)
    }

    @Test fun recordKeyAndPayloadAreBounded() {
        MemorySyncPolicy.validateRecord("profile_notes", "User-approved note")
        try {
            MemorySyncPolicy.validateRecord("../private", "note")
            fail("Path-like keys must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            MemorySyncPolicy.validateRecord("profile", "")
            fail("Empty memory must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            MemorySyncPolicy.validateRecord("profile", "x".repeat(100_001))
            fail("Oversized memory must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
