package com.boltamunshi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyVoiceTest {
    private val v = MyVoice

    @Test fun numbersAsUrduWords() {
        assertEquals("paanch sau", v.numberWords("500"))
        assertEquals("ek hazaar paanch sau", v.numberWords("1,500"))
        assertEquals("do lakh pachaas hazaar", v.numberWords("250000"))
        assertEquals("ek crore", v.numberWords("10000000"))
        assertEquals("pachattar", v.numberWords("75"))
        assertEquals("do point paanch", v.numberWords("2.5"))
    }

    @Test fun longestRecordingWins() {
        val have = setOf("petrol", "ke", "paanch sau", "rupay likh liye", "rupay", "likh liye")
        val p = v.plan("Petrol ke 500 rupay likh liye.", have)
        assertEquals(listOf(MyVoice.Part.Clip("petrol"), MyVoice.Part.Clip("ke"), MyVoice.Part.Clip("paanch sau"), MyVoice.Part.Clip("rupay likh liye")), p.parts)
        assertEquals(1.0, p.covered, 0.001)
        assertTrue(p.usable)
    }

    @Test fun unrecordedWordsGoToThePhoneVoice() {
        val have = setOf("ko", "rupay diye", "likh liya", "ek hazaar")
        val p = v.plan("Ahmed ko 1000 rupay diye, likh liya.", have)
        assertEquals(MyVoice.Part.Say("ahmed"), p.parts[0])
        assertEquals(MyVoice.Part.Pause(160), p.parts[4])
        assertEquals(listOf("ahmed"), p.missing)
        assertTrue(p.usable)
    }

    @Test fun tooLittleRecordedKeepsThePhoneVoice() {
        val p = v.plan("Aaj aap ne bijli ka bill diya tha.", setOf("aaj"))
        assertFalse(p.usable)
    }

    @Test fun silenceIsCutAndQuietIsEmpty() {
        val rate = 16000
        val quiet = ShortArray(rate) { ((it % 7) - 3).toShort() }
        assertEquals(0, v.clean(quiet, rate).size)
        val talk = ShortArray(rate * 2) { i -> if (i in rate / 2 until rate) (6000 * kotlin.math.sin(i / 5.0)).toInt().toShort() else ((i % 7) - 3).toShort() }
        val out = v.clean(talk, rate)
        assertTrue(out.size in rate / 2..rate / 2 + rate / 5)
    }

    @Test fun phraseFileNamesRoundTrip() {
        assertEquals("rupay likh liye", v.keyOf(v.fileName("rupay likh liye")))
        assertTrue(v.PHRASES.size > 150)
    }

    @Test fun joinedWordsHaveNoGap() {
        val rate = 16000
        fun word(len: Int) = ShortArray(rate / 10 + len + rate / 10) { i -> if (i in rate / 10 until rate / 10 + len) (5000 * kotlin.math.sin(i / 4.0)).toInt().toShort() else 0 }
        val clips = mapOf("a" to word(rate / 4), "b" to word(rate / 4))
        val out = v.join(listOf(MyVoice.Part.Clip("a"), MyVoice.Part.Clip("b")), { clips[it] }, rate)
        // two quarter-second words with almost nothing between them: the silence around each is cut and they blend
        assertTrue(out.size < rate / 2 + rate / 10)
        assertTrue(out.size > rate / 2 - rate / 20)
        val withPause = v.join(listOf(MyVoice.Part.Clip("a"), MyVoice.Part.Pause(320), MyVoice.Part.Clip("b")), { clips[it] }, rate)
        assertTrue(withPause.size > out.size + rate * 3 / 10)
    }

    @Test fun elevenLabsGetsHindiLetters() {
        val r = ElevenLabs.request("rupay likh liye")
        assertEquals("रुपये लिख लिए", r.getString("text"))
        assertEquals(ElevenLabs.MODEL, r.getString("model_id"))
    }

    @Test fun trainingWavHasAProperHeader() {
        val b = VoiceData.wavBytes(ShortArray(100) { it.toShort() }, 22050)
        assertEquals(244, b.size)
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4)); assertEquals("data", String(b, 36, 4))
        assertEquals(22050, java.nio.ByteBuffer.wrap(b, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(5, java.nio.ByteBuffer.wrap(b, 44 + 10, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt())
    }
}
