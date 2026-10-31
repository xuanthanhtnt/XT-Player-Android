package com.xuanthanhtnt.xtplayer.player

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** MPVPlayerEngine.swift `AudioEnhancement.filter`: the same choices and numbers. */
class AudioEnhancementTest {
    private val night = "dynaudnorm=f=250:g=31:p=0.6:m=8"

    @Test
    fun stereo() {
        assertEquals("lavfi=[dialoguenhance=original=1:enhance=1.5:voice=3]", AudioEnhancement.filter(false, true, 2, 2))
        assertEquals("lavfi=[$night]", AudioEnhancement.filter(true, false, 2, 2))
        assertEquals("lavfi=[dialoguenhance=original=1:enhance=1.5:voice=3,$night]", AudioEnhancement.filter(true, true, 2, 2))
        // Mono has nothing to pull the voice out of.
        assertEquals("", AudioEnhancement.filter(false, true, 1, 2))
    }

    @Test
    fun surroundToHeadphones() {
        assertEquals("lavfi=[aresample=out_chlayout=stereo:clev=1.20:slev=0.35:lfe_mix_level=0]", AudioEnhancement.filter(false, true, 6, 2))
        assertEquals(
            "lavfi=[aresample=out_chlayout=stereo:clev=1.00:slev=0.50:lfe_mix_level=0,$night]",
            AudioEnhancement.filter(true, false, 8, 2),
        )
    }

    @Test
    fun surroundToReceiver() {
        assertEquals(
            "lavfi=[aresample=out_chlayout=5.1,pan=5.1|FL=0.70*FL|FR=0.70*FR|FC=FC|LFE=0.70*LFE|BL=0.60*BL|BR=0.60*BR]",
            AudioEnhancement.filter(false, true, 6, 6),
        )
        assertEquals(
            "lavfi=[aresample=out_chlayout=7.1,pan=7.1|FL=0.70*FL|FR=0.70*FR|FC=FC|LFE=0.50*LFE|BL=0.60*BL|BR=0.60*BR|SL=0.60*SL|SR=0.60*SR,$night]",
            AudioEnhancement.filter(true, true, 8, 8),
        )
        // 7.1 to a 5.1 receiver: not wide.
        assertEquals(
            "lavfi=[aresample=out_chlayout=5.1,pan=5.1|FL=1.00*FL|FR=1.00*FR|FC=FC|LFE=0.50*LFE|BL=0.75*BL|BR=0.75*BR,$night]",
            AudioEnhancement.filter(true, false, 8, 6),
        )
    }

    @Test
    fun offOrUnknownLayout() {
        assertEquals("", AudioEnhancement.filter(false, false, 6, 2))
        assertEquals("", AudioEnhancement.filter(true, true, 0, 2))
        assertEquals(AudioEnhancement.Plan.None, AudioEnhancement.plan(true, true, 6, 0))
    }

    @Test
    fun downmixIntoTheFrontPair() {
        // FL FR FC LFE BL BR
        val frame = floatArrayOf(0.1f, 0.2f, 0.5f, 0.9f, 0.3f, 0.4f)
        ChannelEnhancer().process(frame, 1, 6, 48_000, AudioEnhancement.plan(false, true, 6, 2), 1f)
        assertEquals(0.1f + 1.2f * 0.5f + 0.35f * 0.3f, frame[0], 1e-5f)
        assertEquals(0.2f + 1.2f * 0.5f + 0.35f * 0.4f, frame[1], 1e-5f)
        // The rest is silenced, so the system's downmix passes the pair through; LFE is dropped.
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), frame.copyOfRange(2, 6), 0f)
    }

    @Test
    fun receiverKeepsTheLayout() {
        val frame = FloatArray(8) { 1f }
        ChannelEnhancer().process(frame, 1, 8, 48_000, AudioEnhancement.plan(true, true, 8, 8), 1f)
        assertArrayEquals(floatArrayOf(0.7f, 0.7f, 1f, 0.5f, 0.6f, 0.6f, 0.6f, 0.6f), frame, 1e-6f)
    }

    @Test
    fun stereoDialogOnlyTouchesWhatBothSidesShare() {
        // Opposite sides (no common part): nothing to enhance.
        val samples = FloatArray(2 * 480) { i -> if (i % 2 == 0) 0.3f else -0.3f }
        val before = samples.copyOf()
        ChannelEnhancer().process(samples, 480, 2, 48_000, AudioEnhancement.plan(false, true, 2, 2), 1f)
        assertArrayEquals(before, samples, 1e-6f)
    }

    @Test
    fun pcmRoundTrip() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            val values = floatArrayOf(0f, 0.5f, -0.5f, 0.25f)
            val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            PcmSamples.write(buffer, encoding, values, values.size)
            buffer.flip()
            val back = FloatArray(values.size)
            PcmSamples.read(buffer, encoding, back, values.size)
            assertArrayEquals("encoding $encoding", values, back, 1e-4f)
        }
    }
}
