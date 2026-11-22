package com.lumo.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the phone hears after the 🤙 sign, and which call it starts. */
class CallSpeechTest {
    @Test fun english() {
        assertEquals(true, callKindFromSpeech("Video call"))
        assertEquals(false, callKindFromSpeech("audio call please"))
        assertEquals(false, callKindFromSpeech("voice"))
    }
    @Test fun romanUrdu() {
        assertEquals(false, callKindFromSpeech("awaz wali call karo"))
        assertEquals(true, callKindFromSpeech("vedio lagao"))
    }
    @Test fun urduAndHindiScript() {
        assertEquals(true, callKindFromSpeech("ویڈیو کال"))
        assertEquals(false, callKindFromSpeech("آڈیو"))
        assertEquals(true, callKindFromSpeech("वीडियो कॉल"))
        assertEquals(false, callKindFromSpeech("ऑडियो"))
    }
    @Test fun otherWordsDoNothing() {
        assertEquals(null, callKindFromSpeech("call"))
        assertEquals(null, callKindFromSpeech("hello how are you"))
    }
}
