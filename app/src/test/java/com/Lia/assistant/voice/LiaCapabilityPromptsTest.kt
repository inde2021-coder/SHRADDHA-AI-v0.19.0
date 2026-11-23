package com.Lia.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiaCapabilityPromptsTest {
    @Test
    fun usesConfiguredAssistantNameAndStatesRealToolLimits() {
        val prompt = LiaCapabilityPrompts.toolInstruction("My Custom Assistant")
        assertTrue(prompt.contains("My Custom Assistant"))
        assertTrue(prompt.contains("WhatsApp"))
        assertTrue(prompt.contains("SEND_MESSAGE only"))
        assertTrue(prompt.contains("does not contact independent external agents"))
        assertTrue(prompt.contains("not a deployed, publicly hosted website"))
        assertFalse(prompt.contains("comprehensive phone automation"))
    }
}
