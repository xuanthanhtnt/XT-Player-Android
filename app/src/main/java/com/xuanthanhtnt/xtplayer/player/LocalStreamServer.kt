package com.xuanthanhtnt.xtplayer.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.Http
import com.xuanthanhtnt.xtplayer.network.RemoteFileSystems
import com.xuanthanhtnt.xtplayer.network.ServerRegistry
import okhttp3.Request
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Serves the playing file over plain HTTP on the LAN (GET / HEAD with byte ranges) so a Chromecast can fetch it —
 * the Android counterpart of the Apple app's AirPlay server. SMB goes through [SmbFileReader], WebDAV is proxied
 * with the server's login, files on this device are read directly. No transcoding: the receiver gets the original.
 */
object LocalStreamServer {
    private var socket: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "xt-cast-http").apply { isDaemon = true } }
    private val published = ConcurrentHashMap<String, String>()

    /** An http://<this device>:<port>/… URL for [location], or null when the device has no LAN address. */
    @Synchronized
    fun publish(location: String, fileName: String): String? {
        val address = lanAddress(AppGraph.context) ?: return null
        val server = socket?.takeIf { !it.isClosed } ?: ServerSocket(0, 16, InetAddress.getByName("0.0.0.0")).also {
            socket = it
            workers.execute { accept(it) }
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        published[id] = location
        return "http://$address:${server.localPort}/s/$id/${Uri.encode(fileName)}"
    }

    @Synchronized
    fun stop() {
        published.clear()
        runCatching { socket?.close() }
        socket = null
    }

    fun mimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "webm" -> "video/webm"
        // Chromecast's media stack demuxes Matroska when it is announced as WebM (its subset).
        "mkv", "mka" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        else -> "video/mp4"
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            workers.execute { runCatching { client.use(::handle) } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
            .toMap()
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1) ?: return
        val out = client.getOutputStream()
        val id = path.removePrefix("/s/").substringBefore('/')
        val location = published[id]
        if (!path.startsWith("/s/") || location == null) return status(out, 404, "Not Found")
        if (method != "GET" && method != "HEAD") return status(out, 405, "Method Not Allowed")

        val name = Uri.decode(path.substringAfterLast('/'))
        val source = try {
            open(location)
        } catch (e: Exception) {
            return status(out, 502, "Bad Gateway")
        }
        source.use {
            val size = it.size
            val range = headers["range"]?.let { value -> parseRange(value, size) }
            if (headers.containsKey("range") && range == null) {
                return status(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$size\r\n")
            }
            val start = range?.first ?: 0L
            val end = range?.last ?: (size - 1)
            val length = (end - start + 1).coerceAtLeast(0)
            val head = buildString {
                append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${mimeType(name)}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            if (method == "HEAD" || length == 0L) return out.flush()
            it.copy(start, length, out)
            out.flush()
        }
    }

    private fun status(out: OutputStream, code: Int, text: String, extra: String = "") {
        out.write("HTTP/1.1 $code $text\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** "bytes=a-b", "bytes=a-", "bytes=-n" → the inclusive range, or null when it is outside the file. */
    private fun parseRange(value: String, size: Long): LongRange? {
        val spec = value.removePrefix("bytes=").substringBefore(',').trim()
        val (a, b) = spec.split('-', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
        val range = when {
            a.isEmpty() && b.isNotEmpty() -> (size - b.toLong()).coerceAtLeast(0)..(size - 1)
            a.isNotEmpty() -> a.toLong()..(if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1))
            else -> return null
        }
        return if (range.first in 0 until size && range.last >= range.first) range else null
    }

    // Sources

    private abstract class Source : Closeable {
        abstract val size: Long
        abstract fun stream(start: Long, length: Long): InputStream

        fun copy(start: Long, length: Long, out: OutputStream) {
            stream(start, length).use { input ->
                val buffer = ByteArray(256 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    left -= n
                }
            }
        }
    }

    private fun open(location: String): Source {
        val uri = Uri.parse(location)
        return when (uri.scheme) {
            RemoteFileSystems.SMB_SCHEME -> SmbSource(SmbFileReader.acquire(uri))
            RemoteFileSystems.DAV_SCHEME -> DavSource(location)
            "content" -> ContentSource(uri)
            "http", "https" -> DavSource(location)
            else -> FileSource(File(uri.path ?: location))
        }
    }

    private class SmbSource(private val reader: SmbFileReader) : Source() {
        override val size get() = reader.size
        override fun stream(start: Long, length: Long) = object : InputStream() {
            private var position = start
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) <= 0) -1 else it[0].toInt() and 0xFF }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = reader.read(position, b, off, len)
                if (n > 0) position += n
                return n
            }
        }
        override fun close() = reader.release()
    }

    private class FileSource(file: File) : Source() {
        private val raf = RandomAccessFile(file, "r")
        override val size = raf.length()
        override fun stream(start: Long, length: Long): InputStream {
            raf.seek(start)
            return object : InputStream() {
                override fun read(): Int = raf.read()
                override fun read(b: ByteArray, off: Int, len: Int) = raf.read(b, off, len)
            }
        }
        override fun close() = raf.close()
    }

    private class ContentSource(uri: Uri) : Source() {
        private val descriptor: ParcelFileDescriptor = AppGraph.context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("cannot open $uri")
        private val input = FileInputStream(descriptor.fileDescriptor)
        override val size = input.channel.size()
        override fun stream(start: Long, length: Long): InputStream {
            input.channel.position(start)
            return object : InputStream() {
                override fun read(): Int = input.read()
                override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
            }
        }
        override fun close() {
            runCatching { input.close() }
            runCatching { descriptor.close() }
        }
    }

    /** WebDAV (with the server's login and TLS policy) or a plain URL, fetched with the same byte range. */
    private class DavSource(location: String) : Source() {
        private val uri = Uri.parse(location)
        private val server = if (uri.scheme == RemoteFileSystems.DAV_SCHEME) ServerRegistry.get(uri.authority ?: "") else null
        private val url = if (server != null) RemoteFileSystems.webDavUrl(server, uri) else location
        private val client = server?.let(Http::forServer) ?: Http.client
        private var body: okhttp3.ResponseBody? = null

        override val size: Long = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { response ->
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: throw IOException("no size")
        }

        override fun stream(start: Long, length: Long): InputStream {
            val response = client.newCall(Request.Builder().url(url).header("Range", "bytes=$start-${start + length - 1}").build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code}")
            }
            body = response.body
            return response.body?.byteStream() ?: throw IOException("empty body")
        }

        override fun close() {
            runCatching { body?.close() }
        }
    }

    /** IPv4 address of the Wi-Fi / Ethernet network (not a VPN), which the Chromecast can reach. */
    fun lanAddress(context: Context): String? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        @Suppress("DEPRECATION")
        connectivity?.allNetworks?.forEach { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@forEach
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@forEach
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@forEach
            connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress?.let { return it }
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
    }
}
