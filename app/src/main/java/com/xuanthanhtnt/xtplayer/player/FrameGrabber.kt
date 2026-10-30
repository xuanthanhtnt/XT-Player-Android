package com.xuanthanhtnt.xtplayer.player

import android.graphics.Bitmap
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

    private fun grabBlocking(location: String, output: File): Probe? {
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
                    val server = ServerRegistry.get(uri.authority ?: "") ?: return null
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
                else -> return null
            }
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

            val scaled = if (frame.width > 640) {
                Bitmap.createScaledBitmap(frame, 640, (frame.height * 640f / frame.width).roundToInt().coerceAtLeast(1), true)
            } else {
                frame
            }
            output.parentFile?.mkdirs()
            val temp = File(output.parentFile, output.name + ".tmp")
            temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            temp.renameTo(output)
            return Probe(durationMs / 1000.0, width, height)
        } finally {
            runCatching { retriever.release() }
            reader?.release()
        }
    }
}
