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
import com.xuanthanhtnt.xtplayer.ui.theme.XtTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {
    private var engine: PlayerEngine? = null
    private var controller: PlayerController? = null
    private var mediaSession: MediaSession? = null
    private val inPip = mutableStateOf(false)
    private var isTv = false
    private var switchedMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        addOnPictureInPictureModeChangedListener { info -> inPip.value = info.isInPictureInPictureMode }
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
        val engine = PlayerEngine(this).also { engine = it }
        mediaSession = runCatching { MediaSession.Builder(this, engine.player).setId("xtplayer-${System.nanoTime()}").build() }.getOrNull()
        val controller = PlayerController(engine, session, lifecycleScope, ::matchFrameRate).also { controller = it }
        setContent {
            XtTheme(isTv = isTv, isWide = true) {
                CompositionLocalProvider(LocalPlayerController provides controller) {
                    PlayerScreen(controller = controller, isTv = isTv, inPip = inPip.value, onClose = ::close)
                }
            }
        }
        controller.start()
    }

    private fun close() {
        controller?.stop()
        finish()
    }

    override fun onStop() {
        super.onStop()
        controller?.saveProgress()
        // Keep playing in picture-in-picture, pause otherwise (Infuse pauses in the background).
        if (!inPip.value) engine?.pause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfPlaying()
    }

    private fun enterPipIfPlaying() {
        val engine = engine ?: return
        if (isTv || engine.state.value != PlaybackState.Playing) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val video = engine.video.value
        val ratio = if (video.width > 0 && video.height > 0) {
            val r = video.width.toFloat() / video.height
            // Android accepts 1:2.39 … 2.39:1.
            Rational((r.coerceIn(0.42f, 2.38f) * 1000).roundToInt(), 1000)
        } else {
            Rational(16, 9)
        }
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    override fun onDestroy() {
        controller?.stop()
        mediaSession?.release()
        engine?.release()
        if (switchedMode) DisplayModeMatcher.reset(this)
        super.onDestroy()
    }

    /** Settings ▸ Khớp tần số quét = "always": switch the TV to a refresh rate that is a multiple of the video's. */
    private fun matchFrameRate(fps: Float) {
        if (AppGraph.settings.frameRateMatching.value != "always") return
        if (DisplayModeMatcher.apply(this, fps)) switchedMode = true
    }
}

/** Match Content for Android TV: picks a display mode of the same resolution whose refresh rate fits the video. */
object DisplayModeMatcher {
    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay

    fun apply(activity: Activity, fps: Float): Boolean {
        val display = display(activity) ?: return false
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        // The lowest refresh rate that is a whole multiple of the frame rate (23.976 → 23.976 / 47.95 / 119.88 Hz).
        val best = candidates.mapNotNull { mode ->
            val multiple = (mode.refreshRate / fps).roundToInt()
            if (multiple >= 1 && abs(mode.refreshRate - multiple * fps) < 0.05f * multiple) mode to multiple else null
        }.minByOrNull { it.second }?.first ?: return false
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
