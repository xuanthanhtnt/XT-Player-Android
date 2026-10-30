package com.xuanthanhtnt.xtplayer.player

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File
import com.xuanthanhtnt.xtplayer.network.ServerCredentials
import com.xuanthanhtnt.xtplayer.network.ServerRegistry
import com.xuanthanhtnt.xtplayer.network.SmbConnections
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Reads one SMB file for the player, the artwork grabber and the subtitle hash.
 *
 * Same design as the iOS bridge that fixed constant re-buffering on a NAS: 1 MiB blocks, several reads in
 * flight at once (smbj pipelines them over the session), read-ahead that grows while playback is sequential
 * and resets on a seek, and an LRU of blocks so a demuxer jumping between distant regions of a non-interleaved
 * MP4 does not download the same data again. Readers are shared by URI and stay open briefly after the last
 * user, so the player re-opening the source at a new position costs nothing.
 */
class SmbFileReader private constructor(
    private val server: ServerCredentials,
    private val path: String,
    private val cacheKey: String,
) {
    @Volatile
    private var file: File = SmbConnections.openRead(server, path)
    val size: Long = SmbConnections.size(file)

    private val lock = Any()
    private val blocks = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }
    private val inflight = HashMap<Long, Future<ByteArray>>()
    private val workers: ExecutorService = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread(r, "xt-smb-read").apply { isDaemon = true }
    }
    private var readAhead = MIN_AHEAD
    private var lastIndex = -1L
    private var generation = 0
    private var refs = 0
    private var closeTask: ScheduledFuture<*>? = null
    @Volatile
    private var closed = false

    /** Copies up to [length] bytes at [position]; -1 at the end of the file. Blocking. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val index = position / BLOCK
        val block = block(index)
        val inBlock = (position - index * BLOCK).toInt()
        val n = min(length, block.size - inBlock)
        if (n <= 0) return -1
        System.arraycopy(block, inBlock, target, offset, n)
        return n
    }

    fun readFully(position: Long, length: Int): ByteArray? {
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = read(position + filled, out, filled, length - filled)
            if (n <= 0) break
            filled += n
        }
        return if (filled == length) out else null
    }

    private fun block(index: Long): ByteArray {
        val future: Future<ByteArray>
        synchronized(lock) {
            if (closed) throw IOException("SMB reader closed")
            when (index) {
                lastIndex -> Unit
                lastIndex + 1 -> readAhead = min(MAX_AHEAD, readAhead + 1)
                else -> readAhead = MIN_AHEAD
            }
            lastIndex = index
            val cached = blocks[index]
            if (cached != null) {
                prefetch(index)
                return cached
            }
            future = inflight[index] ?: submit(index)
            prefetch(index)
        }
        return try {
            future.get(90, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw IOException(e.cause?.message ?: "SMB read failed", e.cause)
        } catch (e: Exception) {
            throw IOException(e.message ?: "SMB read failed", e)
        }
    }

    /** Caller holds [lock]. */
    private fun prefetch(index: Long) {
        var i = index + 1
        while (i <= index + readAhead && i * BLOCK < size && inflight.size < MAX_INFLIGHT) {
            if (!blocks.containsKey(i) && !inflight.containsKey(i)) submit(i)
            i++
        }
    }

    /** Caller holds [lock]. */
    private fun submit(index: Long): Future<ByteArray> {
        val future = workers.submit(Callable { fetch(index) })
        inflight[index] = future
        return future
    }

    private fun fetch(index: Long): ByteArray {
        try {
            val start = index * BLOCK
            val length = min(BLOCK.toLong(), size - start).toInt()
            val data = ByteArray(length)
            var filled = 0
            var attempts = 0
            while (filled < length) {
                val seen = synchronized(lock) { generation }
                try {
                    val n = file.read(data, start + filled, filled, length - filled)
                    if (n <= 0) break
                    filled += n
                    TrafficMeter.add(n.toLong())
                } catch (e: Exception) {
                    if (closed || ++attempts > 3) throw e
                    reopen(seen)
                }
            }
            val block = if (filled == length) data else data.copyOf(filled)
            synchronized(lock) {
                blocks[index] = block
                inflight.remove(index)
            }
            return block
        } catch (e: Exception) {
            synchronized(lock) { inflight.remove(index) }
            throw e
        }
    }

    /** The NAS dropped the session (long pause, sleep, Wi-Fi switch): log in again and re-open the file once. */
    private fun reopen(seenGeneration: Int) {
        synchronized(reopenLock) {
            if (synchronized(lock) { generation } != seenGeneration) return
            runCatching { file.close() }
            SmbConnections.reset(server.id)
            file = SmbConnections.openRead(server, path)
            synchronized(lock) { generation++ }
        }
    }

    private val reopenLock = Any()

    fun release() {
        synchronized(readers) {
            refs--
            if (refs > 0) return
            closeTask = scheduler.schedule(Runnable {
                synchronized(readers) {
                    if (refs <= 0 && readers[cacheKey] === this) {
                        readers.remove(cacheKey)
                        closeNow()
                    }
                }
            }, IDLE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun closeNow() {
        closed = true
        workers.shutdownNow()
        synchronized(lock) {
            blocks.clear()
            inflight.clear()
        }
        runCatching { file.close() }
    }

    companion object {
        private const val BLOCK = 1 shl 20
        private const val WORKERS = 4
        private const val MIN_AHEAD = 4
        private const val MAX_AHEAD = 16
        private const val MAX_INFLIGHT = 8
        private const val MAX_BLOCKS = 24
        private const val IDLE_SECONDS = 20L

        private val readers = HashMap<String, SmbFileReader>()
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "xt-smb-idle").apply { isDaemon = true }
        }

        /** Opens (or shares) the reader of an `xtsmb://<server>/<share>/<path>` URI. Blocking; throws on failure. */
        fun acquire(uri: Uri): SmbFileReader {
            val key = uri.toString()
            synchronized(readers) {
                readers[key]?.let { existing ->
                    existing.refs++
                    existing.closeTask?.cancel(false)
                    existing.closeTask = null
                    return existing
                }
            }
            val server = ServerRegistry.get(uri.authority ?: "") ?: throw IOException("Không tìm thấy máy chủ của tệp này.")
            val path = uri.path?.trimStart('/') ?: throw IOException("Đường dẫn SMB không hợp lệ.")
            val reader = SmbFileReader(server, path, key)
            synchronized(readers) {
                readers[key]?.let { existing ->
                    reader.closeNow()
                    existing.refs++
                    existing.closeTask?.cancel(false)
                    existing.closeTask = null
                    return existing
                }
                reader.refs = 1
                readers[key] = reader
                return reader
            }
        }
    }
}

/** Media3 source for `xtsmb://` URIs. */
class SmbDataSource : BaseDataSource(true) {
    private var reader: SmbFileReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val source = try {
            SmbFileReader.acquire(dataSpec.uri)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        reader = source
        if (dataSpec.position > source.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else source.size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val source = reader ?: throw IOException("not opened")
        val n = source.read(position, buffer, offset, min(length.toLong(), remaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        reader?.release()
        reader = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** For MediaMetadataRetriever (artwork frames, duration and resolution of NAS files). */
class SmbMediaDataSource(private val reader: SmbFileReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        return try {
            reader.read(position, buffer, offset, size)
        } catch (e: IOException) {
            -1
        }
    }

    override fun getSize(): Long = reader.size

    override fun close() = Unit
}

/** Real network bytes (SMB block fetches, HTTP / WebDAV transfers) for the "Tốc độ mạng" line of the info panel. */
object TrafficMeter : androidx.media3.datasource.TransferListener {
    private val total = AtomicLong()
    private var lastTotal = 0L
    private var lastTime = 0L

    fun add(bytes: Long) {
        total.addAndGet(bytes)
    }

    /** Bytes per second since the previous call. */
    @Synchronized
    fun sample(): Double {
        val now = System.nanoTime()
        val value = total.get()
        val rate = if (lastTime == 0L) 0.0 else (value - lastTotal) / ((now - lastTime) / 1e9)
        lastTotal = value
        lastTime = now
        return rate
    }

    override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        // SMB counts its own block fetches (cache hits are not network traffic).
        if (isNetwork && source !is SmbDataSource) add(bytesTransferred.toLong())
    }
}
