package com.xuanthanhtnt.xtplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** MusicViews.swift `ArtistCard.initials`. */
class ArtistsTest {
    @Test
    fun initials() {
        assertEquals("AB", Artists.initials("Ace of Base"))
        assertEquals("ÁT", Artists.initials("Ánh Tuyết"))
        // Decomposed accents (as some file systems store names) give the same letters.
        assertEquals("ÁT", Artists.initials("Ánh Tuyết"))
        assertEquals("B", Artists.initials("The Beatles"))
        assertEquals("SG", Artists.initials("Simon & Garfunkel"))
        assertEquals("TC", Artists.initials("Trịnh Công Sơn"))
        assertEquals("♪", Artists.initials("!!!"))
    }

    @Test
    fun albumsWithoutArtist() {
        val album = LibraryItem(key = "a", kindRaw = LibraryKind.ALBUM.raw, title = "Live")
        assertEquals("Không rõ nghệ sĩ", Artists.name(album))
        assertEquals("Khánh Ly", Artists.name(album.copy(tagline = "Khánh Ly")))
    }
}
