package com.xuanthanhtnt.xtplayer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.network.ServerCredentials
import com.xuanthanhtnt.xtplayer.network.ServerKind
import java.util.UUID

/** A NAS / file server the user added. The password lives in the SecureStore under "server.<id>". */
@Entity(tableName = "servers")
data class MediaServer(
    @PrimaryKey val id: String = UUID.randomUUID().toString().lowercase(),
    val name: String,
    val kindRaw: String,
    val host: String,
    /** 0 = protocol default (445 for SMB, 80/443 for WebDAV). */
    val port: Int = 0,
    val username: String = "",
    val domain: String = "",
    val useTLS: Boolean = false,
    /** WebDAV root, e.g. "/" or "/dav/media". */
    val basePath: String = "/",
    val allowInsecureTLS: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val kind: ServerKind get() = ServerKind.from(kindRaw)

    val displayHost: String get() = if (port > 0) "$host:$port" else host

    /** Reads the password from the SecureStore — call once per operation, not per row render. */
    val credentials: ServerCredentials
        get() = ServerCredentials(
            id = id, kind = kind, host = host, port = port, username = username,
            password = AppGraph.secrets.get("server.$id") ?: "", domain = domain,
            useTLS = useTLS, basePath = basePath, allowInsecureTLS = allowInsecureTLS,
        )

    fun setPassword(password: String) = AppGraph.secrets.set("server.$id", password)
}

/** A folder of a server that feeds the library (scanned recursively). */
@Entity(tableName = "folders", indices = [Index("serverID")])
data class LibraryFolder(
    @PrimaryKey val key: String,
    val serverID: String,
    val path: String,
    val name: String,
    val lastScan: Long? = null,
    val fileCount: Int = 0,
) {
    companion object {
        fun key(serverID: String, path: String) = "$serverID:$path"
    }
}

enum class LibraryKind(val raw: String) {
    MOVIE("movie"), SHOW("show");

    companion object {
        fun from(raw: String) = entries.firstOrNull { it.raw == raw } ?: MOVIE
    }
}

/** A movie or a TV show, enriched from TMDb when an API key is configured. */
@Entity(tableName = "items")
data class LibraryItem(
    @PrimaryKey val key: String,
    val kindRaw: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val rating: Double? = null,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    val tmdbID: Int? = null,
    /** "Name|Character|/profile.jpg" */
    val cast: List<String> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val kind: LibraryKind get() = LibraryKind.from(kindRaw)

    val castMembers: List<CastMember>
        get() = cast.mapNotNull { raw ->
            val parts = raw.split('|')
            val name = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CastMember(name, parts.getOrNull(1).orEmpty(), parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }
}

data class CastMember(val name: String, val character: String, val profilePath: String?)

/** One playable file of the library (a movie version or an episode). */
@Entity(tableName = "files", indices = [Index("itemKey"), Index("folderKey")])
data class LibraryFile(
    /** Same as the playback key of the file, so progress is shared with [MediaEntry]. */
    @PrimaryKey val key: String,
    val itemKey: String,
    val folderKey: String,
    val serverID: String?,
    /** SMB: "Share/dir/file.mkv"; WebDAV: "/dav/dir/file.mkv". */
    val path: String,
    val fileName: String,
    val size: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeOverview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val subtitlePaths: List<String> = emptyList(),
    val badges: List<String> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
) {
    val episodeCode: String?
        get() {
            val s = season ?: return null
            val e = episode ?: return null
            return "S%02dE%02d".format(s, e)
        }
}

/**
 * One playable item the user has opened, with its resume point ("Continue Watching").
 * `key` is the credential-free playback URI, shared with [LibraryFile.key].
 */
@Entity(tableName = "entries")
data class MediaEntry(
    @PrimaryKey val key: String,
    val title: String,
    /** Credential-free location (content:// or file path, xtsmb://server/share/path, xtdav://server/path, https://…). */
    val uri: String,
    val isLocalFile: Boolean,
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val tags: List<String> = emptyList(),
    /** Network source the file lives on (credentials are fetched at play time). */
    val serverID: String? = null,
    /** Secondary line, e.g. the show name for an episode. */
    val subtitle: String? = null,
    /** TMDb image path (still or backdrop) or local frame for the Continue Watching card. */
    val artworkPath: String? = null,
) {
    /** 0…1, or null when nothing worth resuming has been watched yet. */
    val progress: Double?
        get() = if (duration > 0 && position > 0) minOf(1.0, position / duration) else null

    /** Where playback should start: resume unless the item was (nearly) finished. */
    val resumePosition: Double?
        get() = if (position > 30 && (duration <= 0 || position < duration - 60)) position else null

    val isWatched: Boolean get() = duration > 0 && position >= duration - 60
}
