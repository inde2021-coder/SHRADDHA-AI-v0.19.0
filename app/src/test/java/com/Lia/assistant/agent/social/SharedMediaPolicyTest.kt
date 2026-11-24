package com.Lia.assistant.agent.social

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMediaPolicyTest {
    @Test fun acceptsImageAndVideoMimeTypes() {
        assertTrue(SharedMediaPolicy.supports("image/jpeg"))
        assertTrue(SharedMediaPolicy.supports("video/mp4"))
        assertTrue(SharedMediaPolicy.supports("image/png; charset=binary"))
    }

    @Test fun rejectsNonMediaAndMissingMimeTypes() {
        assertFalse(SharedMediaPolicy.supports("text/plain"))
        assertFalse(SharedMediaPolicy.supports("application/pdf"))
        assertFalse(SharedMediaPolicy.supports(null))
    }

    @Test fun enforcesNonEmptyFiftyMegabyteLimit() {
        assertFalse(SharedMediaPolicy.isAllowedSize(0))
        assertTrue(SharedMediaPolicy.isAllowedSize(1))
        assertTrue(SharedMediaPolicy.isAllowedSize(SharedMediaPolicy.MAX_FILE_BYTES))
        assertFalse(SharedMediaPolicy.isAllowedSize(SharedMediaPolicy.MAX_FILE_BYTES + 1))
    }

    @Test fun mapsKnownMimeTypesToSafeExtensions() {
        assertTrue(SharedMediaPolicy.extensionFor("image/jpeg") == ".jpg")
        assertTrue(SharedMediaPolicy.extensionFor("video/mp4") == ".mp4")
    }
}
