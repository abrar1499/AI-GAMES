package com.lumo.app.gesture

/** Face expressions Lumo understands, and how long each must be held before it counts. */
enum class FaceSign(val emoji: String, val holdMs: Long) {
    Laugh("😂", 600),
    Smile("😊", 1200),
    Wink("😉", 250),
    Kiss("😘", 600),
    Surprised("😮", 600),
    Angry("😠", 900),
    Sad("😢", 900),
}

/**
 * Turns MediaPipe's per-frame face blendshape scores (0..1, e.g. "mouthSmileLeft", "jawOpen")
 * into deliberate expressions. Thresholds are deliberately high so a resting or talking face
 * never sends anything; an expression must be held, can't repeat until the face relaxes, and
 * there is a cool-down after each one.
 *
 * [withSmile] = false leaves out the plain smile (used in calls, where people smile all the time).
 */
class FaceLogic(private val withSmile: Boolean = true, private val cooldownMs: Long = 4000) {
    private var candidate: FaceSign? = null
    private var since = 0L
    private var lastFired: FaceSign? = null
    private var firedAt = -1_000_000L

    fun onFrame(scores: Map<String, Float>?, now: Long): FaceSign? {
        val sign = scores?.let { classify(it) }
        if (sign == null) {
            candidate = null
            lastFired = null // face relaxed (or left): any expression may count again
            return null
        }
        if (sign != candidate) {
            candidate = sign
            since = now
            return null
        }
        if (sign == lastFired || now - since < sign.holdMs || now - firedAt < cooldownMs) return null
        firedAt = now
        lastFired = sign
        return sign
    }

    fun classify(s: Map<String, Float>): FaceSign? {
        fun v(k: String) = s[k] ?: 0f
        val smile = (v("mouthSmileLeft") + v("mouthSmileRight")) / 2
        val jaw = v("jawOpen")
        val blinkL = v("eyeBlinkLeft")
        val blinkR = v("eyeBlinkRight")
        val pucker = v("mouthPucker")
        val browUp = v("browInnerUp")
        val browDown = (v("browDownLeft") + v("browDownRight")) / 2
        val frown = (v("mouthFrownLeft") + v("mouthFrownRight")) / 2
        return when {
            smile > 0.55f && jaw > 0.35f -> FaceSign.Laugh
            (blinkL > 0.7f && blinkR < 0.25f) || (blinkR > 0.7f && blinkL < 0.25f) -> FaceSign.Wink
            pucker > 0.75f && smile < 0.3f -> FaceSign.Kiss
            jaw > 0.55f && browUp > 0.5f && smile < 0.25f -> FaceSign.Surprised
            browDown > 0.55f && smile < 0.15f -> FaceSign.Angry
            frown > 0.5f && smile < 0.15f -> FaceSign.Sad
            withSmile && smile > 0.75f && jaw < 0.25f -> FaceSign.Smile
            else -> null
        }
    }
}
