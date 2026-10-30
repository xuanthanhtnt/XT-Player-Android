package com.xuanthanhtnt.xtplayer.network

import android.net.Uri
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.Http
import com.xuanthanhtnt.xtplayer.metadata.FilenameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

enum class ServerKind(val raw: String, val label: String) {
    SMB("smb", "SMB (Windows / NAS)"),
    WEBDAV("webdav", "WebDAV"),
    /** This device: internal storage, SD cards and USB drives (a built-in source, not a saved server). */
    LOCAL("local", "Thiết bị này");

    companion object {
        fun from(raw: String) = entries.firstOrNull { it.raw == raw } ?: SMB
    }
}

/** Everything needed to talk to a server, safe to hand to background threads (the player's loader, scanners). */
data class ServerCredentials(
    val id: String,
    val kind: ServerKind,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val domain: String,
    val useTLS: Boolean,
    val basePath: String,
    val allowInsecureTLS: Boolean,
) {
    val displayHost: String get() = if (port > 0) "$host:$port" else host

    /** http(s)://host[:port] for WebDAV. */
    val httpBase: String
        get() = (if (useTLS) "https://" else "http://") + (if (host.contains(':') && !host.startsWith("[")) "[$host]" else host) +
            (if (port > 0) ":$port" else "")

    override fun toString() = "ServerCredentials($id, $kind, $displayHost)"
}

/** One entry of a remote directory listing. */
data class RemoteItem(
    val name: String,
    /** SMB: "Share/dir/name"; WebDAV: absolute server path "/dav/dir/name". */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long?,
) {
    val fileExtension: String get() = name.substringAfterLast('.', "").lowercase()
    val isVideo: Boolean get() = !isDirectory && fileExtension in FilenameParser.videoExtensions
    val isSubtitle: Boolean get() = !isDirectory && fileExtension in FilenameParser.subtitleExtensions
    val baseName: String get() = if (name.contains('.')) name.substringBeforeLast('.') else name
}

interface RemoteFileSystem {
    /** Lists `path` ("" = root: SMB shares, or the WebDAV base path). */
    suspend fun list(path: String): List<RemoteItem>
}

class RemoteException(message: String) : Exception(message)

object RemoteFileSystems {
    const val SMB_SCHEME = "xtsmb"
    const val DAV_SCHEME = "xtdav"

    fun make(server: ServerCredentials): RemoteFileSystem = when (server.kind) {
        ServerKind.SMB -> SmbFileSystem(server)
        ServerKind.WEBDAV -> WebDavFileSystem(server)
        ServerKind.LOCAL -> LocalFileSystem()
    }

    /**
     * Playback URI handed to the player. Credentials are looked up by server id at read time
     * ([ServerRegistry]) and never put in the URI, so it doubles as the library / resume key.
     */
    fun playbackUri(server: ServerCredentials, path: String): Uri = when (server.kind) {
        ServerKind.SMB -> Uri.Builder().scheme(SMB_SCHEME).authority(server.id).path("/" + path.trim('/')).build()
        ServerKind.WEBDAV -> Uri.Builder().scheme(DAV_SCHEME).authority(server.id).path(if (path.startsWith("/")) path else "/$path").build()
        ServerKind.LOCAL -> Uri.fromFile(java.io.File(path))
    }

    /** Credential-free identity of a remote file (shared by the library and the resume points); a plain path on this device. */
    fun key(server: ServerCredentials, path: String): String =
        if (server.kind == ServerKind.LOCAL) path else playbackUri(server, path).toString()

    /** The real http(s) URL of an `xtdav://` URI. */
    fun webDavUrl(server: ServerCredentials, uri: Uri): String =
        server.httpBase + (uri.encodedPath ?: "/")
}

/** Credentials by server id for the streaming code, which only sees `xtsmb://<id>/…` URIs. */
object ServerRegistry {
    private val servers = ConcurrentHashMap<String, ServerCredentials>()

    fun register(server: ServerCredentials) {
        servers[server.id] = server
    }

    /** Blocking (database) on a miss — call from a background thread. */
    fun get(id: String): ServerCredentials? = if (id == LocalStorage.SERVER_ID) LocalStorage.credentials else servers[id] ?: runBlocking(Dispatchers.IO) {
        AppGraph.dao.server(id)?.credentials
    }?.also { register(it) }

    /** A saved server, or the built-in "this device" source. */
    suspend fun lookup(id: String): ServerCredentials? =
        if (id == LocalStorage.SERVER_ID) LocalStorage.credentials else AppGraph.dao.server(id)?.credentials

    suspend fun preload() {
        AppGraph.dao.servers().forEach { register(it.credentials) }
    }

    fun forget(id: String) {
        servers.remove(id)
    }
}

/** Drops cached connections of a server (after editing / removing it, or "Thử lại" in the browser). */
object RemoteFileSystemPool {
    fun reset(serverID: String) {
        SmbConnections.reset(serverID)
        Http.forget(serverID)
        ServerRegistry.forget(serverID)
    }
}
