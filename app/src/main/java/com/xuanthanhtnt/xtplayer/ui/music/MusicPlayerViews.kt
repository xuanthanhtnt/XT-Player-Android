package com.xuanthanhtnt.xtplayer.ui.music

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xuanthanhtnt.xtplayer.core.TimeFormat
import com.xuanthanhtnt.xtplayer.player.MusicPlayer
import com.xuanthanhtnt.xtplayer.player.PlaybackItem
import com.xuanthanhtnt.xtplayer.player.SignalPath
import com.xuanthanhtnt.xtplayer.ui.ActionsDialog
import com.xuanthanhtnt.xtplayer.ui.ArtworkImage
import com.xuanthanhtnt.xtplayer.ui.LocalAppActions
import com.xuanthanhtnt.xtplayer.ui.focusClickable
import com.xuanthanhtnt.xtplayer.ui.sources.SwitchRow
import com.xuanthanhtnt.xtplayer.ui.theme.LocalIsTv
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import kotlinx.coroutines.launch

// The music player's views (MusicPlayerViews.swift), after Roon: a bar along the bottom while the library is
// browsed, a full Now Playing (blurred cover, queue, signal path), and the queue itself.

/** MiniPlayerBar: above the bottom bar on phones, along the bottom of the window on tablets. */
@Composable
fun MiniPlayerBar() {
    val queue by MusicPlayer.queue.collectAsState()
    val index by MusicPlayer.index.collectAsState()
    val playing by MusicPlayer.isPlaying.collectAsState()
    val position by MusicPlayer.position.collectAsState()
    val duration by MusicPlayer.duration.collectAsState()
    val item = queue.getOrNull(index)?.item ?: return
    Column(Modifier.fillMaxWidth().background(XtColors.surface)) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .focusClickable(RoundedCornerShape(8.dp), focusScale = 1.02f) { MusicPlayer.setShowsNowPlaying(true) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ArtworkImage(item.artworkPath, Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)), size = "w185", placeholderIcon = Icons.Filled.MusicNote)
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            MusicButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Tạm dừng" else "Phát", 22.dp) {
                MusicPlayer.togglePause()
            }
            MusicButton(Icons.Filled.SkipNext, "Bài tiếp", 18.dp) { MusicPlayer.next() }
        }
        // A hairline of progress along the bar's bottom edge, as in Music.
        Box(
            Modifier
                .fillMaxWidth(if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f)
                .height(2.dp)
                .background(XtColors.leaf)
        )
    }
}

@Composable
private fun MusicButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(width = size + 18.dp, height = size + 14.dp)
            .focusClickable(RoundedCornerShape(8.dp), focusScale = 1.1f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size))
    }
}

/** Elapsed · scrubber · remaining (a plain bar on TV, as on Apple TV). */
@Composable
private fun SeekRow(modifier: Modifier = Modifier) {
    val position by MusicPlayer.position.collectAsState()
    val duration by MusicPlayer.duration.collectAsState()
    var scrub by remember { mutableStateOf<Double?>(null) }
    val isTv = LocalIsTv.current
    val shown = scrub ?: position
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            TimeFormat.clock(shown),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 44.dp),
        )
        if (isTv) {
            LinearProgressIndicator(
                progress = { if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f },
                modifier = Modifier.weight(1f),
                color = XtColors.leaf,
                trackColor = Color.White.copy(alpha = 0.2f),
            )
        } else {
            Slider(
                value = shown.toFloat().coerceIn(0f, duration.coerceAtLeast(1.0).toFloat()),
                onValueChange = { scrub = it.toDouble() },
                onValueChangeFinished = {
                    scrub?.let(MusicPlayer::seek)
                    scrub = null
                },
                valueRange = 0f..duration.coerceAtLeast(1.0).toFloat(),
                colors = SliderDefaults.colors(thumbColor = XtColors.leaf, activeTrackColor = XtColors.leaf),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "-" + TimeFormat.clock(maxOf(0.0, duration - shown)),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.widthIn(min = 48.dp),
        )
    }
}

// Now Playing

/**
 * MusicNowPlayingView: the cover on a blur of itself, title / album / artist, transport, signal path, queue. Always
 * leaves: "Thu nhỏ", Back (also the D-pad Back on TV), or a pull down on a phone. A window of its own (the
 * full-screen cover of iOS), so the D-pad cannot wander into the library underneath.
 */
@Composable
fun NowPlayingOverlay() {
    val shows by MusicPlayer.showsNowPlaying.collectAsState()
    val queue by MusicPlayer.queue.collectAsState()
    val index by MusicPlayer.index.collectAsState()
    val item = queue.getOrNull(index)?.item
    if (!shows || item == null) return
    Dialog(
        onDismissRequest = { MusicPlayer.setShowsNowPlaying(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        NowPlayingContent(item)
    }
}

@Composable
private fun NowPlayingContent(item: PlaybackItem) {
    val isTv = LocalIsTv.current
    if (isTv) {
        TvNowPlaying(item)
        return
    }
    val density = LocalDensity.current
    var showsQueue by remember { mutableStateOf(false) }
    var showsOptions by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            // Opaque underneath: a blur fades out at its edges and would let the library show through.
            .background(XtColors.background)
            .pointerInput(isTv) {
                if (isTv) return@pointerInput
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onDragEnd = { if (pulled > with(density) { 120.dp.toPx() }) MusicPlayer.setShowsNowPlaying(false) },
                ) { _, amount -> pulled += amount }
            }
    ) {
        ArtworkImage(item.artworkPath, Modifier.fillMaxSize().blur(70.dp), size = "w780", placeholderIcon = Icons.Filled.MusicNote)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            val wide = maxWidth > 900.dp
            val cover = minOf(420.dp, maxWidth * 0.7f, maxHeight * 0.42f)
            // Read here: Row / Column scopes cannot reach BoxWithConstraints' size.
            val queueHeight = maxHeight * 0.55f
            val queueWidth = minOf(420.dp, maxWidth * 0.38f)
            Row(Modifier.fillMaxSize().padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
                ) {
                    if (showsQueue && !wide) {
                        QueueList(Modifier.fillMaxWidth().height(queueHeight))
                    } else {
                        ArtworkImage(item.artworkPath, Modifier.size(cover).clip(RoundedCornerShape(14.dp)), size = "w780", placeholderIcon = Icons.Filled.MusicNote)
                    }
                    NowPlayingTitle(item)
                    SignalPathBadge()
                    SeekRow(Modifier.widthIn(max = 560.dp).fillMaxWidth())
                    Transport(playFocus)
                }
                if (showsQueue && wide) {
                    QueueList(Modifier.width(queueWidth).fillMaxHeight().padding(vertical = 60.dp))
                }
            }
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp, start = 20.dp)
                    .focusClickable(CircleShape, focusScale = 1.05f) { MusicPlayer.setShowsNowPlaying(false) }
                    .background(Color.White.copy(alpha = 0.14f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Thu nhỏ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            if (showsOptions) AudioOptionsDialog { showsOptions = false }
            Row(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicButton(Icons.Filled.Tune, "Xử lý âm thanh", 17.dp) { showsOptions = true }
                MusicButton(Icons.AutoMirrored.Filled.QueueMusic, "Hàng đợi", 18.dp, tint = if (showsQueue) XtColors.leaf else Color.White) {
                    showsQueue = !showsQueue
                }
            }
        }
    }
}

/** AudioOptionsMenu: the sound processing switches of Settings ▸ Âm thanh, applied to what is playing. */
@Composable
private fun AudioOptionsDialog(onDismiss: () -> Unit) {
    val settings = com.xuanthanhtnt.xtplayer.AppGraph.settings
    val dialog by settings.dialogBoost.collect()
    val night by settings.nightMode.collect()
    val leveling by settings.volumeLeveling.collect()
    val match by settings.matchSampleRate.collect()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xử lý âm thanh", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                SwitchRow("Tăng thoại", dialog, null) { settings.dialogBoost.value = it }
                SwitchRow("Chế độ ban đêm", night, null) { settings.nightMode.value = it }
                SwitchRow("Cân bằng âm lượng (ReplayGain)", leveling, null) { settings.volumeLeveling.value = it }
                if (com.xuanthanhtnt.xtplayer.player.HiResOutput.isAvailable) {
                    SwitchRow("Khớp tần số lấy mẫu với DAC USB", match, null) { settings.matchSampleRate.value = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

@Composable
private fun NowPlayingTitle(item: PlaybackItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            item.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Transport(playFocus: FocusRequester) {
    val playing by MusicPlayer.isPlaying.collectAsState()
    val shuffled by MusicPlayer.isShuffled.collectAsState()
    val repeat by MusicPlayer.repeatMode.collectAsState()
    val dim = Color.White.copy(alpha = 0.75f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        MusicButton(Icons.Filled.Shuffle, "Trộn bài", 17.dp, tint = if (shuffled) XtColors.leaf else dim) { MusicPlayer.toggleShuffle() }
        MusicButton(Icons.Filled.SkipPrevious, "Bài trước", 22.dp) { MusicPlayer.previous() }
        MusicButton(
            if (playing) Icons.Filled.PauseCircleFilled else Icons.Filled.PlayCircleFilled,
            if (playing) "Tạm dừng" else "Phát",
            54.dp,
            modifier = Modifier.focusRequester(playFocus),
        ) { MusicPlayer.togglePause() }
        MusicButton(Icons.Filled.SkipNext, "Bài tiếp", 22.dp) { MusicPlayer.next() }
        MusicButton(
            if (repeat == MusicPlayer.RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            "Lặp lại",
            17.dp,
            tint = if (repeat == MusicPlayer.RepeatMode.OFF) dim else XtColors.leaf,
        ) { MusicPlayer.cycleRepeat() }
    }
}

// TV Now Playing (MusicPlayerViews.swift `TVNowPlaying`, Apple v0.3.4)

/**
 * The large cover on a slowly drifting blur of itself, "ĐANG PHÁT · BÀI n/N", the title, quality, a thick progress
 * line, round glass buttons that grow under the focus; track changes cross-fade, the queue slides in from the right.
 * Back closes the queue first, then Now Playing.
 */
@Composable
private fun TvNowPlaying(item: PlaybackItem) {
    val playing by MusicPlayer.isPlaying.collectAsState()
    val queue by MusicPlayer.queue.collectAsState()
    val index by MusicPlayer.index.collectAsState()
    val shuffled by MusicPlayer.isShuffled.collectAsState()
    val repeat by MusicPlayer.repeatMode.collectAsState()
    var showsQueue by remember { mutableStateOf(false) }
    var showsOptions by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val queueButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { playFocus.requestFocus() }
    }
    BackHandler(enabled = showsQueue) {
        showsQueue = false
        runCatching { queueButton.requestFocus() }
    }
    val drift = rememberInfiniteTransition(label = "drift")
    val driftScale by drift.animateFloat(1.2f, 1.5f, infiniteRepeatable(tween(28_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "driftScale")
    val driftTurn by drift.animateFloat(-6f, 6f, infiniteRepeatable(tween(28_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "driftTurn")
    val coverSide by animateDpAsState(if (showsQueue) 190.dp else 280.dp, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow), label = "cover")
    // Breathes with playback, as in Music: full size while playing, set back when paused.
    val coverScale by animateFloatAsState(if (playing) 1f else 0.9f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow), label = "breath")

    Box(
        Modifier
            .fillMaxSize()
            .background(XtColors.background)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    MusicPlayer.togglePause()
                    true
                } else {
                    false
                }
            }
    ) {
        // A small copy of the cover, stretched and blurred, drifting and turning slowly: the screen never sits still.
        Crossfade(targetState = item.artworkPath, animationSpec = tween(600), label = "backdrop") { path ->
            ArtworkImage(
                path,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = driftScale
                        scaleY = driftScale
                        rotationZ = driftTurn
                    }
                    .blur(20.dp),
                size = "w92",
                placeholderIcon = Icons.Filled.MusicNote,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.8f)))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))))
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (showsQueue) 45.dp else 65.dp, vertical = 45.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (showsQueue) 27.dp else 48.dp),
        ) {
            Crossfade(targetState = item, animationSpec = tween(600), label = "cover") { shown ->
                ArtworkImage(
                    shown.artworkPath,
                    Modifier
                        .size(coverSide)
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                        }
                        .shadow(30.dp, RoundedCornerShape(15.dp))
                        .clip(RoundedCornerShape(15.dp)),
                    size = "w780",
                    placeholderIcon = Icons.Filled.MusicNote,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(
                    if (queue.size > 1) "ĐANG PHÁT · BÀI ${index + 1}/${queue.size}" else "ĐANG PHÁT",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 3.sp),
                    color = XtColors.leaf,
                )
                Crossfade(targetState = item, animationSpec = tween(600), label = "title") { shown ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            shown.title,
                            fontSize = if (showsQueue) 20.sp else 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        shown.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                SignalPathBadge()
                TvProgress(Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                    TvRoundButton(Icons.Filled.SkipPrevious, "Bài trước", 40.dp) { MusicPlayer.previous() }
                    TvRoundButton(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "Tạm dừng" else "Phát",
                        52.dp,
                        prominent = true,
                        modifier = Modifier.focusRequester(playFocus),
                    ) { MusicPlayer.togglePause() }
                    TvRoundButton(Icons.Filled.SkipNext, "Bài tiếp", 40.dp) { MusicPlayer.next() }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    TvRoundButton(Icons.Filled.Shuffle, "Trộn bài", 30.dp, active = shuffled) { MusicPlayer.toggleShuffle() }
                    TvRoundButton(
                        if (repeat == MusicPlayer.RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Lặp lại",
                        30.dp,
                        active = repeat != MusicPlayer.RepeatMode.OFF,
                    ) { MusicPlayer.cycleRepeat() }
                    TvRoundButton(Icons.AutoMirrored.Filled.QueueMusic, "Hàng đợi", 30.dp, active = showsQueue, modifier = Modifier.focusRequester(queueButton)) {
                        showsQueue = !showsQueue
                    }
                    TvRoundButton(Icons.Filled.Tune, "Xử lý âm thanh", 30.dp) { showsOptions = true }
                }
            }
            AnimatedVisibility(
                visible = showsQueue,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) {
                QueueList(
                    Modifier
                        .width(300.dp)
                        .fillMaxHeight(0.82f)
                        .clip(RoundedCornerShape(17.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(14.dp)
                )
            }
        }
    }
    if (showsOptions) AudioOptionsDialog { showsOptions = false }
}

/** Thick progress line with the times under it. */
@Composable
private fun TvProgress(modifier: Modifier = Modifier) {
    val position by MusicPlayer.position.collectAsState()
    val duration by MusicPlayer.duration.collectAsState()
    val fraction = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(fraction, tween(500, easing = LinearEasing), label = "progress")
    Column(modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(XtColors.leaf, XtColors.leaf.copy(alpha = 0.75f))))
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(TimeFormat.clock(position), style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), color = Color.White.copy(alpha = 0.65f), modifier = Modifier.weight(1f))
            Text("-" + TimeFormat.clock(maxOf(0.0, duration - position)), style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), color = Color.White.copy(alpha = 0.65f))
        }
    }
}

/** Round glass button: grows to 1.16 and turns white under the focus, like the system's own. */
@Composable
private fun TvRoundButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    prominent: Boolean = false,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.16f else 1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "roundScale")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (focused) 11.dp else 0.dp, CircleShape)
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White
                    prominent -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.12f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            label,
            tint = when {
                focused -> Color.Black
                active -> XtColors.leaf
                else -> Color.White
            },
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

// Queue

/** QueueList: played tracks dimmed, the current one marked, then what comes next. */
@Composable
fun QueueList(modifier: Modifier = Modifier) {
    val queue by MusicPlayer.queue.collectAsState()
    val index by MusicPlayer.index.collectAsState()
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val upcoming = (queue.size - index - 1).coerceAtLeast(0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hàng đợi", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
            Text("$upcoming bài tiếp theo", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
            if (upcoming > 0) {
                Text(
                    "Xoá hàng đợi",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = XtColors.leaf,
                    modifier = Modifier
                        .focusClickable(RoundedCornerShape(6.dp), focusScale = 1.05f) { MusicPlayer.clearUpcoming() }
                        .padding(4.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            itemsIndexed(queue, key = { _, entry -> entry.id }) { position, entry ->
                QueueRow(
                    entry.item,
                    isCurrent = position == index,
                    played = position < index,
                    onLongClick = { menuFor = position },
                ) { MusicPlayer.jump(position) }
            }
        }
    }
    val position = menuFor
    val entry = position?.let { queue.getOrNull(it) }
    if (position != null && entry != null) {
        // Drag to reorder and swipe to delete of the Apple app, as a menu (D-pad friendly).
        val options = buildList<Pair<String, () -> Unit>> {
            if (position > index + 1) add("Phát tiếp" to { MusicPlayer.move(position, index + 1) })
            if (position > 0) add("Chuyển lên" to { MusicPlayer.move(position, position - 1) })
            if (position < queue.size - 1) add("Chuyển xuống" to { MusicPlayer.move(position, position + 1) })
            if (position != index) add("Xoá khỏi hàng đợi" to { MusicPlayer.remove(position) })
        }
        ActionsDialog(entry.item.title, options) { menuFor = null }
    }
}

@Composable
private fun QueueRow(item: PlaybackItem, isCurrent: Boolean, played: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusClickable(RoundedCornerShape(8.dp), focusScale = 1.02f, onLongClick = onLongClick, onClick = onClick)
            .background(if (isCurrent) Color.White.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(if (played) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            ArtworkImage(item.artworkPath, Modifier.fillMaxSize(), size = "w185", placeholderIcon = Icons.Filled.MusicNote)
            if (isCurrent) {
                Box(Modifier.size(24.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.GraphicEq, "Đang phát", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// Signal path

/** The light's colour for each grade. */
fun SignalPath.Grade.color(): Color = when (this) {
    SignalPath.Grade.BIT_PERFECT -> Color(0.62f, 0.45f, 1.0f)
    SignalPath.Grade.HIGH_QUALITY -> XtColors.leaf
    SignalPath.Grade.ENHANCED -> Color(0.35f, 0.62f, 1.0f)
    SignalPath.Grade.LOSSY -> Color(1.0f, 0.78f, 0.25f)
    SignalPath.Grade.UNKNOWN -> Color.Gray
}

/** "● Bit-perfect · FLAC · 96 kHz · 24-bit" under the title in Now Playing; tap for the whole path. */
@Composable
private fun SignalPathBadge() {
    val input by MusicPlayer.signal.collectAsState()
    var showsPath by remember { mutableStateOf(false) }
    val path = SignalPath(input)
    Row(
        Modifier
            .alpha(if (input.hasOutput) 1f else 0f)
            .focusClickable(CircleShape, focusScale = 1.05f) { showsPath = true }
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(path.grade.color(), CircleShape))
        Text(
            listOf(path.grade.label, path.source).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
    }
    if (showsPath) SignalPathDialog(path) { showsPath = false }
}

/** SignalPathView: source → processing → output, each step on its own line. */
@Composable
fun SignalPathDialog(path: SignalPath, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(path.grade.color(), CircleShape))
                Text("Đường tín hiệu: ${path.grade.label}", style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PathStep("Nguồn", path.source.ifEmpty { "—" })
                if (path.processing.isEmpty()) {
                    PathStep("Xử lý", "Không — giữ nguyên dữ liệu gốc")
                } else {
                    path.processing.forEach { PathStep("Xử lý", it) }
                }
                PathStep("Thiết bị", path.output)
                Text(SignalPath.explanation(path.grade), style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

@Composable
private fun PathStep(title: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = XtColors.secondaryText, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** tvOS "Đang phát": back to Now Playing from the music page while music plays (TV has no bar). */
@Composable
fun NowPlayingButton() {
    val queue by MusicPlayer.queue.collectAsState()
    if (queue.isEmpty()) return
    Row(
        Modifier
            .focusClickable(CircleShape, focusScale = 1.05f) { MusicPlayer.setShowsNowPlaying(true) }
            .background(XtColors.glass, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.GraphicEq, null, tint = XtColors.leaf, modifier = Modifier.size(18.dp))
        Text("Đang phát", style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** Long press on an album or a track: Roon's play menu (AlbumQueueActions). */
@Composable
fun QueueActionsDialog(title: String, items: suspend () -> List<PlaybackItem>, onDismiss: () -> Unit) {
    // The app's scope: the dialog is gone by the time the tracks are read.
    val scope = LocalAppActions.current.scope
    fun run(action: (List<PlaybackItem>) -> Unit): () -> Unit = {
        scope.launch { action(items()) }
    }
    ActionsDialog(
        title,
        listOf(
            "Phát" to run { MusicPlayer.play(it) },
            "Trộn bài" to run { MusicPlayer.play(it, shuffle = true) },
            "Phát tiếp" to run { MusicPlayer.playNext(it) },
            "Thêm vào hàng đợi" to run { MusicPlayer.addToQueue(it) },
        ),
        onDismiss,
    )
}
