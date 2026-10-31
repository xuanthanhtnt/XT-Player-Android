package com.xuanthanhtnt.xtplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.common.util.ParsableByteArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DSD (DSF and DSDIFF files) for a player without an FFmpeg demuxer. The Apple app hands DSD to FFmpeg, which turns
 * it into PCM at 1/8 of the DSD rate (DSD64 → 352.8 kHz). Here the 1-bit stream is filtered and decimated to
 * 176.4 kHz (192 kHz for 48 kHz-family DSD), the highest rate every Android audio output accepts. The DSD rate is
 * kept in the format's `codecs` ("dsd:2822400") so the signal path still says DSD64.
 */
object DsdFormat {
    private const val PREFIX = "dsd:"

    fun codecs(dsdRate: Int): String = "$PREFIX$dsdRate"

    /** The DSD rate of a format made by [DsfExtractor] / [DffExtractor], else null. */
    fun dsdRate(format: Format): Int? = format.codecs?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toIntOrNull()

    /** Decimation from the DSD rate to the PCM rate: a power of two, ≥ 16, leaving at most 192 kHz. */
    fun decimation(dsdRate: Int): Int {
        var factor = 16
        while (dsdRate / factor > 192_000) factor *= 2
        return factor
    }
}

/**
 * 1-bit DSD to float PCM for one channel. Stage 1: a 128-tap low-pass applied a byte (8 DSD samples) at a time
 * through lookup tables, one output per byte (÷8). Stage 2: a windowed-sinc low-pass decimating the rest of the
 * way (÷2 for DSD64). Bytes are taken oldest bit first (DSF order); DSDIFF bytes are bit-reversed before.
 */
class DsdDecimator(dsdRate: Int) {
    private val stage2 = DsdFormat.decimation(dsdRate) / 8
    private val history = ByteArray(TABLE_BYTES)
    private var head = 0
    private val taps2 = filter2(stage2)
    private val ring = FloatArray(taps2.size)
    private var ringHead = 0
    private var phase = 0

    fun reset() {
        history.fill(SILENCE)
        ring.fill(0f)
        head = 0
        ringHead = 0
        phase = 0
    }

    init {
        reset()
    }

    /** Feeds [count] DSD bytes (LSB = oldest bit) from [input], writing PCM samples to [output] at [outputStep]. Returns how many. */
    fun process(input: ByteArray, offset: Int, count: Int, inputStep: Int, output: FloatArray, outputOffset: Int, outputStep: Int): Int {
        var written = 0
        var at = offset
        for (i in 0 until count) {
            head = (head + TABLE_BYTES - 1) % TABLE_BYTES
            history[head] = input[at]
            at += inputStep
            var sample = 0.0
            for (j in 0 until TABLE_BYTES) sample += tables[j][history[(head + j) % TABLE_BYTES].toInt() and 0xFF]
            ringHead = (ringHead + ring.size - 1) % ring.size
            ring[ringHead] = sample.toFloat()
            phase++
            if (phase == stage2) {
                phase = 0
                var acc = 0f
                for (k in taps2.indices) acc += taps2[k] * ring[(ringHead + k) % ring.size]
                output[outputOffset + written * outputStep] = acc
                written++
            }
        }
        return written
    }

    companion object {
        private const val TABLE_BYTES = 16
        /** 0x69 = 01101001: equal ones and zeros, DSD's idle pattern. */
        private const val SILENCE: Byte = 0x69

        /** table[j][b]: the contribution of byte b at age j (byte 0 the newest) to stage 1's output. */
        private val tables: Array<DoubleArray> = run {
            val taps = TABLE_BYTES * 8
            // Cut-off 0.45 of stage 1's output Nyquist (the byte rate), Blackman window, unity gain at DC.
            val h = lowPass(taps, 0.45 / 16.0)
            Array(TABLE_BYTES) { j ->
                DoubleArray(256) { b ->
                    var acc = 0.0
                    for (k in 0 until 8) {
                        // Bit k is the k-th oldest of the byte: age 8j + (7 − k).
                        val sign = if ((b shr k) and 1 == 1) 1.0 else -1.0
                        acc += h[8 * j + (7 - k)] * sign
                    }
                    acc
                }
            }
        }

        private fun filter2(factor: Int): FloatArray {
            val taps = 48 * factor
            return lowPass(taps, 0.45 / factor / 2.0).map { it.toFloat() }.toFloatArray()
        }

        /** Windowed-sinc low-pass, [cutoff] in cycles per sample, Blackman window, taps summing to 1. */
        internal fun lowPass(taps: Int, cutoff: Double): DoubleArray {
            val m = taps - 1
            val h = DoubleArray(taps) { n ->
                val x = n - m / 2.0
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val window = 0.42 - 0.5 * cos(2 * PI * n / m) + 0.08 * cos(4 * PI * n / m)
                sinc * window
            }
            val sum = h.sum()
            for (i in h.indices) h[i] /= sum
            return h
        }

        /** Reverses the bit order of a byte (DSDIFF stores the oldest bit first in the MSB). */
        val reversed = ByteArray(256) { b ->
            var r = 0
            for (k in 0 until 8) if ((b shr k) and 1 == 1) r = r or (1 shl (7 - k))
            r.toByte()
        }
    }
}

/** What a DSD file's header says. */
data class DsdHeader(
    val channels: Int,
    val sampleRate: Int,
    /** DSD samples per channel (DSF); 0 when only the data length says it (DSDIFF). */
    val sampleCount: Long,
    /** Bytes per channel per block (DSF: 4096); DSDIFF interleaves byte by byte (1). */
    val blockSize: Int,
    val dataStart: Long,
    val dataLength: Long,
    /** DSF: the oldest bit is the least significant one. */
    val lsbFirst: Boolean,
    val compressed: Boolean = false,
) {
    val durationUs: Long
        get() {
            val samples = if (sampleCount > 0) sampleCount else dataLength / channels * 8
            return samples * 1_000_000L / sampleRate
        }

    companion object {
        /** "DSD " chunk, "fmt " chunk, "data" chunk header: 92 bytes, little-endian. */
        fun parseDsf(b: ByteArray): DsdHeader? {
            if (b.size < 92 || !tag(b, 0, "DSD ") || !tag(b, 28, "fmt ")) return null
            val fmtSize = le64(b, 32)
            val dataAt = 28 + fmtSize.toInt()
            if (dataAt + 12 > b.size || !tag(b, dataAt, "data")) return null
            if (le32(b, 44) != 0) return null // format id 0 = DSD raw
            val channels = le32(b, 52)
            val rate = le32(b, 56)
            val bits = le32(b, 60)
            val count = le64(b, 64)
            val block = le32(b, 72)
            if (channels !in 1..8 || rate <= 0 || block <= 0) return null
            return DsdHeader(
                channels = channels, sampleRate = rate, sampleCount = count, blockSize = block,
                dataStart = dataAt + 12L, dataLength = le64(b, dataAt + 4) - 12, lsbFirst = bits != 8,
            )
        }

        /** "FRM8" … "DSD ", then FVER / PROP (SND: FS, CHNL, CMPR) / "DSD " or "DST " chunks; big-endian. */
        fun parseDff(b: ByteArray): DsdHeader? {
            if (b.size < 16 || !tag(b, 0, "FRM8") || !tag(b, 12, "DSD ")) return null
            var at = 16
            var rate = 0
            var channels = 0
            var compressed = false
            while (at + 12 <= b.size) {
                val id = String(b, at, 4, Charsets.US_ASCII)
                val size = be64(b, at + 4)
                val body = at + 12
                when (id) {
                    "PROP" -> {
                        var p = body + 4 // "SND "
                        val end = body + size.toInt()
                        while (p + 12 <= minOf(end, b.size)) {
                            val sub = String(b, p, 4, Charsets.US_ASCII)
                            val subSize = be64(b, p + 4).toInt()
                            val d = p + 12
                            when (sub) {
                                "FS  " -> if (d + 4 <= b.size) rate = be32(b, d)
                                "CHNL" -> if (d + 2 <= b.size) channels = ((b[d].toInt() and 0xFF) shl 8) or (b[d + 1].toInt() and 0xFF)
                                "CMPR" -> if (d + 4 <= b.size) compressed = !tag(b, d, "DSD ")
                            }
                            p = d + subSize + (subSize and 1)
                        }
                    }
                    "DSD ", "DST " -> {
                        if (channels !in 1..8 || rate <= 0) return null
                        return DsdHeader(
                            channels = channels, sampleRate = rate, sampleCount = 0, blockSize = 1,
                            dataStart = body.toLong(), dataLength = size, lsbFirst = false,
                            compressed = compressed || id == "DST ",
                        )
                    }
                }
                at = body + size.toInt() + (size.toInt() and 1)
            }
            return null
        }

        private fun tag(b: ByteArray, at: Int, text: String): Boolean =
            at + 4 <= b.size && (0 until 4).all { b[at + it] == text[it].code.toByte() }

        private fun le32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

        private fun le64(b: ByteArray, at: Int): Long = (le32(b, at).toLong() and 0xFFFFFFFFL) or (le32(b, at + 4).toLong() shl 32)

        private fun be32(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

        private fun be64(b: ByteArray, at: Int): Long = (be32(b, at).toLong() shl 32) or (be32(b, at + 4).toLong() and 0xFFFFFFFFL)
    }
}

/**
 * Reads DSF or DSDIFF and outputs float PCM. Reads a group of blocks at a time into its own buffer (a network error
 * mid-way loses nothing: the load resumes where the bytes stopped), converts, and writes one sample per group.
 */
abstract class DsdExtractor : Extractor {
    private var output: ExtractorOutput? = null
    private var track: TrackOutput? = null
    private var header: DsdHeader? = null
    private var decimators: Array<DsdDecimator> = emptyArray()
    private var group = ByteArray(0)
    private var filled = 0
    /** DSD bytes per channel already turned into PCM (for the sample times). */
    private var bytesDone = 0L
    private var pcm = FloatArray(0)
    private val scratch = ParsableByteArray()

    protected abstract fun parse(peeked: ByteArray): DsdHeader?

    /** How many header bytes to look at. */
    protected open val headerBytes: Int = 4096

    override fun sniff(input: ExtractorInput): Boolean {
        val peeked = ByteArray(headerBytes)
        val n = peekUpTo(input, peeked)
        val parsed = parse(peeked.copyOf(n)) ?: return false
        return !parsed.compressed
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val h = header ?: return readHeader(input)
        val need = groupBytesPerChannel(h) * h.channels
        val left = (h.dataStart + h.dataLength - input.position).coerceAtLeast(0)
        val target = minOf(need.toLong(), filled + left).toInt()
        while (filled < target) {
            val n = input.read(group, filled, target - filled)
            if (n == C.RESULT_END_OF_INPUT) break
            filled += n
        }
        // A DSF block group cut short (a truncated file) cannot be split into its channels.
        val usable = if (h.blockSize > 1 && filled < need) 0 else filled - filled % h.channels
        if (usable == 0) return Extractor.RESULT_END_OF_INPUT
        emit(h, usable)
        filled = 0
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        filled = 0
        decimators.forEach { it.reset() }
        val h = header ?: return
        bytesDone = if (position > h.dataStart) (position - h.dataStart) / h.channels else 0
    }

    override fun release() = Unit

    private fun readHeader(input: ExtractorInput): Int {
        val peeked = ByteArray(headerBytes)
        val n = peekUpTo(input, peeked)
        val h = parse(peeked.copyOf(n))?.takeIf { !it.compressed } ?: throw androidx.media3.common.ParserException.createForMalformedContainer("DSD", null)
        input.skipFully(h.dataStart.toInt())
        header = h
        decimators = Array(h.channels) { DsdDecimator(h.sampleRate) }
        group = ByteArray(groupBytesPerChannel(h) * h.channels)
        val outRate = h.sampleRate / DsdFormat.decimation(h.sampleRate)
        val out = output ?: return Extractor.RESULT_END_OF_INPUT
        val t = out.track(0, C.TRACK_TYPE_AUDIO)
        t.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setCodecs(DsdFormat.codecs(h.sampleRate))
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setChannelCount(h.channels)
                .setSampleRate(outRate)
                .setAverageBitrate(h.sampleRate * h.channels)
                .build()
        )
        track = t
        out.endTracks()
        out.seekMap(DsdSeekMap(h, groupBytesPerChannel(h)))
        return Extractor.RESULT_CONTINUE
    }

    /** DSF: one block per channel; DSDIFF (interleaved bytes): 4096 bytes per channel. */
    private fun groupBytesPerChannel(h: DsdHeader): Int = if (h.blockSize > 1) h.blockSize else 4096

    private fun emit(h: DsdHeader, bytes: Int) {
        val perChannel = bytes / h.channels
        val decimation = DsdFormat.decimation(h.sampleRate)
        // DSF: samples past the end of the file are padding in the last block.
        val valid = if (h.sampleCount > 0) minOf(perChannel.toLong(), (h.sampleCount + 7) / 8 - bytesDone).coerceAtLeast(0).toInt() else perChannel
        val frames = valid * 8 / decimation + 1
        if (pcm.size < frames * h.channels) pcm = FloatArray(frames * h.channels)
        var produced = 0
        for (ch in 0 until h.channels) {
            if (!h.lsbFirst) {
                // DSDIFF: bytes interleaved and the oldest bit in the MSB.
                for (i in 0 until perChannel) {
                    val at = i * h.channels + ch
                    group[at] = DsdDecimator.reversed[group[at].toInt() and 0xFF]
                }
            }
            val (start, step) = if (h.blockSize > 1) ch * perChannel to 1 else ch to h.channels
            produced = decimators[ch].process(group, start, valid, step, pcm, ch, h.channels)
        }
        val timeUs = bytesDone * 8 * 1_000_000L / h.sampleRate
        bytesDone += perChannel
        if (produced == 0) return
        val size = produced * h.channels * 4
        scratch.reset(size)
        val data = scratch.data
        for (i in 0 until produced * h.channels) {
            val bits = java.lang.Float.floatToRawIntBits(pcm[i])
            data[4 * i] = bits.toByte()
            data[4 * i + 1] = (bits shr 8).toByte()
            data[4 * i + 2] = (bits shr 16).toByte()
            data[4 * i + 3] = (bits shr 24).toByte()
        }
        val t = track ?: return
        t.sampleData(scratch, size)
        t.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
    }

    private fun peekUpTo(input: ExtractorInput, into: ByteArray): Int {
        var n = 0
        while (n < into.size) {
            val r = input.peek(into, n, into.size - n)
            if (r == C.RESULT_END_OF_INPUT) break
            n += r
        }
        input.resetPeekPosition()
        return n
    }
}

class DsfExtractor : DsdExtractor() {
    override val headerBytes: Int = 92
    override fun parse(peeked: ByteArray): DsdHeader? = DsdHeader.parseDsf(peeked)
}

class DffExtractor : DsdExtractor() {
    // Comments and other chunks may come before the sound data.
    override val headerBytes: Int = 65_536
    override fun parse(peeked: ByteArray): DsdHeader? = DsdHeader.parseDff(peeked)
}

/** Seeks to the start of a block group (DSF) or a byte frame (DSDIFF). */
private class DsdSeekMap(private val h: DsdHeader, private val groupBytesPerChannel: Int) : SeekMap {
    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = h.durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val bytesPerChannel = timeUs.coerceAtLeast(0) * h.sampleRate / 8 / 1_000_000L
        val aligned = if (h.blockSize > 1) bytesPerChannel / groupBytesPerChannel * groupBytesPerChannel else bytesPerChannel
        val limit = (h.dataLength / h.channels).coerceAtLeast(0)
        val clamped = aligned.coerceAtMost(limit)
        val position = h.dataStart + clamped * h.channels
        return SeekMap.SeekPoints(SeekPoint(clamped * 8 * 1_000_000L / h.sampleRate, position))
    }
}

/** Media3's extractors plus DSF / DSDIFF (first for .dsf / .dff, last otherwise so nothing else is mistaken for DSD). */
object XtExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = defaults.createExtractors() + dsd()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        val all = defaults.createExtractors(uri, responseHeaders)
        return if (extension == "dsf" || extension == "dff") dsd() + all else all + dsd()
    }

    private fun dsd(): Array<Extractor> = arrayOf(DsfExtractor(), DffExtractor())
}
