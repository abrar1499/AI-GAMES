package com.aigames.voicewhisper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {

    @Test
    fun `chunks are joined and scaled to minus one to one`() {
        val out = AudioRecorder.toFloats(listOf(shortArrayOf(0, 16384), shortArrayOf(-32768, 32767)), 4)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f, 32767 / 32768f), out, 1e-6f)
    }

    @Test
    fun `joining stops at the stated total`() {
        val out = AudioRecorder.toFloats(listOf(shortArrayOf(100, 200, 300)), 2)
        assertEquals(2, out.size)
    }

    @Test
    fun `silence is quiet and a loud tone is loud`() {
        assertEquals(0f, AudioRecorder.loudness(ShortArray(100), 100), 0f)
        assertEquals(0f, AudioRecorder.loudness(ShortArray(100), 0), 0f)
        assertTrue(AudioRecorder.loudness(ShortArray(100) { 20000 }, 100) > 0.9f)
    }

    @Test
    fun `language tags become two letter codes`() {
        assertEquals("ur", WhisperText.languageCode("ur-PK"))
        assertEquals("en", WhisperText.languageCode("en_US"))
        assertEquals("hi", WhisperText.languageCode("HI"))
    }

    @Test
    fun `bracketed noises are removed from the text`() {
        assertEquals("", WhisperText.clean(" [BLANK_AUDIO] "))
        assertEquals("hello there", WhisperText.clean(" hello  (music) there\n"))
        assertEquals("aap kaise hain", WhisperText.clean(" aap kaise hain"))
    }
}
