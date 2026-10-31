package com.xuanthanhtnt.xtplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MusicPlayer.swift `SignalPath` (grades, steps, `quality`) and InfoPanel.kHz. */
class SignalPathTest {
    private val flac96 = AudioPathInput(
        codec = "FLAC", sampleRate = 96_000, bitDepth = 24, outputSampleRate = 96_000, deviceSampleRate = 96_000, deviceName = "USB · DAC",
    )

    @Test
    fun qualityLabels() {
        // "FLAC · 96 kHz · 24-bit", "DSD128", "MP3 · 44.1 kHz".
        assertEquals("FLAC · 96 kHz · 24-bit", SignalPath.quality("FLAC", 96_000, 24))
        assertEquals("MP3 · 44.1 kHz", SignalPath.quality("MP3", 44_100, null))
        assertEquals("ALAC · 44.1 kHz · 16-bit", SignalPath.quality("ALAC", 44_100, 16))
        // DSD decoded to PCM at 1/8 of its rate: 352.8 kHz = DSD64, 705.6 kHz = DSD128.
        assertEquals("DSD64", SignalPath.quality("DSD", 352_800, null))
        assertEquals("DSD128", SignalPath.quality("DSD", 705_600, null))
        assertEquals("FLAC", SignalPath.quality("FLAC", 0, 24))
        assertEquals("48 kHz", SignalPath.kHz(48_000.0))
        assertEquals("176.4 kHz", SignalPath.kHz(176_400.0))
    }

    @Test
    fun bitPerfectWhenNothingTouchesTheSamples() {
        val path = SignalPath(flac96)
        assertEquals(SignalPath.Grade.BIT_PERFECT, path.grade)
        assertTrue(path.processing.isEmpty())
        assertEquals("FLAC · 96 kHz · 24-bit", path.source)
        assertEquals("USB · DAC · 96 kHz", path.output)
    }

    @Test
    fun resamplingIsHighQuality() {
        // A CD rip on a 48 kHz mixer.
        val path = SignalPath(flac96.copy(sampleRate = 44_100, outputSampleRate = 44_100, deviceSampleRate = 48_000, bitDepth = 16))
        assertEquals(SignalPath.Grade.HIGH_QUALITY, path.grade)
        assertEquals(listOf("Lấy mẫu lại 44.1 kHz → 48 kHz"), path.processing)
    }

    @Test
    fun volumeAndMute() {
        assertEquals(listOf("Âm lượng 50 %"), SignalPath(flac96.copy(volume = 0.5f)).processing)
        assertEquals(SignalPath.Grade.HIGH_QUALITY, SignalPath(flac96.copy(volume = 0.5f)).grade)
        assertEquals(listOf("Tắt tiếng"), SignalPath(flac96.copy(muted = true, volume = 0f)).processing)
    }

    @Test
    fun processingIsEnhanced() {
        val path = SignalPath(flac96.copy(nightMode = true, volumeLeveling = true))
        assertEquals(SignalPath.Grade.ENHANCED, path.grade)
        assertEquals(listOf("Chế độ ban đêm", "Cân bằng âm lượng (ReplayGain)"), path.processing)
    }

    @Test
    fun lossyAndUnknown() {
        assertEquals(SignalPath.Grade.LOSSY, SignalPath(flac96.copy(codec = "AAC", bitDepth = null)).grade)
        // Lossy wins over processing: the source already lost data.
        assertEquals(SignalPath.Grade.LOSSY, SignalPath(flac96.copy(codec = "MP3", dialogBoost = true)).grade)
        assertEquals(SignalPath.Grade.UNKNOWN, SignalPath(AudioPathInput()).grade)
        assertEquals(SignalPath.Grade.UNKNOWN, SignalPath(flac96.copy(sampleRate = 0)).grade)
        for (codec in listOf("FLAC", "ALAC", "WavPack", "APE", "TTA", "TAK", "PCM", "DSD", "DSD (DST)", "TrueHD", "MLP", "WMALOSSLESS")) {
            assertEquals(codec, SignalPath.Grade.BIT_PERFECT, SignalPath(flac96.copy(codec = codec)).grade)
        }
        assertEquals("Đang mở…", SignalPath.Grade.UNKNOWN.label)
    }

    @Test
    fun noOutputYetMeansNoResampleStep() {
        val path = SignalPath(flac96.copy(outputSampleRate = 0, deviceSampleRate = 48_000))
        assertTrue(path.processing.isEmpty())
    }

    @Test
    fun bitDepthFromTheFile() {
        assertEquals(24, SignalPath.bitDepth(C.ENCODING_PCM_24BIT, MimeTypes.AUDIO_FLAC, emptyList()))
        assertEquals(16, SignalPath.bitDepth(C.ENCODING_PCM_16BIT, MimeTypes.AUDIO_RAW, emptyList()))
        assertNull(SignalPath.bitDepth(C.ENCODING_PCM_FLOAT, MimeTypes.AUDIO_RAW, emptyList()))
        assertNull(SignalPath.bitDepth(Format.NO_VALUE, MimeTypes.AUDIO_MPEG, emptyList()))
        // FLAC in Matroska: "fLaC" + STREAMINFO, 96 kHz / 2 channels / 24 bits.
        assertEquals(24, SignalPath.bitDepth(Format.NO_VALUE, MimeTypes.AUDIO_FLAC, listOf(streamInfo(96_000, 2, 24))))
        assertEquals(16, SignalPath.bitDepth(Format.NO_VALUE, MimeTypes.AUDIO_FLAC, listOf(streamInfo(44_100, 2, 16))))
        // ALAC magic cookie: bitDepth is its sixth byte.
        val cookie = ByteArray(24).also { it[5] = 24 }
        assertEquals(24, SignalPath.bitDepth(Format.NO_VALUE, MimeTypes.AUDIO_ALAC, listOf(cookie)))
    }

    /** "fLaC", a STREAMINFO block header, then the 34-byte STREAMINFO. */
    private fun streamInfo(sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val data = ByteArray(4 + 4 + 34)
        "fLaC".forEachIndexed { i, c -> data[i] = c.code.toByte() }
        data[4] = 0x80.toByte()
        data[7] = 34
        val info = 8
        data[info + 10] = (sampleRate ushr 12).toByte()
        data[info + 11] = (sampleRate ushr 4).toByte()
        data[info + 12] = (((sampleRate and 0xF) shl 4) or ((channels - 1) shl 1) or ((bits - 1) ushr 4)).toByte()
        data[info + 13] = (((bits - 1) and 0xF) shl 4).toByte()
        return data
    }
}
