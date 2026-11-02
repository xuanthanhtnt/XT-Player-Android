package com.xuanthanhtnt.xtplayer.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.data.MediaLibrary
import com.xuanthanhtnt.xtplayer.metadata.ThumbnailStore
import com.xuanthanhtnt.xtplayer.network.ServerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** One entry of the music queue (the same track may be queued twice). */
data class QueueEntry(val id: String, val item: PlaybackItem)

/**
 * The app's music player, in the manner of Roon (MusicPlayer.swift): one queue for the whole app that keeps playing
 * while the library is browsed (a bar at the bottom, a full Now Playing on demand), gapless between tracks, with
 * play-next / add-to-queue, shuffle and repeat. Videos keep their own full-screen player and pause the music.
 *
 * The ExoPlayer lives here, in the app process; [MusicService] puts it in a media session so it plays on in the
 * background with a media notification and lock-screen controls. Main thread only.
 */
object MusicPlayer {
    enum class RepeatMode { OFF, ALL, ONE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Library writes in the order they were asked for (touch before saveProgress). */
    private val dbLock = Mutex()

    private val queueFlow = MutableStateFlow<List<QueueEntry>>(emptyList())
    private val indexFlow = MutableStateFlow(0)
    private val shuffledFlow = MutableStateFlow(false)
    private val repeatFlow = MutableStateFlow(RepeatMode.OFF)
    private val nowPlayingFlow = MutableStateFlow(false)
    private val playingFlow = MutableStateFlow(false)
    private val positionFlow = MutableStateFlow(0.0)
    private val durationFlow = MutableStateFlow(0.0)
    private val signalFlow = MutableStateFlow(AudioPathInput())

    val queue: StateFlow<List<QueueEntry>> = queueFlow
    val index: StateFlow<Int> = indexFlow
    val isShuffled: StateFlow<Boolean> = shuffledFlow
    val repeatMode: StateFlow<RepeatMode> = repeatFlow
    val showsNowPlaying: StateFlow<Boolean> = nowPlayingFlow
    val isPlaying: StateFlow<Boolean> = playingFlow
    val position: StateFlow<Double> = positionFlow
    val duration: StateFlow<Double> = durationFlow
    val signal: StateFlow<AudioPathInput> = signalFlow

    /** Android TV opens Now Playing when music starts (tvOS); set by the UI. */
    var isTv = false

    var player: ExoPlayer? = null
        private set

    /** Queue order before shuffling, to restore it. */
    private var unshuffled: List<QueueEntry> = emptyList()
    /** The library key of the track playing and its length, for saving progress. */
    private var entryKey: String? = null
    private var trackDuration = 0.0
    /** Audio format of each queue entry, as the decoder reported it (the next track arrives before it plays). */
    private val formats = mutableMapOf<String, Format>()
    private var outputSampleRate = 0
    private var ticker: Job? = null
    private var settingsJob: Job? = null
    private val night = NightCompressor()
    private val replayGain = GainHolder()
    private var deviceCallback: AudioDeviceCallback? = null

    val current: QueueEntry? get() = queueFlow.value.getOrNull(indexFlow.value)
    val isActive: Boolean get() = queueFlow.value.isNotEmpty()
    val upcoming: List<QueueEntry> get() = queueFlow.value.drop(indexFlow.value + 1)

    fun setShowsNowPlaying(show: Boolean) {
        nowPlayingFlow.value = show && isActive
    }

    // Queue

    /** Replaces the queue and starts playing. */
    fun play(items: List<PlaybackItem>, startAt: Int = 0, shuffle: Boolean = false) {
        if (items.isEmpty()) return
        saveProgress()
        val entries = items.map { QueueEntry(UUID.randomUUID().toString(), it) }
        var list = entries
        var first = startAt.coerceIn(0, items.size - 1)
        unshuffled = entries
        if (shuffle) {
            list = entries.shuffled()
            first = 0
        }
        shuffledFlow.value = shuffle
        queueFlow.value = list
        indexFlow.value = first
        formats.clear()
        items.forEach(::register)
        val exo = makePlayer()
        trackChanged()
        exo.setMediaItems(list.map(::mediaItem), first, 0)
        exo.prepare()
        exo.play()
        if (isTv) nowPlayingFlow.value = true
    }

    /** Plays these right after the current track (Roon's "Play next"). */
    fun playNext(items: List<PlaybackItem>) {
        val exo = player
        if (!isActive || exo == null) return play(items)
        val entries = items.map { QueueEntry(UUID.randomUUID().toString(), it) }
        items.forEach(::register)
        val at = indexFlow.value + 1
        queueFlow.value = queueFlow.value.toMutableList().apply { addAll(at, entries) }
        unshuffled = unshuffled + entries
        exo.addMediaItems(at, entries.map(::mediaItem))
    }

    /** Adds these at the end of the queue. */
    fun addToQueue(items: List<PlaybackItem>) {
        val exo = player
        if (!isActive || exo == null) return play(items)
        val entries = items.map { QueueEntry(UUID.randomUUID().toString(), it) }
        items.forEach(::register)
        queueFlow.value = queueFlow.value + entries
        unshuffled = unshuffled + entries
        exo.addMediaItems(entries.map(::mediaItem))
    }

    fun jump(to: Int) {
        val exo = player ?: return
        if (to !in queueFlow.value.indices) return
        saveProgress()
        exo.seekTo(to, 0)
        exo.play()
    }

    fun remove(at: Int) {
        val exo = player ?: return
        if (at !in queueFlow.value.indices || at == indexFlow.value) return
        val removed = queueFlow.value[at]
        queueFlow.value = queueFlow.value.toMutableList().apply { removeAt(at) }
        unshuffled = unshuffled.filter { it.id != removed.id }
        exo.removeMediaItem(at)
        if (at < indexFlow.value) indexFlow.value -= 1
    }

    /** Moves an entry so it ends up at index [to] (the current track keeps playing). */
    fun move(from: Int, to: Int) {
        val exo = player ?: return
        val list = queueFlow.value
        if (from !in list.indices || to !in list.indices || from == to) return
        val playing = current?.id
        queueFlow.value = list.toMutableList().apply { add(to, removeAt(from)) }
        exo.moveMediaItem(from, to)
        playing?.let { id -> queueFlow.value.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { indexFlow.value = it } }
    }

    /** Clears what is left after the current track. */
    fun clearUpcoming() {
        val exo = player ?: return
        if (!isActive) return
        val keep = indexFlow.value + 1
        if (keep < queueFlow.value.size) exo.removeMediaItems(keep, queueFlow.value.size)
        queueFlow.value = queueFlow.value.take(keep)
        unshuffled = queueFlow.value
    }

    /** Only what comes after the current track changes order; turning it off restores the order it had. */
    fun toggleShuffle() {
        val exo = player ?: return
        if (current == null) return
        shuffledFlow.value = !shuffledFlow.value
        val (rest, saved) = MusicQueue.reorderUpcoming(queueFlow.value, indexFlow.value, unshuffled, shuffledFlow.value)
        unshuffled = saved
        val keep = indexFlow.value + 1
        if (keep < queueFlow.value.size) exo.removeMediaItems(keep, queueFlow.value.size)
        queueFlow.value = queueFlow.value.take(keep) + rest
        exo.addMediaItems(rest.map(::mediaItem))
    }

    /** Off → the whole queue → one track. */
    fun cycleRepeat() {
        repeatFlow.value = MusicQueue.nextRepeat(repeatFlow.value)
        player?.repeatMode = exoRepeat()
    }

    // Transport

    fun togglePause() {
        val exo = player ?: return
        when {
            exo.playbackState == Player.STATE_ENDED -> jump(0)
            exo.playWhenReady -> pause()
            else -> exo.play()
        }
    }

    fun pause() {
        player?.pause()
        saveProgress()
    }

    /** A video starts: the music pauses and the video's own session takes the remote / lock-screen controls. */
    fun suspendForVideo() {
        if (!isActive) return
        pause()
        nowPlayingFlow.value = false
    }

    fun next() {
        val exo = player ?: return
        if (!exo.hasNextMediaItem()) return
        saveProgress()
        exo.seekToNextMediaItem()
        exo.play()
    }

    /** Back to the start of the track, or the previous track within the first seconds (like every music player). */
    fun previous() {
        val exo = player ?: return
        if (MusicQueue.restartsTrack(exo.currentPosition, indexFlow.value)) {
            exo.seekTo(0)
        } else {
            saveProgress()
            exo.seekToPreviousMediaItem()
        }
        exo.play()
    }

    fun seek(seconds: Double) {
        player?.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
    }

    fun stop() {
        saveProgress()
        MusicService.shutdown()
        player?.release()
        player = null
        ticker?.cancel()
        ticker = null
        settingsJob?.cancel()
        settingsJob = null
        night.release()
        deviceCallback?.let { audioManager().unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        queueFlow.value = emptyList()
        unshuffled = emptyList()
        indexFlow.value = 0
        nowPlayingFlow.value = false
        playingFlow.value = false
        formats.clear()
        entryKey = null
    }

    // Engine

    private fun makePlayer(): ExoPlayer {
        player?.let { return it }
        val context = AppGraph.context
        val exo = ExoPlayer.Builder(context, XtRenderersFactory(context, passthrough = false, gain = replayGain))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(XtDataSource.Factory(context), XtExtractorsFactory)
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.repeatMode = exoRepeat()
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        player = exo
        // Cancelled by stop(): delay() throws then.
        ticker = scope.launch {
            while (true) {
                refresh()
                delay(500)
            }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = updateSignal()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = updateSignal()
        }
        audioManager().registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        deviceCallback = callback
        // Dialogue boost, night mode: applied by the sink at once; the light shows them.
        val settings = AppGraph.settings
        night.attach(exo.audioSessionId, settings.nightMode.value)
        settingsJob = scope.launch {
            combine(settings.nightMode.flow, settings.dialogBoost.flow, settings.volumeLeveling.flow) { nightMode, _, _ -> nightMode }
                .collect { nightMode ->
                    night.setEnabled(nightMode)
                    applyLevelling()
                    updateSignal()
                }
        }
        // A USB DAC switched to (or away from) the file's rate: the output reopens at the same spot.
        scope.launch {
            settings.matchSampleRate.flow.drop(1).collect {
                HiResOutput.reapply(context)
                player?.let { p -> p.seekTo(p.currentPosition) }
                updateSignal()
            }
        }
        // The media session (notification, lock screen, headset buttons) and background playback.
        runCatching { context.startService(Intent(context, MusicService::class.java)) }
        return exo
    }

    private fun exoRepeat(): Int = when (repeatFlow.value) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A track that ended on its own counts as listened to; skips saved their position before the command.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) finish(entryKey, trackDuration)
            syncIndex()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = syncIndex()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finish(entryKey, trackDuration)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playingFlow.value = isPlaying
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Paused from the notification, a headset or a phone call: keep the place.
            if (!playWhenReady) saveProgress()
        }

        override fun onVolumeChanged(volume: Float) = updateSignal()

        override fun onAudioSessionIdChanged(audioSessionId: Int) = night.attach(audioSessionId, AppGraph.settings.nightMode.value)
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            val id = mediaId(eventTime) ?: return
            formats[id] = format
            replayGain.db = ReplayGain.gainDb(format.metadata) ?: 0f
            applyLevelling()
            log(
                "input ${PlayerEngine.codecName(format)} ${format.sampleRate} Hz, ${format.channelCount} ch, " +
                    "pcm ${format.pcmEncoding}, ReplayGain ${replayGain.db} dB",
            )
            if (id == current?.id) updateSignal()
            learnQuality(id, format)
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            outputSampleRate = audioTrackConfig.sampleRate
            log(
                "output ${audioTrackConfig.sampleRate} Hz, encoding ${audioTrackConfig.encoding}, " +
                    "channels 0x${Integer.toHexString(audioTrackConfig.channelConfig)}, buffer ${audioTrackConfig.bufferSize} B",
            )
            updateSignal()
        }

        // Diagnostics for crackles: the output ran dry, or the sink failed.
        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            log("underrun: buffer $bufferSizeMs ms, $elapsedSinceLastFeedMs ms since the last write")
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            log("sink error: ${audioSinkError.message ?: audioSinkError}")
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            log("decoder error: ${audioCodecError.message ?: audioCodecError}")
        }
    }

    private fun log(message: String) = com.xuanthanhtnt.xtplayer.core.AppLog.append("[audio] $message")

    /**
     * ReplayGain lowers through the player's volume, as the output scales it, with no sample processing (nearly
     * every album gain is negative); a positive gain is applied by the audio sink.
     */
    private fun applyLevelling() {
        val exo = player ?: return
        exo.volume = levellingFactor()
    }

    private fun levellingFactor(): Float =
        if (AppGraph.settings.volumeLeveling.value && replayGain.db < 0f) ReplayGain.factor(replayGain.db) else 1f

    private fun mediaId(eventTime: AnalyticsListener.EventTime): String? {
        val timeline = eventTime.timeline
        if (eventTime.windowIndex !in 0 until timeline.windowCount) return null
        return timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
    }

    /** The player's position in its playlist is the source of truth after transitions, removals and moves. */
    private fun syncIndex() {
        val exo = player ?: return
        val position = exo.currentMediaItemIndex
        if (position !in queueFlow.value.indices) return
        val changed = queueFlow.value[position].item.key != entryKey || position != indexFlow.value
        indexFlow.value = position
        if (changed) trackChanged()
    }

    private fun refresh() {
        val exo = player ?: return
        positionFlow.value = exo.currentPosition / 1000.0
        val length = exo.duration
        durationFlow.value = if (length == C.TIME_UNSET || length <= 0) 0.0 else length / 1000.0
        if (durationFlow.value > 0) trackDuration = durationFlow.value
    }

    private fun register(item: PlaybackItem) {
        item.server?.let(ServerRegistry::register)
    }

    private fun trackChanged() {
        val playing = current ?: return
        entryKey = playing.item.key
        trackDuration = 0.0
        positionFlow.value = 0.0
        durationFlow.value = 0.0
        updateSignal()
        scope.launch { dbLock.withLock { MediaLibrary.touch(playing.item) } }
    }

    private fun finish(key: String?, length: Double) {
        if (key == null || length <= 0) return
        scope.launch { dbLock.withLock { MediaLibrary.saveProgress(key, length, length) } }
    }

    private fun saveProgress() {
        val exo = player ?: return
        val key = entryKey ?: return
        val length = exo.duration
        if (length == C.TIME_UNSET || length <= 0) return
        val at = exo.currentPosition / 1000.0
        scope.launch { dbLock.withLock { MediaLibrary.saveProgress(key, at, length / 1000.0) } }
    }

    /** What the file really is ("FLAC · 96 kHz · 24-bit"), shown on the album page from then on. */
    private fun learnQuality(id: String, format: Format) {
        val entry = queueFlow.value.firstOrNull { it.id == id } ?: return
        if (format.sampleRate <= 0) return
        val quality = SignalPath.quality(
            PlayerEngine.codecName(format), sourceRate(format),
            SignalPath.bitDepth(format.pcmEncoding, format.sampleMimeType, format.initializationData),
        )
        if (quality.isEmpty()) return
        scope.launch { dbLock.withLock { MediaLibrary.learnTrack(entry.item.key, quality) } }
    }

    private fun updateSignal() {
        val exo = player
        val format = current?.id?.let { formats[it] }
        val context = AppGraph.context
        signalFlow.value = AudioPathInput(
            codec = format?.let(PlayerEngine::codecName).orEmpty(),
            sampleRate = format?.let(::sourceRate) ?: 0,
            bitDepth = format?.let { SignalPath.bitDepth(it.pcmEncoding, it.sampleMimeType, it.initializationData) },
            outputSampleRate = outputSampleRate,
            deviceSampleRate = AudioOutputs.mixerSampleRate(context),
            deviceName = AudioOutputs.deviceName(context),
            dialogBoost = AppGraph.settings.dialogBoost.value,
            nightMode = AppGraph.settings.nightMode.value,
            volumeLeveling = AppGraph.settings.volumeLeveling.value,
            muted = exo?.volume == 0f,
            // The app's own volume, without the ReplayGain part of it.
            volume = (exo?.volume ?: 1f) / levellingFactor(),
        )
    }

    /** The file's rate; DSD counts as FFmpeg decodes it, 1/8 of the DSD rate (DSD64 → 352.8 kHz), as in the Apple app. */
    private fun sourceRate(format: Format): Int = DsdFormat.dsdRate(format)?.let { it / 8 } ?: format.sampleRate.coerceAtLeast(0)

    private fun mediaItem(entry: QueueEntry): MediaItem {
        val item = entry.item
        val uri = if (item.uri.startsWith("/")) Uri.fromFile(File(item.uri)) else Uri.parse(item.uri)
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(entry.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setDisplayTitle(item.title)
                    .setArtist(item.subtitle)
                    .setSubtitle(item.subtitle)
                    .setArtworkUri(artworkUri(item.artworkPath))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    /** A TMDb image or a local cover, for the notification and lock screen. */
    private fun artworkUri(path: String?): Uri? {
        if (path.isNullOrEmpty()) return null
        if (ThumbnailStore.isLocal(path)) return ThumbnailStore.file(path)?.takeIf { it.exists() }?.let(Uri::fromFile)
        return Uri.parse("https://image.tmdb.org/t/p/w500$path")
    }

    private fun audioManager(): AudioManager = AppGraph.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}

/** MusicPlayer.swift's queue rules, apart from the player so they can be tested. */
object MusicQueue {
    /**
     * Shuffle on: the tracks after the current one in random order (the order before is kept to restore).
     * Shuffle off: back to the kept order after the current track. Returns (what follows, the kept order).
     */
    fun <T> reorderUpcoming(
        queue: List<T>,
        index: Int,
        unshuffled: List<T>,
        shuffle: Boolean,
        shuffler: (List<T>) -> List<T> = { it.shuffled() },
    ): Pair<List<T>, List<T>> {
        val upcoming = queue.drop(index + 1)
        if (shuffle) return shuffler(upcoming) to queue
        val start = unshuffled.indexOf(queue[index]) + 1
        return (if (start > 0) unshuffled.drop(start) else upcoming) to unshuffled
    }

    /** "Previous": past the first 3 s, or on the first track, it starts the track again. */
    fun restartsTrack(positionMs: Long, index: Int): Boolean = positionMs > 3_000 || index == 0

    /** Off → the whole queue → one track → off. */
    fun nextRepeat(mode: MusicPlayer.RepeatMode): MusicPlayer.RepeatMode {
        val all = MusicPlayer.RepeatMode.entries
        return all[(mode.ordinal + 1) % all.size]
    }
}

/** Which output the sound goes to, as far as Android tells an app. */
object AudioOutputs {
    /**
     * Channels the output takes for a source of [source] channels: a receiver or TV on HDMI (or a digital line out)
     * that reports more than two keeps the layout; speakers, headphones and Bluetooth get stereo.
     */
    fun outputChannels(context: Context, source: Int): Int {
        if (source <= 2) return source
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).maxByOrNull { priority(it.type) } ?: return 2
        val multichannel = device.type in setOf(
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        )
        if (!multichannel) return 2
        // An empty list means any channel count.
        val counts = device.channelCounts
        return if (counts.isEmpty()) source else minOf(source, counts.max()).takeIf { it > 2 } ?: 2
    }

    /** The primary output's mixer rate (usually 48 kHz): what the system resamples to. */
    fun mixerSampleRate(context: Context): Int =
        HiResOutput.preferredRate(context).takeIf { it > 0 }
            ?: (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

    /** The output Android routes media to: a USB DAC before Bluetooth, wired headphones, HDMI, then the speaker. */
    fun deviceName(context: Context): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).maxByOrNull { priority(it.type) } ?: return ""
        if (priority(device.type) == 0) return ""
        val product = device.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Tai nghe có dây"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line out"
            else -> "Loa của máy"
        }
        return if (product != null && kind != "Loa của máy") "$kind · $product" else kind
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 6
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 5
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 4
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> 3
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }
}
