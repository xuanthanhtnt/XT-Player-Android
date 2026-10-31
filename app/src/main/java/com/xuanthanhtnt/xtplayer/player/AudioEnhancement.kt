package com.xuanthanhtnt.xtplayer.player

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.xuanthanhtnt.xtplayer.AppGraph
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Night mode and dialogue boost (MPVPlayerEngine.swift `AudioEnhancement`). The Apple app builds an FFmpeg filter
 * chain; here the same choices and numbers drive [ChannelEnhancer] (channels) and [NightCompressor] (dynamics).
 */
object AudioEnhancement {
    /** What happens to the channels. */
    sealed interface Plan {
        data object None : Plan

        /** Stereo has no centre channel: the voice band of what both sides share goes up (dialoguenhance). */
        data class StereoDialog(val original: Double, val enhance: Double, val voice: Double) : Plan

        /** 5.1 / 7.1 to speakers or headphones: a dialogue-first downmix instead of the standard −3 dB centre. */
        data class Downmix(val centre: Double, val surround: Double) : Plan

        /** 5.1 / 7.1 to a receiver or TV: the layout kept, everything around the centre lowered. */
        data class Receiver(val front: Double, val side: Double, val lfe: Double, val wide: Boolean) : Plan
    }

    /** dynaudnorm=f=250:g=31:p=0.6:m=8 — frame, window, peak, maximum gain. */
    const val NIGHT_FRAME_MS = 250
    const val NIGHT_WINDOW = 31
    const val NIGHT_PEAK = 0.6
    const val NIGHT_MAX_GAIN = 8.0

    fun plan(night: Boolean, dialog: Boolean, source: Int, output: Int): Plan {
        if (!(night || dialog) || source <= 0 || output <= 0) return Plan.None
        return when {
            source <= 2 -> if (dialog && source == 2) Plan.StereoDialog(original = 1.0, enhance = 1.5, voice = 3.0) else Plan.None
            output <= 2 -> Plan.Downmix(centre = if (dialog) 1.2 else 1.0, surround = if (dialog) 0.35 else 0.5)
            else -> Plan.Receiver(
                front = if (dialog) 0.7 else 1.0,
                side = if (dialog) 0.6 else 0.75,
                lfe = if (night) 0.5 else 0.7,
                wide = source >= 8 && output >= 8,
            )
        }
    }

    /** The Apple app's mpv `af` value for the same choices: the tests hold both apps to the same numbers. */
    fun filter(night: Boolean, dialog: Boolean, source: Int, output: Int): String {
        if (!(night || dialog) || source <= 0 || output <= 0) return ""
        val chain = mutableListOf<String>()
        when (val plan = plan(night, dialog, source, output)) {
            Plan.None -> Unit
            is Plan.StereoDialog -> chain += "dialoguenhance=original=1:enhance=1.5:voice=3"
            is Plan.Downmix -> chain += "aresample=out_chlayout=stereo:clev=%.2f:slev=%.2f:lfe_mix_level=0".format(Locale.US, plan.centre, plan.surround)
            is Plan.Receiver -> {
                val gains = mutableListOf(
                    "FL=%.2f*FL".format(Locale.US, plan.front), "FR=%.2f*FR".format(Locale.US, plan.front), "FC=FC",
                    "LFE=%.2f*LFE".format(Locale.US, plan.lfe),
                    "BL=%.2f*BL".format(Locale.US, plan.side), "BR=%.2f*BR".format(Locale.US, plan.side),
                )
                if (plan.wide) gains += listOf("SL=%.2f*SL".format(Locale.US, plan.side), "SR=%.2f*SR".format(Locale.US, plan.side))
                val layout = if (plan.wide) "7.1" else "5.1"
                chain += "aresample=out_chlayout=$layout"
                chain += "pan=$layout|${gains.joinToString("|")}"
            }
        }
        if (night) chain += "dynaudnorm=f=$NIGHT_FRAME_MS:g=$NIGHT_WINDOW:p=$NIGHT_PEAK:m=${NIGHT_MAX_GAIN.toInt()}"
        return if (chain.isEmpty()) "" else "lavfi=[${chain.joinToString(",")}]"
    }
}

/**
 * Applies a [AudioEnhancement.Plan] and a gain to interleaved float samples, keeping the channel count: a downmix
 * goes into the front pair with the other channels silenced, so the system's own downmix passes it through as is.
 * Android channel order: FL FR FC LFE BL BR (SL SR).
 */
class ChannelEnhancer {
    // Voice band (300 Hz – 3.4 kHz) of the stereo mid: RBJ band-pass, 0 dB peak.
    private var b0 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var filterRate = 0

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    fun process(data: FloatArray, frames: Int, channels: Int, sampleRate: Int, plan: AudioEnhancement.Plan, gain: Float) {
        when (plan) {
            AudioEnhancement.Plan.None -> Unit
            is AudioEnhancement.Plan.StereoDialog -> if (channels == 2) stereoDialog(data, frames, sampleRate, plan)
            is AudioEnhancement.Plan.Downmix -> if (channels == 6 || channels == 8) downmix(data, frames, channels, plan)
            is AudioEnhancement.Plan.Receiver -> if (channels == 6 || channels == 8) receiver(data, frames, channels, plan)
        }
        if (gain != 1f) {
            for (i in 0 until frames * channels) data[i] *= gain
        }
    }

    private fun stereoDialog(data: FloatArray, frames: Int, sampleRate: Int, plan: AudioEnhancement.Plan.StereoDialog) {
        if (sampleRate != filterRate) design(sampleRate)
        val boost = (plan.enhance - plan.original).toFloat()
        for (f in 0 until frames) {
            val l = data[2 * f]
            val r = data[2 * f + 1]
            val mid = (l + r) * 0.5
            val voice = b0 * mid + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = mid; y2 = y1; y1 = voice
            val add = boost * voice.toFloat()
            data[2 * f] = l + add
            data[2 * f + 1] = r + add
        }
    }

    private fun design(sampleRate: Int) {
        filterRate = sampleRate
        val low = 300.0
        val high = 3400.0
        val centre = sqrt(low * high)
        val q = centre / (high - low)
        val w = 2 * PI * centre / sampleRate
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        b0 = alpha / a0
        b2 = -alpha / a0
        a1 = -2 * cos(w) / a0
        a2 = (1 - alpha) / a0
        reset()
    }

    private fun downmix(data: FloatArray, frames: Int, channels: Int, plan: AudioEnhancement.Plan.Downmix) {
        val c = plan.centre.toFloat()
        val s = plan.surround.toFloat()
        for (f in 0 until frames) {
            val o = f * channels
            var left = data[o] + c * data[o + 2] + s * data[o + 4]
            var right = data[o + 1] + c * data[o + 2] + s * data[o + 5]
            if (channels == 8) {
                left += s * data[o + 6]
                right += s * data[o + 7]
            }
            data[o] = left
            data[o + 1] = right
            for (ch in 2 until channels) data[o + ch] = 0f
        }
    }

    private fun receiver(data: FloatArray, frames: Int, channels: Int, plan: AudioEnhancement.Plan.Receiver) {
        val gains = FloatArray(channels) { 1f }
        gains[0] = plan.front.toFloat()
        gains[1] = plan.front.toFloat()
        gains[3] = plan.lfe.toFloat()
        for (ch in 4 until channels) gains[ch] = plan.side.toFloat()
        for (f in 0 until frames) {
            val o = f * channels
            for (ch in 0 until channels) data[o + ch] *= gains[ch]
        }
    }
}

/**
 * The audio sink with [ChannelEnhancer] in front: what the decoder hands over is enhanced before the sink (and its
 * float path for hi-res, where Media3 runs no audio processors) gets it. Reads the settings at every buffer, so a
 * switch in Settings or Now Playing changes what is playing right away. Bitstreamed audio passes untouched.
 */
internal class XtAudioSink(sink: AudioSink, private val context: Context) : ForwardingAudioSink(sink) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var outputChannels = 2
    private val enhancer = ChannelEnhancer()
    private var scratch = FloatArray(0)
    private var out: ByteBuffer? = null
    /** The buffer last handed over and what was passed for it: a partly used buffer comes back until it is used up. */
    private var lastInput: ByteBuffer? = null
    private var lastTimeUs = Long.MIN_VALUE
    private var pending: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        encoding = if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW) inputFormat.pcmEncoding else C.ENCODING_INVALID
        channels = inputFormat.channelCount
        sampleRate = inputFormat.sampleRate
        this.outputChannels = AudioOutputs.outputChannels(context, channels)
        enhancer.reset()
        lastInput = null
        pending = null
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val retry = buffer === lastInput && presentationTimeUs == lastTimeUs
        if (!retry) {
            lastInput = buffer
            lastTimeUs = presentationTimeUs
            val settings = AppGraph.settings
            val plan = AudioEnhancement.plan(settings.nightMode.value, settings.dialogBoost.value, channels, outputChannels)
            pending = if (plan != AudioEnhancement.Plan.None && bytesPerSample() > 0) enhanced(buffer, plan) else null
        }
        val done = super.handleBuffer(pending ?: buffer, presentationTimeUs, encodedAccessUnitCount)
        if (done) {
            lastInput = null
            pending = null
        }
        return done
    }

    override fun flush() {
        lastInput = null
        pending = null
        enhancer.reset()
        super.flush()
    }

    override fun reset() {
        lastInput = null
        pending = null
        super.reset()
    }

    private fun bytesPerSample(): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun enhanced(input: ByteBuffer, plan: AudioEnhancement.Plan): ByteBuffer {
        val size = input.remaining()
        val width = bytesPerSample()
        val samples = size / width
        val frames = if (channels > 0) samples / channels else 0
        if (scratch.size < samples) scratch = FloatArray(samples)
        val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        PcmSamples.read(source, encoding, scratch, samples)
        enhancer.process(scratch, frames, channels, sampleRate, plan, 1f)
        val target = out?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(size).also { out = it }
        target.clear()
        target.order(ByteOrder.LITTLE_ENDIAN)
        PcmSamples.write(target, encoding, scratch, samples)
        // Bytes that did not make a whole sample stay as they were.
        for (i in samples * width until size) target.put(source.get(source.position() + i))
        target.flip()
        return target
    }
}

/** Little-endian PCM to and from floats in −1…1 (integers are clipped on the way back). */
object PcmSamples {
    fun read(buffer: ByteBuffer, encoding: Int, into: FloatArray, count: Int) {
        val start = buffer.position()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until count) into[i] = buffer.getShort(start + 2 * i) / 32768f
            C.ENCODING_PCM_24BIT -> for (i in 0 until count) {
                val p = start + 3 * i
                val v = (buffer.get(p).toInt() and 0xFF) or ((buffer.get(p + 1).toInt() and 0xFF) shl 8) or (buffer.get(p + 2).toInt() shl 16)
                into[i] = v / 8388608f
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until count) into[i] = (buffer.getInt(start + 4 * i) / 2147483648.0).toFloat()
            C.ENCODING_PCM_FLOAT -> for (i in 0 until count) into[i] = buffer.getFloat(start + 4 * i)
        }
    }

    fun write(buffer: ByteBuffer, encoding: Int, from: FloatArray, count: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until count) {
                buffer.putShort((from[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            C.ENCODING_PCM_24BIT -> for (i in 0 until count) {
                val v = (from[i].coerceIn(-1f, 1f) * 8388607f).toInt()
                buffer.put(v.toByte())
                buffer.put((v shr 8).toByte())
                buffer.put((v shr 16).toByte())
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until count) {
                buffer.putInt((from[i].coerceIn(-1f, 1f) * 2147483647.0).toInt())
            }
            C.ENCODING_PCM_FLOAT -> for (i in 0 until count) buffer.putFloat(from[i])
        }
    }
}

/**
 * Night mode's dynamics: quiet passages raised, explosions held back. The Apple app runs FFmpeg's dynaudnorm, which
 * looks seconds ahead and would put the sound out of step with the picture here; Android's DynamicsProcessing on
 * the player's audio session does it without delay, with dynaudnorm's limits: up to 8× (+18 dB) on quiet parts,
 * peaks held at 0.6 (−4.4 dBFS), 250 ms release (its frame length). Android 9 and later.
 */
class NightCompressor {
    private var effect: DynamicsProcessing? = null

    fun attach(audioSessionId: Int, enabled: Boolean) {
        release()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        effect = runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 1,
                false, 0, true, 1, false, 0, true,
            ).build()
            config.setMbcBandAllChannelsTo(
                0,
                DynamicsProcessing.MbcBand(
                    true, 20_000f, 10f, AudioEnhancement.NIGHT_FRAME_MS.toFloat(), 4f, -40f, 10f, -90f, 1f, 0f, MAX_GAIN_DB,
                ),
            )
            config.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, PEAK_DB, 0f))
            DynamicsProcessing(0, audioSessionId, config)
        }.getOrNull()
        setEnabled(enabled)
    }

    fun setEnabled(enabled: Boolean) {
        runCatching { effect?.setEnabled(enabled) }
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
    }

    private companion object {
        /** 20·log10(8). */
        const val MAX_GAIN_DB = 18.06f
        /** 20·log10(0.6). */
        const val PEAK_DB = -4.44f
    }
}
