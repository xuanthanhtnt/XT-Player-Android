package com.xuanthanhtnt.xtplayer.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaSession
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.CrashLog
import com.xuanthanhtnt.xtplayer.ui.CrashReportDialog
import com.xuanthanhtnt.xtplayer.ui.theme.XtTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {
    private var engine: PlayerEngine? = null
    private var controller: PlayerController? = null
    private var mediaSession: MediaSession? = null
    private var castBridge: CastBridge? = null
    private val inPip = mutableStateOf(false)
    private val castBridgeState = mutableStateOf<CastBridge?>(null)
    private var isTv = false
    private var switchedMode = false
    /** The video view, for the picture-in-picture animation's source rectangle. */
    private var playerView: androidx.media3.ui.PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        addOnPictureInPictureModeChangedListener { info ->
            inPip.value = info.isInPictureInPictureMode
            // Leaving PiP while stopped means the window was closed (✕ or swiped away), not expanded: stop playing.
            if (!info.isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                engine?.pause()
                close()
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Phones play in landscape; tablets and TVs keep their orientation.
        if (!isTv && resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        val pending = PlayerLauncher.pending
        PlayerLauncher.pending = null
        if (pending != null) {
            begin(pending)
        } else {
            lifecycleScope.launch {
                val session = sessionFrom(intent)
                if (session == null) finish() else begin(session)
            }
        }
    }

    /**
     * A new video while this player is open — also while it is the small picture-in-picture window: it plays in this
     * same player (no second window), and Android brings the window back to full screen with its own animation.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pending = PlayerLauncher.pending
        PlayerLauncher.pending = null
        lifecycleScope.launch {
            val session = pending ?: sessionFrom(intent) ?: return@launch
            controller?.replace(session) ?: begin(session)
        }
    }

    /** "Open with XT-Player" from a file manager, the browser, or another app. */
    private suspend fun sessionFrom(intent: Intent?): PlaybackSession? {
        val uri = intent?.data ?: return null
        if (uri.scheme == "content") {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val item = when (uri.scheme) {
            "http", "https" -> PlaybackFactory.url(uri.toString())
            else -> PlaybackFactory.local(this, uri)
        } ?: return null
        return PlaybackSession(listOf(item))
    }

    private fun begin(session: PlaybackSession) {
        if (session.items.isEmpty()) {
            finish()
            return
        }
        try {
            start(session)
        } catch (e: Throwable) {
            // Show what went wrong instead of closing the app; the report is also offered on the home screen.
            CrashLog.record(e)
            engine?.release()
            engine = null
            controller = null
            val report = CrashLog.last().orEmpty()
            setContent {
                XtTheme(isTv = isTv, isWide = true) {
                    CrashReportDialog(report, title = "Không mở được trình phát") { finish() }
                }
            }
        }
    }

    private fun start(session: PlaybackSession) {
        val engine = PlayerEngine(this).also { engine = it }
        mediaSession = runCatching { MediaSession.Builder(this, engine.player).setId("xtplayer-${System.nanoTime()}").build() }.getOrNull()
        val controller = PlayerController(engine, session, lifecycleScope, ::matchContent).also { controller = it }
        setContent {
            XtTheme(isTv = isTv, isWide = true) {
                CompositionLocalProvider(LocalPlayerController provides controller) {
                    PlayerScreen(
                        controller = controller, isTv = isTv, inPip = inPip.value, onClose = ::close,
                        onPip = if (canPip) ({ enterPip(onlyWhenPlaying = false) }) else null,
                        castBridge = castBridgeState.value,
                        onPlayerView = { view ->
                            playerView = view
                            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePipParams() }
                        },
                    )
                }
            }
        }
        controller.start()
        // Cast on phones and tablets (a TV box is the screen already).
        castBridge = if (isTv) null else CastBridge.create(this, controller, lifecycleScope)
        castBridgeState.value = castBridge
        lifecycleScope.launch { engine.video.collect { updatePipParams() } }
        lifecycleScope.launch { engine.state.collect { updatePipParams() } }
    }

    private fun close() {
        controller?.stop()
        finish()
    }

    override fun onStop() {
        super.onStop()
        controller?.saveProgress()
        // Keep playing in picture-in-picture, pause otherwise (Infuse pauses in the background).
        if (!isInPictureInPictureMode) engine?.pause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip(onlyWhenPlaying = true)
    }

    private val canPip: Boolean
        get() = !isTv && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip(onlyWhenPlaying: Boolean) {
        val engine = engine ?: return
        if (!canPip || (onlyWhenPlaying && engine.state.value != PlaybackState.Playing)) return
        if (Build.VERSION.SDK_INT >= 31 && onlyWhenPlaying) return   // auto-enter already animated the window down
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /**
     * The video's shape (Android accepts 1:2.39 … 2.39:1) and where the picture is on screen, so the window shrinks
     * from the video itself; on Android 12+ it also goes into picture-in-picture by itself when leaving the app while
     * playing. Seamless resizing stays on: turning it off is for non-video content and shows the app icon on black.
     */
    private fun pipParams(): PictureInPictureParams {
        val video = engine?.video?.value
        val ratio = if (video != null && video.width > 0 && video.height > 0) {
            val r = video.width.toFloat() / video.height
            Rational((r.coerceIn(0.42f, 2.38f) * 1000).roundToInt(), 1000)
        } else {
            Rational(16, 9)
        }
        return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
            videoBounds()?.let { setSourceRectHint(it) }
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(canPip && engine?.state?.value == PlaybackState.Playing)
            }
        }.build()
    }

    /** The visible picture (PlayerView's content frame, letterboxing excluded) in window coordinates. */
    private fun videoBounds(): android.graphics.Rect? {
        val frame = playerView?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame) ?: return null
        val rect = android.graphics.Rect()
        return if (frame.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) rect else null
    }

    private fun updatePipParams() {
        if (canPip) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onDestroy() {
        castBridge?.release(endCast = true)
        controller?.stop()
        mediaSession?.release()
        engine?.release()
        if (switchedMode) DisplayModeMatcher.reset(this)
        super.onDestroy()
    }

    /**
     * Settings ▸ Khớp tần số quét = "always" (Match Content): the TV switches to the video's frame rate, and to 4K
     * output for 4K video when the box runs its menus in 1080p.
     */
    private fun matchContent(video: VideoSignal) {
        if (AppGraph.settings.frameRateMatching.value != "always") return
        if (DisplayModeMatcher.apply(this, video.fps, video.width, video.height)) switchedMode = true
    }
}

/** Match Content for Android TV: a display mode whose refresh rate (and, for 4K video, resolution) fits the video. */
object DisplayModeMatcher {
    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay

    fun apply(activity: Activity, fps: Float, width: Int, height: Int): Boolean {
        val display = display(activity) ?: return false
        val current = display.mode
        val modes = display.supportedModes.toList()
        // Resolution: keep the current one, unless the video is 4K and the box outputs less while a 4K mode exists.
        val wantsUhd = maxOf(width, height) >= 3000 && maxOf(current.physicalWidth, current.physicalHeight) < 3000
        val uhd = modes.filter { maxOf(it.physicalWidth, it.physicalHeight) >= 3000 }
        val sized = if (wantsUhd && uhd.isNotEmpty()) {
            val size = uhd.maxBy { it.physicalWidth * it.physicalHeight }
            modes.filter { it.physicalWidth == size.physicalWidth && it.physicalHeight == size.physicalHeight }
        } else {
            modes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        }
        // Refresh rate: the lowest whole multiple of the frame rate (23.976 → 23.976 / 47.95 / 119.88 Hz);
        // without a known frame rate (or a match), the current rate at the chosen size.
        val byRate = if (fps > 0) sized.mapNotNull { mode ->
            val multiple = (mode.refreshRate / fps).roundToInt()
            if (multiple >= 1 && abs(mode.refreshRate - multiple * fps) < 0.05f * multiple) mode to multiple else null
        }.minByOrNull { it.second }?.first else null
        val best = byRate ?: sized.minByOrNull { abs(it.refreshRate - current.refreshRate) } ?: return false
        if (best.modeId == current.modeId) return false
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = best.modeId
        activity.window.attributes = attributes
        return true
    }

    fun reset(activity: Activity) {
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = 0
        activity.window.attributes = attributes
    }
}
