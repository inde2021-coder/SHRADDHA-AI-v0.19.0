package com.Lia.assistant.ui.screens.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcCommandInterpreterTest {
    @Test fun parsesEnglishAndHindiDesktopCommands() {
        assertEquals("home", PcCommandInterpreter.parse("desktop").getOrThrow().getString("kind"))
        assertEquals("home", PcCommandInterpreter.parse("डेस्कटॉप दिखाओ").getOrThrow().getString("kind"))
        assertEquals("back", PcCommandInterpreter.parse("वापस जाओ").getOrThrow().getString("kind"))
    }

    @Test fun parsesOpenTypeScrollAndHardwareCommands() {
        assertEquals("Notepad", PcCommandInterpreter.parse("Open Notepad").getOrThrow().getString("appName"))
        assertEquals("नमस्ते", PcCommandInterpreter.parse("लिखो नमस्ते").getOrThrow().getString("text"))
        assertEquals(-5, PcCommandInterpreter.parse("scroll down").getOrThrow().getInt("amount"))
        assertEquals(40, PcCommandInterpreter.parse("volume 40").getOrThrow().getInt("percent"))
        assertEquals("hardware_status", PcCommandInterpreter.parse("PC status").getOrThrow().getString("kind"))
    }

    @Test fun parsesScreenOcrAndExactTextClickCommands() {
        assertEquals("ocr", PcCommandInterpreter.parse("read screen").getOrThrow().getString("kind"))
        assertEquals("ocr", PcCommandInterpreter.parse("स्क्रीन पढ़ो").getOrThrow().getString("kind"))
        val click = PcCommandInterpreter.parse("click text Save").getOrThrow()
        assertEquals("click_text", click.getString("kind"))
        assertEquals("Save", click.getString("text"))
        assertEquals("Submit", PcCommandInterpreter.parse("बटन दबाओ Submit").getOrThrow().getString("text"))
    }

    @Test fun parsesHindiAndEnglishFileOpenCommandsSafely() {
        val english = PcCommandInterpreter.parse("open file Documents/report.pdf").getOrThrow()
        assertEquals("file_open", english.getString("kind"))
        assertEquals("Documents/report.pdf", english.getString("path"))
        val hindi = PcCommandInterpreter.parse("फ़ाइल खोलो Documents/report.pdf").getOrThrow()
        assertEquals("file_open", hindi.getString("kind"))
        assertEquals("Documents/report.pdf", hindi.getString("path"))
        assertFalse(PcCommandInterpreter.parse("open file ../Windows/win.ini").isSuccess)
        assertFalse(PcCommandInterpreter.parse("फ़ाइल खोलो C:/Windows/win.ini").isSuccess)
        assertFalse(PcCommandInterpreter.parse("open file .ssh/key").isSuccess)
    }

    @Test fun parsesHindiAndEnglishFileSearchCommands() {
        val english = PcCommandInterpreter.parse("search files invoice").getOrThrow()
        assertEquals("file_search", english.getString("kind"))
        assertEquals("invoice", english.getString("query"))
        val hindi = PcCommandInterpreter.parse("फ़ाइल खोजो बिल").getOrThrow()
        assertEquals("file_search", hindi.getString("kind"))
        assertEquals("बिल", hindi.getString("query"))
        assertFalse(PcCommandInterpreter.parse("find files x").isSuccess)
    }

    @Test fun rejectsUnknownAndSensitiveCommands() {
        assertFalse(PcCommandInterpreter.parse("do anything").isSuccess)
        assertFalse(PcCommandInterpreter.parse("type my password 123").isSuccess)
        assertFalse(PcCommandInterpreter.parse("volume 140").isSuccess)
    }

    @Test fun onlyProducesProtocolAllowlistedActions() {
        val kinds = listOf("home", "back", "scroll up", "open Calculator", "type hello", "volume 20")
            .map { PcCommandInterpreter.parse(it).getOrThrow().getString("kind") }
        assertTrue(kinds.all { it in setOf("home", "back", "scroll", "open_app", "type", "set_volume") })
    }
}
