package com.xuanthanhtnt.xtplayer.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One persisted setting, observable from Compose (the @AppStorage of the iOS app). */
class Pref<T>(
    private val prefs: SharedPreferences,
    val key: String,
    default: T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
    read: SharedPreferences.(String, T) -> T,
) {
    private val state = MutableStateFlow(prefs.read(key, default))
    val flow: StateFlow<T> = state

    var value: T
        get() = state.value
        set(v) {
            prefs.edit().apply { write(key, v) }.apply()
            state.value = v
        }

    @Composable
    fun collect(): State<T> = flow.collectAsState()
}

/** SharedPreferences keys + defaults. */
class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) =
        Pref(prefs, key, default, { k, v -> putBoolean(k, v) }, { k, d -> getBoolean(k, d) })

    private fun int(key: String, default: Int) =
        Pref(prefs, key, default, { k, v -> putInt(k, v) }, { k, d -> getInt(k, d) })

    private fun float(key: String, default: Float) =
        Pref(prefs, key, default, { k, v -> putFloat(k, v) }, { k, d -> getFloat(k, d) })

    private fun string(key: String, default: String) =
        Pref(prefs, key, default, { k, v -> putString(k, v) }, { k, d -> getString(k, d) ?: d })

    /** Send AC3 / E-AC3 / DTS / TrueHD untouched to an HDMI receiver when it accepts them (Android TV). */
    val audioPassthrough = bool("player.audioPassthrough", true)
    /** "off", "seamless" (only when the switch is seamless) or "always" (switch the display mode, like Match Content). */
    val frameRateMatching = string("player.frameRate", "seamless")
    val cacheMegabytes = int("player.cacheMegabytes", 200)
    val autoplayNext = bool("player.autoplayNext", true)
    val skipBackSeconds = int("player.skipBack", 10)
    val skipForwardSeconds = int("player.skipForward", 30)
    val tunneling = bool("player.tunneling", false)
    /** Video decoders: the device's hardware first (software when it fails), or Android's software decoders only. */
    val hardwareDecoding = bool("player.hardwareDecoding", true)
    val subtitleLanguages = string("subtitles.languages", "vi,vie,en,eng")
    val audioLanguages = string("audio.languages", "")
    /** Centre channel (or the stereo voice band) up against everything else. */
    val dialogBoost = bool("audio.dialogBoost", false)
    /** Quiet passages raised, explosions held back. */
    val nightMode = bool("audio.nightMode", false)
    /** ReplayGain album gain from the files' tags (music sounds equally loud from album to album). */
    val volumeLeveling = bool("audio.volumeLeveling", false)
    /** Android 14+: a USB DAC runs at the file's own rate (bit-perfect when it can). */
    val matchSampleRate = bool("audio.matchSampleRate", false)
    val subtitleScale = float("subtitles.scale", 1f)
    /** 100 = at the bottom, 94 = a little higher, 60 = middle of the screen. */
    val subtitlePosition = int("subtitles.position", 100)
    val subtitleBackground = bool("subtitles.background", false)
    val metadataLanguage = string("library.language", "vi-VN")
    val openSubtitlesUser = string("opensubtitles.username", "")
    val traktScrobble = bool("trakt.scrobble", true)

    fun languages(raw: String): List<String> =
        raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    companion object {
        private val secrets get() = AppGraph.secrets

        /** TMDb v3 API key or v4 read-access token: user setting first, then the build-time key (CI secret). */
        val tmdbCredential: String?
            get() = secrets.get("tmdb")?.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() }

        /** OpenSubtitles.com API key: user setting first, then the build-time key. */
        val openSubtitlesKey: String?
            get() = secrets.get("opensubtitles")?.takeIf { it.isNotBlank() }
                ?: BuildConfig.OPENSUBTITLES_API_KEY.takeIf { it.isNotBlank() }

        /**
         * The Trakt API application: user setting first, then the build-time one. Apps created since October 2026
         * sign in with PKCE and have no secret (null).
         */
        val traktClient: Pair<String, String?>?
            get() {
                val id = secrets.get("trakt.clientID")
                if (!id.isNullOrBlank()) return id to secrets.get("trakt.clientSecret")?.takeIf { it.isNotBlank() }
                if (BuildConfig.TRAKT_CLIENT_ID.isNotBlank()) {
                    return BuildConfig.TRAKT_CLIENT_ID to BuildConfig.TRAKT_CLIENT_SECRET.takeIf { it.isNotBlank() }
                }
                return null
            }
    }
}
