package com.xuanthanhtnt.xtplayer.player

import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.data.MediaLibrary
import com.xuanthanhtnt.xtplayer.trakt.TraktMedia
import com.xuanthanhtnt.xtplayer.trakt.TraktService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

/**
 * The play queue around [PlayerEngine]: resume points, Continue Watching, Trakt scrobbles, the library's
 * resolution / running time, and Up Next (what PlayerScreen.swift did around MPVPlayerEngine).
 */
class PlayerController(
    val engine: PlayerEngine,
    session: PlaybackSession,
    private val scope: CoroutineScope,
    private val onVideo: (VideoSignal) -> Unit,
) {
    private val items = session.items.toMutableList()
    private val indexState = MutableStateFlow(session.safeStart)
    private val currentState = MutableStateFlow(items[session.safeStart])
    private val upNextState = MutableStateFlow<Int?>(null)

    val index: StateFlow<Int> = indexState
    val current: StateFlow<PlaybackItem> = currentState
    /** Seconds left before the next item starts, or null. */
    val upNextCountdown: StateFlow<Int?> = upNextState

    val hasNext: Boolean get() = indexState.value + 1 < items.size
    val hasPrevious: Boolean get() = indexState.value > 0
    val next: PlaybackItem? get() = items.getOrNull(indexState.value + 1)

    /** While casting: the TV's (position, duration), used for resume points. */
    var positionOverride: (() -> Pair<Double, Double>?)? = null
    /** While casting: this device's player stays paused when the queue moves on. */
    var holdLocal = false
    /** After a queue item is loaded (the Cast bridge sends it on to the TV). */
    var onLoaded: ((PlaybackItem) -> Unit)? = null

    private var traktMedia: TraktMedia? = null
    private val scrobbleGate = com.xuanthanhtnt.xtplayer.trakt.ScrobbleGate()
    private var learned = false
    private var durationKnown = false
    private var finished = false
    private var upNextJob: Job? = null
    private var lastState: PlaybackState = PlaybackState.Idle

    fun start() {
        load(indexState.value)
        scope.launch { engine.state.collect { onState(it) } }
        // Hardware decoding switched in Settings (or in the player's Video panel): reload at the same spot.
        scope.launch { AppGraph.settings.hardwareDecoding.flow.drop(1).collect { retry() } }
        // The duration often arrives just after "playing": send the start then (PlayerScreen.swift onChange).
        scope.launch {
            engine.duration.collect { duration ->
                if (duration > 0 && !durationKnown) {
                    durationKnown = true
                    if (engine.state.value == PlaybackState.Playing) scrobble(TraktService.ScrobbleAction.START)
                }
            }
        }
        scope.launch {
            engine.video.collect { video ->
                if (video.fps > 0 || video.width > 0) onVideo(video)
                maybeLearn()
            }
        }
        scope.launch {
            // Resume points survive a crash or the system killing the app.
            while (true) {
                delay(15_000)
                if (engine.state.value == PlaybackState.Playing) saveProgress()
            }
        }
    }

    /** Another queue opened while playing (e.g. from a file manager). */
    fun replace(session: PlaybackSession) {
        saveProgress()
        scrobble(TraktService.ScrobbleAction.STOP)
        items.clear()
        items.addAll(session.items)
        load(session.safeStart)
    }

    private fun load(newIndex: Int) {
        cancelUpNext()
        indexState.value = newIndex
        val item = items[newIndex]
        currentState.value = item
        learned = false
        finished = false
        durationKnown = false
        traktMedia = null
        item.server?.let(com.xuanthanhtnt.xtplayer.network.ServerRegistry::register)
        engine.load(item)
        if (holdLocal) engine.pause()
        onLoaded?.invoke(item)
        scope.launch {
            traktMedia = TraktMedia.from(item.key, item.fileName)
            // Looked up after playback may already have started (the Apple app knows it before loading).
            if (engine.state.value == PlaybackState.Playing) scrobble(TraktService.ScrobbleAction.START)
            MediaLibrary.touch(item)
        }
    }

    /** The TV finished the item: it counts as watched, and the next one follows on the TV. */
    fun remoteEnded() {
        finished = true
        saveProgress()
        if (AppGraph.settings.autoplayNext.value && hasNext) advance(1)
    }

    fun advance(step: Int) {
        val target = indexState.value + step
        if (target !in items.indices) return
        saveProgress()
        scrobble(TraktService.ScrobbleAction.STOP)
        load(target)
    }

    fun restartFromBeginning() {
        engine.seek(0.0)
        engine.play()
    }

    /** Loads the current item again at the same position (after an error, or a decoder switch). */
    fun retry() {
        val item = items[indexState.value].copy(startAt = engine.position.value.takeIf { it > 0 })
        items[indexState.value] = item
        currentState.value = item
        engine.load(item)
    }

    fun addSubtitle(file: File) {
        val updated = engine.addSubtitle(file, items[indexState.value])
        items[indexState.value] = updated
        currentState.value = updated
    }

    /** Called when the player closes. */
    fun stop() {
        cancelUpNext()
        saveProgress()
        scrobble(TraktService.ScrobbleAction.STOP)
    }

    fun saveProgress() {
        val key = currentState.value.key
        val (playhead, duration) = positionOverride?.invoke() ?: (engine.position.value to engine.duration.value)
        val position = if (finished) duration else playhead
        if (duration <= 0 && position <= 0) return
        AppGraph.scope.launch { MediaLibrary.saveProgress(key, position, duration) }
    }

    // Up Next

    fun playNextNow() = advance(1)

    fun cancelUpNext() {
        upNextJob?.cancel()
        upNextJob = null
        upNextState.value = null
    }

    private fun startUpNext() {
        upNextJob?.cancel()
        upNextState.value = 10
        upNextJob = scope.launch {
            while ((upNextState.value ?: 0) > 0) {
                delay(1000)
                upNextState.value = (upNextState.value ?: 1) - 1
            }
            if (upNextState.value == 0) advance(1)
        }
    }

    // Events

    private fun onState(state: PlaybackState) {
        val previous = lastState
        lastState = state
        when (state) {
            PlaybackState.Playing -> scrobble(TraktService.ScrobbleAction.START)
            PlaybackState.Paused -> {
                if (previous == PlaybackState.Playing) {
                    scrobble(TraktService.ScrobbleAction.PAUSE)
                    saveProgress()
                }
            }
            PlaybackState.Ended -> {
                finished = true
                scrobble(TraktService.ScrobbleAction.STOP, 100.0)
                saveProgress()
                if (AppGraph.settings.autoplayNext.value && hasNext) startUpNext()
            }
            else -> Unit
        }
        maybeLearn()
    }

    /** Resolution and running time for the library (Infuse's "2 hrs 40 min · 1080p"). */
    private fun maybeLearn() {
        if (learned) return
        val label = engine.video.value.resolutionLabel
        val duration = engine.duration.value
        if (label.isEmpty() || duration <= 0) return
        learned = true
        val key = currentState.value.key
        AppGraph.scope.launch { MediaLibrary.learn(key, label, duration) }
    }

    /** Trakt scrobble for the current file; a stop under 80 % leaves a resume point other devices pick up. */
    private fun scrobble(action: TraktService.ScrobbleAction, progress: Double? = null) {
        val media = traktMedia
        val duration = engine.duration.value
        if (!scrobbleGate.allow(action, hasMedia = media != null, durationKnown = duration > 0)) return
        AppGraph.trakt.scrobble(action, media ?: return, progress ?: (engine.position.value / duration * 100))
    }
}
