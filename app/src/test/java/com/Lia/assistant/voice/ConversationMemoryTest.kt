package com.Lia.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryTest {
    @Test
    fun recordsOnlyNonBlankTurnsAndBuildsRoleAwareRecap() {
        ConversationMemory.clear()
        ConversationMemory.recordTurn(MemoryRole.USER, "  नमस्ते  ")
        ConversationMemory.recordTurn(MemoryRole.ASSISTANT, "नमस्ते, कैसे मदद करूँ?")
        ConversationMemory.recordTurn(MemoryRole.USER, "   ")

        assertEquals(2, ConversationMemory.size())
        val recap = ConversationMemory.recap()
        assertTrue(recap!!.contains("User: नमस्ते"))
        assertTrue(recap.contains("Assistant: नमस्ते, कैसे मदद करूँ?"))
        ConversationMemory.clear()
    }

    @Test
    fun retainsOnlyMostRecentTwelveTurns() {
        ConversationMemory.clear()
        repeat(15) { ConversationMemory.recordTurn(MemoryRole.USER, "turn-$it") }

        assertEquals(12, ConversationMemory.size())
        val recap = ConversationMemory.recap()!!
        assertFalse(recap.contains("turn-0"))
        assertFalse(recap.contains("turn-2"))
        assertTrue(recap.contains("turn-3"))
        assertTrue(recap.contains("turn-14"))
        ConversationMemory.clear()
    }

    @Test
    fun clearRemovesSessionConversation() {
        ConversationMemory.clear()
        ConversationMemory.recordTurn(MemoryRole.USER, "temporary")
        ConversationMemory.clear()

        assertEquals(0, ConversationMemory.size())
        assertNull(ConversationMemory.recap())
    }
}
