package com.aigames.voicetext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionControllerTest {

    /** Records what the controller asks the engine to do. */
    private class FakeEngine : SpeechEngine {
        override var events: SpeechEngine.Events? = null
        var starts = 0
        var stops = 0
        var cancels = 0
        var resets = 0
        var lastLanguage = ""

        override fun start(language: String, preferOffline: Boolean) {
            starts++
            lastLanguage = language
        }

        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun reset() { resets++ }
    }

    private lateinit var engine: FakeEngine
    private lateinit var session: SessionController
    private val delayed = mutableListOf<() -> Unit>()
    private val finals = mutableListOf<String>()
    private val partials = mutableListOf<String>()
    private val errors = mutableListOf<VoiceError>()
    private val listening = mutableListOf<Boolean>()

    private fun runDelayed() {
        val now = delayed.toList()
        delayed.clear()
        now.forEach { it() }
    }

    @Before
    fun setUp() {
        engine = FakeEngine()
        session = SessionController(engine) { _, block -> delayed.add(block) }
        session.onFinal = { finals.add(it) }
        session.onPartial = { partials.add(it) }
        session.onError = { errors.add(it) }
        session.onListeningChanged = { listening.add(it) }
    }

    @Test
    fun `one sentence is delivered and listening ends`() {
        session.language = "ur-PK"
        session.start()
        engine.events!!.onReady()
        engine.events!!.onPartial("assalam")
        engine.events!!.onResult(" assalam o alaikum ")

        assertEquals("ur-PK", engine.lastLanguage)
        assertEquals(listOf("assalam"), partials)
        assertEquals(listOf("assalam o alaikum"), finals)
        assertEquals(listOf(true, false), listening)
        assertFalse(session.isListening)
        runDelayed()
        assertEquals(1, engine.starts)
    }

    @Test
    fun `continuous mode listens again after each sentence`() {
        session.continuous = true
        session.start()
        engine.events!!.onResult("first")
        runDelayed()
        engine.events!!.onResult("second")
        runDelayed()

        assertEquals(listOf("first", "second"), finals)
        assertEquals(3, engine.starts)
        assertTrue(session.isListening)
        assertEquals(listOf(true), listening)
    }

    @Test
    fun `silence in continuous mode keeps listening without an error`() {
        session.continuous = true
        session.start()
        engine.events!!.onFailure(VoiceError.NOTHING_HEARD)
        runDelayed()

        assertTrue(errors.isEmpty())
        assertEquals(2, engine.starts)
        assertTrue(session.isListening)
    }

    @Test
    fun `silence in single mode reports nothing heard`() {
        session.start()
        engine.events!!.onFailure(VoiceError.NOTHING_HEARD)

        assertEquals(listOf(VoiceError.NOTHING_HEARD), errors)
        assertFalse(session.isListening)
    }

    @Test
    fun `stop still delivers the sentence but does not restart`() {
        session.continuous = true
        session.start()
        session.stop()
        assertEquals(1, engine.stops)
        assertTrue(session.isListening)

        engine.events!!.onResult("last words")
        runDelayed()

        assertEquals(listOf("last words"), finals)
        assertEquals(1, engine.starts)
        assertFalse(session.isListening)
    }

    @Test
    fun `an error after stop is not shown`() {
        session.start()
        session.stop()
        engine.events!!.onFailure(VoiceError.NOTHING_HEARD)

        assertTrue(errors.isEmpty())
        assertFalse(session.isListening)
    }

    @Test
    fun `cancel ignores anything that arrives late`() {
        session.continuous = true
        session.start()
        session.cancel()
        engine.events!!.onPartial("late")
        engine.events!!.onResult("late result")
        engine.events!!.onFailure(VoiceError.SERVER)
        runDelayed()

        assertEquals(1, engine.cancels)
        assertTrue(finals.isEmpty() && partials.isEmpty() && errors.isEmpty())
        assertEquals(1, engine.starts)
        assertEquals(listOf(true, false), listening)
    }

    @Test
    fun `a busy recognizer is retried a few times and then reported`() {
        session.start()
        repeat(SessionController.MAX_BUSY_RETRIES) {
            engine.events!!.onFailure(VoiceError.BUSY)
            runDelayed()
        }
        assertTrue(errors.isEmpty())
        assertEquals(1 + SessionController.MAX_BUSY_RETRIES, engine.starts)

        engine.events!!.onFailure(VoiceError.BUSY)
        assertEquals(listOf(VoiceError.BUSY), errors)
        assertFalse(session.isListening)
    }

    @Test
    fun `a real error ends continuous listening`() {
        session.continuous = true
        session.start()
        engine.events!!.onFailure(VoiceError.NETWORK)
        runDelayed()

        assertEquals(listOf(VoiceError.NETWORK), errors)
        assertEquals(1, engine.starts)
        assertFalse(session.isListening)
    }

    @Test
    fun `a restart that was waiting is dropped when the user stops`() {
        session.continuous = true
        session.start()
        engine.events!!.onResult("one")
        session.stop()
        runDelayed()

        assertEquals(1, engine.starts)
        assertFalse(session.isListening)
    }

    @Test
    fun `a phone that never answers stop still ends listening`() {
        session.start()
        session.stop()
        assertTrue(session.isListening)
        runDelayed()

        assertFalse(session.isListening)
        assertEquals(1, engine.cancels)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `the stop timeout does nothing once the result has arrived`() {
        session.start()
        session.stop()
        engine.events!!.onResult("done")
        session.start()
        runDelayed()

        assertTrue(session.isListening)
        assertEquals(0, engine.cancels)
    }

    @Test
    fun `can start again after an error`() {
        session.start()
        engine.events!!.onFailure(VoiceError.NETWORK)
        session.start()
        engine.events!!.onResult("hello")

        assertEquals(2, engine.starts)
        assertEquals(listOf("hello"), finals)
    }
}
