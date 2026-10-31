package com.xuanthanhtnt.xtplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MediaEntry.swift (`isWatched`, `isStarted`, `resumePosition`) and HomeView.swift `LibraryIndex.isUnplayed`. */
class WatchedRulesTest {
    private fun entry(key: String = "k", position: Double, duration: Double) =
        MediaEntry(key = key, title = "t", uri = key, isLocalFile = false, position = position, duration = duration)

    @Test
    fun watchedInTheLastMinute() {
        assertTrue(entry(position = 7140.0, duration = 7200.0).isWatched)      // 60 s left
        assertFalse(entry(position = 6400.0, duration = 7200.0).isWatched)     // 88.9 %, 800 s left
    }

    @Test
    fun watchedAtNinetyPercent() {
        // A 4-minute music video: the outro (last 10 %) counts as finished.
        assertTrue(entry(position = 216.0, duration = 240.0).isWatched)
        assertFalse(entry(position = 170.0, duration = 240.0).isWatched)
        // A film's end credits: 90 % of 2 h is 108 min, long before the last minute.
        assertTrue(entry(position = 6480.0, duration = 7200.0).isWatched)
    }

    @Test
    fun unknownDurationIsNeverWatched() {
        assertFalse(entry(position = 5000.0, duration = 0.0).isWatched)
    }

    @Test
    fun startedAfterTenSecondsOrWhenWatched() {
        assertFalse(entry(position = 10.0, duration = 3600.0).isStarted)
        assertTrue(entry(position = 10.5, duration = 3600.0).isStarted)
        assertTrue(entry(position = 1.0, duration = 1.0).isStarted)            // "Đánh dấu đã xem" with no duration known
        assertFalse(entry(position = 0.0, duration = 0.0).isStarted)
    }

    @Test
    fun resumeOnlyBetweenThirtySecondsAndWatched() {
        assertNull(entry(position = 30.0, duration = 3600.0).resumePosition)
        assertEquals(31.0, entry(position = 31.0, duration = 3600.0).resumePosition!!, 0.0)
        assertNull(entry(position = 3300.0, duration = 3600.0).resumePosition)  // past 90 %
    }

    private fun item(key: String, kind: LibraryKind) = LibraryItem(key = key, kindRaw = kind.raw, title = key)
    private fun file(key: String, itemKey: String) =
        LibraryFile(key = key, itemKey = itemKey, folderKey = "f", serverID = "s", path = key, fileName = key, size = 1)

    @Test
    fun unwatchedFilmIsOneNeverPlayed() {
        val movie = item("m", LibraryKind.MOVIE)
        val files = listOf(file("m1", "m"))
        assertTrue(LibraryIndex(files, emptyList()).isUnplayed(movie))
        // In progress counts as played, as in Infuse.
        assertFalse(LibraryIndex(files, listOf(entry("m1", position = 600.0, duration = 7200.0))).isUnplayed(movie))
        // Opened for a few seconds only: still unwatched.
        assertTrue(LibraryIndex(files, listOf(entry("m1", position = 8.0, duration = 7200.0))).isUnplayed(movie))
    }

    @Test
    fun unwatchedShowHasAnEpisodeNotPlayed() {
        val show = item("s", LibraryKind.SHOW)
        val files = listOf(file("e1", "s"), file("e2", "s"))
        val oneWatched = listOf(entry("e1", position = 2400.0, duration = 2400.0))
        assertTrue(LibraryIndex(files, oneWatched).isUnplayed(show))
        val bothStarted = oneWatched + entry("e2", position = 120.0, duration = 2400.0)
        assertFalse(LibraryIndex(files, bothStarted).isUnplayed(show))
    }
}
