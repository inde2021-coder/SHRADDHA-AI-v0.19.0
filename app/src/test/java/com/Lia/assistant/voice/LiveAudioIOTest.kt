package com.Lia.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioIOTest {

    @Test
    fun testRmsEmptyAndOddLengthBuffers() {
        assertEquals(0f, LiveAudioIO.calculateRms(byteArrayOf()), 0f)
        assertEquals(0f, LiveAudioIO.calculateRms(byteArrayOf(1)), 0f)
    }

    @Test
    fun testRmsSilence() {
        val silence = ByteArray(2048) { 0 }
        val rms = LiveAudioIO.calculateRms(silence)
        assertEquals(0f, rms, 0.0001f)
    }

    @Test
    fun testRmsNegativeFullScale() {
        val negativeFullScale = ByteArray(2048)
        for (i in 0 until 1024) {
            negativeFullScale[i * 2] = 0x00
            negativeFullScale[i * 2 + 1] = 0x80.toByte()
        }
        assertEquals(1f, LiveAudioIO.calculateRms(negativeFullScale), 0.0001f)
    }

    @Test
    fun testRmsMaxAmplitude() {
        val maxWave = ByteArray(2048)
        for (i in 0 until 1024) {
            // 32767 in little endian: 0xFF, 0x7F
            maxWave[i * 2] = 0xFF.toByte()
            maxWave[i * 2 + 1] = 0x7F.toByte()
        }
        val rms = LiveAudioIO.calculateRms(maxWave)
        assertTrue("RMS should be close to 1.0", rms > 0.99f)
    }
}
