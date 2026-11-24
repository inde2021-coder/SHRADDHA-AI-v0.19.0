package `in`.shraddha.ai.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteActionReplayGuardTest {
    @Test fun acceptsFirstActionAndRejectsDuplicateForSamePeer() {
        var now = 100L
        val guard = RemoteActionReplayGuard(maxEntries = 8, ttlMs = 1_000L, nowMs = { now })
        assertTrue(guard.claim("peer-a", "action_12345678"))
        assertFalse(guard.claim("peer-a", "action_12345678"))
        assertEquals(1, guard.size())
    }

    @Test fun scopesActionIdsToPeerAndExpiresOldClaims() {
        var now = 100L
        val guard = RemoteActionReplayGuard(maxEntries = 8, ttlMs = 1_000L, nowMs = { now })
        assertTrue(guard.claim("peer-a", "action_12345678"))
        assertTrue(guard.claim("peer-b", "action_12345678"))
        now = 1_100L
        assertTrue(guard.claim("peer-a", "action_12345678"))
    }

    @Test fun rejectsMalformedPeerAndActionIds() {
        val guard = RemoteActionReplayGuard(nowMs = { 100L })
        assertFalse(guard.claim("", "action_12345678"))
        assertFalse(guard.claim("peer-a", "short"))
    }

    @Test fun evictsOldestClaimWhenCapacityIsReached() {
        val guard = RemoteActionReplayGuard(maxEntries = 1, ttlMs = 10_000L, nowMs = { 100L })
        assertTrue(guard.claim("peer-a", "action_12345678"))
        assertTrue(guard.claim("peer-b", "action_87654321"))
        assertTrue(guard.claim("peer-a", "action_12345678"))
    }
}
