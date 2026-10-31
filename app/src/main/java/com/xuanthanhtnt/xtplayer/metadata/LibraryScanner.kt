package com.xuanthanhtnt.xtplayer.metadata

import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.data.LibraryFile
import com.xuanthanhtnt.xtplayer.data.LibraryFolder
import com.xuanthanhtnt.xtplayer.data.LibraryFolderKind
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

    /** A video or a track; tracks also carry their folder, the folder above it, and the cover image next to them. */
    private class Found(
        val item: RemoteItem,
        val parentName: String,
        val subtitles: List<String>,
        val directory: String = "",
        val directoryParent: String? = null,
        val cover: RemoteItem? = null,
    )

    /** What a music video's file and folder say: its title, year, and artist / show. */
    data class MusicVideoInfo(val title: String, val year: Int?, val artist: String?)

    /** What an album folder's name says: "Artist - Album (2019) [FLAC 24-96]". */
    data class AlbumName(val artist: String?, val title: String, val year: Int?)

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

            val kind = LibraryFolderKind.of(folder.key)
            val fs = RemoteFileSystems.make(server)
            val found = try {
                collect(fs, folder.path, audio = kind == LibraryFolderKind.MUSIC) { count ->
                    statusText.value = "Đang quét ${folder.name}: $count tệp…"
                }
            } catch (e: Exception) {
                error.value = e.message ?: e.toString()
                statusText.value = ""
                return@withContext
            }

            val seenKeys = mutableSetOf<String>()
            when (kind) {
                LibraryFolderKind.FILMS -> recogniseFilms(found, folder, server, seenKeys)
                LibraryFolderKind.MUSIC_VIDEOS -> recogniseMusicVideos(found, folder, server, seenKeys)
                LibraryFolderKind.MUSIC -> recogniseAlbums(found, fs, folder, server, seenKeys)
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
        // New library files may be ones Trakt already has as watched.
        AppGraph.scope.launch { AppGraph.trakt.sync() }
    }

    /** Movies and episodes: recognised from names, enriched from TMDb. */
    private suspend fun recogniseFilms(found: List<Found>, folder: LibraryFolder, server: ServerCredentials, seenKeys: MutableSet<String>) {
        val tmdb = TmdbClient.current()
        val showCache = mutableMapOf<String, LibraryItem>()
        val movieCache = mutableMapOf<String, LibraryItem>()
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

    }

    /**
     * One item per file, titled from its name, artist / show from its folder; no TMDb or Trakt lookup (a concert
     * would otherwise turn into an unrelated film). Artwork is a frame of the video.
     */
    private suspend fun recogniseMusicVideos(found: List<Found>, folder: LibraryFolder, server: ServerCredentials, seenKeys: MutableSet<String>) {
        found.forEachIndexed { index, entry ->
            statusText.value = "Nhận diện ${index + 1}/${found.size}…"
            val fileKey = RemoteFileSystems.key(server, entry.item.path)
            seenKeys.add(fileKey)
            val info = musicVideoInfo(entry.item.name, entry.item.baseName, entry.parentName, folder.name)
            val key = "musicvideo:$fileKey"
            val existing = dao.item(key)
            val item = (existing?.copy(kindRaw = LibraryKind.MUSIC_VIDEO.raw)
                ?: LibraryItem(key = key, kindRaw = LibraryKind.MUSIC_VIDEO.raw, title = info.title, year = null))
                .let { it.copy(year = info.year ?: it.year, tagline = info.artist) }
            dao.upsertItem(item)
            val file = (dao.file(fileKey) ?: LibraryFile(
                key = fileKey, itemKey = item.key, folderKey = folder.key, serverID = server.id,
                path = entry.item.path, fileName = entry.item.name, size = entry.item.size,
            )).copy(
                itemKey = item.key,
                folderKey = folder.key,
                size = entry.item.size,
                subtitlePaths = entry.subtitles,
                badges = FilenameParser.parse(entry.item.name).badges,
            )
            dao.upsertFile(file)
        }
    }

    /**
     * One album per folder of tracks ("CD1" / "Disc 2" folders join their parent). The album artist is the folder
     * above, or the part before " - " in "Artist - Album"; the cover is cover / folder / front.jpg next to the
     * tracks, else the picture embedded in the first track (see generateMissingArtwork).
     */
    private suspend fun recogniseAlbums(
        found: List<Found>,
        fs: RemoteFileSystem,
        folder: LibraryFolder,
        server: ServerCredentials,
        seenKeys: MutableSet<String>,
    ) {
        val albums = mutableMapOf<String, LibraryItem>()
        val covers = mutableMapOf<String, RemoteItem>()
        found.forEachIndexed { index, entry ->
            statusText.value = "Nhận diện ${index + 1}/${found.size}…"
            val fileKey = RemoteFileSystems.key(server, entry.item.path)
            seenKeys.add(fileKey)

            var albumPath = entry.directory
            var albumName = entry.parentName
            var artistName = entry.directoryParent
            var disc: Int? = null
            val number = discNumber(albumName)
            val parent = entry.directoryParent
            if (number != null && parent != null) {
                disc = number
                albumPath = deletingLastPathComponent(albumPath)
                albumName = parent
                artistName = null
            }
            val parsedAlbum = albumTitle(albumName)
            val key = "album:${server.id}:$albumPath"
            val previous = albums[key] ?: plainItem(key, LibraryKind.ALBUM, parsedAlbum.title)
            val album = previous.copy(
                title = parsedAlbum.title,
                year = parsedAlbum.year ?: previous.year,
                tagline = parsedAlbum.artist ?: (if (artistName == folder.name) null else artistName),
            )
            if (album != albums[key]) dao.upsertItem(album)
            albums[key] = album
            if (covers[key] == null) entry.cover?.let { covers[key] = it }

            val track = FilenameParser.track(entry.item.name)
            dao.upsertFile(
                libraryFile(entry, fileKey, album, folder, server).copy(
                    season = disc ?: track.disc,
                    episode = track.number,
                    episodeTitle = track.title,
                    badges = listOf(trackFormat(entry.item.fileExtension)),
                )
            )
        }

        for ((key, cover) in covers) {
            val album = albums[key] ?: continue
            if (ThumbnailStore.isUsable(album.posterPath)) continue
            val reference = ThumbnailStore.reference("cover:$key")
            val output = ThumbnailStore.file(reference) ?: continue
            val data = runCatching { fs.download(cover.path) }.getOrNull() ?: continue
            if (data.isEmpty()) continue
            if (runCatching { output.writeBytes(data) }.isFailure) continue
            dao.item(key)?.let { dao.upsertItem(it.copy(posterPath = reference, backdropPath = reference)) }
        }
    }

    private suspend fun plainItem(key: String, kind: LibraryKind, title: String): LibraryItem =
        dao.item(key)?.copy(kindRaw = kind.raw) ?: LibraryItem(key = key, kindRaw = kind.raw, title = title, year = null)

    private suspend fun libraryFile(entry: Found, key: String, item: LibraryItem, folder: LibraryFolder, server: ServerCredentials): LibraryFile =
        (dao.file(key) ?: LibraryFile(
            key = key, itemKey = item.key, folderKey = folder.key, serverID = server.id,
            path = entry.item.path, fileName = entry.item.name, size = entry.item.size,
        )).copy(itemKey = item.key, folderKey = folder.key, size = entry.item.size)

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
                    val location = RemoteFileSystems.playbackUri(server, file.path).toString()
                    if (item.kind == LibraryKind.ALBUM) {
                        // An album's picture is the cover embedded in its first track.
                        if (!FrameGrabber.grabEmbeddedPicture(location, output)) return@forEachIndexed
                    } else {
                        val probe = FrameGrabber.grab(location, output) ?: return@forEachIndexed
                        MediaLibrary.learn(file.key, probe.resolutionLabel, probe.duration)
                    }
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
        private val coverNames = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")
        private val albumYear = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
        private val bracketedTag = Regex("""\s*[(\[{][^)\]}]*[)\]}]""")

        /** "CD1", "CD 2", "Disc 3", "Disk 1" → disc number. */
        fun discNumber(name: String): Int? {
            val folded = name.lowercase().replace(" ", "")
            for (prefix in listOf("cd", "disc", "disk")) {
                if (folded.startsWith(prefix)) return folded.drop(prefix.length).toIntOrNull()
            }
            return null
        }

        /** "Artist - Album (2019) [FLAC 24-96]" → artist, album, year. */
        fun albumTitle(name: String): AlbumName {
            var title = name.replace('_', ' ')
            var year = albumYear.find(title)?.groupValues?.get(1)?.toIntOrNull()
            // Drop bracketed tags: years, formats, bit depths.
            title = title.replace(bracketedTag, "")
            var artist: String? = null
            val parts = title.split(" - ")
            val first = parts.first().trim()
            if (parts.size >= 2 && first.isNotEmpty()) {
                val leadingYear = first.toIntOrNull()
                if (leadingYear != null && leadingYear in 1900..2100) year = year ?: leadingYear else artist = first
                title = parts.drop(1).joinToString(" - ")
            }
            title = title.trim()
            return AlbumName(artist, title.ifEmpty { name }, year)
        }

        /** "Share/Artist/Album/CD1" → "Share/Artist/Album" (NSString `deletingLastPathComponent`). */
        internal fun deletingLastPathComponent(path: String): String {
            val trimmed = path.trimEnd('/')
            val slash = trimmed.lastIndexOf('/')
            return when {
                slash > 0 -> trimmed.substring(0, slash)
                slash == 0 -> "/"
                else -> ""
            }
        }

        /** A track's format chip, named as the player names its codec: "FLAC", "ALAC", "DSD", "WavPack", "PCM". */
        fun trackFormat(fileExtension: String): String = prettyCodec(if (fileExtension == "dff") "dsd" else codecName(fileExtension))

        private fun codecName(fileExtension: String): String = when (fileExtension) {
            "dsf", "dff" -> "dsd"
            "wv" -> "wavpack"
            "m4a", "alac" -> "alac"
            "aif", "aiff", "aifc", "wav", "w64" -> "pcm_$fileExtension"
            "thd", "truehd" -> "truehd"
            "dtshd" -> "dts"
            "ec3" -> "eac3"
            "oga", "ogg" -> "vorbis"
            "mpc" -> "mpc8"
            else -> fileExtension
        }

        /** MPVPlayerEngine.swift `prettyCodec`. */
        fun prettyCodec(codec: String): String {
            val lower = codec.lowercase()
            if (lower.startsWith("pcm_")) return "PCM"
            if (lower.startsWith("dsd_")) return "DSD"
            return when (lower) {
                "truehd" -> "TrueHD"
                "mlp" -> "MLP"
                "dst" -> "DSD (DST)"
                "wavpack" -> "WavPack"
                "alac" -> "ALAC"
                "ape" -> "APE"
                "tta" -> "TTA"
                "tak" -> "TAK"
                "mpc7", "mpc8" -> "Musepack"
                "vorbis" -> "Vorbis"
                "mp3", "mp3float" -> "MP3"
                "vvc" -> "H.266"
                "vp9" -> "VP9"
                "dts" -> "DTS"
                "eac3" -> "DD+"
                "ac3" -> "AC3"
                "flac" -> "FLAC"
                "aac" -> "AAC"
                "opus" -> "Opus"
                "hevc" -> "HEVC"
                "h264" -> "H.264"
                "av1" -> "AV1"
                "subrip" -> "SRT"
                "ass", "ssa" -> "ASS"
                "webvtt" -> "VTT"
                "hdmv_pgs_subtitle" -> "PGS"
                "dvd_subtitle" -> "VobSub"
                else -> codec.uppercase()
            }
        }

        /** A music video's title (from the file name, at least two characters), year, and artist / show (its folder). */
        fun musicVideoInfo(fileName: String, baseName: String, parentName: String, folderName: String): MusicVideoInfo {
            val parsed = FilenameParser.parse(fileName)
            val title = if (parsed.title.length >= 2) parsed.title else baseName
            return MusicVideoInfo(title, parsed.year, if (parentName == folderName) null else parentName)
        }

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

    /** Video files (with their subtitles), or audio tracks (with a cover image of their folder) for music folders. */
    private suspend fun collect(fs: RemoteFileSystem, root: String, audio: Boolean = false, progress: (Int) -> Unit): List<Found> {
        val results = mutableListOf<Found>()
        val queue = ArrayDeque<Triple<String, Int, String>>()
        queue.add(Triple(root, 0, root.trimEnd('/').substringAfterLast('/')))
        val showFolderName = mutableMapOf<String, String>()
        val parentName = mutableMapOf<String, String>()
        while (queue.isNotEmpty()) {
            val (path, depth, name) = queue.removeFirst()
            val items = try {
                fs.list(path)
            } catch (e: Exception) {
                if (depth == 0) throw e
                continue
            }
            if (audio) {
                val tracks = items.filter { it.isAudio }
                val images = items.filter { !it.isDirectory && it.fileExtension in setOf("jpg", "jpeg", "png") && it.size < 15_000_000 }
                val cover = coverNames.firstNotNullOfOrNull { wanted -> images.firstOrNull { it.baseName.lowercase() == wanted } }
                    ?: images.firstOrNull()
                for (track in tracks) {
                    results += Found(
                        track, name, emptyList(),
                        directory = path,
                        directoryParent = if (depth == 0) null else parentName[path],
                        cover = cover,
                    )
                }
            }
            val subtitles = items.filter { it.isSubtitle }
            val videos = if (audio) emptyList() else items.filter { it.isVideo }
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
                // The folder above an album (its artist); never the library folder itself.
                if (depth > 0) parentName[dir.path] = name
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
