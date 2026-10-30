package com.xuanthanhtnt.xtplayer.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Frames for the scrub bubble (the Mac app's scrub thumbnails): a second reader of the file decodes the key frame
 * nearest to the scrub position. Only the latest request is served; frames are kept per 10-second slot.
 */
class ScrubThumbnailer(private val scope: CoroutineScope) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xt-scrub").apply { isDaemon = true } }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val cache = LruCache<Int, ImageBitmap>(60)
    private val state = MutableStateFlow<Pair<Double, ImageBitmap>?>(null)
    /** (seconds, frame) of the latest preview. */
    val image: StateFlow<Pair<Double, ImageBitmap>?> = state

    private var location: String? = null
    private var source: FrameGrabber.Source? = null
    private var failed = false
    private var job: Job? = null
    @Volatile
    private var latest = -1

    fun setSource(newLocation: String) {
        if (newLocation == location) return
        location = newLocation
        cache.evictAll()
        state.value = null
        scope.launch(dispatcher) {
            source?.close()
            source = null
            failed = false
        }
    }

    fun request(seconds: Double) {
        val slot = (seconds / SLOT).toInt().coerceAtLeast(0)
        cache.get(slot)?.let {
            state.value = slot * SLOT to it
            return
        }
        latest = slot
        if (job?.isActive == true) return   // the worker picks up `latest` when it is done
        job = scope.launch(dispatcher) {
            while (true) {
                val wanted = latest
                if (cache.get(wanted) == null) decode(wanted)?.let { frame ->
                    cache.put(wanted, frame)
                    if (wanted == latest) state.value = wanted * SLOT to frame
                }
                if (wanted == latest) break
            }
        }
    }

    private fun decode(slot: Int): ImageBitmap? {
        if (failed) return null
        val current = location ?: return null
        val opened = source ?: FrameGrabber.open(current)?.also { source = it } ?: run {
            failed = true
            return null
        }
        val timeUs = slot * SLOT.toLong() * 1_000_000L
        val bitmap: Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= 27) {
                opened.retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                opened.retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }.getOrNull()
        return bitmap?.asImageBitmap()
    }

    fun release() {
        job?.cancel()
        scope.launch(dispatcher) {
            source?.close()
            source = null
        }.invokeOnCompletion { worker.shutdown() }
    }

    private companion object {
        const val SLOT = 10.0
    }
}
