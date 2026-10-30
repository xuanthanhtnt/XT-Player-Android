package com.xuanthanhtnt.xtplayer.player

import android.graphics.Color as AndroidColor
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.ByteFormat
import com.xuanthanhtnt.xtplayer.core.TimeFormat
import com.xuanthanhtnt.xtplayer.metadata.FilenameParser
import com.xuanthanhtnt.xtplayer.metadata.OpenSubtitlesClient
import com.xuanthanhtnt.xtplayer.metadata.OpenSubtitlesHash
import com.xuanthanhtnt.xtplayer.metadata.ParsedMediaName
import com.xuanthanhtnt.xtplayer.ui.TagChip
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

enum class PlayerPanel(val title: String) {
    AUDIO("Âm thanh"), SUBTITLES("Phụ đề"), VIDEO("Video"), INFO("Thông tin")
}

@Composable
fun PlayerScreen(controller: PlayerController, isTv: Boolean, inPip: Boolean, onClose: () -> Unit) {
    val engine = controller.engine
    val settings = AppGraph.settings
    val state by engine.state.collectAsState()
    val position by engine.position.collectAsState()
    val duration by engine.duration.collectAsState()
    val buffered by engine.buffered.collectAsState()
    val buffering by engine.isBuffering.collectAsState()
    val fit by engine.videoFit.collectAsState()
    val item by controller.current.collectAsState()
    val upNext by controller.upNextCountdown.collectAsState()
    val subtitleScale by settings.subtitleScale.collect()
    val subtitlePosition by settings.subtitlePosition.collect()
    val subtitleBackground by settings.subtitleBackground.collect()

    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var flashId by remember { mutableIntStateOf(0) }
    var resumeChip by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun poke() {
        interaction++
    }

    fun showControls() {
        controlsVisible = true
        poke()
    }

    fun skip(forward: Boolean) {
        val seconds = if (forward) settings.skipForwardSeconds.value else settings.skipBackSeconds.value
        engine.seekBy(if (forward) seconds.toDouble() else -seconds.toDouble())
        seekFlash = if (forward) "+$seconds s" else "−$seconds s"
        flashId++
    }

    // Controls fade out while playing, as in Infuse.
    LaunchedEffect(controlsVisible, interaction, state, panel) {
        if (controlsVisible && panel == null && state == PlaybackState.Playing) {
            delay(4500)
            controlsVisible = false
        }
    }
    LaunchedEffect(flashId) {
        if (seekFlash != null) {
            delay(900)
            seekFlash = null
        }
    }
    LaunchedEffect(item.key) {
        resumeChip = (item.startAt ?: 0.0) > 0
        delay(15_000)
        resumeChip = false
    }
    LaunchedEffect(controlsVisible, panel, upNext) {
        if (upNext != null) return@LaunchedEffect
        if (!controlsVisible && panel == null) {
            runCatching { rootFocus.requestFocus() }
        } else if (controlsVisible && panel == null && isTv) {
            delay(50)
            runCatching { playFocus.requestFocus() }
        }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            isTv && controlsVisible && state == PlaybackState.Playing -> controlsVisible = false
            else -> onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        engine.togglePause(); showControls(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        engine.play(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        engine.pause(); showControls(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        skip(true); true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        skip(false); true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        controller.advance(1); true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        controller.advance(-1); true
                    }
                    KeyEvent.KEYCODE_CAPTIONS -> {
                        panel = PlayerPanel.SUBTITLES; true
                    }
                    else -> if (!controlsVisible && panel == null && upNext == null) {
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                skip(false); true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                skip(true); true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                                engine.togglePause(); showControls(); true
                            }
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                                showControls(); true
                            }
                            else -> false
                        }
                    } else {
                        poke()
                        false
                    }
                }
            }
            .focusable()
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    player = engine.player
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
            },
            update = { view ->
                view.setResizeMode(fit.resizeMode)
                styleSubtitles(view.subtitleView, subtitleScale, subtitlePosition, subtitleBackground)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (inPip) return@Box

        // Touch: tap shows / hides the controls, double tap on either half skips.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (panel != null) {
                                panel = null
                            } else {
                                controlsVisible = !controlsVisible
                                poke()
                            }
                        },
                        onDoubleTap = { offset -> skip(forward = offset.x > size.width / 2) },
                    )
                }
        )

        if (buffering || state == PlaybackState.Loading) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White)
                if (buffered > 0.5 && state != PlaybackState.Loading) {
                    Spacer(Modifier.height(8.dp))
                    Text("Đã nạp ${buffered.roundToInt()} s", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        AnimatedVisibility(controlsVisible && upNext == null, enter = fadeIn(), exit = fadeOut()) {
            ControlsOverlay(
                controller = controller,
                position = position,
                duration = duration,
                buffered = buffered,
                state = state,
                showResume = resumeChip,
                playFocus = playFocus,
                onInteraction = ::poke,
                onClose = onClose,
                onPanel = { panel = it },
                onSkip = ::skip,
            )
        }

        seekFlash?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        val next = controller.next
        if (upNext != null && next != null) {
            UpNextCard(next, upNext ?: 0, onPlay = controller::playNextNow, onCancel = {
                controller.cancelUpNext()
                showControls()
            }, modifier = Modifier.align(Alignment.BottomEnd))
        }

        (state as? PlaybackState.Failed)?.let { failure ->
            ErrorCard(failure.message, onRetry = controller::retry, onClose = onClose, modifier = Modifier.align(Alignment.Center))
        }

        panel?.let { current ->
            PanelSheet(current, onSelect = { panel = it }, onDismiss = { panel = null }) {
                when (current) {
                    PlayerPanel.AUDIO -> audioPanel(engine)
                    PlayerPanel.SUBTITLES -> subtitlePanel(engine)
                    PlayerPanel.VIDEO -> videoPanel(engine)
                    PlayerPanel.INFO -> Unit
                }
            }
        }
    }
}

private fun styleSubtitles(view: SubtitleView?, scale: Float, position: Int, background: Boolean) {
    view ?: return
    view.setApplyEmbeddedStyles(true)
    view.setApplyEmbeddedFontSizes(false)
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
    view.setBottomPaddingFraction(
        when {
            position >= 100 -> SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
            position >= 90 -> 0.16f
            else -> 0.40f
        }
    )
    view.setStyle(
        CaptionStyleCompat(
            AndroidColor.WHITE,
            if (background) 0x99000000.toInt() else AndroidColor.TRANSPARENT,
            AndroidColor.TRANSPARENT,
            if (background) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            AndroidColor.BLACK,
            null,
        )
    )
}

// Controls

@Composable
private fun ControlsOverlay(
    controller: PlayerController,
    position: Double,
    duration: Double,
    buffered: Double,
    state: PlaybackState,
    showResume: Boolean,
    playFocus: FocusRequester,
    onInteraction: () -> Unit,
    onClose: () -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    onSkip: (Boolean) -> Unit,
) {
    val engine = controller.engine
    val item by controller.current.collectAsState()
    val settings = AppGraph.settings
    val back = settings.skipBackSeconds.value
    val forward = settings.skipForwardSeconds.value

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
        )

        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Đóng", onClick = onClose)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerIconButton(Icons.Filled.Subtitles, "Phụ đề") { onInteraction(); onPanel(PlayerPanel.SUBTITLES) }
                PlayerIconButton(Icons.Filled.Audiotrack, "Âm thanh") { onInteraction(); onPanel(PlayerPanel.AUDIO) }
                PlayerIconButton(Icons.Filled.Tune, "Video") { onInteraction(); onPanel(PlayerPanel.VIDEO) }
                PlayerIconButton(Icons.Filled.Info, "Thông tin") { onInteraction(); onPanel(PlayerPanel.INFO) }
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (controller.hasPrevious) PlayerIconButton(Icons.Filled.SkipPrevious, "Trước") { onInteraction(); controller.advance(-1) }
            PlayerIconButton(rewindIcon(back), "Lùi $back giây", size = 60.dp, iconSize = 34.dp) { onInteraction(); onSkip(false) }
            PlayerIconButton(
                when (state) {
                    PlaybackState.Playing -> Icons.Filled.Pause
                    PlaybackState.Ended -> Icons.Filled.Replay
                    else -> Icons.Filled.PlayArrow
                },
                "Phát / Tạm dừng",
                size = 84.dp,
                iconSize = 48.dp,
                modifier = Modifier.focusRequester(playFocus),
            ) { onInteraction(); engine.togglePause() }
            PlayerIconButton(forwardIcon(forward), "Tới $forward giây", size = 60.dp, iconSize = 34.dp) { onInteraction(); onSkip(true) }
            if (controller.hasNext) PlayerIconButton(Icons.Filled.SkipNext, "Tiếp theo") { onInteraction(); controller.advance(1) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            if (showResume && item.startAt != null) {
                PlayerTextButton("Đã tiếp tục từ ${TimeFormat.fullClock(item.startAt ?: 0.0)} · Xem từ đầu") {
                    onInteraction()
                    controller.restartFromBeginning()
                }
                Spacer(Modifier.height(10.dp))
            }
            SeekBar(
                position = position,
                duration = duration,
                buffered = buffered,
                step = back.toDouble().coerceAtLeast(5.0),
                onInteraction = onInteraction,
                onSeek = engine::seek,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TimeFormat.clock(position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                SignalBadges(engine)
                Spacer(Modifier.weight(1f))
                Text("-" + TimeFormat.clock((duration - position).coerceAtLeast(0.0)), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun rewindIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Filled.Replay5
    10 -> Icons.Filled.Replay10
    30 -> Icons.Filled.Replay30
    else -> Icons.Filled.FastRewind
}

private fun forwardIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Filled.Forward5
    10 -> Icons.Filled.Forward10
    30 -> Icons.Filled.Forward30
    else -> Icons.Filled.FastForward
}

@Composable
private fun SignalBadges(engine: PlayerEngine) {
    val video by engine.video.collectAsState()
    val audio by engine.audio.collectAsState()
    val bitstream by engine.isBitstreaming.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (video.resolutionLabel.isNotEmpty()) TagChip(video.resolutionLabel)
        if (video.dynamicRange != "SDR") TagChip(video.dynamicRange, highlighted = true)
        if (audio.codec.isNotEmpty()) {
            TagChip(listOf(audio.codec, PlayerEngine.channelLayout(audio.channels)).filter { it.isNotEmpty() }.joinToString(" "))
        }
        if (bitstream) TagChip("Bitstream")
    }
}

@Composable
fun PlayerIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun PlayerTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text,
        color = if (focused) Color.Black else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Progress + buffer bar. Touch: tap or drag to seek. D-pad: when focused, ◀ ▶ move a preview position and the
 * player jumps there once the remote is left alone for a moment (or on OK).
 */
@Composable
private fun SeekBar(
    position: Double,
    duration: Double,
    buffered: Double,
    step: Double,
    onInteraction: () -> Unit,
    onSeek: (Double) -> Unit,
) {
    var scrub by remember { mutableStateOf<Double?>(null) }
    var keyScrub by remember { mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shown = scrub ?: position

    LaunchedEffect(keyScrub) {
        if (keyScrub == 0) return@LaunchedEffect
        delay(800)
        scrub?.let(onSeek)
        scrub = null
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || duration <= 0) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val delta = if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step
                        // Holding the button speeds up.
                        val boost = if (event.nativeKeyEvent.repeatCount > 8) 6 else if (event.nativeKeyEvent.repeatCount > 2) 3 else 1
                        scrub = ((scrub ?: position) + delta * boost).coerceIn(0.0, duration)
                        keyScrub++
                        onInteraction()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val target = scrub ?: return@onKeyEvent false
                        onSeek(target)
                        scrub = null
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) onSeek((offset.x / size.width).toDouble().coerceIn(0.0, 1.0) * duration)
                    onInteraction()
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (duration > 0) scrub = (offset.x / size.width).toDouble().coerceIn(0.0, 1.0) * duration
                    },
                    onDragEnd = {
                        scrub?.let(onSeek)
                        scrub = null
                    },
                    onDragCancel = { scrub = null },
                    onHorizontalDrag = { change, _ ->
                        if (duration > 0) scrub = (change.position.x / size.width).toDouble().coerceIn(0.0, 1.0) * duration
                        onInteraction()
                    },
                )
            },
    ) {
        val fraction = if (duration > 0) (shown / duration).toFloat().coerceIn(0f, 1f) else 0f
        val bufferedFraction = if (duration > 0) ((position + buffered) / duration).toFloat().coerceIn(0f, 1f) else 0f
        val barHeight = if (focused || scrub != null) 8.dp else 5.dp
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.Center)
        ) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = radius)
            drawRoundRect(Color.White.copy(alpha = 0.38f), size = Size(size.width * bufferedFraction, size.height), cornerRadius = radius)
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(XtColors.lime, XtColors.leaf)),
                size = Size(size.width * fraction, size.height),
                cornerRadius = radius,
            )
            if (focused || scrub != null) {
                drawCircle(Color.White, radius = size.height * 1.1f, center = Offset(size.width * fraction, size.height / 2))
            }
        }
        scrub?.let { target ->
            val density = LocalDensity.current
            val bubbleWidth = 96.dp
            val x = with(density) { ((maxWidth.toPx() * fraction) - bubbleWidth.toPx() / 2).coerceIn(0f, (maxWidth - bubbleWidth).toPx()) }
            Text(
                TimeFormat.clock(target),
                color = Color.Black,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), -36.dp.roundToPx()) }
                    .width(bubbleWidth)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .padding(vertical = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// Up Next / errors

@Composable
private fun UpNextCard(next: PlaybackItem, seconds: Int, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .padding(32.dp)
            .width(380.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE6151B18))
            .border(1.dp, XtColors.glassBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Text("Tiếp theo", style = MaterialTheme.typography.labelLarge, color = XtColors.leaf)
        Text(next.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        next.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, maxLines = 1) }
        Spacer(Modifier.height(6.dp))
        Text("Phát sau $seconds giây", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onPlay,
                modifier = Modifier.focusRequester(focus),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Phát ngay") }
            OutlinedButton(onClick = onCancel) { Text("Huỷ", color = Color.White) }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(message) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .padding(32.dp)
            .width(460.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF0151B18))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.ErrorOutline, null, tint = XtColors.danger, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Thử lại") }
            OutlinedButton(onClick = onClose) { Text("Đóng", color = Color.White) }
        }
    }
}

// Panels

@Composable
private fun PanelSheet(
    current: PlayerPanel,
    onSelect: (PlayerPanel) -> Unit,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .padding(16.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2111613))
                .border(1.dp, XtColors.glassBorder, RoundedCornerShape(22.dp))
                .padding(vertical = 14.dp)
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            ) {
                items(PlayerPanel.entries.toList()) { tab ->
                    PanelTab(tab.title, selected = tab == current, modifier = if (tab == current) Modifier.focusRequester(firstTab) else Modifier) {
                        onSelect(tab)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = XtColors.glassBorder)
            if (current == PlayerPanel.INFO) {
                InfoPanelContent()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun PanelTab(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected || focused) Color.Black else Color.White,
        modifier = modifier
            .clip(CircleShape)
            .background(
                when {
                    focused -> Brush.linearGradient(listOf(Color.White, Color.White))
                    selected -> XtColors.brandGradient
                    else -> Brush.linearGradient(listOf(XtColors.glass, XtColors.glass))
                }
            )
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun PanelChoice(title: String, detail: String? = null, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp)) {
            if (selected) Icon(Icons.Filled.Check, null, tint = XtColors.leaf, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) {
                Text(detail, color = XtColors.secondaryText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionTitle(text: String) {
    item {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = XtColors.leaf,
            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.audioPanel(engine: PlayerEngine) {
    item { AudioTrackList(engine) }
}

@Composable
private fun AudioTrackList(engine: PlayerEngine) {
    val tracks by engine.audioTracks.collectAsState()
    val bitstream by engine.isBitstreaming.collectAsState()
    Column {
        if (tracks.isEmpty()) {
            Text("Không có track âm thanh", color = XtColors.secondaryText, modifier = Modifier.padding(12.dp))
        }
        tracks.forEach { track ->
            PanelChoice(track.label, track.detail + if (!track.supported) " · không giải mã được" else "", track.selected) {
                engine.selectTrack(track)
            }
        }
        if (bitstream) {
            Text(
                "Bitstream qua HDMI — âm lượng chỉnh trên ampli / TV. Tắt trong Cài đặt ▸ Âm thanh để giải mã sang PCM.",
                color = XtColors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.subtitlePanel(engine: PlayerEngine) {
    item { SubtitleTrackList(engine) }
    sectionTitle("Hiển thị")
    item { SubtitleStyleControls() }
    sectionTitle("Phụ đề trực tuyến")
    item { OnlineSubtitles() }
}

@Composable
private fun SubtitleTrackList(engine: PlayerEngine) {
    val tracks by engine.subtitleTracks.collectAsState()
    Column {
        PanelChoice("Tắt", null, selected = tracks.none { it.selected }) { engine.disableSubtitles() }
        tracks.forEach { track ->
            PanelChoice(track.label, track.detail, track.selected, enabled = track.supported) { engine.selectTrack(track) }
        }
    }
}

@Composable
private fun SubtitleStyleControls() {
    val settings = AppGraph.settings
    val scale by settings.subtitleScale.collect()
    val position by settings.subtitlePosition.collect()
    val background by settings.subtitleBackground.collect()
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cỡ chữ", color = Color.White, modifier = Modifier.weight(1f))
            PlayerIconButton(Icons.Filled.Remove, "Nhỏ hơn", size = 40.dp, iconSize = 20.dp) {
                settings.subtitleScale.value = (scale - 0.1f).coerceAtLeast(0.5f)
            }
            Text("${(scale * 100).roundToInt()}%", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp))
            PlayerIconButton(Icons.Filled.Add, "Lớn hơn", size = 40.dp, iconSize = 20.dp) {
                settings.subtitleScale.value = (scale + 0.1f).coerceAtMost(2.5f)
            }
        }
        PanelChoice("Sát đáy", null, position >= 100) { settings.subtitlePosition.value = 100 }
        PanelChoice("Cao hơn", null, position in 90..99) { settings.subtitlePosition.value = 94 }
        PanelChoice("Giữa màn hình", null, position < 90) { settings.subtitlePosition.value = 60 }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Nền tối sau chữ", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = background, onCheckedChange = { settings.subtitleBackground.value = it })
        }
    }
}

private sealed class OnlineState {
    data object Idle : OnlineState()
    data object Searching : OnlineState()
    data class Results(val list: List<OpenSubtitlesClient.Subtitle>) : OnlineState()
    data object Downloading : OnlineState()
    data class Message(val text: String) : OnlineState()
}

/** Search OpenSubtitles by file hash and title, download, and attach to the playing file. */
@Composable
private fun OnlineSubtitles() {
    val controller = LocalPlayerController.current ?: return
    val item by controller.current.collectAsState()
    val scope = rememberCoroutineScope()
    var state by remember(item.key) { mutableStateOf<OnlineState>(OnlineState.Idle) }
    val client = remember { OpenSubtitlesClient.current() }
    Column {
        if (client == null) {
            Text(
                "Thêm API key OpenSubtitles trong Cài đặt ▸ Phụ đề trực tuyến để tìm phụ đề.",
                color = XtColors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
            return@Column
        }
        when (val s = state) {
            OnlineState.Idle, is OnlineState.Message -> {
                if (s is OnlineState.Message) {
                    Text(s.text, color = XtColors.secondaryText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
                PanelChoice("Tìm trên OpenSubtitles…", "Theo mã băm của tệp và tên phim", selected = false) {
                    state = OnlineState.Searching
                    scope.launch {
                        state = try {
                            val hash = withContext(Dispatchers.IO) { OpenSubtitlesHash.compute(item.uri) }
                            val parsed = FilenameParser.parse(item.fileName)
                            val episode = parsed.kind as? ParsedMediaName.Kind.Episode
                            val settings = AppGraph.settings
                            val results = client.search(
                                parsed.title, parsed.year, episode?.season, episode?.episode,
                                settings.languages(settings.subtitleLanguages.value), hash,
                            )
                            if (results.isEmpty()) OnlineState.Message("Không tìm thấy phụ đề.") else OnlineState.Results(results)
                        } catch (e: Exception) {
                            OnlineState.Message(e.message ?: e.toString())
                        }
                    }
                }
            }
            OnlineState.Searching -> Text("Đang tìm…", color = XtColors.secondaryText, modifier = Modifier.padding(12.dp))
            OnlineState.Downloading -> Text("Đang tải phụ đề…", color = XtColors.secondaryText, modifier = Modifier.padding(12.dp))
            is OnlineState.Results -> s.list.take(40).forEach { subtitle ->
                val tags = buildList {
                    add("${subtitle.downloads} lượt tải")
                    if (subtitle.hashMatch) add("Khớp tệp")
                    if (subtitle.hearingImpaired) add("SDH")
                    if (subtitle.machineTranslated) add("Máy dịch")
                }
                PanelChoice("${subtitle.language.uppercase(Locale.ROOT)} · ${subtitle.release}", tags.joinToString(" · "), selected = false) {
                    state = OnlineState.Downloading
                    scope.launch {
                        state = try {
                            controller.addSubtitle(client.download(subtitle))
                            OnlineState.Message("Đã thêm phụ đề ${subtitle.language.uppercase(Locale.ROOT)}.")
                        } catch (e: Exception) {
                            OnlineState.Message(e.message ?: e.toString())
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.videoPanel(engine: PlayerEngine) {
    sectionTitle("Khung hình")
    item { FitChoices(engine) }
    sectionTitle("Tốc độ phát")
    item { SpeedChoices(engine) }
}

@Composable
private fun FitChoices(engine: PlayerEngine) {
    val fit by engine.videoFit.collectAsState()
    Column {
        VideoFit.entries.forEach { option -> PanelChoice(option.label, null, fit == option) { engine.setVideoFit(option) } }
    }
}

@Composable
private fun SpeedChoices(engine: PlayerEngine) {
    val speed by engine.speed.collectAsState()
    Column {
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
            PanelChoice(if (value == 1f) "Bình thường" else "${value}×", null, kotlin.math.abs(speed - value) < 0.01f) { engine.setSpeed(value) }
        }
    }
}

@Composable
private fun InfoPanelContent() {
    val controller = LocalPlayerController.current ?: return
    val engine = controller.engine
    val item by controller.current.collectAsState()
    val video by engine.video.collectAsState()
    val audio by engine.audio.collectAsState()
    val videoDecoder by engine.videoDecoder.collectAsState()
    val audioDecoder by engine.audioDecoder.collectAsState()
    val bitstream by engine.isBitstreaming.collectAsState()
    val buffered by engine.buffered.collectAsState()
    val network by engine.networkSpeed.collectAsState()
    val dropped by engine.droppedFrames.collectAsState()
    val rows = buildList {
        add("Tệp" to "")
        add("Tên" to item.fileName)
        add("Nguồn" to when {
            item.uri.startsWith("xtsmb:") -> "SMB · ${item.server?.displayHost.orEmpty()}"
            item.uri.startsWith("xtdav:") -> "WebDAV · ${item.server?.displayHost.orEmpty()}"
            item.isLocal -> "Thiết bị này"
            else -> "URL"
        })
        add("Video" to "")
        add("Codec" to video.codec.ifEmpty { "—" })
        add("Độ phân giải" to if (video.width > 0) "${video.width}×${video.height} (${video.resolutionLabel})" else "—")
        add("Khung hình" to if (video.fps > 0) String.format(Locale.US, "%.3f fps", video.fps) else "—")
        add("Dải động" to video.dynamicRange)
        add("Bitrate" to ByteFormat.bitrate(video.bitrate.toDouble()))
        add("Bộ giải mã" to videoDecoder.ifEmpty { "—" })
        add("Khung hình rớt" to "$dropped")
        add("Âm thanh" to "")
        add("Codec" to listOf(audio.codec, PlayerEngine.channelLayout(audio.channels)).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "—" })
        add("Tần số mẫu" to if (audio.sampleRate > 0) "${audio.sampleRate / 1000.0} kHz" else "—")
        add("Ngôn ngữ" to audio.language.ifEmpty { "—" })
        add("Đầu ra" to if (bitstream) "Bitstream qua HDMI — âm lượng chỉnh trên ampli" else "PCM")
        add("Bộ giải mã" to audioDecoder.ifEmpty { "—" })
        add("Bộ đệm" to "")
        add("Đã nạp trước" to "${buffered.roundToInt()} s")
        add("Tốc độ mạng" to ByteFormat.bitrate(network * 8))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        items(rows) { (label, value) ->
            if (value.isEmpty()) {
                Text(label, color = XtColors.leaf, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            } else {
                Row(Modifier.fillMaxWidth().focusable().padding(vertical = 3.dp)) {
                    Text(label, color = XtColors.secondaryText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                    Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

val LocalPlayerController = androidx.compose.runtime.staticCompositionLocalOf<PlayerController?> { null }
