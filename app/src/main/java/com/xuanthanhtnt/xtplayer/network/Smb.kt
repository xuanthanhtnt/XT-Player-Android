package com.xuanthanhtnt.xtplayer.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 sessions through smbj (pure Java). One authenticated session per server, shared by browsing, scanning
 * and streaming; smbj multiplexes concurrent requests over its TCP connection.
 *
 * Staying connected: no socket read timeout (smbj closes the connection whenever its reader thread times out, i.e.
 * after any idle minute — while the player's buffer is full, or a page is left open); a dead connection is
 * found by the request timeout instead, dropped without talking to the NAS (a logoff on a dead socket waits for
 * the full timeout), and only by the user whose session failed, so one error does not tear down the others.
 */
object SmbConnections {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withDfsEnabled(false)
            .withReadBufferSize(1 shl 20)
            .build()
    )

    /** A logged-in session and the shares opened on it. */
    class Handle(val server: ServerCredentials, val session: Session) {
        internal val shares = HashMap<String, DiskShare>()

        @Synchronized
        fun share(name: String): DiskShare {
            shares[name]?.takeIf { it.isConnected }?.let { return it }
            val share = session.connectShare(name) as? DiskShare ?: throw RemoteException("“$name” không phải thư mục chia sẻ.")
            shares[name] = share
            return share
        }
    }

    private val handles = HashMap<String, Handle>()

    @Synchronized
    fun handle(server: ServerCredentials): Handle {
        handles[server.id]?.let { existing ->
            if (existing.server == server && existing.session.connection.isConnected) return existing
            handles.remove(server.id)
            drop(existing)
        }
        val address = resolve(server.host)
        val connection = if (server.port > 0) client.connect(address, server.port) else client.connect(address)
        val session = try {
            login(connection, server)
        } catch (e: Exception) {
            runCatching { connection.close(true) }
            throw e
        }
        return Handle(server, session).also { handles[server.id] = it }
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

    fun session(server: ServerCredentials): Session = handle(server).session

    /** Closes the socket straight away (no logoff / tree disconnect round trips on a connection that may be dead). */
    private fun drop(handle: Handle) {
        runCatching { handle.session.connection.close(true) }
    }

    /** The session that failed is replaced on next use — unless another user already replaced it. */
    @Synchronized
    fun invalidate(server: ServerCredentials, failed: Session) {
        val current = handles[server.id] ?: return
        if (current.session === failed) {
            handles.remove(server.id)
            drop(current)
        }
    }

    @Synchronized
    fun reset(serverID: String) {
        handles.remove(serverID)?.let(::drop)
    }

    /** Wi-Fi changed or dropped: every socket is stale. */
    @Synchronized
    fun resetAll() {
        handles.values.forEach(::drop)
        handles.clear()
    }

    /** Errors that a new connection will not fix (wrong password, missing share or file). */
    private fun isPermanent(e: Throwable): Boolean {
        if (e is RemoteException) return true
        val status = (e as? SMBApiException)?.status ?: (e.cause as? SMBApiException)?.status ?: return false
        return status in setOf(
            NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
            NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE, NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED,
        )
    }

    /**
     * Runs [block] on the server's session, reconnecting up to twice (after 0.3 s, then 1.5 s) when the NAS dropped
     * the connection (idle timeout, sleep, Wi-Fi roaming, VPN reconnect).
     */
    fun <T> withRetry(server: ServerCredentials, block: (Handle) -> T): T {
        var attempt = 0
        while (true) {
            var current: Handle? = null
            try {
                current = handle(server)
                return block(current)
            } catch (e: Exception) {
                if (isPermanent(e) || attempt >= 2) throw e
                current?.let { invalidate(server, it.session) }
                Thread.sleep(if (attempt == 0) 300L else 1500L)
                attempt++
            }
        }
    }

    /** "Share/dir/file.mkv" → share name + path inside the share ("dir\file.mkv"). */
    fun split(path: String): Pair<String, String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw RemoteException("Đường dẫn SMB không hợp lệ.")
        return parts[0] to parts.drop(1).joinToString("\\")
    }

    class OpenFile(val file: File, val session: Session)

    fun openRead(server: ServerCredentials, path: String): OpenFile {
        val (shareName, inner) = split(path)
        return withRetry(server) { handle ->
            OpenFile(
                handle.share(shareName).openFile(
                    inner,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ),
                handle.session,
            )
        }
    }

    fun size(file: File): Long = file.getFileInformation(FileStandardInformation::class.java).endOfFile

    /**
     * The NAS address. A name ("dsm.xtin.local") is looked up each time a session starts, and the last address that
     * worked is remembered: when the local DNS / mDNS answer is missing for a moment, the NAS is still reachable.
     */
    private fun resolve(host: String): String {
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return host
        val key = "smb.address.$host"
        val prefs = com.xuanthanhtnt.xtplayer.AppGraph.settings.prefs
        return try {
            val addresses = InetAddress.getAllByName(host)
            val address = (addresses.firstOrNull { it is Inet4Address } ?: addresses.first()).hostAddress ?: host
            prefs.edit().putString(key, address).apply()
            address
        } catch (e: UnknownHostException) {
            prefs.getString(key, null) ?: throw RemoteException("Không tìm thấy máy chủ “$host” — kiểm tra Wi-Fi / VPN, hoặc nhập địa chỉ IP của NAS.")
        }
    }
}

/** Browsing an SMB server: shares at the root (DCE/RPC NetShareEnum), then directories. */
class SmbFileSystem(private val server: ServerCredentials) : RemoteFileSystem {
    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@withContext listShares()
        val share = parts[0]
        val inner = parts.drop(1).joinToString("\\")
        val entries = SmbConnections.withRetry(server) { it.share(share).list(inner) }
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

    override suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        val open = SmbConnections.openRead(server, path)
        try {
            val data = ByteArray(SmbConnections.size(open.file).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            var filled = 0
            while (filled < data.size) {
                val n = open.file.read(data, filled.toLong(), filled, data.size - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled == data.size) data else data.copyOf(filled)
        } finally {
            runCatching { open.file.close() }
        }
    }

    private fun listShares(): List<RemoteItem> = SmbConnections.withRetry(server) { handle ->
        val transport = SMBTransportFactories.SRVSVC.getTransport(handle.session)
        ServerService(transport).shares1
            // Disk shares only (type 0, ignoring the special / temporary flags), no hidden "$" shares.
            .filter { (it.type and 0x0FFFFFFF) == 0 && !it.netName.endsWith("$") }
            .map { RemoteItem(it.netName, it.netName, isDirectory = true, size = 0, modified = null) }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }
}
