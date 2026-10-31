package com.xuanthanhtnt.xtplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DsdTest {
    @Test
    fun pcmRates() {
        // DSD64 / DSD128 / DSD256 / DSD512 (44.1 kHz family) → 176.4 kHz; 48 kHz family → 192 kHz.
        assertEquals(16, DsdFormat.decimation(2_822_400))
        assertEquals(32, DsdFormat.decimation(5_644_800))
        assertEquals(64, DsdFormat.decimation(11_289_600))
        assertEquals(128, DsdFormat.decimation(22_579_200))
        assertEquals(192_000, 3_072_000 / DsdFormat.decimation(3_072_000))
    }

    @Test
    fun allOnesIsFullScaleAndIdleIsSilence() {
        assertEquals(1.0, settle(0xFF.toByte()), 1e-3)
        assertEquals(-1.0, settle(0x00), 1e-3)
        assertTrue(abs(settle(0x69)) < 1e-3)
        assertTrue(abs(settle(0x55)) < 1e-3)
    }

    /** The last PCM sample after a long run of the same byte. */
    private fun settle(byte: Byte): Double {
        val decimator = DsdDecimator(2_822_400)
        val input = ByteArray(4096) { byte }
        val output = FloatArray(4096)
        val n = decimator.process(input, 0, input.size, 1, output, 0, 1)
        assertEquals(input.size / 2, n)
        return output[n - 1].toDouble()
    }

    @Test
    fun dsfHeader() {
        val b = ByteArray(92)
        put(b, 0, "DSD "); le64(b, 4, 28)
        put(b, 28, "fmt "); le64(b, 32, 52)
        le32(b, 40, 1); le32(b, 44, 0); le32(b, 48, 2); le32(b, 52, 2)
        le32(b, 56, 2_822_400); le32(b, 60, 1); le64(b, 64, 2_822_400L * 3); le32(b, 72, 4096)
        put(b, 80, "data"); le64(b, 84, 12 + 4096L * 2 * 2068)
        val h = DsdHeader.parseDsf(b)!!
        assertEquals(2, h.channels)
        assertEquals(2_822_400, h.sampleRate)
        assertEquals(4096, h.blockSize)
        assertEquals(92L, h.dataStart)
        assertTrue(h.lsbFirst)
        assertEquals(3_000_000L, h.durationUs)
        assertNull(DsdHeader.parseDsf(ByteArray(92)))
    }

    @Test
    fun dffHeader() {
        fun chunk(id: String, body: ByteArray): ByteArray = id.toByteArray(Charsets.US_ASCII) + be64(body.size.toLong()) + body
        val prop = chunk(
            "PROP",
            "SND ".toByteArray(Charsets.US_ASCII) +
                chunk("FS  ", be32(5_644_800)) +
                chunk("CHNL", byteArrayOf(0, 2) + "SLFTSRGT".toByteArray(Charsets.US_ASCII)) +
                chunk("CMPR", "DSD ".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)),
        )
        val body = "DSD ".toByteArray(Charsets.US_ASCII) + chunk("FVER", be32(0x01050000)) + prop
        // The sound chunk's header only: 1 411 200 bytes for 2 channels = 1 s of DSD128.
        val sound = "DSD ".toByteArray(Charsets.US_ASCII) + be64(1_411_200)
        val file = "FRM8".toByteArray(Charsets.US_ASCII) + be64(body.size + 1_411_212L) + body + sound
        val h = DsdHeader.parseDff(file)!!
        assertEquals(2, h.channels)
        assertEquals(5_644_800, h.sampleRate)
        assertEquals(false, h.lsbFirst)
        assertEquals(false, h.compressed)
        assertEquals(file.size.toLong(), h.dataStart)
        assertEquals(1_000_000L, h.durationUs)
        // DST (compressed DSD) is recognised but not played.
        val dst = file.copyOf().also { "DST ".forEachIndexed { i, c -> it[file.size - 12 + i] = c.code.toByte() } }
        assertEquals(true, DsdHeader.parseDff(dst)!!.compressed)
    }

    @Test
    fun bitOrder() {
        assertEquals(0x80.toByte(), DsdDecimator.reversed[0x01])
        assertEquals(0x69.toByte(), DsdDecimator.reversed[0x96])
    }

    private fun put(b: ByteArray, at: Int, text: String) = text.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
    private fun le32(b: ByteArray, at: Int, v: Int) { for (i in 0 until 4) b[at + i] = (v shr (8 * i)).toByte() }
    private fun le64(b: ByteArray, at: Int, v: Long) { for (i in 0 until 8) b[at + i] = (v shr (8 * i)).toByte() }
    private fun be32(v: Int) = ByteArray(4) { i -> (v shr (8 * (3 - i))).toByte() }
    private fun be64(v: Long) = ByteArray(8) { i -> (v shr (8 * (7 - i))).toByte() }
}
