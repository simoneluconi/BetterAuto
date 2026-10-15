package com.simoneluconi.betterauto.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTest {
    private fun track(playing: Boolean, speed: Float = 1f, durationMs: Long = 200_000) = Track(
        packageName = "app", appLabel = "App", appIcon = null, title = "", artist = "", album = "", art = null,
        accent = null, durationMs = durationMs, positionMs = 10_000, positionUpdatedAt = 1_000, speed = speed,
        playing = playing, shuffle = false, repeat = false, repeatOne = false, playlist = null, upNext = null,
        queue = emptyList(), activeQueueId = -1, customActions = emptyList(), canSeek = false,
    )

    @Test
    fun pausedTrackStaysWhereItWas() {
        assertEquals(10_000, track(playing = false).positionAt(now = 50_000))
    }

    @Test
    fun playingTrackAdvancesAtItsSpeed() {
        assertEquals(14_000, track(playing = true).positionAt(now = 5_000))
        assertEquals(18_000, track(playing = true, speed = 2f).positionAt(now = 5_000))
    }

    @Test
    fun positionNeverLeavesTheTrack() {
        assertEquals(200_000, track(playing = true).positionAt(now = 1_000_000))
        assertEquals(0, track(playing = true, speed = -1f).positionAt(now = 50_000))
        // Live streams have no duration: only the lower bound applies
        assertEquals(1_009_000, track(playing = true, durationMs = 0).positionAt(now = 1_000_000))
    }
}
