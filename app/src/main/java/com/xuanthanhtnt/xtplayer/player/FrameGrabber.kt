package com.xuanthanhtnt.xtplayer.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.network.RemoteFileSystems
import com.xuanthanhtnt.xtplayer.network.ServerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A frame of the video as artwork (like Infuse) for files TMDb does not know, plus the duration and size of the
 * file. Uses the platform decoders through MediaMetadataRetriever, reading NAS files through [SmbFileReader].
 */
object FrameGrabber {
    data class Probe(val duration: Double, val width: Int, val height: Int) {
        val resolutionLabel: String
            get() = when (max(width, height)) {
                in 3000..Int.MAX_VALUE -> "4K"
                in 1800 until 3000 -> "1080p"
                in 1200 until 1800 -> "720p"
                in 1 until 1200 -> "SD"
                else -> ""
            }
    }

    /** Two at a time: decoding 4K HEVC frames is heavy on a TV box. */
    private val permits = Semaphore(2)

    suspend fun grab(location: String, output: File): Probe? = permits.withPermit {
        withContext(Dispatchers.IO) { runCatching { grabBlocking(location, output) }.getOrNull() }
    }

    /** An album's picture: the cover embedded in its first track (ID3 APIC, FLAC PICTURE, MP4 covr). */
    suspend fun grabEmbeddedPicture(location: String, output: File): Boolean = permits.withPermit {
        withContext(Dispatchers.IO) { runCatching { embeddedPictureBlocking(location, output) }.getOrDefault(false) }
    }

    /** A retriever reading [location] (NAS files through [SmbFileReader]); the reader must be released with it. */
    class Source(val retriever: MediaMetadataRetriever, private val reader: SmbFileReader?) {
        fun close() {
            runCatching { retriever.release() }
            reader?.release()
        }
    }

    /** Blocking. */
    fun open(location: String): Source? {
        val retriever = MediaMetadataRetriever()
        var reader: SmbFileReader? = null
        try {
            val uri = Uri.parse(location)
            when (uri.scheme) {
                RemoteFileSystems.SMB_SCHEME -> {
                    reader = SmbFileReader.acquire(uri)
                    retriever.setDataSource(SmbMediaDataSource(reader))
                }
                RemoteFileSystems.DAV_SCHEME -> {
                    val server = ServerRegistry.get(uri.authority ?: "") ?: throw IllegalStateException("no server")
                    val headers = if (server.username.isNotEmpty()) {
                        mapOf("Authorization" to Credentials.basic(server.username, server.password, Charsets.UTF_8))
                    } else {
                        emptyMap()
                    }
                    retriever.setDataSource(RemoteFileSystems.webDavUrl(server, uri), headers)
                }
                "content", "file" -> retriever.setDataSource(AppGraph.context, uri)
                "http", "https" -> retriever.setDataSource(location, emptyMap())
                null -> retriever.setDataSource(location)
                else -> throw IllegalStateException("unsupported")
            }
            return Source(retriever, reader)
        } catch (e: Exception) {
            runCatching { retriever.release() }
            reader?.release()
            return null
        }
    }

    private fun grabBlocking(location: String, output: File): Probe? {
        val source = open(location) ?: return null
        val retriever = source.retriever
        try {
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) width = height.also { height = width }

            // 20 % into the file: past intros and black openings.
            val timeUs = if (durationMs > 0) durationMs * 200 else 60_000_000L
            val frame = (if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }) ?: retriever.frameAtTime ?: return null

            save(frame, output, 640)
            return Probe(durationMs / 1000.0, width, height)
        } finally {
            source.close()
        }
    }

    private fun embeddedPictureBlocking(location: String, output: File): Boolean {
        val source = open(location) ?: return false
        try {
            val bytes = source.retriever.embeddedPicture ?: return false
            val picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            save(picture, output, 600)
            return true
        } finally {
            source.close()
        }
    }

    private fun save(bitmap: Bitmap, output: File, maxWidth: Int) {
        val scaled = if (bitmap.width > maxWidth) {
            Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * maxWidth.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        temp.renameTo(output)
    }
}
