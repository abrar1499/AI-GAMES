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
}
