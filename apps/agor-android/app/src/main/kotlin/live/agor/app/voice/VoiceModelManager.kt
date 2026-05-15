package live.agor.app.voice

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import okhttp3.OkHttpClient
import okhttp3.Request

class VoiceModelManager(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    fun whisperModelFile(): File = whisperModelFile(context)
    fun isWhisperModelReady(): Boolean = isReady(whisperModelFile())

    suspend fun downloadWhisperModel(): File = downloadModel(
        url = WHISPER_BASE_EN_URL,
        dest = whisperModelFile(),
        label = "Whisper base.en",
    )

    private suspend fun downloadModel(url: String, dest: File, label: String): File = withContext(Dispatchers.IO) {
        if (isReady(dest)) return@withContext dest
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.download")
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("$label download HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("$label download returned an empty body")
            tmp.outputStream().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }
        if (tmp.length() <= 0L) {
            tmp.delete()
            throw IOException("$label download produced an empty file")
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        AppLogger.log("$label model ready at ${dest.absolutePath}", LogLevel.INFO, "Voice")
        dest
    }

    private fun isReady(file: File): Boolean = file.exists() && file.length() > 0L

    companion object {
        private const val WHISPER_BASE_EN_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
        fun whisperModelFile(context: Context): File =
            File(context.filesDir, "voice-models/whisper/ggml-base.en.bin")
    }
}
