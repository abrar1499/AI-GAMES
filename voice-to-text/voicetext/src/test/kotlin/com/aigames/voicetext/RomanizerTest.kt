package com.aigames.voicetext

import org.junit.Assert.assertEquals
import org.junit.Test

class RomanizerTest {

    private fun check(devanagari: String, roman: String) = assertEquals(roman, Romanizer.toRoman(devanagari))

    @Test
    fun `everyday sentences`() {
        check("आप कैसे हैं", "aap kaise hain")
        check("मेरा नाम अली है", "mera naam ali hai")
        check("मैं ठीक हूँ शुक्रिया", "main theek hoon shukriya")
        check("यह क्या है", "yeh kya hai")
        check("मुझे पानी चाहिए", "mujhe pani chahie")
        check("आज बहुत गर्मी है", "aaj bohat garmi hai")
        check("वह कल आएगा", "woh kal aega")
    }

    @Test
    fun `the silent a at the end of a word is dropped`() {
        check("नाम", "naam")
        check("काम", "kaam")
        check("घर", "ghar")
        check("किताब", "kitab")
        check("औरत", "aurat")
        check("बादल", "badal")
    }

    @Test
    fun `the silent a in the middle of a word is dropped`() {
        check("करना", "karna")
        check("लड़का", "larka")
        check("समझना", "samajhna")
        check("मतलब", "matlab")
        check("नमकीन", "namkeen")
        check("कमरे", "kamre")
        check("हमारा", "hamara")
    }

    @Test
    fun `letters with a dot use the Urdu sound`() {
        check("ज़रूर", "zaroor")
        check("फ़ोन", "fon")
        check("क़लम", "qalam")
        check("ग़लत", "ghalat")
        // The same letters when the dot is a separate character.
        check("ज़रूर", "zaroor")
    }

    @Test
    fun `joined consonants`() {
        check("पाकिस्तान", "pakistan")
        check("दोस्त", "dost")
        check("स्कूल", "skool")
        check("प्यार", "pyaar")
        check("ज़िन्दगी", "zindagi")
    }

    @Test
    fun `nasal sounds`() {
        check("कहाँ", "kahan")
        check("माँ", "maan")
        check("लंबा", "lamba")
        check("अंदर", "andar")
    }

    @Test
    fun `numbers punctuation and other text are kept`() {
        check("१२३ रुपये।", "123 rupye.")
        check("Hello दोस्त!", "Hello dost!")
        check("already roman", "already roman")
        check("", "")
    }
}
