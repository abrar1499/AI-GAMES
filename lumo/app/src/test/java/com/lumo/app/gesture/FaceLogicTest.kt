package com.lumo.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/** Face expressions, frame by frame (~15 face frames a second). */
class FaceLogicTest {
    private val neutral = mapOf("mouthSmileLeft" to 0.05f, "mouthSmileRight" to 0.05f, "jawOpen" to 0.05f)
    private val talking = mapOf("jawOpen" to 0.3f, "mouthSmileLeft" to 0.2f, "mouthSmileRight" to 0.2f)
    private val laugh = mapOf("mouthSmileLeft" to 0.8f, "mouthSmileRight" to 0.85f, "jawOpen" to 0.5f)
    private val smile = mapOf("mouthSmileLeft" to 0.9f, "mouthSmileRight" to 0.88f, "jawOpen" to 0.05f)
    private val wink = mapOf("eyeBlinkLeft" to 0.9f, "eyeBlinkRight" to 0.05f)
    private val blink = mapOf("eyeBlinkLeft" to 0.9f, "eyeBlinkRight" to 0.9f)
    private val kiss = mapOf("mouthPucker" to 0.9f)
    private val wow = mapOf("jawOpen" to 0.7f, "browInnerUp" to 0.7f)

    private fun run(logic: FaceLogic, frames: List<Map<String, Float>?>): List<FaceSign> {
        val out = mutableListOf<FaceSign>()
        frames.forEachIndexed { i, f -> logic.onFrame(f, i * 66L)?.let { out += it } }
        return out
    }
    private fun hold(f: Map<String, Float>, ms: Long) = List((ms / 66).toInt()) { f }

    @Test fun restingAndTalkingSendNothing() =
        assertEquals(emptyList<FaceSign>(), run(FaceLogic(), hold(neutral, 3000) + hold(talking, 3000)))

    @Test fun laughSendsOnce() = assertEquals(listOf(FaceSign.Laugh), run(FaceLogic(), hold(laugh, 2000)))

    @Test fun shortSmileIgnoredHeldSmileSent() {
        assertEquals(emptyList<FaceSign>(), run(FaceLogic(), hold(smile, 700) + hold(neutral, 500)))
        assertEquals(listOf(FaceSign.Smile), run(FaceLogic(), hold(smile, 1600)))
    }

    @Test fun callsIgnorePlainSmile() = assertEquals(emptyList<FaceSign>(), run(FaceLogic(withSmile = false), hold(smile, 3000)))

    @Test fun winkCountsButBlinkDoesNot() {
        assertEquals(listOf(FaceSign.Wink), run(FaceLogic(), hold(neutral, 300) + hold(wink, 400)))
        assertEquals(emptyList<FaceSign>(), run(FaceLogic(), hold(blink, 400)))
    }

    @Test fun kissAndSurprise() {
        assertEquals(listOf(FaceSign.Kiss), run(FaceLogic(), hold(kiss, 1000)))
        assertEquals(listOf(FaceSign.Surprised), run(FaceLogic(), hold(wow, 1000)))
    }

    @Test fun coolDownBetweenExpressions() {
        // Laugh, relax briefly, laugh again within 4 s: only the first counts.
        assertEquals(listOf(FaceSign.Laugh), run(FaceLogic(), hold(laugh, 900) + hold(neutral, 300) + hold(laugh, 900)))
    }
}
