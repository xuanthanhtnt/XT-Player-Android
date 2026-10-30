package com.xuanthanhtnt.xtplayer.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.xuanthanhtnt.xtplayer.core.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 sessions through smbj (pure Java). One authenticated session per server, shared by browsing,
 * scanning and streaming; smbj multiplexes concurrent requests over its TCP connection.
 */
object SmbConnections {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withDfsEnabled(false)
            .withReadBufferSize(1 shl 20)
            .build()
    )

    private class Entry(val server: ServerCredentials, val session: Session) {
        val shares = HashMap<String, DiskShare>()
    }

    private val sessions = HashMap<String, Entry>()

    @Synchronized
    private fun entry(server: ServerCredentials): Entry {
        val existing = sessions[server.id]
        if (existing != null && existing.server == server && existing.session.connection.isConnected) return existing
        existing?.let { runCatching { it.session.close() } }
        val connection = if (server.port > 0) client.connect(server.host, server.port) else client.connect(server.host)
        val session = try {
            login(connection, server)
        } catch (e: Exception) {
            runCatching { connection.close() }
            throw e
        }
        return Entry(server, session).also { sessions[server.id] = it }
    }

    /** NTLMv2; an empty user name tries guest, then anonymous. */
    private fun login(connection: com.hierynomus.smbj.connection.Connection, server: ServerCredentials): Session {
        if (server.username.isEmpty()) {
            return runCatching { connection.authenticate(AuthenticationContext.guest()) }
                .getOrElse { connection.authenticate(AuthenticationContext.anonymous()) }
        }
        return connection.authenticate(
            AuthenticationContext(server.username, server.password.toCharArray(), server.domain.ifEmpty { null })
        )
    }

    fun session(server: ServerCredentials): Session = entry(server).session

    @Synchronized
    fun share(server: ServerCredentials, name: String): DiskShare {
        val entry = entry(server)
        entry.shares[name]?.takeIf { it.isConnected }?.let { return it }
        val share = entry.session.connectShare(name) as? DiskShare ?: throw RemoteException("“$name” không phải thư mục chia sẻ.")
        entry.shares[name] = share
        return share
    }

    @Synchronized
    fun reset(serverID: String) {
        sessions.remove(serverID)?.let { entry ->
            entry.shares.values.forEach { runCatching { it.close() } }
            runCatching { entry.session.close() }
        }
    }

    /** Runs [block], reconnecting once when the NAS dropped the session (idle timeout, sleep, Wi-Fi switch). */
    fun <T> withRetry(server: ServerCredentials, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        reset(server.id)
        block()
    }

    /** "Share/dir/file.mkv" → share name + path inside the share ("dir\file.mkv"). */
    fun split(path: String): Pair<String, String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw RemoteException("Đường dẫn SMB không hợp lệ.")
        return parts[0] to parts.drop(1).joinToString("\\")
    }

    fun openRead(server: ServerCredentials, path: String): File {
        val (shareName, inner) = split(path)
        return withRetry(server) {
            share(server, shareName).openFile(
                inner,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        }
    }

    fun size(file: File): Long = file.getFileInformation(FileStandardInformation::class.java).endOfFile
}

/** Browsing an SMB server: shares at the root (DCE/RPC NetShareEnum), then directories. */
class SmbFileSystem(private val server: ServerCredentials) : RemoteFileSystem {
    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@withContext listShares()
        val share = parts[0]
        val inner = parts.drop(1).joinToString("\\")
        val entries = SmbConnections.withRetry(server) { SmbConnections.share(server, share).list(inner) }
        entries
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .filter { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value == 0L }
            .map { info ->
                RemoteItem(
                    name = info.fileName,
                    path = (parts + info.fileName).joinToString("/"),
                    isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = info.endOfFile,
                    modified = runCatching { info.lastWriteTime.toEpochMillis() }.getOrNull(),
                )
            }
    }

    private fun listShares(): List<RemoteItem> = SmbConnections.withRetry(server) {
        val transport = SMBTransportFactories.SRVSVC.getTransport(SmbConnections.session(server))
        ServerService(transport).shares1
            // Disk shares only (type 0, ignoring the special / temporary flags), no hidden "$" shares.
            .filter { (it.type and 0x0FFFFFFF) == 0 && !it.netName.endsWith("$") }
            .map { RemoteItem(it.netName, it.netName, isDirectory = true, size = 0, modified = null) }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }
}
