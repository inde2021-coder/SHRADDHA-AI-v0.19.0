package com.Lia.assistant.action

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialPublishApprovalPolicyTest {
    @Test fun supportsOnlyConfiguredTextPlatforms() {
        listOf("telegram", "mastodon", "bluesky", "linkedin").forEach { platform ->
            assertNull(SocialPublishApprovalPolicy.validate(platform, "TEXT_POST", "Hello"))
        }
        assertTrue(SocialPublishApprovalPolicy.validate("instagram", "TEXT_POST", "Hello") != null)
    }

    @Test fun appliesPlatformSpecificCharacterLimitsByUnicodeCodePoint() {
        assertNull(SocialPublishApprovalPolicy.validate("bluesky", "TEXT_POST", "😀".repeat(300)))
        assertTrue(SocialPublishApprovalPolicy.validate("bluesky", "TEXT_POST", "😀".repeat(301)) != null)
        assertNull(SocialPublishApprovalPolicy.validate("linkedin", "CREATE_POST", "x".repeat(3000)))
        assertTrue(SocialPublishApprovalPolicy.validate("linkedin", "CREATE_POST", "x".repeat(3001)) != null)
        assertNull(SocialPublishApprovalPolicy.validate("mastodon", "TEXT_POST", "x".repeat(5000)))
        assertTrue(SocialPublishApprovalPolicy.validate("telegram", "TEXT_POST", "x".repeat(5001)) != null)
    }

    @Test fun rejectsMediaAndUnsupportedActions() {
        assertTrue(SocialPublishApprovalPolicy.validate("telegram", "CREATE_REEL", "caption") != null)
        assertTrue(SocialPublishApprovalPolicy.validate("telegram", "CREATE_STORY", "caption") != null)
        assertTrue(SocialPublishApprovalPolicy.validate("telegram", "TEXT_POST", "  ") != null)
    }

    @Test fun approvalMustMatchExactPlatformAndExactText() {
        assertTrue(SocialPublishApprovalPolicy.isApproved("telegram", "Hello world", true, "TELEGRAM", "Hello world"))
        assertFalse(SocialPublishApprovalPolicy.isApproved("telegram", "Hello world", false, "telegram", "Hello world"))
        assertFalse(SocialPublishApprovalPolicy.isApproved("telegram", "Hello world", true, "mastodon", "Hello world"))
        assertFalse(SocialPublishApprovalPolicy.isApproved("telegram", "Hello world", true, "telegram", "Hello"))
        assertFalse(SocialPublishApprovalPolicy.isApproved("telegram", "Hello world", true, "telegram", "Hello world "))
    }
}
