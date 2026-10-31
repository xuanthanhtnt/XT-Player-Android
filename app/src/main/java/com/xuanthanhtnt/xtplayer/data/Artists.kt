package com.xuanthanhtnt.xtplayer.data

import java.text.Normalizer

/** Album artists (MusicViews.swift `ArtistsView`, `ArtistCard`). */
object Artists {
    /** Albums without an artist are grouped under this name. */
    const val UNKNOWN = "Không rõ nghệ sĩ"

    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val skipped = setOf("of", "the", "và", "and", "&")

    fun name(album: LibraryItem): String = album.artist ?: UNKNOWN

    /** "Ace of Base" → "AB", "Ánh Tuyết" → "ÁT"; "♪" when there are no letters. */
    fun initials(name: String): String {
        val words = Normalizer.normalize(name, Normalizer.Form.NFC).split(separators)
            .filter { it.isNotEmpty() && it.lowercase() !in skipped }
        val letters = words.take(2).map { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }
        return if (letters.isEmpty()) "♪" else letters.joinToString("")
    }
}
