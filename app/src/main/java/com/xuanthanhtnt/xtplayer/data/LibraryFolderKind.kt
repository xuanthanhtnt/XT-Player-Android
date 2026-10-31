package com.xuanthanhtnt.xtplayer.data

import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.network.RemoteItem
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.Normalizer

/**
 * What a library folder holds, which decides how its files are recognised (LibraryModels.swift). Kept in the
 * settings by folder key ("library.folderKinds", as the Apple app keeps it in UserDefaults), so the Room store
 * needs no migration.
 */
enum class LibraryFolderKind(val raw: String, val label: String) {
    FILMS("films", "Phim & TV"),
    MUSIC_VIDEOS("musicVideos", "Video ca nhạc"),
    MUSIC("music", "Nhạc");

    companion object {
        private const val STORE_KEY = "library.folderKinds"
        private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

        private fun stored(): Map<String, String> = runCatching {
            Json.decodeFromString(mapSerializer, AppGraph.settings.prefs.getString(STORE_KEY, null) ?: "{}")
        }.getOrDefault(emptyMap())

        fun of(folderKey: String): LibraryFolderKind =
            stored()[folderKey]?.let { raw -> entries.firstOrNull { it.raw == raw } } ?: FILMS

        /** `null` forgets the folder (it was removed from the library). */
        fun set(kind: LibraryFolderKind?, folderKey: String) {
            val updated = stored().toMutableMap()
            if (kind == null) updated.remove(folderKey) else updated[folderKey] = kind.raw
            AppGraph.settings.prefs.edit().putString(STORE_KEY, Json.encodeToString(mapSerializer, updated)).apply()
        }

        /**
         * First guess when a folder is added. Its name first: "Music Videos", "MV", "Concert", "Live", "Karaoke",
         * "Ca nhạc" → music videos; "Music", "Nhạc", "Audio", "Albums", "Lossless", "FLAC", "DSD" → music. Otherwise
         * what it holds: mostly audio → music, else films.
         */
        fun guess(name: String, items: List<RemoteItem>): LibraryFolderKind {
            val folded = fold(name)
            val words = folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
            if (folded.contains("music video") || folded.contains("ca nhac") || folded.contains("video nhac") ||
                words.any { it in setOf("mv", "mvs", "concert", "concerts", "live", "karaoke", "liveshow") }
            ) {
                return MUSIC_VIDEOS
            }
            if (words.any { it in setOf("music", "nhac", "audio", "album", "albums", "songs", "lossless", "flac", "dsd", "sacd", "hires") }) {
                return MUSIC
            }
            val audio = items.count { it.isAudio }
            val video = items.count { it.isVideo }
            return if (audio > 0 && audio >= video) MUSIC else FILMS
        }

        /** Swift's `folding([.caseInsensitive, .diacriticInsensitive])`: lower case, combining marks removed (đ stays đ). */
        internal fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    }
}
