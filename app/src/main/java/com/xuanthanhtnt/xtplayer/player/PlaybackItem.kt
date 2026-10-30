package com.xuanthanhtnt.xtplayer.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.data.LibraryFile
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.data.MediaEntry
import com.xuanthanhtnt.xtplayer.metadata.FilenameParser
import com.xuanthanhtnt.xtplayer.network.RemoteFileSystems
import com.xuanthanhtnt.xtplayer.network.RemoteItem
import com.xuanthanhtnt.xtplayer.network.ServerCredentials
import com.xuanthanhtnt.xtplayer.network.ServerRegistry

/** One entry of the play queue. */
data class PlaybackItem(
    /** xtsmb://, xtdav://, content://, an absolute path, or http(s):// — never with credentials. */
    val uri: String,
    val title: String,
    /** The file name (for recognising the title, Trakt and OpenSubtitles). */
    val fileName: String,
    val subtitle: String? = null,
    /** TMDb image path (or local frame) for Now Playing / Continue Watching. */
    val artworkPath: String? = null,
    val server: ServerCredentials? = null,
    /** Sidecar subtitle locations (playback URIs) loaded with the file. */
    val externalSubtitles: List<String> = emptyList(),
    val startAt: Double? = null,
) {
    val key: String get() = uri
    val isLocal: Boolean get() = uri.startsWith("/") || uri.startsWith("content:") || uri.startsWith("file:")
    val displayName: String get() = fileName
}

/** What the player is asked to play: a queue and where to start. */
data class PlaybackSession(val items: List<PlaybackItem>, val startIndex: Int = 0) {
    val safeStart: Int get() = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
}

/** Starts [PlayerActivity] with a queue (kept in memory: items carry server credentials). */
object PlayerLauncher {
    @Volatile
    var pending: PlaybackSession? = null

    fun play(context: Context, session: PlaybackSession) {
        if (session.items.isEmpty()) return
        session.items.mapNotNull { it.server }.forEach(ServerRegistry::register)
        pending = session
        val intent = Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Builds playback items from the different places media comes from. */
object PlaybackFactory {
    private val dao get() = AppGraph.dao

    /** A file picked with the system picker (or opened from another app). */
    suspend fun local(context: Context, uri: Uri): PlaybackItem {
        val name = displayName(context, uri)
        val key = if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString()
        return PlaybackItem(
            uri = key,
            title = FilenameParser.parse(name).displayTitle,
            fileName = name,
            startAt = dao.entry(key)?.resumePosition,
        )
    }

    /** "nas.local:5005/movies/x.mkv", "https://…" → a direct URL item; credentials are kept out of the key. */
    suspend fun url(text: String): PlaybackItem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = Uri.parse(candidate)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        val name = Uri.decode(uri.lastPathSegment ?: uri.host ?: candidate)
        val item = PlaybackItem(uri = candidate, title = FilenameParser.parse(name).displayTitle, fileName = name)
        return item.copy(startAt = dao.entry(item.key)?.resumePosition)
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
                }
            }
        }
        return Uri.decode(uri.lastPathSegment ?: uri.toString()).substringAfterLast('/')
    }

    /** A file picked in the network browser; siblings become the queue (folder order), sidecar subtitles attach. */
    suspend fun remote(files: List<RemoteItem>, subtitles: List<RemoteItem>, server: ServerCredentials): List<PlaybackItem> =
        files.map { file ->
            val base = file.baseName.lowercase()
            var subs = subtitles.filter { it.baseName.lowercase().startsWith(base) }
            if (subs.isEmpty() && files.size == 1) subs = subtitles
            val key = RemoteFileSystems.key(server, file.path)
            PlaybackItem(
                uri = key,
                title = FilenameParser.parse(file.name).displayTitle,
                fileName = file.name,
                server = server,
                externalSubtitles = subs.map { RemoteFileSystems.key(server, it.path) },
                startAt = dao.entry(key)?.resumePosition,
            )
        }

    /** Library files of one item (a movie's versions, or a show's episodes in order). */
    suspend fun library(files: List<LibraryFile>, item: LibraryItem): List<PlaybackItem> {
        val servers = mutableMapOf<String, ServerCredentials?>()
        return files.mapNotNull { file ->
            val serverID = file.serverID ?: return@mapNotNull null
            val server = servers.getOrPut(serverID) { ServerRegistry.lookup(serverID) } ?: return@mapNotNull null
            val code = file.episodeCode
            val (title, subtitle) = if (item.kind == LibraryKind.SHOW && code != null) {
                (file.episodeTitle?.let { "$code · $it" } ?: code) to item.title
            } else {
                (item.year?.let { "${item.title} ($it)" } ?: item.title) to file.badges.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            }
            PlaybackItem(
                uri = RemoteFileSystems.key(server, file.path),
                title = title,
                fileName = file.fileName,
                subtitle = subtitle,
                artworkPath = file.stillPath ?: item.backdropPath ?: item.posterPath,
                server = server,
                externalSubtitles = file.subtitlePaths.map { RemoteFileSystems.key(server, it) },
                startAt = dao.entry(file.key)?.resumePosition,
            )
        }
    }

    /** Re-opens a Continue Watching entry. */
    suspend fun resume(entry: MediaEntry): PlaybackItem? {
        val server = entry.serverID?.let { id -> ServerRegistry.lookup(id) ?: return null }
        val file = dao.file(entry.key)
        val name = file?.fileName ?: Uri.decode(Uri.parse(entry.uri).lastPathSegment ?: entry.title)
        return PlaybackItem(
            uri = entry.uri,
            title = entry.title,
            fileName = name,
            subtitle = entry.subtitle,
            artworkPath = entry.artworkPath,
            server = server,
            externalSubtitles = if (server != null && file != null) file.subtitlePaths.map { RemoteFileSystems.key(server, it) } else emptyList(),
            startAt = entry.resumePosition,
        )
    }
}
