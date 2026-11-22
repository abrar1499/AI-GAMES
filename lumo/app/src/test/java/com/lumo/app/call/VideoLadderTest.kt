package com.lumo.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLadderTest {
    @Test fun picksSizeForSpeed() {
        assertEquals("720p", VideoLadder.pick(2500.0).name)
        assertEquals("480p", VideoLadder.pick(800.0).name)
        assertEquals("360p", VideoLadder.pick(400.0).name)
        assertEquals("270p", VideoLadder.pick(200.0).name)
        assertEquals("180p", VideoLadder.pick(100.0).name)   // ~100 kbps: 180p at 15 fps
        assertEquals("180p low", VideoLadder.pick(40.0).name)
    }

    @Test fun voiceGetsLessOnThinConnections() {
        val at100 = VideoLadder.pick(100.0)
        assert(at100.audioMaxBps <= 26_000) { "voice must leave room for video at 100 kbps" }
        assert(VideoLadder.pick(2500.0).audioMaxBps > at100.audioMaxBps)
    }

    @Test fun dropsAtOnceClimbsSlowly() {
        val f = LadderFollower(upAfterTicks = 3)
        assertEquals("180p", f.update(100.0)?.name)        // weaker: straight down
        assertNull(f.update(2500.0))                       // better: wait…
        assertNull(f.update(2500.0))
        assertEquals("270p", f.update(2500.0)?.name)       // …then one step up at a time
        assertNull(f.update(2500.0)); assertNull(f.update(2500.0))
        assertEquals("360p", f.update(2500.0)?.name)
    }

    @Test fun noFlappingOnSmallWobble() {
        val f = LadderFollower()
        f.update(400.0) // 360p
        assertNull(f.update(420.0)); assertNull(f.update(380.0)); assertNull(f.update(400.0))
        assertEquals("360p", f.current.name)
    }
}
