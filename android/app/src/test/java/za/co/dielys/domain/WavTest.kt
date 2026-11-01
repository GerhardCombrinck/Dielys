package za.co.dielys.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WavTest {
    @Test
    fun `header describes 16 kHz mono 16-bit PCM and the data follows`() {
        val wav = Wav.pcm16Mono(byteArrayOf(1, 2, 3, 4), 16_000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        // sample rate 16000 = 0x3E80, little endian
        assertEquals(0x80.toByte(), wav[24])
        assertEquals(0x3E.toByte(), wav[25])
        assertEquals(4, wav[40].toInt())
        assertEquals(listOf<Byte>(1, 2, 3, 4), wav.drop(44))
    }

    private fun pcm(vararg levels: Int): ByteArray {
        // 320 samples (20 ms at 16 kHz) per level, alternating sign like a waveform.
        val out = ByteArray(levels.size * 640)
        levels.forEachIndexed { f, level ->
            for (i in 0 until 320) {
                val v = if (i % 2 == 0) level else -level
                out[f * 640 + i * 2] = (v and 0xFF).toByte()
                out[f * 640 + i * 2 + 1] = (v shr 8).toByte()
            }
        }
        return out
    }

    @Test
    fun `trims the quiet either side of speech and keeps the padding`() {
        val quiet = IntArray(50) { 40 }
        val speech = IntArray(10) { 4000 }
        val clip = pcm(*(quiet + speech + quiet))
        val trimmed = Wav.trimSilence(clip, 16_000, padMs = 100)
        // 10 speech frames plus 5 padding frames each side.
        assertEquals(20 * 640, trimmed.size)
    }

    @Test
    fun `leaves a clip with nothing above the noise floor alone`() {
        val clip = pcm(*IntArray(30) { 40 })
        assertEquals(clip.size, Wav.trimSilence(clip, 16_000).size)
    }
}
