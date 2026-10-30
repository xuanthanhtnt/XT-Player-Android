package com.xuanthanhtnt.xtplayer.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameParserTest {
    @Test
    fun sceneMovieWithDolbyVisionAndAtmos() {
        val p = FilenameParser.parse("The.Matrix.1999.2160p.UHD.BluRay.REMUX.DV.HDR10.TrueHD.Atmos.7.1-FGT.mkv")
        assertEquals("The Matrix", p.title)
        assertEquals(1999, p.year)
        assertEquals(ParsedMediaName.Kind.Movie, p.kind)
        assertEquals("4K", p.resolution)
        assertEquals(listOf("DV", "HDR10"), p.dynamicRange)
        assertEquals(listOf("TrueHD Atmos"), p.audio)
        assertEquals("The Matrix (1999)", p.displayTitle)
        assertEquals(listOf("4K", "DV", "HDR10", "TrueHD Atmos"), p.badges)
    }

    @Test
    fun episodeWithStreamingTags() {
        val p = FilenameParser.parse("Severance.S02E05.Chikhai.Bardo.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv")
        assertEquals("Severance", p.title)
        assertEquals(ParsedMediaName.Kind.Episode(2, 5), p.kind)
        assertNull(p.year)
        assertEquals(listOf("DV", "HDR10"), p.dynamicRange)
        assertEquals(listOf("DD+ Atmos"), p.audio)
        assertEquals("Severance · S02E05", p.displayTitle)
    }

    @Test
    fun plexStyleMovieWithBracketTags() {
        val p = FilenameParser.parse("Dune Part Two (2024) [2160p HDR10+ DTS-HD MA 7.1].mkv")
        assertEquals("Dune Part Two", p.title)
        assertEquals(2024, p.year)
        assertEquals("4K", p.resolution)
        assertEquals(listOf("HDR10+"), p.dynamicRange)
        assertEquals(listOf("DTS-HD MA"), p.audio)
    }

    @Test
    fun titleStartingWithAYearKeepsIt() {
        val p = FilenameParser.parse("2001.A.Space.Odyssey.1968.1080p.BluRay.x264.mkv")
        assertEquals("2001 A Space Odyssey", p.title)
        assertEquals(1968, p.year)
        assertEquals("1080p", p.resolution)
        assertTrue(p.dynamicRange.isEmpty())
    }

    @Test
    fun kodiStyleEpisodeWithGroupTag() {
        val p = FilenameParser.parse("[SubsPlease] Frieren - 1x05 (1080p).mkv")
        assertEquals("Frieren", p.title)
        assertEquals(ParsedMediaName.Kind.Episode(1, 5), p.kind)
    }

    @Test
    fun episodeWithSpacedSeparators() {
        val p = FilenameParser.parse("Mr. Robot - S01E02 - eps1.1_ones-and-zer0es.mpeg")
        assertEquals("Mr. Robot", p.title)
        assertEquals(ParsedMediaName.Kind.Episode(1, 2), p.kind)
    }

    @Test
    fun qualityTokenEndsTitleWhenThereIsNoYear() {
        val p = FilenameParser.parse("Some.Concert.Film.1080p.WEB-DL.AAC2.0.mp4")
        assertEquals("Some Concert Film", p.title)
        assertNull(p.year)
        assertEquals(listOf("AAC"), p.audio)
    }

    @Test
    fun plainNameIsUntouched() {
        val p = FilenameParser.parse("Home Movie.mp4")
        assertEquals("Home Movie", p.title)
        assertEquals("Home Movie", p.displayTitle)
        assertTrue(p.badges.isEmpty())
    }

    @Test
    fun folderNamesParseAsTitleAndYear() {
        val p = FilenameParser.parse("Dune Part Two (2024)")
        assertEquals("Dune Part Two", p.title)
        assertEquals(2024, p.year)
    }

    @Test
    fun subtitleExtensionsAreKnown() {
        assertTrue("ass" in FilenameParser.subtitleExtensions)
        assertFalse("srt" in FilenameParser.videoExtensions)
    }
}
