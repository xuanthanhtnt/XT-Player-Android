package com.xuanthanhtnt.xtplayer.data

import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.NaturalOrder
import com.xuanthanhtnt.xtplayer.metadata.FilenameParser
import com.xuanthanhtnt.xtplayer.player.PlaybackItem
import com.xuanthanhtnt.xtplayer.trakt.TraktMedia
import kotlin.math.roundToInt

object MediaLibrary {
    private val resolutionBadges = setOf("4K", "1080p", "720p", "SD")
    private val dao get() = AppGraph.dao

    /**
     * What playback or the artwork probe found out about a library file: its resolution badge (when the file
     * name has none) and, for movies TMDb did not match, the running time — like Infuse's "2 hrs 40 min · 1080p".
     */
    suspend fun learn(fileKey: String, resolution: String, duration: Double) {
        val file = dao.file(fileKey) ?: return
        if (resolution.isNotEmpty() && file.badges.none { it in resolutionBadges }) {
            dao.upsertFile(file.copy(badges = listOf(resolution) + file.badges))
        }
        val item = dao.item(file.itemKey) ?: return
        if (duration > 60 && item.kind == LibraryKind.MOVIE && item.runtime == null) {
            dao.upsertItem(item.copy(runtime = (duration / 60).roundToInt()))
        }
    }

    suspend fun entry(key: String): MediaEntry? = dao.entry(key)

    /** Find or create the entry for a playback item and mark it as just played. */
    suspend fun touch(item: PlaybackItem): MediaEntry {
        val existing = dao.entry(item.key)
        val entry = if (existing != null) {
            existing.copy(
                lastPlayedAt = System.currentTimeMillis(),
                title = item.title,
                subtitle = item.subtitle,
                artworkPath = item.artworkPath ?: existing.artworkPath,
            )
        } else {
            MediaEntry(
                key = item.key,
                title = item.title,
                uri = item.key,
                isLocalFile = item.isLocal,
                tags = FilenameParser.parse(item.displayName).badges,
                serverID = item.server?.id,
                subtitle = item.subtitle,
                artworkPath = item.artworkPath,
            )
        }
        dao.upsertEntry(entry)
        return entry
    }

    /** Marks watched / unwatched here and on Trakt when connected. */
    suspend fun setWatched(watched: Boolean, key: String, title: String) {
        val existing = dao.entry(key) ?: MediaEntry(key = key, title = title, uri = key, isLocalFile = key.startsWith("/"))
        val duration = if (existing.duration <= 0) 1.0 else existing.duration
        dao.upsertEntry(
            if (watched) existing.copy(duration = duration, position = duration) else existing.copy(position = 0.0)
        )
        TraktMedia.from(key, null)?.let { AppGraph.trakt.setWatched(watched, listOf(it)) }
    }

    suspend fun saveProgress(key: String, position: Double, duration: Double) {
        if (!position.isFinite() || !duration.isFinite()) return
        val entry = dao.entry(key) ?: return
        dao.upsertEntry(
            entry.copy(
                position = maxOf(0.0, position),
                duration = if (duration > 0) duration else entry.duration,
                lastPlayedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun remove(entryKey: String) = dao.deleteEntry(entryKey)

    // Maintenance

    suspend fun removeFolder(folder: LibraryFolder) {
        LibraryFolderKind.set(null, folder.key)
        dao.deleteFilesOfFolder(folder.key)
        dao.deleteFolder(folder.key)
        dao.deleteOrphanItems()
    }

    suspend fun removeServer(server: MediaServer) {
        for (folder in dao.foldersOf(server.id)) {
            dao.deleteFilesOfFolder(folder.key)
            dao.deleteFolder(folder.key)
        }
        AppGraph.secrets.set("server.${server.id}", null)
        com.xuanthanhtnt.xtplayer.network.RemoteFileSystemPool.reset(server.id)
        dao.deleteServer(server.id)
        dao.deleteOrphanItems()
    }
}

object LibraryOrdering {
    val episodes: Comparator<LibraryFile> = Comparator { a, b ->
        val sa = a.season ?: Int.MAX_VALUE
        val sb = b.season ?: Int.MAX_VALUE
        if (sa != sb) return@Comparator sa.compareTo(sb)
        val ea = a.episode ?: Int.MAX_VALUE
        val eb = b.episode ?: Int.MAX_VALUE
        if (ea != eb) return@Comparator ea.compareTo(eb)
        NaturalOrder.compare(a.fileName, b.fileName)
    }
}

/** Lookup tables so shelves don't scan every file for every poster. */
class LibraryIndex(files: List<LibraryFile>, entries: List<MediaEntry>) {
    private val filesByItem = files.groupBy { it.itemKey }
    private val filesByKey = files.associateBy { it.key }
    private val entriesByKey = entries.associateBy { it.key }

    fun files(item: LibraryItem): List<LibraryFile> = filesByItem[item.key].orEmpty()
    fun entry(file: LibraryFile): MediaEntry? = entriesByKey[file.key]
    fun entry(key: String): MediaEntry? = entriesByKey[key]

    fun itemKey(forFileKey: String): String? = filesByKey[forFileKey]?.itemKey

    fun badges(item: LibraryItem): List<String> = files(item).firstOrNull()?.badges.orEmpty()

    /** The file to continue with: the one in progress, else the first unwatched one. */
    fun nextUp(sorted: List<LibraryFile>): LibraryFile? {
        sorted.filter { entriesByKey[it.key]?.resumePosition != null }
            .maxByOrNull { entriesByKey[it.key]?.lastPlayedAt ?: 0 }
            ?.let { return it }
        return sorted.firstOrNull { entriesByKey[it.key]?.isWatched != true } ?: sorted.firstOrNull()
    }

    data class Progress(val progress: Double?, val watched: Boolean)

    /**
     * "Unwatched": a film never played (in progress counts as played, as in Infuse); a show with an episode
     * not played yet.
     */
    fun isUnplayed(item: LibraryItem): Boolean {
        val files = filesByItem[item.key].orEmpty()
        val unplayed: (LibraryFile) -> Boolean = { entriesByKey[it.key]?.isStarted != true }
        return if (item.kind == LibraryKind.SHOW) files.any(unplayed) else files.all(unplayed)
    }

    fun progress(item: LibraryItem): Progress {
        val files = filesByItem[item.key].orEmpty()
        val mine = files.mapNotNull { entriesByKey[it.key] }
        if (item.kind == LibraryKind.MOVIE || item.kind == LibraryKind.MUSIC_VIDEO) {
            val best = mine.maxByOrNull { it.progress ?: 0.0 }
            return Progress(if (best?.resumePosition != null) best.progress else null, best?.isWatched ?: false)
        }
        val watched = mine.count { it.isWatched }
        return Progress(null, files.isNotEmpty() && watched == files.size)
    }
}
