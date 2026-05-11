package live.agor.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionServiceTest {
    @Test
    fun cleanTranscriptRemovesSpecialTokensAndCollapsesWhitespace() {
        val cleaned = cleanTranscript("  [BLANK_AUDIO]  hello   Hermes \n\n [MUSIC] ")

        assertEquals("hello Hermes", cleaned)
    }

    @Test
    fun encodeWavWritesPcmHeaderAndDataSize() {
        val wav = encodeWav(shortArrayOf(1, -1), sampleRate = 16_000)

        assertEquals("RIFF", wav.decodeAscii(0, 4))
        assertEquals("WAVE", wav.decodeAscii(8, 12))
        assertEquals("fmt ", wav.decodeAscii(12, 16))
        assertEquals("data", wav.decodeAscii(36, 40))
        assertTrue(wav.size == 48)
    }

    private fun ByteArray.decodeAscii(start: Int, end: Int): String {
        return copyOfRange(start, end).toString(Charsets.US_ASCII)
    }
}
