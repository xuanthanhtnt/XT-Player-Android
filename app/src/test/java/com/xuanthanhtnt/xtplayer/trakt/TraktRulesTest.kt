package com.xuanthanhtnt.xtplayer.trakt

import com.xuanthanhtnt.xtplayer.data.LibraryFile
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlayerScreen.swift `scrobble` and TraktService.swift (`syncIfStale`, 429 retry, `TraktLibraryIndex`). */
class TraktRulesTest {
    private val start = TraktService.ScrobbleAction.START
    private val pause = TraktService.ScrobbleAction.PAUSE
    private val stop = TraktService.ScrobbleAction.STOP

    @Test
    fun startWaitsForTheDuration() {
        val gate = ScrobbleGate()
        assertFalse(gate.allow(start, hasMedia = true, durationKnown = false))   // "playing" before the duration
        assertFalse(gate.allow(pause, hasMedia = true, durationKnown = true))    // no start was sent: dropped
        assertTrue(gate.allow(start, hasMedia = true, durationKnown = true))     // sent once the duration is known
        assertTrue(gate.allow(pause, hasMedia = true, durationKnown = true))
        assertTrue(gate.allow(start, hasMedia = true, durationKnown = true))     // resumed
        assertTrue(gate.allow(stop, hasMedia = true, durationKnown = true))
        assertFalse(gate.allow(stop, hasMedia = true, durationKnown = true))     // a stop goes out once
        assertFalse(gate.allow(pause, hasMedia = true, durationKnown = true))
    }

    @Test
    fun nothingWithoutATraktItem() {
        val gate = ScrobbleGate()
        assertFalse(gate.allow(start, hasMedia = false, durationKnown = true))
        assertFalse(gate.allow(stop, hasMedia = true, durationKnown = true))
    }

    @Test
    fun retryAfterWithinOneToThirtySeconds() {
        assertEquals(2.0, TraktService.retryDelaySeconds(null), 0.0)
        assertEquals(2.0, TraktService.retryDelaySeconds("soon"), 0.0)
        assertEquals(5.0, TraktService.retryDelaySeconds("5"), 0.0)
        assertEquals(1.0, TraktService.retryDelaySeconds("0"), 0.0)
        assertEquals(30.0, TraktService.retryDelaySeconds("120"), 0.0)
    }

    @Test
    fun syncIsDueAfterTheInterval() {
        val now = 10_000_000L
        assertTrue(TraktService.isStale(null, now, 300))
        assertFalse(TraktService.isStale(now - 299_000, now, 300))     // back in front within 5 min
        assertTrue(TraktService.isStale(now - 300_000, now, 300))
        assertFalse(TraktService.isStale(now - 599_000, now, 600))     // the 15-minute timer: only if > 10 min
        assertTrue(TraktService.isStale(now - 601_000, now, 600))
    }

    private fun movie(key: String, title: String, year: Int?, tmdb: Int? = null, original: String? = null, kind: LibraryKind = LibraryKind.MOVIE) =
        LibraryItem(key = key, kindRaw = kind.raw, title = title, originalTitle = original, year = year, tmdbID = tmdb)

    private fun file(key: String, itemKey: String, season: Int? = null, episode: Int? = null) =
        LibraryFile(key = key, itemKey = itemKey, folderKey = "f", serverID = "s", path = key, fileName = key, size = 1, season = season, episode = episode)

    @Test
    fun matchesByTmdbThenByOriginalTitle() {
        // TMDb in Vietnamese gives a Vietnamese title; Trakt lists the original one.
        val items = listOf(movie("i1", "Hành Tinh Cát", 2021, tmdb = 438631, original = "Dune"))
        val index = TraktLibraryIndex.build(items, listOf(file("f1", "i1")))
        assertEquals(listOf("f1"), index.keys(TraktMedia.Movie("Dune", 2021, 438631)))
        assertEquals(listOf("f1"), index.keys(TraktMedia.Movie("Dune", 2021, 999)))   // other id: by title and year
        assertEquals(listOf("f1"), index.keys(TraktMedia.Movie("Dune", 2021, null)))
    }

    @Test
    fun yearMayBeMissingOrOneApart() {
        val items = listOf(movie("i1", "Parasite", 2019), movie("i2", "Home Video", null))
        val index = TraktLibraryIndex.build(items, listOf(file("f1", "i1"), file("f2", "i2")))
        assertEquals(listOf("f1"), index.keys(TraktMedia.Movie("Parasite", 2020, null)))   // festival vs release year
        assertEquals(emptyList<String>(), index.keys(TraktMedia.Movie("Parasite", 2017, null)))
        assertEquals(listOf("f1"), index.keys(TraktMedia.Movie("Parasite", null, null)))
        assertEquals(listOf("f2"), index.keys(TraktMedia.Movie("Home Video", 2015, null)))  // no year here
    }

    @Test
    fun episodesByOriginalShowTitle() {
        val items = listOf(movie("s1", "Trò Chơi Con Mực", 2021, tmdb = 93405, original = "Squid Game", kind = LibraryKind.SHOW))
        val index = TraktLibraryIndex.build(items, listOf(file("e1", "s1", season = 1, episode = 2)))
        assertEquals(listOf("e1"), index.keys(TraktMedia.Episode("Squid Game", 2021, null, 1, 2)))
        assertEquals(listOf("e1"), index.keys(TraktMedia.Episode("Squid Game", 2021, 93405, 1, 2)))
    }

    @Test
    fun musicVideosAreNotOnTrakt() {
        val items = listOf(movie("v1", "Xin Còn Gọi Tên Nhau", null, kind = LibraryKind.MUSIC_VIDEO))
        val index = TraktLibraryIndex.build(items, listOf(file("f1", "v1")))
        assertTrue(index.media.isEmpty())
        assertEquals(emptyList<String>(), index.keys(TraktMedia.Movie("Xin Còn Gọi Tên Nhau", null, null)))
    }

    @Test
    fun labelsForTheLog() {
        assertEquals("Dune (2021)", TraktMedia.Movie("Dune", 2021, null).label)
        assertEquals("Breaking Bad S01E02", TraktMedia.Episode("Breaking Bad", 2008, null, 1, 2).label)
    }
}
