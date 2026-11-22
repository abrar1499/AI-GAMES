package com.lumo.app.call

/**
 * Picks how to spend the available bandwidth so the picture stays sharp. Squeezing 720p into a
 * thin connection gives blocky, smeared video; sending fewer pixels at good quality looks far
 * clearer. Each step sets the size (as a fraction of the 720p camera), the frame rate, and how
 * much the voice may use, leaving the rest for video.
 */
object VideoLadder {
    data class Step(val name: String, val minKbps: Int, val scaleDown: Double, val fps: Int, val audioMaxBps: Int)

    val steps = listOf(
        Step("720p", 1200, 1.0, 30, 80_000),
        Step("480p", 650, 1.5, 30, 64_000),
        Step("360p", 350, 2.0, 25, 48_000),
        Step("270p", 180, 2.67, 20, 36_000),
        Step("180p", 85, 4.0, 15, 26_000),
        Step("180p low", 0, 4.0, 10, 20_000),
    )

    /** The best step a connection of [kbps] can carry well. */
    fun pick(kbps: Double): Step = steps.first { kbps >= it.minKbps }
}

/**
 * Follows the live bandwidth estimate without flapping: drops a step as soon as the connection
 * gets weaker (so the picture never smears), but only climbs after it has stayed better for a
 * few seconds.
 */
class LadderFollower(private val upAfterTicks: Int = 3) {
    var current: VideoLadder.Step = VideoLadder.steps.first()
        private set
    private var betterTicks = 0

    /** Feed one estimate (about every 2 s). Returns the new step when it changes, else null. */
    fun update(kbps: Double): VideoLadder.Step? {
        val target = VideoLadder.pick(kbps)
        val ti = VideoLadder.steps.indexOf(target)
        val ci = VideoLadder.steps.indexOf(current)
        return when {
            ti > ci -> { betterTicks = 0; current = target; target }             // weaker: step down now
            ti < ci -> {
                if (++betterTicks >= upAfterTicks) {
                    betterTicks = 0
                    current = VideoLadder.steps[ci - 1]                             // better: one step up at a time
                    current
                } else null
            }
            else -> { betterTicks = 0; null }
        }
    }
}
