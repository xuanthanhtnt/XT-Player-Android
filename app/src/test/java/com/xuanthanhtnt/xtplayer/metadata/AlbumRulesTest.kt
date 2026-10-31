package com.xuanthanhtnt.xtplayer.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** LibraryScanner.swift (`discNumber`, `albumTitle`, `codecName`) and MPVPlayerEngine.swift `prettyCodec`. */
class AlbumRulesTest {
    @Test
    fun discFolders() {
        // "CD1", "CD 2", "Disc 3", "Disk 1" → disc number.
        assertEquals(1, LibraryScanner.discNumber("CD1"))
        assertEquals(2, LibraryScanner.discNumber("CD 2"))
        assertEquals(3, LibraryScanner.discNumber("Disc 3"))
        assertEquals(1, LibraryScanner.discNumber("Disk 1"))
        assertEquals(1, LibraryScanner.discNumber("cd01"))
        assertNull(LibraryScanner.discNumber("Abbey Road"))
        // "disc" matches, the rest is not a number: an album folder, not a disc.
        assertNull(LibraryScanner.discNumber("Discography"))
        assertNull(LibraryScanner.discNumber("CD"))
    }

    @Test
    fun albumFolderNames() {
        // "Artist - Album (2019) [FLAC 24-96]" → artist, album, year.
        assertEquals(LibraryScanner.AlbumName("Artist", "Album", 2019), LibraryScanner.albumTitle("Artist - Album (2019) [FLAC 24-96]"))
        assertEquals(
            LibraryScanner.AlbumName("Khánh Ly", "Hát Cho Quê Hương Việt Nam", null),
            LibraryScanner.albumTitle("Khánh Ly - Hát Cho Quê Hương Việt Nam"),
        )
        // A leading year is the year, not the artist.
        assertEquals(LibraryScanner.AlbumName(null, "OK Computer", 1997), LibraryScanner.albumTitle("1997 - OK Computer"))
        assertEquals(LibraryScanner.AlbumName(null, "Abbey Road", null), LibraryScanner.albumTitle("Abbey Road"))
        assertEquals(LibraryScanner.AlbumName("Pink Floyd", "The Wall", 1979), LibraryScanner.albumTitle("Pink_Floyd - The_Wall [1979]"))
        // Only the first " - " splits off the artist.
        assertEquals(LibraryScanner.AlbumName("Adele", "25 - Deluxe", null), LibraryScanner.albumTitle("Adele - 25 - Deluxe"))
        // Nothing left after the tags: the folder name stays.
        assertEquals(LibraryScanner.AlbumName(null, "[2019]", 2019), LibraryScanner.albumTitle("[2019]"))
    }

    @Test
    fun discFolderJoinsItsAlbum() {
        assertEquals("Music/Artist/Album", LibraryScanner.deletingLastPathComponent("Music/Artist/Album/CD1"))
        assertEquals("/storage/emulated/0/Music/Album", LibraryScanner.deletingLastPathComponent("/storage/emulated/0/Music/Album/CD 2/"))
    }

    @Test
    fun trackFormats() {
        val expected = mapOf(
            "flac" to "FLAC", "m4a" to "ALAC", "alac" to "ALAC", "wav" to "PCM", "aiff" to "PCM", "w64" to "PCM",
            "dsf" to "DSD", "dff" to "DSD", "wv" to "WavPack", "ape" to "APE", "tta" to "TTA", "tak" to "TAK",
            "mp3" to "MP3", "aac" to "AAC", "ogg" to "Vorbis", "oga" to "Vorbis", "opus" to "Opus", "mpc" to "Musepack",
            "ac3" to "AC3", "ec3" to "DD+", "eac3" to "DD+", "dts" to "DTS", "dtshd" to "DTS", "thd" to "TrueHD",
            "truehd" to "TrueHD", "mlp" to "MLP", "wma" to "WMA", "mka" to "MKA",
        )
        for ((ext, format) in expected) assertEquals(ext, format, LibraryScanner.trackFormat(ext))
    }
}
