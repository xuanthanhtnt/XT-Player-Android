package com.xuanthanhtnt.xtplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.xuanthanhtnt.xtplayer.AppGraph
import kotlin.math.pow

/**
 * Volume levelling (mpv's `replaygain=album`, `replaygain-clip=no`, `replaygain-fallback=0`): the album gain from
 * the file's tags (the track gain when there is no album gain), no clipping protection, 0 dB without tags.
 */
object ReplayGain {
    private val number = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    /** The gain in dB from FLAC / Ogg Vorbis comments, ID3 TXXX frames (MP3) or iTunes atoms (M4A). */
    fun gainDb(metadata: Metadata?): Float? {
        if (metadata == null) return null
        var album: Float? = null
        var track: Float? = null
        for (i in 0 until metadata.length()) {
            val (key, value) = when (val entry = metadata.get(i)) {
                is VorbisComment -> entry.key to entry.value
                is TextInformationFrame -> (entry.description ?: continue) to (entry.values.firstOrNull() ?: continue)
                is InternalFrame -> entry.description to entry.text
                else -> continue
            }
            when (key.uppercase()) {
                "REPLAYGAIN_ALBUM_GAIN" -> album = parse(value)
                "REPLAYGAIN_TRACK_GAIN" -> track = parse(value)
            }
        }
        return album ?: track
    }

    /** "-6.48 dB" → −6.48. */
    fun parse(value: String): Float? = number.find(value)?.value?.replace(',', '.')?.toFloatOrNull()

    /** Linear factor for a gain in dB. */
    fun factor(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
}

/** The ReplayGain of what a player is decoding, set from the decoder's input format, read by its audio sink. */
class GainHolder {
    @Volatile
    var db: Float = 0f
}

/**
 * Hi-res over USB (MPVPlayerEngine `setMatchSampleRate` / `followSourceSampleRate`): Android 14 lets an app ask a USB
 * DAC to run at the file's own rate, bit-perfect when the DAC allows it, instead of the system mixer resampling
 * everything to 48 kHz. Off by default, as on the Mac ("Khớp tần số lấy mẫu").
 */
object HiResOutput {
    private val attributes: AudioAttributes by lazy { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }

    val isAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** The last PCM format an output opened for: (rate, channels, float). */
    @Volatile
    private var last: Triple<Int, Int, Boolean>? = null

    /** The setting changed: ask again for the format playing (the output reopens on the next seek). */
    fun reapply(context: Context) {
        val (rate, channels, highResolution) = last ?: return
        follow(context, rate, channels, highResolution)
    }

    /**
     * Called by the sink before an output opens for a new format: asks the USB DAC (if one is the output) for
     * [sampleRate], 16-bit or float as the sink will write it ([highResolution]), or lets the mixer decide again.
     */
    fun follow(context: Context, sampleRate: Int, channels: Int, highResolution: Boolean) {
        last = Triple(sampleRate, channels, highResolution)
        if (!isAvailable) return
        runCatching { follow34(context, sampleRate, channels, highResolution) }
    }

    /** The rate the USB DAC was asked to run at, or 0. */
    fun preferredRate(context: Context): Int {
        if (!isAvailable) return 0
        return runCatching { preferredRate34(context) }.getOrDefault(0)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun follow34(context: Context, sampleRate: Int, channels: Int, highResolution: Boolean) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = usbOutput(manager) ?: return
        if (!AppGraph.settings.matchSampleRate.value || channels != 2) {
            manager.clearPreferredMixerAttributes(attributes, device)
            return
        }
        val encoding = if (highResolution) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val options = manager.getSupportedMixerAttributes(device).filter {
            it.format.sampleRate == sampleRate && it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO
        }
        val choice = options.firstOrNull { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT && it.format.encoding == encoding }
            ?: options.firstOrNull { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT }
        if (choice == null) {
            manager.clearPreferredMixerAttributes(attributes, device)
        } else {
            manager.setPreferredMixerAttributes(attributes, device, choice)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun preferredRate34(context: Context): Int {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = usbOutput(manager) ?: return 0
        return manager.getPreferredMixerAttributes(attributes, device)?.format?.sampleRate ?: 0
    }

    private fun usbOutput(manager: AudioManager): AudioDeviceInfo? =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    /** Whether the sink will write this PCM as float (Media3 keeps 24 / 32-bit sources in float). */
    fun isHighResolution(pcmEncoding: Int): Boolean =
        pcmEncoding == C.ENCODING_PCM_24BIT || pcmEncoding == C.ENCODING_PCM_32BIT || pcmEncoding == C.ENCODING_PCM_FLOAT ||
            pcmEncoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || pcmEncoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
}
