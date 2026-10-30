package com.xuanthanhtnt.xtplayer.metadata

import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.data.LibraryFile
import com.xuanthanhtnt.xtplayer.data.LibraryFolder
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.data.MediaEntry
import com.xuanthanhtnt.xtplayer.data.MediaLibrary
import com.xuanthanhtnt.xtplayer.network.RemoteFileSystem
import com.xuanthanhtnt.xtplayer.network.RemoteFileSystems
import com.xuanthanhtnt.xtplayer.network.RemoteItem
import com.xuanthanhtnt.xtplayer.network.ServerCredentials
import com.xuanthanhtnt.xtplayer.network.ServerRegistry
import com.xuanthanhtnt.xtplayer.player.FrameGrabber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Walks library folders on a server, recognises movies / episodes from file and folder names,
 * enriches them from TMDb and upserts [LibraryItem] / [LibraryFile] rows.
 */
class LibraryScanner {
    private val scanning = MutableStateFlow(false)
    private val statusText = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)
    val isScanning: StateFlow<Boolean> = scanning
    val status: StateFlow<String> = statusText
    val lastError: StateFlow<String?> = error

    private val artworkLock = Mutex()
    private val dao get() = AppGraph.dao

    private class Found(val item: RemoteItem, val parentName: String, val subtitles: List<String>)

    fun scanAllInBackground() {
        AppGraph.scope.launch { scanAll() }
    }

    fun scanInBackground(folder: LibraryFolder) {
        AppGraph.scope.launch { scan(folder) }
    }

    /** Scans every library folder of every server. */
    suspend fun scanAll() {
        for (folder in dao.folders()) scan(folder)
    }

    suspend fun scan(folder: LibraryFolder): Unit = withContext(Dispatchers.IO) {
        if (!scanning.compareAndSet(false, true)) return@withContext
        try {
            val server = ServerRegistry.lookup(folder.serverID) ?: return@withContext
            ServerRegistry.register(server)
            error.value = null
            statusText.value = "Đang quét ${folder.name}…"

            val found = try {
                collect(RemoteFileSystems.make(server), folder.path) { count ->
                    statusText.value = "Đang quét ${folder.name}: $count tệp…"
                }
            } catch (e: Exception) {
                error.value = e.message ?: e.toString()
                statusText.value = ""
                return@withContext
            }

            val tmdb = TmdbClient.current()
            val showCache = mutableMapOf<String, LibraryItem>()
            val movieCache = mutableMapOf<String, LibraryItem>()
            val seenKeys = mutableSetOf<String>()
            val files = mutableListOf<LibraryFile>()
            found.forEachIndexed { index, entry ->
                statusText.value = "Nhận diện ${index + 1}/${found.size}…"
                val parsed = recognise(entry.item.name, entry.parentName)
                val fileKey = RemoteFileSystems.key(server, entry.item.path)
                seenKeys.add(fileKey)

                val item = when (parsed.kind) {
                    is ParsedMediaName.Kind.Episode -> showCache.getOrPut(parsed.title.lowercase()) {
                        upsertItem(LibraryKind.SHOW, parsed, tmdb)
                    }
                    ParsedMediaName.Kind.Movie -> movieCache.getOrPut("${parsed.title.lowercase()}|${parsed.year ?: 0}") {
                        upsertItem(LibraryKind.MOVIE, parsed, tmdb)
                    }
                }
                val existing = dao.file(fileKey)
                val episode = parsed.kind as? ParsedMediaName.Kind.Episode
                files += (existing ?: LibraryFile(
                    key = fileKey, itemKey = item.key, folderKey = folder.key, serverID = server.id,
                    path = entry.item.path, fileName = entry.item.name, size = entry.item.size,
                )).copy(
                    itemKey = item.key,
                    size = entry.item.size,
                    subtitlePaths = entry.subtitles,
                    // Keep a resolution learned from playback when the name has none.
                    badges = if (parsed.badges.isEmpty() && existing != null) existing.badges else parsed.badges,
                    season = episode?.season ?: existing?.season,
                    episode = episode?.episode ?: existing?.episode,
                )
            }
            dao.upsertFiles(files)

            // Episode titles and stills, one TMDb request per season.
            if (tmdb != null) {
                for (show in showCache.values) {
                    val id = show.tmdbID ?: continue
                    val showFiles = dao.filesOfItem(show.key)
                    for (number in showFiles.mapNotNull { it.season }.toSortedSet()) {
                        statusText.value = "Tải thông tin ${show.title} mùa $number…"
                        val season = runCatching { tmdb.season(id, number) }.getOrNull() ?: continue
                        val byEpisode = season.episodes.associateBy { it.episodeNumber }
                        dao.upsertFiles(showFiles.filter { it.season == number }.mapNotNull { file ->
                            val info = file.episode?.let { byEpisode[it] } ?: return@mapNotNull null
                            file.copy(episodeTitle = info.name, episodeOverview = info.overview, stillPath = info.stillPath, airDate = info.airDate)
                        })
                    }
                }
            }

            // Files that disappeared from this folder.
            val stale = dao.filesOfFolder(folder.key).filter { it.key !in seenKeys }.map { it.key }
            if (stale.isNotEmpty()) dao.deleteFiles(stale)
            dao.deleteOrphanItems()

            dao.upsertFolder(folder.copy(lastScan = System.currentTimeMillis(), fileCount = found.size))
            statusText.value = "Đã quét ${found.size} tệp trong ${folder.name}"
        } finally {
            scanning.value = false
        }
        AppGraph.scope.launch { generateMissingArtwork() }
    }

    /**
     * Items without TMDb artwork (home videos, concerts, unmatched titles) and Continue Watching entries
     * without a picture get a frame of their video, as Infuse does. Runs after each scan and once at launch.
     */
    suspend fun generateMissingArtwork(): Unit = withContext(Dispatchers.IO) {
        if (!artworkLock.tryLock()) return@withContext
        try {
            val items = dao.items().filter { !ThumbnailStore.isUsable(it.posterPath) && !ThumbnailStore.isUsable(it.backdropPath) }
            val filesByItem = dao.files().groupBy { it.itemKey }
            val servers = dao.servers().associate { it.id to it.credentials } +
                (com.xuanthanhtnt.xtplayer.network.LocalStorage.SERVER_ID to com.xuanthanhtnt.xtplayer.network.LocalStorage.credentials)
            val previous = statusText.value

            items.forEachIndexed { index, item ->
                val files = filesByItem[item.key].orEmpty().sortedWith(
                    compareBy<LibraryFile>({ it.season ?: 0 }, { it.episode ?: 0 }, { it.path })
                )
                val file = files.firstOrNull() ?: return@forEachIndexed
                val server = file.serverID?.let { servers[it] } ?: return@forEachIndexed
                val reference = ThumbnailStore.reference(file.key)
                val output = ThumbnailStore.file(reference) ?: return@forEachIndexed
                if (!output.exists()) {
                    statusText.value = "Tạo ảnh bìa ${index + 1}/${items.size}…"
                    ServerRegistry.register(server)
                    val probe = FrameGrabber.grab(RemoteFileSystems.playbackUri(server, file.path).toString(), output)
                        ?: return@forEachIndexed
                    MediaLibrary.learn(file.key, probe.resolutionLabel, probe.duration)
                }
                dao.item(item.key)?.let { dao.upsertItem(it.copy(posterPath = reference, backdropPath = reference)) }
                for (f in files) {
                    val entry = dao.entry(f.key)
                    if (entry != null && entry.artworkPath == null) dao.upsertEntry(entry.copy(artworkPath = reference))
                }
            }

            // Continue Watching entries that are not library files: opened from a share, from WebDAV or from this device.
            for (entry in dao.entries().filter { it.artworkPath == null }) {
                val source = grabSource(entry, servers) ?: continue
                val reference = ThumbnailStore.reference(entry.key)
                val output = ThumbnailStore.file(reference) ?: continue
                if (!output.exists() && FrameGrabber.grab(source, output) == null) continue
                dao.entry(entry.key)?.let { dao.upsertEntry(it.copy(artworkPath = reference)) }
            }
            statusText.value = previous
        } finally {
            artworkLock.unlock()
        }
    }

    private fun grabSource(entry: MediaEntry, servers: Map<String, ServerCredentials>): String? {
        val serverID = entry.serverID
        if (serverID != null) {
            val server = servers[serverID] ?: return null
            ServerRegistry.register(server)
        }
        return entry.uri
    }

    /** Re-fetch TMDb metadata for one item (e.g. after a wrong match or adding a key). */
    suspend fun refresh(item: LibraryItem): Unit = withContext(Dispatchers.IO) {
        val tmdb = TmdbClient.current() ?: return@withContext
        val parsed = ParsedMediaName(
            title = item.title, year = item.year,
            kind = if (item.kind == LibraryKind.SHOW) ParsedMediaName.Kind.Episode(1, 1) else ParsedMediaName.Kind.Movie,
            resolution = null, dynamicRange = emptyList(), audio = emptyList(),
        )
        dao.upsertItem(enrich(item.copy(tmdbID = null), parsed, tmdb))
    }

    // Recognition

    companion object {
        private val skippedFolders = setOf(
            "@eadir", "#recycle", "#snapshot", ".snapshots", "\$recycle.bin", "system volume information",
            "extras", "featurettes", "behind the scenes", "deleted scenes", "trailers", "sample", "samples", "other", "interviews",
        )
        private const val MAX_DEPTH = 7

        fun recognise(fileName: String, parentFolder: String): ParsedMediaName {
            var parsed = FilenameParser.parse(fileName)
            if (parsed.kind == ParsedMediaName.Kind.Movie) {
                val folder = FilenameParser.parse(parentFolder)
                val weakName = parsed.title.length < 3 || parsed.title.lowercase() in setOf("movie", "video", "main", "feature", "film")
                // "Dune (2021)/dune.2160p.mkv": the folder carries the canonical title + year.
                if (weakName || (parsed.year == null && folder.year != null)) {
                    parsed = parsed.copy(title = folder.title, year = folder.year ?: parsed.year)
                }
            }
            if (parsed.kind is ParsedMediaName.Kind.Episode && (parsed.title.isEmpty() || parsed.title.lowercase().startsWith("season"))) {
                parsed = parsed.copy(title = FilenameParser.parse(parentFolder).title)
            }
            return parsed
        }
    }

    private suspend fun collect(fs: RemoteFileSystem, root: String, progress: (Int) -> Unit): List<Found> {
        val results = mutableListOf<Found>()
        val queue = ArrayDeque<Triple<String, Int, String>>()
        queue.add(Triple(root, 0, root.trimEnd('/').substringAfterLast('/')))
        val showFolderName = mutableMapOf<String, String>()
        while (queue.isNotEmpty()) {
            val (path, depth, name) = queue.removeFirst()
            val items = try {
                fs.list(path)
            } catch (e: Exception) {
                if (depth == 0) throw e
                continue
            }
            val subtitles = items.filter { it.isSubtitle }
            val videos = items.filter { it.isVideo }
            // A "Season 01" folder inherits its show's name.
            val lower = name.lowercase()
            val parent = if (lower.startsWith("season") || lower.startsWith("mùa")) showFolderName[path] ?: name else name
            for (video in videos) {
                val base = video.baseName.lowercase()
                var matched = subtitles.filter { it.baseName.lowercase().startsWith(base) }
                if (matched.isEmpty() && videos.size == 1) matched = subtitles
                results += Found(video, parent, matched.map { it.path })
            }
            progress(results.size)
            if (depth >= MAX_DEPTH) continue
            for (dir in items) {
                if (!dir.isDirectory || dir.name.lowercase() in skippedFolders) continue
                showFolderName[dir.path] = parent
                queue.add(Triple(dir.path, depth + 1, dir.name))
            }
        }
        return results
    }

    // Persistence

    private suspend fun upsertItem(kind: LibraryKind, parsed: ParsedMediaName, tmdb: TmdbClient?): LibraryItem {
        val localKey = "local:${kind.raw}:${parsed.title.lowercase()}|${if (kind == LibraryKind.MOVIE) parsed.year ?: 0 else 0}"
        val existing = dao.item(localKey)
        if (existing != null) {
            if (existing.tmdbID == null && tmdb != null) {
                val enriched = enrich(existing, parsed, tmdb)
                dao.upsertItem(enriched)
                return enriched
            }
            return existing
        }
        var item = LibraryItem(key = localKey, kindRaw = kind.raw, title = parsed.title, year = parsed.year)
        if (tmdb != null) item = enrich(item, parsed, tmdb)
        dao.upsertItem(item)
        return item
    }

    private suspend fun enrich(item: LibraryItem, parsed: ParsedMediaName, tmdb: TmdbClient): LibraryItem = try {
        val details = if (item.kind == LibraryKind.MOVIE) {
            tmdb.searchMovie(parsed.title, parsed.year)?.let { tmdb.movie(it.id) }
        } else {
            tmdb.searchShow(parsed.title, null)?.let { tmdb.show(it.id) }
        }
        if (details == null) {
            item
        } else {
            item.copy(
                tmdbID = details.id,
                title = details.displayTitle ?: item.title,
                originalTitle = details.originalTitle ?: details.originalName,
                year = details.year ?: item.year,
                overview = details.overview,
                tagline = details.tagline,
                posterPath = details.posterPath,
                backdropPath = details.backdropPath,
                rating = details.voteAverage,
                runtime = details.runtime ?: details.episodeRunTime?.firstOrNull(),
                genres = details.genres?.map { it.name }.orEmpty(),
                cast = details.credits?.cast.orEmpty().take(15).map { "${it.name}|${it.character.orEmpty()}|${it.profilePath.orEmpty()}" },
                updatedAt = System.currentTimeMillis(),
            )
        }
    } catch (e: Exception) {
        error.value = "TMDb: ${e.message ?: e}"
        item
    }
}
