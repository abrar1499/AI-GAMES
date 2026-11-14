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

    @Test fun liveLinesSayNumbersAsWords() {
        assertEquals("Petrol ke ek hazaar paanch sau rupay likh liye.", LiveVoice.spokenNumbers("Petrol ke 1,500 rupay likh liye."))
        assertEquals("पेट्रोल के एक हज़ार पांच सौ रुपये लिख लिए।", Devanagari.fromRoman(LiveVoice.spokenNumbers("Petrol ke 1,500 rupay likh liye.")))
    }

    @Test fun googleWavGivesUpItsSound() {
        val wav = wav(ShortArray(50) { (it * 3).toShort() }, 16000)
        val pcm = GoogleVoice.pcmOf(wav)
        assertEquals(100, pcm.size)
        assertEquals(3, java.nio.ByteBuffer.wrap(pcm, 2, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt())
        assertEquals("Turn on the Text-to-Speech API in Google Cloud", GoogleVoice.explain(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"details\":[{\"reason\":\"SERVICE_DISABLED\"}]}}"))
    }

    private fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}
