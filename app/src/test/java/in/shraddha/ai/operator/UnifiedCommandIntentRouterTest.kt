package `in`.shraddha.ai.operator

import org.junit.Assert.*
import org.junit.Test

class UnifiedCommandIntentRouterTest {
    @Test fun classifiesHindiAkkpsSubmitWithoutExecutingIt() {
        val result = UnifiedCommandIntentRouter.classify("AKKPS में किसान का रिकॉर्ड submit करो")
        assertEquals(UnifiedCommandDomain.AKKPS, result.domain)
        assertEquals(UnifiedCommandAction.SUBMIT, result.action)
        assertFalse(result.needsClarification)
        assertFalse(result.requiresHighImpactApproval)
        assertEquals("AKKPS में किसान का रिकॉर्ड submit करो", result.originalCommand)
    }

    @Test fun classifiesEnglishPcFileDownload() {
        val result = UnifiedCommandIntentRouter.classify("Download the file on my Windows PC")
        assertEquals(UnifiedCommandDomain.PC, result.domain)
        assertEquals(UnifiedCommandAction.DOWNLOAD, result.action)
        assertFalse(result.needsClarification)
    }

    @Test fun marksMissingDomainOrActionForClarification() {
        val noDomain = UnifiedCommandIntentRouter.classify("send this now")
        assertEquals(UnifiedCommandDomain.UNKNOWN, noDomain.domain)
        assertEquals(UnifiedCommandAction.SEND, noDomain.action)
        assertTrue(noDomain.needsClarification)

        val noAction = UnifiedCommandIntentRouter.classify("open WhatsApp")
        assertEquals(UnifiedCommandDomain.WHATSAPP, noAction.domain)
        assertEquals(UnifiedCommandAction.OPEN, noAction.action)
        assertFalse(noAction.needsClarification)
    }

    @Test fun explicitAppDomainWinsOverGenericPhoneChannel() {
        val result = UnifiedCommandIntentRouter.classify("call on WhatsApp")
        assertEquals(UnifiedCommandDomain.WHATSAPP, result.domain)
        assertEquals(UnifiedCommandAction.CALL, result.action)
        assertFalse(result.needsClarification)
    }

    @Test fun HindiActionTermsRequireUnicodeTokenBoundaries() {
        val embeddedVerb = UnifiedCommandIntentRouter.classify("अभेजें")
        assertEquals(UnifiedCommandAction.UNKNOWN, embeddedVerb.action)
        assertTrue(embeddedVerb.needsClarification)
        val combiningMarkPrefix = UnifiedCommandIntentRouter.classify("ाभेजें")
        assertEquals(UnifiedCommandAction.UNKNOWN, combiningMarkPrefix.action)

        val standaloneVerb = UnifiedCommandIntentRouter.classify("AKKPS में रिकॉर्ड भेजें")
        assertEquals(UnifiedCommandAction.SEND, standaloneVerb.action)
        assertEquals(UnifiedCommandDomain.AKKPS, standaloneVerb.domain)
    }

    @Test fun highImpactActionsAreFlaggedButNeverAutoApproved() {
        val delete = UnifiedCommandIntentRouter.classify("delete this farmer record in AKKPS")
        assertTrue(delete.requiresHighImpactApproval)
        assertTrue(delete.needsClarification.not())

        val pay = UnifiedCommandIntentRouter.classify("pay this invoice from phone")
        assertTrue(pay.requiresHighImpactApproval)
    }

    @Test fun emptyAndOversizedCommandsRemainUnknown() {
        assertTrue(UnifiedCommandIntentRouter.classify(" ").needsClarification)
        assertEquals(
            UnifiedCommandAction.UNKNOWN,
            UnifiedCommandIntentRouter.classify("x".repeat(4001)).action
        )
    }
}
