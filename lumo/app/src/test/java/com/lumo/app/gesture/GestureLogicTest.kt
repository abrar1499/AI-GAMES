package com.lumo.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks the rules that decide when a hand sign is sent, frame by frame (~30 fps). */
class GestureLogicTest {
    // A neutral hand: wrist at the bottom, fingers pointing up.
    private fun hand(dx: Float = 0f, okPinch: Boolean = false): List<Pair<Float, Float>> {
        val p = MutableList(21) { 0.5f + dx to 0.5f }
        p[0] = 0.5f + dx to 0.9f                       // wrist
        p[9] = 0.5f + dx to 0.6f                       // middle knuckle
        p[4] = 0.40f + dx to 0.55f; p[8] = 0.45f + dx to 0.40f // thumb tip, index tip
        if (okPinch) p[8] = 0.41f + dx to 0.55f       // index tip touches thumb tip
        // middle, ring, pinky: pip then tip further from the wrist (stretched out)
        p[10] = 0.5f + dx to 0.5f; p[12] = 0.5f + dx to 0.3f
        p[14] = 0.55f + dx to 0.52f; p[16] = 0.56f + dx to 0.33f
        p[18] = 0.6f + dx to 0.55f; p[20] = 0.62f + dx to 0.4f
        return p
    }

    private fun run(logic: GestureLogic, frames: List<HandFrame?>, startMs: Long = 0): List<Gesture> {
        val out = mutableListOf<Gesture>()
        frames.forEachIndexed { i, f -> logic.onFrame(f, startMs + i * 33L)?.let { out += it } }
        return out
    }

    @Test fun heldThumbsUpSendsOnce() {
        val frames = List(60) { HandFrame("Thumb_Up", 0.9f, hand()) } // 2 seconds held
        assertEquals(listOf(Gesture.ThumbsUp), run(GestureLogic(), frames))
    }

    @Test fun briefFlashSendsNothing() {
        val frames = List(5) { HandFrame("Thumb_Up", 0.9f, hand()) } + List(10) { null }
        assertEquals(emptyList<Gesture>(), run(GestureLogic(), frames))
    }

    @Test fun lowConfidenceIgnored() {
        val frames = List(40) { HandFrame("Victory", 0.4f, hand()) }
        assertEquals(emptyList<Gesture>(), run(GestureLogic(), frames))
    }

    @Test fun sameSignAgainAfterHandLeaves() {
        val up = List(30) { HandFrame("Thumb_Up", 0.9f, hand()) }
        val gone = List(60) { null }
        assertEquals(listOf(Gesture.ThumbsUp, Gesture.ThumbsUp), run(GestureLogic(), up + gone + up))
    }

    @Test fun okSignFromFingerPositions() {
        val frames = List(30) { HandFrame("None", 0.8f, hand(okPinch = true)) }
        assertEquals(listOf(Gesture.Ok), run(GestureLogic(), frames))
    }

    @Test fun waveIsDetected() {
        // Palm swinging left and right by 0.12 every ~200 ms.
        val frames = List(40) { i -> HandFrame("Open_Palm", 0.9f, hand(dx = if ((i / 6) % 2 == 0) 0f else 0.12f)) }
        assertEquals(listOf(Gesture.Wave), run(GestureLogic(), frames))
    }

    @Test fun stillOpenPalmIsNotAWave() {
        val frames = List(60) { HandFrame("Open_Palm", 0.9f, hand()) }
        assertEquals(emptyList<Gesture>(), run(GestureLogic(), frames))
    }

    @Test fun noHandNoGesture() {
        assertNull(GestureLogic().onFrame(null, 0))
    }
}
