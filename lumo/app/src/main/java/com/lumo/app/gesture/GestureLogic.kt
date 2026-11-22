package com.lumo.app.gesture

import kotlin.math.hypot

/** The gestures Lumo understands and what each one sends. */
enum class Gesture(val emoji: String, val label: String) {
    ThumbsUp("👍", "Thumbs up"),
    ThumbsDown("👎", "Thumbs down"),
    Ok("👌", "OK"),
    Wave("👋", "Bye"),
    Victory("✌️", "Victory"),
    LoveYou("🤟", "Love you"),
    PointUp("☝️", "One sec"),
    Fist("✊", "Fist"),
}

/** A hand seen in one camera frame: MediaPipe's label and its 21 landmarks (x, y in 0..1). */
class HandFrame(val label: String?, val score: Float, val points: List<Pair<Float, Float>>)

/**
 * Turns a stream of per-frame hand readings into deliberate gestures.
 * A gesture only counts once it is held steady for a moment, so a passing hand
 * never sends anything; after sending, the same gesture can't repeat until the
 * hand changes or leaves, and nothing fires during a short cool-down.
 */
class GestureLogic(
    private val holdMs: Long = 450,
    private val cooldownMs: Long = 1500,
) {
    private var candidate: Gesture? = null
    private var since = 0L
    private var lastFired: Gesture? = null
    private var firedAt = -1_000_000L
    private val palmTrack = ArrayDeque<Pair<Long, Float>>()

    /** Feed one frame; returns a gesture when one should be sent now. */
    fun onFrame(frame: HandFrame?, now: Long): Gesture? {
        if (frame == null || frame.points.size < 21) {
            candidate = null
            lastFired = null // hand left the view: the same gesture may be sent again
            palmTrack.clear()
            return null
        }
        if (isWave(frame, now) && lastFired != Gesture.Wave) return fire(Gesture.Wave, now)

        val g = classify(frame)
        if (g != candidate) {
            candidate = g
            since = now
            if (g != lastFired) lastFired = null
            return null
        }
        if (g == null || g == lastFired) return null
        if (now - since < holdMs) return null
        return fire(g, now)
    }

    /** The gesture currently in view (for the live hint), steady or not. */
    fun current(): Gesture? = candidate

    private fun fire(g: Gesture, now: Long): Gesture? {
        if (now - firedAt < cooldownMs) return null
        firedAt = now
        lastFired = g
        palmTrack.clear()
        return g
    }

    private fun classify(f: HandFrame): Gesture? {
        // MediaPipe has no OK sign, so it is read from the finger positions.
        if (isOk(f.points)) return Gesture.Ok
        if (f.score < 0.6f) return null
        return when (f.label) {
            "Thumb_Up" -> Gesture.ThumbsUp
            "Thumb_Down" -> Gesture.ThumbsDown
            "Victory" -> Gesture.Victory
            "ILoveYou" -> Gesture.LoveYou
            "Pointing_Up" -> Gesture.PointUp
            "Closed_Fist" -> Gesture.Fist
            else -> null // Open_Palm alone is not a message; waving it is.
        }
    }

    /** Thumb tip touching index tip, other three fingers stretched out. */
    private fun isOk(p: List<Pair<Float, Float>>): Boolean {
        fun d(a: Int, b: Int) = hypot(p[a].first - p[b].first, p[a].second - p[b].second)
        val hand = d(0, 9).takeIf { it > 0.01f } ?: return false
        val pinch = d(4, 8) < 0.35f * hand
        val extended = listOf(12 to 10, 16 to 14, 20 to 18).all { (tip, pip) -> d(0, tip) > d(0, pip) * 1.1f }
        return pinch && extended
    }

    /** An open palm swinging side to side: at least two turns of a clear size within 1.5s. */
    private fun isWave(f: HandFrame, now: Long): Boolean {
        if (f.label != "Open_Palm") { palmTrack.clear(); return false }
        val x = (f.points[0].first + f.points[9].first) / 2
        palmTrack.addLast(now to x)
        while (palmTrack.isNotEmpty() && now - palmTrack.first().first > 1500) palmTrack.removeFirst()
        var turns = 0
        var dir = 0
        var extreme = palmTrack.first().second
        for ((_, px) in palmTrack) {
            val delta = px - extreme
            when {
                dir >= 0 && delta < -0.05f -> { if (dir > 0) turns++; dir = -1; extreme = px }
                dir <= 0 && delta > 0.05f -> { if (dir < 0) turns++; dir = 1; extreme = px }
                dir > 0 && px > extreme -> extreme = px
                dir < 0 && px < extreme -> extreme = px
            }
        }
        return turns >= 2
    }
}
