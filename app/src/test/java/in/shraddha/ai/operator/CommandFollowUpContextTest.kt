package `in`.shraddha.ai.operator

import org.junit.Assert.*
import org.junit.Test

class CommandFollowUpContextTest {
    @Test fun resolvesOnlyAfterExplicitSelectionForMatchingType() {
        var now = 10_000L
        val context = CommandFollowUpContext(nowMillis = { now })
        assertEquals(
            CommandReferenceResolution.MissingOrExpired,
            context.resolve("उसे भेजो", CommandReferenceType.CONTACT)
        )
        assertTrue(context.rememberExplicitSelection(CommandReferenceType.CONTACT, "contact-7", "Ravi"))
        val resolved = context.resolve("उसे भेजो", CommandReferenceType.CONTACT)
        assertTrue(resolved is CommandReferenceResolution.Resolved)
        assertEquals("contact-7", (resolved as CommandReferenceResolution.Resolved).reference.stableId)
        assertEquals(
            CommandReferenceResolution.MissingOrExpired,
            context.resolve("उसे भेजो", CommandReferenceType.FILE)
        )
    }

    @Test fun ordinaryCommandsDoNotReusePreviousReference() {
        val context = CommandFollowUpContext(nowMillis = { 20_000L })
        context.rememberExplicitSelection(CommandReferenceType.FILE, "file-1", "invoice.pdf")
        assertEquals(
            CommandReferenceResolution.NoReferenceRequested,
            context.resolve("open downloads", CommandReferenceType.FILE)
        )
    }

    @Test fun englishAndHindiFollowUpsAreRecognizedAtUnicodeWordBoundaries() {
        val context = CommandFollowUpContext(nowMillis = { 30_000L })
        context.rememberExplicitSelection(CommandReferenceType.APP, "app-1", "Calculator")
        assertTrue(context.resolve("open the same app", CommandReferenceType.APP) is CommandReferenceResolution.Resolved)
        assertTrue(context.resolve("वही खोलो", CommandReferenceType.APP) is CommandReferenceResolution.Resolved)
        assertEquals(
            CommandReferenceResolution.NoReferenceRequested,
            context.resolve("title", CommandReferenceType.APP)
        )
    }

    @Test fun expiredAndFutureSelectionsAreRejected() {
        var now = 1_000L
        val context = CommandFollowUpContext(nowMillis = { now }, ttlMillis = 2_000L)
        context.rememberExplicitSelection(CommandReferenceType.CONTACT, "c1", "A")
        now = 3_001L
        assertEquals(
            CommandReferenceResolution.MissingOrExpired,
            context.resolve("send it", CommandReferenceType.CONTACT)
        )
        context.rememberExplicitSelection(CommandReferenceType.CONTACT, "c2", "B")
        now = 500L
        assertEquals(
            CommandReferenceResolution.MissingOrExpired,
            context.resolve("send it", CommandReferenceType.CONTACT)
        )
    }

    @Test fun invalidSelectionsAndOversizedCommandsFailClosed() {
        val context = CommandFollowUpContext(nowMillis = { 5_000L })
        assertFalse(context.rememberExplicitSelection(CommandReferenceType.FILE, " ", "file"))
        assertFalse(context.rememberExplicitSelection(CommandReferenceType.FILE, "id", " "))
        assertEquals(
            CommandReferenceResolution.NoReferenceRequested,
            context.resolve("उसे भेजो".repeat(300), CommandReferenceType.FILE)
        )
    }

    @Test fun invalidClockValuesCannotCreateOrResolveReferences() {
        var now = 0L
        val context = CommandFollowUpContext(nowMillis = { now })
        assertFalse(context.rememberExplicitSelection(CommandReferenceType.CONTACT, "c1", "Ravi"))

        now = 10_000L
        assertTrue(context.rememberExplicitSelection(CommandReferenceType.CONTACT, "c2", "Ravi"))
        now = Long.MIN_VALUE
        assertEquals(
            CommandReferenceResolution.MissingOrExpired,
            context.resolve("send it", CommandReferenceType.CONTACT)
        )
        context.clearExpired()
        now = 20_000L
        assertEquals(
            CommandReferenceResolution.MissingOrExpired,
            context.resolve("send it", CommandReferenceType.CONTACT)
        )
    }

    @Test fun clearRemovesOnlyRequestedTypeOrEverything() {
        val context = CommandFollowUpContext(nowMillis = { 6_000L })
        context.rememberExplicitSelection(CommandReferenceType.CONTACT, "c1", "Ravi")
        context.rememberExplicitSelection(CommandReferenceType.FILE, "f1", "report.pdf")
        context.clear(CommandReferenceType.CONTACT)
        assertEquals(CommandReferenceResolution.MissingOrExpired, context.resolve("send it", CommandReferenceType.CONTACT))
        assertTrue(context.resolve("open same file", CommandReferenceType.FILE) is CommandReferenceResolution.Resolved)
        context.clear()
        assertEquals(CommandReferenceResolution.MissingOrExpired, context.resolve("open same file", CommandReferenceType.FILE))
    }
}
