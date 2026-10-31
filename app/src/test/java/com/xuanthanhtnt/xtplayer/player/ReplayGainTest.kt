package com.xuanthanhtnt.xtplayer.player

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** mpv `replaygain=album`, `replaygain-fallback=0`: album gain, else track gain, else nothing (0 dB). */
class ReplayGainTest {
    @Test
    fun albumGainFirst() {
        val flac = Metadata(
            VorbisComment("REPLAYGAIN_TRACK_GAIN", "-4.20 dB"),
            VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-6.48 dB"),
        )
        assertEquals(-6.48f, ReplayGain.gainDb(flac)!!, 1e-4f)
    }

    @Test
    fun trackGainWhenThereIsNoAlbumGain() {
        val mp3 = Metadata(TextInformationFrame("TXXX", "replaygain_track_gain", listOf("+2.10 dB")))
        assertEquals(2.10f, ReplayGain.gainDb(mp3)!!, 1e-4f)
    }

    @Test
    fun itunesAtoms() {
        val m4a = Metadata(InternalFrame("com.apple.iTunes", "replaygain_album_gain", "-7.5 dB"))
        assertEquals(-7.5f, ReplayGain.gainDb(m4a)!!, 1e-4f)
    }

    @Test
    fun noTagsNoGain() {
        assertNull(ReplayGain.gainDb(null))
        assertNull(ReplayGain.gainDb(Metadata(VorbisComment("ARTIST", "Khánh Ly"))))
        assertEquals(1f, ReplayGain.factor(0f), 1e-6f)
        // −6.02 dB halves the amplitude; positive gains raise it (no clipping protection).
        assertEquals(0.5f, ReplayGain.factor(-6.0206f), 1e-4f)
        assertEquals(2f, ReplayGain.factor(6.0206f), 1e-4f)
        assertEquals(-3.5f, ReplayGain.parse("-3,50 dB")!!, 1e-4f)
    }
}
