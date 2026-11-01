package za.co.dielys.domain

/**
 * A minimal WAV container for 16-bit mono PCM, which is what the Worker hands to
 * Whisper. Pure (E1): bytes in, bytes out. The numbers are the RIFF/WAVE header
 * layout itself; naming each offset would hide it.
 */
@Suppress("MagicNumber")
object Wav {
    private const val HEADER_SIZE = 44
    private const val BITS = 16
    private const val BYTES_PER_SAMPLE = BITS / 8
    private const val FMT_CHUNK_SIZE = 16
    private const val PCM_FORMAT = 1
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8

    fun pcm16Mono(
        pcm: ByteArray,
        sampleRate: Int,
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE + pcm.size)

        fun put(
            at: Int,
            text: String,
        ) = text.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }

        fun le(
            at: Int,
            value: Int,
            bytes: Int,
        ) = repeat(bytes) { out[at + it] = ((value shr (BYTE_BITS * it)) and BYTE_MASK).toByte() }

        put(0, "RIFF")
        le(4, HEADER_SIZE - 8 + pcm.size, 4)
        put(8, "WAVE")
        put(12, "fmt ")
        le(16, FMT_CHUNK_SIZE, 4)
        le(20, PCM_FORMAT, 2)
        le(22, 1, 2)
        le(24, sampleRate, 4)
        le(28, sampleRate * BYTES_PER_SAMPLE, 4)
        le(32, BYTES_PER_SAMPLE, 2)
        le(34, BITS, 2)
        put(36, "data")
        le(40, pcm.size, 4)
        pcm.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * Cuts the quiet before the first word and after the last, keeping [padMs] either
     * side so a soft first or last sound survives. Less audio is a faster upload and
     * a faster transcription. A clip with nothing above the noise floor comes back
     * as it was, for the server to judge.
     */
    fun trimSilence(
        pcm: ByteArray,
        sampleRate: Int,
        padMs: Int = 300,
    ): ByteArray {
        val frameBytes = sampleRate / 50 * BYTES_PER_SAMPLE
        val frames = pcm.size / frameBytes
        if (frames == 0) return pcm
        val levels =
            IntArray(frames) { f ->
                var sum = 0L
                var at = f * frameBytes
                while (at < (f + 1) * frameBytes) {
                    val sample =
                        (pcm[at].toInt() and BYTE_MASK) or (pcm[at + 1].toInt() shl BYTE_BITS)
                    sum += kotlin.math.abs(sample.toShort().toInt())
                    at += BYTES_PER_SAMPLE
                }
                (sum / (frameBytes / BYTES_PER_SAMPLE)).toInt()
            }
        val loudest = levels.max()
        if (loudest < NOISE_FLOOR) return pcm
        val threshold = maxOf(NOISE_FLOOR, loudest / 10)
        val first = levels.indexOfFirst { it >= threshold }
        val last = levels.indexOfLast { it >= threshold }
        val pad = padMs / 20
        val from = maxOf(0, first - pad) * frameBytes
        val to = minOf(pcm.size, (last + 1 + pad) * frameBytes)
        return pcm.copyOfRange(from, to)
    }

    /** Mean sample level below which a 20 ms frame is taken as room noise. */
    private const val NOISE_FLOOR = 250
}
