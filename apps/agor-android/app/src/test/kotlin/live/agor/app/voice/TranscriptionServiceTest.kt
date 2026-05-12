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
    fun cleanTranscriptRemovesWhisperNoSpeechArtifacts() {
        val cleaned = cleanTranscript(" <|nospeech|> (silence)  Hello   world. [ Silence ] ")

        assertEquals("Hello world.", cleaned)
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

    @Test
    fun whisperLiveKitUrlUsesNativeAsrEndpoint() {
        val url = whisperLiveKitAsrUrl("http://100.101.157.56:8090")

        assertEquals("ws://100.101.157.56:8090/asr", url)
    }

    @Test
    fun whisperLiveKitUrlPreservesExplicitWebSocketScheme() {
        val url = whisperLiveKitAsrUrl("wss://voice.example.test/base/")

        assertEquals("wss://voice.example.test/base/asr", url)
    }

    private fun ByteArray.decodeAscii(start: Int, end: Int): String {
        return copyOfRange(start, end).toString(Charsets.US_ASCII)
    }
}
