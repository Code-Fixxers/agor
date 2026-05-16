package live.agor.app.voice

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.agor.app.auth.SecureTokenStore
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import live.agor.app.voice.jni.WhisperJni
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class TranscriptionResult(
    val text: String,
    val source: String,
    val endpoint: String? = null,
)

interface SpeechTranscriber {
    suspend fun transcribe(samples: ShortArray): TranscriptionResult
}

/**
 * Provider facade for Hermes voice STT.
 *
 * If a remote WhisperLiveKit/OpenAI-compatible server is configured, it is
 * attempted first and falls back to local whisper.cpp on failure.
 */
class TranscriptionService(
    context: Context,
    private val tokens: SecureTokenStore? = null,
    private val models: VoiceModelManager = VoiceModelManager(context),
) : SpeechTranscriber {
    private val local = LocalWhisperTranscriber(context, models)
    private val remote: RemoteWhisperTranscriber?
        get() {
            val url = tokens?.remoteWhisperUrl?.takeIf { it.isNotBlank() } ?: return null
            return RemoteWhisperTranscriber(url, tokens.remoteWhisperToken)
        }

    override suspend fun transcribe(samples: ShortArray): TranscriptionResult {
        AppLogger.log("Transcription requested: samples=${samples.size} remote=${remote != null} localReady=${isOnDeviceReady()}", LogLevel.INFO, "Voice")
        val remoteProvider = remote
        if (remoteProvider != null) {
            runCatching { remoteProvider.transcribe(samples) }
                .onSuccess {
                    val cleaned = cleanTranscript(it.text)
                    AppLogger.log("Remote Whisper succeeded: chars=${cleaned.length}", LogLevel.INFO, "Voice")
                    return it.copy(text = cleaned)
                }
                .onFailure {
                    AppLogger.log("Remote Whisper failed, falling back local: ${it.message}", LogLevel.WARNING, "Voice")
                }
        }
        return local.transcribe(samples).let {
            val cleaned = cleanTranscript(it.text)
            AppLogger.log("Local Whisper result: source=${it.source} chars=${cleaned.length}", LogLevel.INFO, "Voice")
            it.copy(text = cleaned)
        }
    }

    suspend fun transcribeRemoteOnly(samples: ShortArray): TranscriptionResult? {
        val remoteProvider = remote ?: return null
        return runCatching { remoteProvider.transcribe(samples) }
            .map {
                val cleaned = cleanTranscript(it.text)
                AppLogger.log("Remote Whisper partial succeeded: chars=${cleaned.length}", LogLevel.DEBUG, "Voice")
                it.copy(text = cleaned)
            }
            .onFailure {
                AppLogger.log("Remote Whisper partial failed: ${it.message}", LogLevel.WARNING, "Voice")
            }
            .getOrNull()
    }

    fun defaultModelPath(): File = local.defaultModelPath()
    fun isOnDeviceReady(): Boolean = local.isReady()
    suspend fun downloadOnDeviceModel(): File = models.downloadWhisperModel()
}

class LocalWhisperTranscriber(
    context: Context,
    private val models: VoiceModelManager = VoiceModelManager(context),
) : SpeechTranscriber {
    private val whisper: WhisperJni? by lazy {
        if (!WhisperJni.loadNative()) {
            null
        } else {
            val modelPath = ensureModelFile() ?: return@lazy null
            val w = WhisperJni()
            if (modelPath.exists() && w.init(modelPath.absolutePath)) w else null
        }
    }

    fun defaultModelPath(): File = models.whisperModelFile()
    fun isReady(): Boolean = whisper != null

    override suspend fun transcribe(samples: ShortArray): TranscriptionResult = withContext(Dispatchers.Default) {
        val w = whisper ?: return@withContext TranscriptionResult("", "local-unavailable")
        val floats = FloatArray(samples.size)
        for (i in samples.indices) floats[i] = samples[i] / 32768f
        val text = runCatching {
            w.transcribe(floats, AudioCapture.SAMPLE_RATE)
        }.onFailure {
            AppLogger.log("Local Whisper JNI failed: ${it.message}", LogLevel.ERROR, "Voice")
        }.getOrDefault("")
        TranscriptionResult(text, "local")
    }

    private fun ensureModelFile(): File? {
        val dest = defaultModelPath()
        if (dest.exists() && dest.length() > 0) return dest
        AppLogger.log("Local Whisper model is not downloaded: ${dest.absolutePath}", LogLevel.INFO, "Voice")
        return null
    }
}

class RemoteWhisperTranscriber(
    rawBaseUrl: String,
    private val bearer: String?,
) : SpeechTranscriber {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(samples: ShortArray): TranscriptionResult = withContext(Dispatchers.IO) {
        AppLogger.log("Remote Whisper POST $baseUrl/v1/audio/transcriptions samples=${samples.size}", LogLevel.INFO, "Voice")
        val wav = encodeWav(samples)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "voice.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("temperature", "0.0")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("model", DEFAULT_REMOTE_WHISPER_MODEL)
            .build()
        val openAiResult = runCatching {
            postTranscription("$baseUrl/v1/audio/transcriptions", body)
        }.getOrElse { error ->
            if ((error as? WhisperHttpException)?.statusCode != 404) throw error
            AppLogger.log("Remote Whisper v1 endpoint unavailable, falling back to /inference", LogLevel.WARNING, "Voice")
            null
        }
        if (openAiResult != null) return@withContext openAiResult
        postTranscription("$baseUrl/inference", body)
    }

    private fun postTranscription(url: String, body: MultipartBody): TranscriptionResult {
        val started = System.nanoTime()
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
            }
            .post(body)
            .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            AppLogger.log(
                "Remote Whisper ${url.substringAfterLast('/')} -> ${resp.code} in ${elapsedMs(started)}ms bytes=${text.length}",
                if (resp.isSuccessful) LogLevel.INFO else LogLevel.WARNING,
                "Voice",
            )
            if (!resp.isSuccessful) throw WhisperHttpException(resp.code, "Whisper ${resp.code}: ${text.take(300)}")
            TranscriptionResult(parseRemoteText(text), "remote", url)
        }
    }

    private fun parseRemoteText(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val obj = JSONObject(trimmed)
            obj.optString("text")
                .ifBlank { obj.optString("transcription") }
                .ifBlank { obj.optString("result") }
                .ifBlank { extractSegments(obj.optJSONArray("segments")) }
        }.getOrElse { trimmed }
    }

    private fun extractSegments(segments: JSONArray?): String {
        if (segments == null) return ""
        val out = StringBuilder()
        for (i in 0 until segments.length()) {
            val item = segments.optJSONObject(i) ?: continue
            val text = item.optString("text")
            if (text.isNotBlank()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(text)
            }
        }
        return out.toString()
    }
}

private class WhisperHttpException(val statusCode: Int, message: String) : IOException(message)

private fun elapsedMs(startedNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

fun cleanTranscript(raw: String): String {
    return raw
        .replace(Regex("<\\|[^>]+\\|>"), " ")
        .replace(Regex("\\[[A-Za-z_ ]+]"), " ")
        .replace(Regex("\\((?i:silence|blank audio|music|noise)\\)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun encodeWav(samples: ShortArray, sampleRate: Int = AudioCapture.SAMPLE_RATE): ByteArray {
    val dataSize = samples.size * 2
    val out = ByteArrayOutputStream(44 + dataSize)
    fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
    fun intLE(value: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }
    fun shortLE(value: Int) {
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }
    ascii("RIFF")
    intLE(36 + dataSize)
    ascii("WAVE")
    ascii("fmt ")
    intLE(16)
    shortLE(1)
    shortLE(1)
    intLE(sampleRate)
    intLE(sampleRate * 2)
    shortLE(2)
    shortLE(16)
    ascii("data")
    intLE(dataSize)
    val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
    for (sample in samples) pcm.putShort(sample)
    out.write(pcm.array())
    return out.toByteArray()
}

data class Pcm16Audio(
    val samples: ShortArray,
    val sampleRate: Int,
)

fun decodeWavPcm16Mono(wav: ByteArray, targetSampleRate: Int = AudioCapture.SAMPLE_RATE): Pcm16Audio {
    require(wav.size >= 44) { "WAV file is too small" }
    require(wav.ascii(0, 4) == "RIFF" && wav.ascii(8, 12) == "WAVE") { "Unsupported WAV header" }

    var offset = 12
    var audioFormat = 0
    var channels = 0
    var sourceSampleRate = 0
    var bitsPerSample = 0
    var dataOffset = -1
    var dataSize = 0

    while (offset + 8 <= wav.size) {
        val chunkId = wav.ascii(offset, offset + 4)
        val chunkSize = wav.intLe(offset + 4)
        val chunkData = offset + 8
        require(chunkSize >= 0 && chunkData + chunkSize <= wav.size) { "Invalid WAV chunk size" }
        when (chunkId) {
            "fmt " -> {
                require(chunkSize >= 16) { "Invalid WAV fmt chunk" }
                audioFormat = wav.shortLe(chunkData)
                channels = wav.shortLe(chunkData + 2)
                sourceSampleRate = wav.intLe(chunkData + 4)
                bitsPerSample = wav.shortLe(chunkData + 14)
            }
            "data" -> {
                dataOffset = chunkData
                dataSize = chunkSize
            }
        }
        offset = chunkData + chunkSize + (chunkSize and 1)
    }

    require(audioFormat == 1) { "Only PCM WAV is supported" }
    require(channels > 0) { "WAV channel count is missing" }
    require(sourceSampleRate > 0) { "WAV sample rate is missing" }
    require(bitsPerSample == 16) { "Only 16-bit PCM WAV is supported" }
    require(dataOffset >= 0 && dataSize > 0) { "WAV data chunk is missing" }

    val frameSize = channels * 2
    val frameCount = dataSize / frameSize
    val samples = ShortArray(frameCount)
    var read = dataOffset
    for (i in 0 until frameCount) {
        samples[i] = wav.shortLeSigned(read)
        read += frameSize
    }

    if (sourceSampleRate == targetSampleRate) return Pcm16Audio(samples, sourceSampleRate)
    return Pcm16Audio(resampleLinear(samples, sourceSampleRate, targetSampleRate), targetSampleRate)
}

private fun resampleLinear(samples: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
    if (samples.isEmpty() || sourceRate == targetRate) return samples
    val outputSize = ((samples.size.toLong() * targetRate) / sourceRate).toInt().coerceAtLeast(1)
    val out = ShortArray(outputSize)
    val ratio = sourceRate.toDouble() / targetRate.toDouble()
    for (i in out.indices) {
        val pos = i * ratio
        val left = pos.toInt().coerceAtMost(samples.lastIndex)
        val right = (left + 1).coerceAtMost(samples.lastIndex)
        val fraction = pos - left
        val value = samples[left] * (1.0 - fraction) + samples[right] * fraction
        out[i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return out
}

private fun ByteArray.ascii(start: Int, end: Int): String =
    copyOfRange(start, end).toString(Charsets.US_ASCII)

private fun ByteArray.intLe(offset: Int): Int =
    ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

private fun ByteArray.shortLe(offset: Int): Int =
    ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

private fun ByteArray.shortLeSigned(offset: Int): Short =
    ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
