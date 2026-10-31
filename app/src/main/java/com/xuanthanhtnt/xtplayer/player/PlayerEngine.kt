package com.xuanthanhtnt.xtplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import com.xuanthanhtnt.xtplayer.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.max

sealed class PlaybackState {
    data object Idle : PlaybackState()
    data object Loading : PlaybackState()
    data object Playing : PlaybackState()
    data object Paused : PlaybackState()
    data object Ended : PlaybackState()
    data class Failed(val message: String) : PlaybackState()
}

enum class VideoFit(val label: String, val resizeMode: Int) {
    FIT("Vừa khung (giữ tỉ lệ)", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Lấp đầy (cắt viền)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Kéo giãn", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

data class MediaTrack(
    val id: String,
    val type: Int,
    val label: String,
    val detail: String,
    val selected: Boolean,
    val supported: Boolean,
    val group: TrackGroup,
    val index: Int,
)

data class VideoSignal(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val codec: String = "",
    val dynamicRange: String = "SDR",
    val bitrate: Int = 0,
) {
    val resolutionLabel: String
        get() = when (max(width, height)) {
            in 3000..Int.MAX_VALUE -> "4K"
            in 1800 until 3000 -> "1080p"
            in 1200 until 1800 -> "720p"
            in 1 until 1200 -> "SD"
            else -> ""
        }
}

data class AudioSignal(
    val codec: String = "",
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    val language: String = "",
)

/**
 * Video decoders in the order the user wants: hardware first (Settings ▸ Giải mã phần cứng, the default), or only
 * Android's software decoders (c2.android.*) for files a device's hardware decoder shows wrongly. Read at every
 * decoder start, so switching takes effect on the next load of the file.
 */
private val codecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
    val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
    when {
        !mimeType.startsWith("video/") -> all
        AppGraph.settings.hardwareDecoding.value -> all.sortedBy { if (it.hardwareAccelerated) 0 else 1 }
        else -> all.filter { it.softwareOnly }.ifEmpty { all }
    }
}

/** Whether a decoder name is one of Android's / FFmpeg's software decoders. */
fun isSoftwareDecoder(name: String): Boolean =
    name.startsWith("c2.android.") || name.startsWith("OMX.google.") || name.contains("ffmpeg", ignoreCase = true)

/** Bitstream when the sink accepts it, or force PCM (Settings ▸ Âm thanh). */
internal class XtRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val gain: GainHolder = GainHolder(),
) : DefaultRenderersFactory(context) {
    private val appContext = context.applicationContext

    init {
        // Platform decoders first; FFmpeg (TrueHD, DTS, …) when the device has none.
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
        // 24-bit / high-sample-rate PCM (FLAC, decoded TrueHD / DTS-HD MA) stays in float instead of being cut to 16 bit.
        setEnableAudioFloatOutput(true)
        setMediaCodecSelector(codecSelector)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        @Suppress("DEPRECATION")
        if (!passthrough) builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        // Dialogue boost (and the channel side of night mode) in front of the sink, on every PCM path.
        return XtAudioSink(builder.build(), appContext, gain)
    }
}

/** ExoPlayer with XT-Player's sources, buffers, track preferences and observable state (the MPVPlayerEngine of iOS). */
class PlayerEngine(context: Context) {
    private val settings = AppGraph.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val night = NightCompressor()
    private val replayGain = GainHolder()

    private val trackSelector = DefaultTrackSelector(context)
    val player: ExoPlayer

    private val stateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val positionFlow = MutableStateFlow(0.0)
    private val durationFlow = MutableStateFlow(0.0)
    private val bufferedFlow = MutableStateFlow(0.0)
    private val bufferingFlow = MutableStateFlow(false)
    private val audioTracksFlow = MutableStateFlow<List<MediaTrack>>(emptyList())
    private val subtitleTracksFlow = MutableStateFlow<List<MediaTrack>>(emptyList())
    private val videoFlow = MutableStateFlow(VideoSignal())
    private val audioFlow = MutableStateFlow(AudioSignal())
    private val speedFlow = MutableStateFlow(1f)
    private val fitFlow = MutableStateFlow(VideoFit.FIT)
    private val decoderFlow = MutableStateFlow("")
    private val audioDecoderFlow = MutableStateFlow("")
    private val bitstreamFlow = MutableStateFlow(false)
    private val networkFlow = MutableStateFlow(0.0)
    private val droppedFlow = MutableStateFlow(0)

    val state: StateFlow<PlaybackState> = stateFlow
    val position: StateFlow<Double> = positionFlow
    val duration: StateFlow<Double> = durationFlow
    /** Seconds of media buffered ahead of the playhead. */
    val buffered: StateFlow<Double> = bufferedFlow
    val isBuffering: StateFlow<Boolean> = bufferingFlow
    val audioTracks: StateFlow<List<MediaTrack>> = audioTracksFlow
    val subtitleTracks: StateFlow<List<MediaTrack>> = subtitleTracksFlow
    val video: StateFlow<VideoSignal> = videoFlow
    val audio: StateFlow<AudioSignal> = audioFlow
    val speed: StateFlow<Float> = speedFlow
    val videoFit: StateFlow<VideoFit> = fitFlow
    val videoDecoder: StateFlow<String> = decoderFlow
    val audioDecoder: StateFlow<String> = audioDecoderFlow
    /** The receiver gets the original AC3 / E-AC3 / DTS / TrueHD stream (volume is set on the receiver). */
    val isBitstreaming: StateFlow<Boolean> = bitstreamFlow
    /** Network bytes per second (SMB block fetches, HTTP). */
    val networkSpeed: StateFlow<Double> = networkFlow
    val droppedFrames: StateFlow<Int> = droppedFlow

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) = night.attach(audioSessionId, settings.nightMode.value)

        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshPosition()
            updateState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateState()

        override fun onPlayerError(error: PlaybackException) = updateState()

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speedFlow.value = playbackParameters.speed
        }

        override fun onTracksChanged(tracks: Tracks) {
            rebuildTracks(tracks)
            player.videoFormat?.let { videoFlow.value = videoSignal(it) }
            player.audioFormat?.let { audioFlow.value = audioSignalFor(it) }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) {
            decoderFlow.value = decoderName
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) {
            audioDecoderFlow.value = decoderName
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            videoFlow.value = videoSignal(format)
        }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            audioFlow.value = audioSignalFor(format)
            replayGain.db = ReplayGain.gainDb(format.metadata) ?: 0f
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            bitstreamFlow.value = audioTrackConfig.encoding in BITSTREAM_ENCODINGS
            if (bitstreamFlow.value) audioDecoderFlow.value = "Bitstream (HDMI)"
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            droppedFlow.value += droppedFrames
        }
    }

    init {
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setPreferredAudioLanguages(*settings.languages(settings.audioLanguages.value).toTypedArray())
                .setPreferredTextLanguages(*settings.languages(settings.subtitleLanguages.value).toTypedArray())
                .setTunnelingEnabled(settings.tunneling.value)
        )
        // The demuxed buffer lives in the Java heap: keep it well under the app's limit (large heap is 512 MB on
        // most devices, 256 MB on some) or a fast NAS fills it in seconds and the app runs out of memory.
        val heapBudget = (Runtime.getRuntime().maxMemory() * 0.35).toLong()
        val cacheBytes = minOf(settings.cacheMegabytes.value.coerceIn(50, 2048) * 1024L * 1024L, heapBudget)
            .coerceAtLeast(32L * 1024 * 1024).toInt()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 600_000, 2_000, 4_000)
            .setTargetBufferBytes(cacheBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(15_000, true)
            .build()
        player = ExoPlayer.Builder(context, XtRenderersFactory(context, settings.audioPassthrough.value, replayGain))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(XtDataSource.Factory(context), XtExtractorsFactory)
                    .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8))
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(settings.skipBackSeconds.value * 1000L)
            .setSeekForwardIncrementMs(settings.skipForwardSeconds.value * 1000L)
            .setVideoChangeFrameRateStrategy(
                if (settings.frameRateMatching.value == "off") C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            )
            .build()
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        // Night mode follows Settings while the video plays.
        night.attach(player.audioSessionId, settings.nightMode.value)
        scope.launch { settings.nightMode.flow.collect { night.setEnabled(it) } }
        scope.launch {
            var tick = 0
            while (isActive) {
                refreshPosition()
                if (tick++ % 2 == 0) networkFlow.value = TrafficMeter.sample()
                delay(500)
            }
        }
    }

    // Commands

    fun load(item: PlaybackItem) {
        val uri = if (item.uri.startsWith("/")) Uri.fromFile(File(item.uri)) else Uri.parse(item.uri)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(item.key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setDisplayTitle(item.title)
                    .setSubtitle(item.subtitle)
                    .build()
            )
            .setSubtitleConfigurations(item.externalSubtitles.mapNotNull(::subtitleConfiguration))
            .build()
        stateFlow.value = PlaybackState.Loading
        videoFlow.value = VideoSignal()
        audioFlow.value = AudioSignal()
        bitstreamFlow.value = false
        player.setMediaItem(mediaItem, ((item.startAt ?: 0.0) * 1000).toLong())
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePause() {
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    fun play() = player.play()
    fun pause() = player.pause()

    fun seek(to: Double) {
        val d = durationFlow.value
        val target = if (d > 0) to.coerceIn(0.0, d) else max(0.0, to)
        player.seekTo((target * 1000).toLong())
        positionFlow.value = target
    }

    fun seekBy(seconds: Double) = seek(positionFlow.value + seconds)

    fun setSpeed(value: Float) {
        player.playbackParameters = PlaybackParameters(value)
    }

    fun setVideoFit(fit: VideoFit) {
        fitFlow.value = fit
    }

    fun selectTrack(track: MediaTrack) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(track.type, false)
            .setOverrideForType(TrackSelectionOverride(track.group, track.index))
            .build()
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** A subtitle downloaded from OpenSubtitles: reloads the item with it attached, at the same position. */
    fun addSubtitle(file: File, current: PlaybackItem): PlaybackItem {
        val updated = current.copy(
            externalSubtitles = current.externalSubtitles + Uri.fromFile(file).toString(),
            startAt = positionFlow.value,
        )
        val playing = player.playWhenReady
        load(updated)
        player.playWhenReady = playing
        pendingSubtitleSelection = file.name
        return updated
    }

    private var pendingSubtitleSelection: String? = null

    fun release() {
        scope.cancel()
        night.release()
        player.removeListener(listener)
        player.release()
    }

    // State

    private fun refreshPosition() {
        val duration = player.duration
        durationFlow.value = if (duration == C.TIME_UNSET || duration < 0) 0.0 else duration / 1000.0
        positionFlow.value = max(0L, player.currentPosition) / 1000.0
        bufferedFlow.value = max(0L, player.bufferedPosition - player.currentPosition) / 1000.0
    }

    private fun updateState() {
        val error = player.playerError
        stateFlow.value = when {
            error != null -> PlaybackState.Failed(describe(error))
            player.playbackState == Player.STATE_ENDED -> PlaybackState.Ended
            player.playbackState == Player.STATE_IDLE -> PlaybackState.Idle
            player.playbackState == Player.STATE_BUFFERING && durationFlow.value <= 0 -> PlaybackState.Loading
            player.playWhenReady -> PlaybackState.Playing
            else -> PlaybackState.Paused
        }
        bufferingFlow.value = player.playbackState == Player.STATE_BUFFERING
    }

    /**
     * True when the file has one audio track. Its language tag is then not shown: MKV / WebM without a Language
     * element read as "eng" (the Matroska default), so a Vietnamese clip would say "Tiếng Anh".
     */
    private var singleAudio = true

    private fun audioSignalFor(format: Format): AudioSignal =
        audioSignal(format).let { if (singleAudio) it.copy(language = "") else it }

    private fun rebuildTracks(tracks: Tracks) {
        val audio = mutableListOf<MediaTrack>()
        val text = mutableListOf<MediaTrack>()
        singleAudio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length } <= 1
        tracks.groups.forEachIndexed { g, group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val track = MediaTrack(
                    id = "$g:$i",
                    type = group.type,
                    label = if (group.type == C.TRACK_TYPE_AUDIO && singleAudio) {
                        format.label?.takeIf { it.isNotBlank() } ?: "Âm thanh gốc"
                    } else {
                        trackLabel(format, if (group.type == C.TRACK_TYPE_AUDIO) audio.size else text.size)
                    },
                    detail = trackDetail(format),
                    selected = group.isTrackSelected(i),
                    supported = group.isTrackSupported(i),
                    group = group.mediaTrackGroup,
                    index = i,
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += track
                    C.TRACK_TYPE_TEXT -> text += track
                }
            }
        }
        audioTracksFlow.value = audio
        subtitleTracksFlow.value = text
        // A subtitle just downloaded from OpenSubtitles becomes the active one.
        pendingSubtitleSelection?.let { name ->
            text.firstOrNull { it.label.contains(name) }?.let {
                pendingSubtitleSelection = null
                Handler(player.applicationLooper).post { selectTrack(it) }
            }
        }
    }

    companion object {
        private val BITSTREAM_ENCODINGS = setOf(
            C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DTS, C.ENCODING_DTS_HD,
            C.ENCODING_DOLBY_TRUEHD, C.ENCODING_DTS_UHD_P2,
        )

        private val vietnamese = Locale("vi")

        fun describe(error: PlaybackException): String = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Không kết nối được tới máy chủ / NAS."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Máy chủ trả lỗi HTTP (sai mật khẩu hoặc không có quyền?)."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Không tìm thấy tệp."
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Không có quyền đọc tệp."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Định dạng tệp chưa được hỗ trợ."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Thiết bị này không giải mã được video / âm thanh của tệp."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "Không mở được đầu ra âm thanh — thử tắt Bitstream trong Cài đặt."
            else -> "Không phát được (${error.errorCodeName})."
        } + (error.cause?.message?.let { "\n$it" } ?: "")

        fun codecName(format: Format): String {
            if (DsdFormat.dsdRate(format) != null) return "DSD"
            val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs ?: "" else format.sampleMimeType ?: ""
            return when (mime) {
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                MimeTypes.AUDIO_DTS -> "DTS"
                MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
                MimeTypes.AUDIO_DTS_X -> "DTS:X"
                MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
                MimeTypes.AUDIO_E_AC3 -> "DD+"
                MimeTypes.AUDIO_E_AC3_JOC -> "DD+ Atmos"
                MimeTypes.AUDIO_AC3 -> "AC3"
                MimeTypes.AUDIO_AC4 -> "AC4"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_VORBIS -> "Vorbis"
                MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_RAW -> "PCM"
                MimeTypes.VIDEO_H265 -> "HEVC"
                MimeTypes.VIDEO_H264 -> "H.264"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP9 -> "VP9"
                MimeTypes.VIDEO_VP8 -> "VP8"
                MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
                MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                MimeTypes.APPLICATION_SUBRIP -> "SRT"
                MimeTypes.TEXT_SSA -> "ASS"
                MimeTypes.TEXT_VTT -> "VTT"
                MimeTypes.APPLICATION_PGS -> "PGS"
                MimeTypes.APPLICATION_VOBSUB -> "VobSub"
                MimeTypes.APPLICATION_TTML -> "TTML"
                MimeTypes.APPLICATION_DVBSUBS -> "DVB"
                else -> mime.substringAfter('/').uppercase()
            }
        }

        fun channelLayout(channels: Int): String = when (channels) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            in 3..Int.MAX_VALUE -> "$channels kênh"
            else -> ""
        }

        fun languageName(code: String?): String? {
            if (code.isNullOrEmpty() || code == "und") return null
            val name = Locale.forLanguageTag(code).getDisplayLanguage(vietnamese)
            return name.takeIf { it.isNotEmpty() && it != code }?.replaceFirstChar { it.uppercase(vietnamese) } ?: code
        }

        private fun trackLabel(format: Format, ordinal: Int): String {
            val parts = listOfNotNull(format.label?.takeIf { it.isNotBlank() }, languageName(format.language)).distinct()
            return parts.joinToString(" · ").ifEmpty { "Track ${ordinal + 1}" }
        }

        private fun trackDetail(format: Format): String {
            val parts = mutableListOf(codecName(format))
            if (format.channelCount > 0) parts += channelLayout(format.channelCount)
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Bắt buộc"
            if (format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) parts += "Mặc định"
            return parts.filter { it.isNotEmpty() }.joinToString(" · ")
        }

        fun dynamicRange(format: Format): String {
            val codecs = format.codecs.orEmpty()
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || codecs.startsWith("dvh") || codecs.startsWith("dva") ||
                codecs.startsWith("dav1")
            ) return "Dolby Vision"
            return when (format.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10"
                C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
        }

        private fun videoSignal(format: Format) = VideoSignal(
            width = max(0, format.width),
            height = max(0, format.height),
            fps = if (format.frameRate > 0) format.frameRate else 0f,
            codec = codecName(format),
            dynamicRange = dynamicRange(format),
            bitrate = max(format.bitrate, format.averageBitrate),
        )

        private fun audioSignal(format: Format) = AudioSignal(
            codec = codecName(format),
            channels = max(0, format.channelCount),
            sampleRate = max(0, format.sampleRate),
            bitrate = max(format.bitrate, format.averageBitrate),
            language = languageName(format.language).orEmpty(),
        )

        /** Sidecar subtitle → Media3 configuration ("Movie.vi.srt" is Vietnamese). */
        fun subtitleConfiguration(location: String): MediaItem.SubtitleConfiguration? {
            val uri = if (location.startsWith("/")) Uri.fromFile(File(location)) else Uri.parse(location)
            val name = Uri.decode(uri.lastPathSegment ?: location).substringAfterLast('/')
            val mime = when (name.substringAfterLast('.', "").lowercase()) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
                else -> return null
            }
            val language = name.substringBeforeLast('.').substringAfterLast('.', "").lowercase()
                .takeIf { it.length in 2..3 && it.all(Char::isLetter) }
            return MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime)
                .setLanguage(language)
                .setLabel(name)
                .setId(location)
                .build()
        }
    }
}
