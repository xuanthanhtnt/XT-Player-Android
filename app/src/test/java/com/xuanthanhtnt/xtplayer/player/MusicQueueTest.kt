package com.xuanthanhtnt.xtplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MusicPlayer.swift: `toggleShuffle`, `previous`, `cycleRepeat`. */
class MusicQueueTest {
    @Test
    fun shuffleOnlyTouchesWhatComesNext() {
        val queue = listOf("1", "2", "3", "4", "5")
        // Playing track 2: 1 and 2 stay, the rest is reordered (reversed here instead of random).
        val (rest, saved) = MusicQueue.reorderUpcoming(queue, 1, emptyList(), shuffle = true) { it.reversed() }
        assertEquals(listOf("5", "4", "3"), rest)
        assertEquals(queue, saved)
    }

    @Test
    fun shuffleOffRestoresTheOrder() {
        val original = listOf("1", "2", "3", "4", "5")
        val shuffled = listOf("1", "2", "5", "3", "4")
        // Now on "5" (index 2): what came after "5" in the original order follows.
        val (rest, saved) = MusicQueue.reorderUpcoming(shuffled, 2, original, shuffle = false)
        assertEquals(emptyList<String>(), rest)
        assertEquals(original, saved)
        // Now on "3": "4", "5".
        assertEquals(listOf("4", "5"), MusicQueue.reorderUpcoming(shuffled, 3, original, shuffle = false).first)
        // The current track is not in the kept order (it was queued later): the queue stays as it is.
        assertEquals(listOf("3", "4"), MusicQueue.reorderUpcoming(listOf("x", "3", "4"), 0, original, shuffle = false).first)
    }

    @Test
    fun previousRestartsAfterThreeSeconds() {
        assertTrue(MusicQueue.restartsTrack(3_500, 4))
        assertFalse(MusicQueue.restartsTrack(2_000, 4))
        // On the first track it always starts again.
        assertTrue(MusicQueue.restartsTrack(500, 0))
    }

    @Test
    fun repeatCycles() {
        assertEquals(MusicPlayer.RepeatMode.ALL, MusicQueue.nextRepeat(MusicPlayer.RepeatMode.OFF))
        assertEquals(MusicPlayer.RepeatMode.ONE, MusicQueue.nextRepeat(MusicPlayer.RepeatMode.ALL))
        assertEquals(MusicPlayer.RepeatMode.OFF, MusicQueue.nextRepeat(MusicPlayer.RepeatMode.ONE))
    }
}
