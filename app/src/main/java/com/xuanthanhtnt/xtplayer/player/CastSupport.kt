package com.xuanthanhtnt.xtplayer.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.TimeFormat
import com.xuanthanhtnt.xtplayer.ui.ArtworkImage
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Google Cast for the player (the Android counterpart of the Apple app's AirPlay): the Chromecast / Google TV picks
 * the original file up from [LocalStreamServer]; this player pauses meanwhile and continues at the TV's position
 * when the cast ends. Needs Google Play services — without them the Cast button is simply not shown.
 */
class CastBridge private constructor(
    private val context: Context,
    private val controller: PlayerController,
    private val scope: CoroutineScope,
    private val castContext: CastContext,
) : SessionAvailabilityListener {

    data class Device(val id: String, val name: String, val description: String, val selected: Boolean)

    /** What the TV is doing while casting. */
    data class Remote(
        val device: String,
        val position: Double = 0.0,
        val duration: Double = 0.0,
        val playing: Boolean = false,
        val buffering: Boolean = true,
        val error: String? = null,
    )

    private val router = MediaRouter.getInstance(context)
    private val selector: MediaRouteSelector = castContext.mergedSelector
        ?: MediaRouteSelector.Builder().addControlCategory("com.google.android.gms.cast.CATEGORY_CAST/CC1AD845").build()
    private val player = CastPlayer(castContext)
    private val devicesState = MutableStateFlow<List<Device>>(emptyList())
    private val remoteState = MutableStateFlow<Remote?>(null)
    private var ticker: Job? = null
    private var discovering = false

    val devices: StateFlow<List<Device>> = devicesState
    val remote: StateFlow<Remote?> = remoteState

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updateRemote()
            if (playbackState == Player.STATE_ENDED && remoteState.value != null) controller.remoteEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updateRemote()
    }

    init {
        router.addCallback(selector, routerCallback)
        player.setSessionAvailabilityListener(this)
        player.addListener(playerListener)
        controller.onLoaded = { if (remoteState.value != null) castCurrent(it.startAt ?: 0.0) }
        refresh()
        if (player.isCastSessionAvailable) onCastSessionAvailable()
    }

    // Devices

    /** While the picker is open: look for Cast devices actively. */
    fun setDiscovering(active: Boolean) {
        if (active == discovering) return
        discovering = active
        router.addCallback(selector, routerCallback, if (active) MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY else 0)
        refresh()
    }

    private fun refresh() {
        devicesState.value = router.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { Device(it.id, it.name, it.description.orEmpty(), it.isSelected) }
    }

    fun select(device: Device) {
        router.routes.firstOrNull { it.id == device.id }?.let(router::selectRoute)
    }

    /** Stops casting; playback continues here at the TV's position. */
    fun disconnect() {
        runCatching { castContext.sessionManager.endCurrentSession(true) }
        router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
    }

    // Session

    override fun onCastSessionAvailable() {
        val name = router.selectedRoute.takeIf { !it.isDefault }?.name ?: "TV"
        remoteState.value = Remote(device = name)
        castCurrent(controller.engine.position.value.takeIf { it > 0 } ?: controller.current.value.startAt ?: 0.0)
    }

    override fun onCastSessionUnavailable() {
        val last = remoteState.value
        ticker?.cancel()
        remoteState.value = null
        controller.holdLocal = false
        controller.positionOverride = null
        LocalStreamServer.stop()
        if (last != null && last.position > 0) {
            controller.engine.seek(last.position)
            controller.engine.play()
        }
        refresh()
    }

    /** Sends the current queue item to the TV, starting at [startAt] seconds. */
    private fun castCurrent(startAt: Double) {
        val item = controller.current.value
        controller.holdLocal = true
        controller.engine.pause()
        val url = LocalStreamServer.publish(item.uri, item.fileName)
        if (url == null) {
            remoteState.value = remoteState.value?.copy(error = "Máy này chưa nối Wi-Fi / mạng LAN cùng mạng với TV.", buffering = false)
            return
        }
        val media = MediaItem.Builder()
            .setUri(url)
            .setMimeType(LocalStreamServer.mimeType(item.fileName))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setSubtitle(item.subtitle).build())
            .build()
        player.setMediaItem(media, (startAt * 1000).toLong())
        player.prepare()
        player.playWhenReady = true
        controller.positionOverride = { remoteState.value?.let { it.position to it.duration } }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                updateRemote()
                delay(1000)
            }
        }
    }

    private fun updateRemote() {
        val current = remoteState.value ?: return
        val duration = player.duration.takeIf { it > 0 }?.div(1000.0) ?: current.duration
        remoteState.value = current.copy(
            position = player.currentPosition.coerceAtLeast(0) / 1000.0,
            duration = duration,
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            error = player.playerError?.let { "TV không phát được tệp này (định dạng / codec). Chromecast nhận tốt MP4, WebM với H.264, HEVC, VP9 và âm thanh AAC, MP3, Opus, AC3." }
                ?: current.error,
        )
    }

    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun seek(seconds: Double) {
        player.seekTo((seconds.coerceAtLeast(0.0) * 1000).toLong())
        remoteState.value = remoteState.value?.copy(position = seconds)
    }

    fun release(endCast: Boolean) {
        if (endCast && remoteState.value != null) disconnect()
        ticker?.cancel()
        controller.onLoaded = null
        router.removeCallback(routerCallback)
        player.removeListener(playerListener)
        player.setSessionAvailabilityListener(null)
        player.release()
    }

    companion object {
        /** Null without Google Play services (some TV boxes, Huawei): no Cast button then. */
        fun create(context: Context, controller: PlayerController, scope: CoroutineScope): CastBridge? = runCatching {
            CastBridge(context, controller, scope, CastContext.getSharedInstance(context))
        }.getOrNull()
    }
}

/** Cast devices on the network; picking one starts casting the current video. */
@Composable
fun CastPickerDialog(bridge: CastBridge, onDismiss: () -> Unit) {
    val devices by bridge.devices.collectAsState()
    val remote by bridge.remote.collectAsState()
    DisposableEffect(Unit) {
        bridge.setDiscovering(true)
        onDispose { bridge.setDiscovering(false) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phát trên TV (Cast)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Đang tìm Chromecast / Google TV trong mạng…")
                    }
                }
                devices.forEach { device ->
                    TextButton(onClick = {
                        bridge.select(device)
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (device.selected) Icons.Filled.CastConnected else Icons.Filled.Cast, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (device.description.isNotEmpty()) {
                                Text(device.description, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, maxLines = 1)
                            }
                        }
                        if (device.selected) Icon(Icons.Filled.Check, null, tint = XtColors.leaf)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "TV nhận tệp gốc qua mạng LAN (không chuyển mã): MP4 / WebM / MKV với H.264, HEVC, VP9 và âm thanh AAC, MP3, " +
                        "Opus, AC3 phát tốt; DTS / TrueHD có thể không có tiếng. Máy này và TV phải cùng mạng.",
                    style = MaterialTheme.typography.bodySmall,
                    color = XtColors.secondaryText,
                )
            }
        },
        confirmButton = {
            if (remote != null) {
                TextButton(onClick = {
                    bridge.disconnect()
                    onDismiss()
                }) { Text("Ngắt kết nối") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

/** "Đang phát trên <TV>": the video's picture, transport and time line for the TV, and Disconnect. */
@Composable
fun CastingOverlay(bridge: CastBridge, item: PlaybackItem, onClose: () -> Unit) {
    val remote by bridge.remote.collectAsState()
    val state = remote ?: return
    var scrub by remember { mutableStateOf<Float?>(null) }
    val skipBack = AppGraph.settings.skipBackSeconds.value
    val skipForward = AppGraph.settings.skipForwardSeconds.value
    Box(Modifier.fillMaxSize().background(XtColors.background)) {
        ArtworkImage(item.artworkPath, Modifier.fillMaxSize(), size = "w1280")
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f))))
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 640.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.CastConnected, null, tint = XtColors.leaf, modifier = Modifier.size(56.dp))
            Text("Đang phát trên ${state.device}", style = MaterialTheme.typography.titleMedium, color = XtColors.leaf)
            Text(item.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center, maxLines = 2)
            state.error?.let { Text(it, color = XtColors.danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIconButton(Icons.Filled.Replay10, "Lùi $skipBack giây", size = 56.dp, iconSize = 30.dp) {
                    bridge.seek(state.position - skipBack)
                }
                if (state.buffering && !state.playing) {
                    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                } else {
                    PlayerIconButton(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Phát / Tạm dừng", size = 76.dp, iconSize = 42.dp) {
                        bridge.togglePause()
                    }
                }
                PlayerIconButton(Icons.Filled.Forward30, "Tới $skipForward giây", size = 56.dp, iconSize = 30.dp) {
                    bridge.seek(state.position + skipForward)
                }
            }
            if (state.duration > 0) {
                Slider(
                    value = scrub ?: (state.position / state.duration).toFloat().coerceIn(0f, 1f),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        scrub?.let { bridge.seek(it * state.duration) }
                        scrub = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(TimeFormat.clock(scrub?.let { it * state.duration } ?: state.position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text("-" + TimeFormat.clock((state.duration - state.position).coerceAtLeast(0.0)), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = bridge::disconnect) { Text("Ngắt kết nối — xem tiếp trên máy này") }
                OutlinedButton(onClick = onClose) { Text("Đóng", color = Color.White) }
            }
        }
    }
}
