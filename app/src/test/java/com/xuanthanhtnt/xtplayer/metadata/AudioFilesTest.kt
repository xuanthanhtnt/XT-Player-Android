package com.xuanthanhtnt.xtplayer.metadata

import com.xuanthanhtnt.xtplayer.network.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FilenameParser.swift (`audioExtensions`, `track()`) and RemoteFileSystem.swift (`isAudio`, `isPlayable`). */
class AudioFilesTest {
    @Test
    fun audioExtensionsAsInTheAppleApp() {
        val expected = ("flac m4a alac wav aif aiff aifc caf w64 dsf dff wv ape tta tak mp3 aac ogg oga opus wma mpc shn mka mp2 " +
            "ac3 eac3 ec3 dts dtshd thd truehd mlp").split(' ').toSet()
        assertEquals(expected, FilenameParser.audioExtensions)
    }

    @Test
    fun trackNumbersFromNames() {
        // The examples in the Apple doc comment.
        assertEquals(FilenameParser.Track(null, 1, "Song"), FilenameParser.track("01 - Song.flac"))
        assertEquals(FilenameParser.Track(1, 3, "Song"), FilenameParser.track("1-03 Song.flac"))
        assertEquals(FilenameParser.Track(null, 3, "Song"), FilenameParser.track("03. Song.dsf"))
        assertEquals(FilenameParser.Track(null, 4, "Song"), FilenameParser.track("Artist - 04 - Song.mp3"))
        // The other forms the patterns name.
        assertEquals(FilenameParser.Track(1, 3, "Title"), FilenameParser.track("1.03 Title.flac"))
        assertEquals(FilenameParser.Track(null, 3, "Title"), FilenameParser.track("03 Title.flac"))
        assertEquals(FilenameParser.Track(null, 7, "Diễm Xưa"), FilenameParser.track("07_Diễm_Xưa.flac"))
    }

    @Test
    fun noNumberKeepsTheName() {
        assertEquals(FilenameParser.Track(null, null, "Song"), FilenameParser.track("Song.flac"))
        assertEquals(FilenameParser.Track(null, null, "Hạ Trắng"), FilenameParser.track("Hạ Trắng.m4a"))
    }

    private fun item(name: String, directory: Boolean = false) = RemoteItem(name, "/x/$name", directory, 1, null)

    @Test
    fun browserShowsVideosAndMusic() {
        assertTrue(item("a.flac").isAudio)
        assertTrue(item("a.flac").isPlayable)
        assertTrue(item("a.mkv").isPlayable)
        assertFalse(item("a.mkv").isAudio)
        assertFalse(item("a.srt").isPlayable)
        assertFalse(item("Album.flac", directory = true).isAudio)
    }

    @Test
    fun musicVideoTitleArtistYear() {
        // LibraryScanner.swift `recogniseMusicVideos`: title from the file, artist / show from its folder.
        assertEquals(
            LibraryScanner.MusicVideoInfo("Xin Còn Gọi Tên Nhau", null, null),
            LibraryScanner.musicVideoInfo("Xin Còn Gọi Tên Nhau.mp4", "Xin Còn Gọi Tên Nhau", "Music Videos", "Music Videos"),
        )
        assertEquals(
            LibraryScanner.MusicVideoInfo("Live Concert", 2019, "Đàm Vĩnh Hưng"),
            LibraryScanner.musicVideoInfo("Live Concert 2019.mkv", "Live Concert 2019", "Đàm Vĩnh Hưng", "Music Videos"),
        )
        // A one-character title falls back to the file name (the year is still read).
        assertEquals(
            LibraryScanner.MusicVideoInfo("A (2019)", 2019, null),
            LibraryScanner.musicVideoInfo("A (2019).mp4", "A (2019)", "MV", "MV"),
        )
    }
}
