package com.xuanthanhtnt.xtplayer.data

import com.xuanthanhtnt.xtplayer.network.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** LibraryModels.swift `LibraryFolderKind.guess`: the folder name first, then what it holds. */
class LibraryFolderKindTest {
    private fun files(vararg names: String) = names.map { RemoteItem(it, "/x/$it", isDirectory = false, size = 1, modified = null) }

    @Test
    fun musicVideoNames() {
        for (name in listOf("Music Videos", "MV", "MVs", "Concert", "Concerts 2024", "Live", "Karaoke", "Liveshow",
            "Ca nhạc", "Video nhạc", "Video Nhạc Trẻ")) {
            assertEquals(name, LibraryFolderKind.MUSIC_VIDEOS, LibraryFolderKind.guess(name, emptyList()))
        }
    }

    @Test
    fun musicNames() {
        for (name in listOf("Music", "Nhạc", "Nhạc Trịnh", "Audio", "Album", "Albums", "Songs", "Lossless", "FLAC", "DSD",
            "SACD", "HiRes")) {
            assertEquals(name, LibraryFolderKind.MUSIC, LibraryFolderKind.guess(name, emptyList()))
        }
    }

    @Test
    fun musicVideoBeatsMusicWords() {
        // "Music Videos" also holds the word "music".
        assertEquals(LibraryFolderKind.MUSIC_VIDEOS, LibraryFolderKind.guess("Music Videos 4K", emptyList()))
    }

    @Test
    fun wordsNotSubstrings() {
        // "Delivery" contains "live", "Movies Musical" is not the word "music".
        assertEquals(LibraryFolderKind.FILMS, LibraryFolderKind.guess("Delivery", files("a.mkv")))
        assertEquals(LibraryFolderKind.FILMS, LibraryFolderKind.guess("Musicals", files("a.mkv")))
    }

    @Test
    fun otherwiseByContent() {
        assertEquals(LibraryFolderKind.MUSIC, LibraryFolderKind.guess("Downloads", files("1.flac", "2.flac", "a.mkv")))
        assertEquals(LibraryFolderKind.MUSIC, LibraryFolderKind.guess("Downloads", files("1.dsf", "a.mkv")))   // a tie goes to music
        assertEquals(LibraryFolderKind.FILMS, LibraryFolderKind.guess("Phim", files("a.mkv", "b.mp4", "1.mp3")))
        assertEquals(LibraryFolderKind.FILMS, LibraryFolderKind.guess("Phim", emptyList()))
    }

    @Test
    fun foldingKeepsDStroke() {
        // Swift's diacritic-insensitive folding removes marks, not the letter đ.
        assertEquals("nhac ca đoan", LibraryFolderKind.fold("Nhạc Ca Đoàn"))
    }
}
