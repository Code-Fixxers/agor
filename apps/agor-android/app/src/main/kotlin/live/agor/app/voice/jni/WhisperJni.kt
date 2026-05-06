package live.agor.app.voice.jni

/**
 * Thin JNI binding for whisper.cpp. The native library is named `agor_voice` and
 * is built by app/src/main/cpp/CMakeLists.txt. If whisper.cpp is not vendored, the
 * native library still loads but `nativeAvailable()` returns false — the caller
 * should use remote Whisper or treat local transcription as unavailable.
 */
class WhisperJni {

    private var handle: Long = 0L

    fun init(modelPath: String): Boolean {
        handle = nativeInitFromFile(modelPath)
        return handle != 0L
    }

    fun transcribe(pcm: FloatArray, sampleRate: Int): String {
        if (handle == 0L) return ""
        return nativeTranscribe(handle, pcm, sampleRate)
    }

    fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    fun isAvailable(): Boolean = nativeAvailable()

    private external fun nativeInitFromFile(path: String): Long
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, sampleRate: Int): String
    private external fun nativeFree(handle: Long)
    private external fun nativeAvailable(): Boolean

    companion object {
        @Volatile private var loaded = false

        fun loadNative(): Boolean {
            if (loaded) return true
            return try {
                System.loadLibrary("agor_voice")
                loaded = true
                true
            } catch (t: Throwable) {
                false
            }
        }
    }
}
