package com.xuanthanhtnt.xtplayer.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the music player knows about the sound (AudioSignal of the Apple app): the file's codec, rate and bit depth,
 * the rate handed to the audio output, the device it plays on, and the processing switched on.
 */
data class AudioPathInput(
    /** "FLAC", "ALAC", "PCM", "MP3" ([PlayerEngine.codecName]); empty before anything is decoded. */
    val codec: String = "",
    val sampleRate: Int = 0,
    /** The file's own bit depth (16 / 24 / 32); null for lossy codecs, float PCM, or when the file does not say. */
    val bitDepth: Int? = null,
    /** Rate handed to the AudioTrack; 0 until an output is open. */
    val outputSampleRate: Int = 0,
    /** Rate the device's mixer runs at. */
    val deviceSampleRate: Int = 0,
    val deviceName: String = "",
    val dialogBoost: Boolean = false,
    val nightMode: Boolean = false,
    val volumeLeveling: Boolean = false,
    val muted: Boolean = false,
    /** Player volume, 0…1 (the app's own volume, not the system's). */
    val volume: Float = 1f,
) {
    /** An output is open (mpv's `audioOutput` is set). */
    val hasOutput: Boolean get() = outputSampleRate > 0

    /** Reaches the device at the file's own rate: no resampling on the way. */
    val isNativeRate: Boolean
        get() = sampleRate > 0 && outputSampleRate == sampleRate && (deviceSampleRate <= 0 || abs(deviceSampleRate - sampleRate) < 1)
}

/** Roon-style signal path (MusicPlayer.swift `SignalPath`): source, what the app does to it, the device, a grade. */
class SignalPath(input: AudioPathInput) {
    enum class Grade(val label: String) {
        /** Lossless and untouched: the file's own samples at its own rate. */
        BIT_PERFECT("Bit-perfect"),
        /** Lossless, but resampled or volume-scaled on the way. */
        HIGH_QUALITY("Chất lượng cao"),
        /** Deliberate processing (dialogue boost, night mode, levelling). */
        ENHANCED("Có xử lý"),
        /** The source itself is lossy (MP3, AAC, Opus…). */
        LOSSY("Nguồn nén (lossy)"),
        /** Between tracks: nothing decoded yet. */
        UNKNOWN("Đang mở…"),
    }

    val source: String = quality(input.codec, input.sampleRate, input.bitDepth)
    val processing: List<String>
    val output: String
    val grade: Grade

    init {
        val steps = mutableListOf<String>()
        if (input.outputSampleRate > 0 && input.sampleRate > 0 && !input.isNativeRate) {
            val device = if (input.deviceSampleRate > 0) input.deviceSampleRate else input.outputSampleRate
            steps += "Lấy mẫu lại ${kHz(input.sampleRate.toDouble())} → ${kHz(device.toDouble())}"
        }
        if (input.dialogBoost) steps += "Tăng thoại"
        if (input.nightMode) steps += "Chế độ ban đêm"
        if (input.volumeLeveling) steps += "Cân bằng âm lượng (ReplayGain)"
        if (input.muted) {
            steps += "Tắt tiếng"
        } else if (abs(input.volume * 100 - 100) > 0.5) {
            steps += "Âm lượng ${(input.volume * 100).toInt()} %"
        }
        processing = steps
        val deviceRate = if (input.deviceSampleRate > 0) kHz(input.deviceSampleRate.toDouble()) else ""
        output = listOf(input.deviceName.ifEmpty { "—" }, deviceRate).filter { it.isNotEmpty() }.joinToString(" · ")
        grade = when {
            input.codec.isEmpty() || input.sampleRate == 0 -> Grade.UNKNOWN
            input.codec !in lossless -> Grade.LOSSY
            input.dialogBoost || input.nightMode || input.volumeLeveling -> Grade.ENHANCED
            steps.isEmpty() -> Grade.BIT_PERFECT
            else -> Grade.HIGH_QUALITY
        }
    }

    companion object {
        private val lossless = setOf(
            "FLAC", "ALAC", "WavPack", "APE", "TTA", "TAK", "PCM", "DSD", "DSD (DST)", "TrueHD", "MLP", "WMALOSSLESS",
        )

        /** "44.1 kHz", "96 kHz" (InfoPanel.kHz). */
        fun kHz(hertz: Double): String =
            String.format(Locale.US, if (hertz % 1000 == 0.0) "%.0f kHz" else "%.1f kHz", hertz / 1000)

        /** "FLAC · 96 kHz · 24-bit", "DSD128", "MP3 · 44.1 kHz". */
        fun quality(codec: String, sampleRate: Int, bitDepth: Int?): String {
            if (sampleRate <= 0) return codec
            if (codec.startsWith("DSD")) {
                // DSD decoded to PCM at 1/8 of its bit rate: 352.8 kHz = DSD64.
                return "DSD${(sampleRate * 8.0 / 44_100).roundToInt()}"
            }
            val parts = mutableListOf(codec, kHz(sampleRate.toDouble()))
            when (bitDepth) {
                8 -> parts += "8-bit"
                16 -> parts += "16-bit"
                24 -> parts += "24-bit"
                32 -> parts += "32-bit"
            }
            return parts.filter { it.isNotEmpty() }.joinToString(" · ")
        }

        /**
         * The file's bit depth from what the extractor reports: the PCM encoding when it sets one (WAV, AIFF, FLAC,
         * ALAC in MP4), else the FLAC STREAMINFO or the ALAC magic cookie in the codec data (FLAC / ALAC in MKV).
         */
        fun bitDepth(pcmEncoding: Int, mimeType: String?, initializationData: List<ByteArray>): Int? {
            when (pcmEncoding) {
                C.ENCODING_PCM_8BIT -> return 8
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> return 16
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> return 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> return 32
                C.ENCODING_PCM_FLOAT -> return null
            }
            val data = initializationData.firstOrNull() ?: return null
            return when (mimeType) {
                MimeTypes.AUDIO_FLAC -> flacBitsPerSample(data)
                // ALACSpecificConfig: frameLength (4), compatibleVersion (1), bitDepth (1), …
                MimeTypes.AUDIO_ALAC -> data.getOrNull(5)?.let { it.toInt() and 0xFF }?.takeIf { it in 8..32 }
                else -> null
            }
        }

        /** STREAMINFO ("fLaC", block header, then 34 bytes): bits per sample − 1 sit across bytes 12 and 13. */
        internal fun flacBitsPerSample(data: ByteArray): Int? {
            val marker = data.size >= 4 && data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() &&
                data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte()
            val info = (if (marker) 4 else 0) + 4
            if (data.size < info + 14) return null
            val b12 = data[info + 12].toInt() and 0xFF
            val b13 = data[info + 13].toInt() and 0xFF
            return (((b12 and 1) shl 4) or (b13 ushr 4)) + 1
        }

        /** Why the grade is what it is (SignalPathView.explanation). */
        fun explanation(grade: Grade): String = when (grade) {
            Grade.BIT_PERFECT -> "Thiết bị nhận đúng từng mẫu của file, ở đúng tần số gốc."
            Grade.HIGH_QUALITY -> "Không mất dữ liệu nhưng có lấy mẫu lại hoặc chỉnh âm lượng số. Để âm lượng của app ở 100 % để đạt bit-perfect."
            Grade.ENHANCED -> "Đang bật xử lý âm thanh (tăng thoại, ban đêm, cân bằng âm lượng)."
            Grade.LOSSY -> "File gốc đã nén mất dữ liệu (MP3, AAC, Opus…)."
            Grade.UNKNOWN -> "Đang mở bài tiếp theo."
        }
    }
}
