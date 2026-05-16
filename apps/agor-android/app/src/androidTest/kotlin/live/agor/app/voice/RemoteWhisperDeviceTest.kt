package live.agor.app.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RemoteWhisperDeviceTest {
    @Test
    fun transcribesWavFixtureThroughRemoteWhisper() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val whisperUrl = args.getString("whisperUrl")
            ?: error("Missing instrumentation argument: whisperUrl")
        val whisperToken = args.getString("whisperToken")
        val audioPath = args.getString("audioFilePath")
            ?: error("Missing instrumentation argument: audioFilePath")

        val audio = decodeWavPcm16Mono(File(audioPath).readBytes())
        val result = RemoteWhisperTranscriber(whisperUrl, whisperToken).transcribe(audio.samples)

        assertEquals("remote", result.source)
        assertTrue(result.endpoint?.endsWith("/v1/audio/transcriptions") == true)
        assertTrue(result.text.contains("Reactor Startup Sequence", ignoreCase = true))
        assertTrue(result.text.contains("core chamber", ignoreCase = true))
    }
}
