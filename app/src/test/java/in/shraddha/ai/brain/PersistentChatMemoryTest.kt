package `in`.shraddha.ai.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentChatMemoryTest {
    @Test fun recapKeepsOnlyTwelveMostRecentTurns() {
        var recap = ""
        repeat(15) { recap = PersistentChatMemory.mergeRecap(recap, "User", "turn-$it") }
        val lines = recap.lines()
        assertEquals(12, lines.size)
        assertFalse(recap.contains("turn-2"))
        assertTrue(recap.contains("turn-3"))
        assertTrue(recap.contains("turn-14"))
    }

    @Test fun recapTruncatesEachTurnAndCapsTotalPayload() {
        val recap = PersistentChatMemory.mergeRecap("", "Assistant", "x".repeat(4_000))
        assertEquals(1_000, recap.substringAfter(": ").length)
        var many = ""
        repeat(12) { many = PersistentChatMemory.mergeRecap(many, "User", "y".repeat(1_000)) }
        assertTrue(many.length <= 12_000)
    }

    @Test fun recapPreservesRoleAndSanitizesWhitespace() {
        val recap = PersistentChatMemory.mergeRecap("User: hello", "Assistant", "  answer  ")
        assertEquals("User: hello\nAssistant: answer", recap)
    }
}
