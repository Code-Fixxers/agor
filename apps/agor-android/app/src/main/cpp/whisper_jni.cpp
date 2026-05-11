#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "AgorVoice"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#if defined(HAVE_WHISPER)
extern "C" {
#include "whisper.h"
}

struct WhisperContext {
    struct whisper_context* ctx;
};
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_live_agor_app_voice_jni_WhisperJni_nativeInitFromFile(
    JNIEnv* env, jobject /*thiz*/, jstring path) {
#if defined(HAVE_WHISPER)
    const char* p = env->GetStringUTFChars(path, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    auto ctx = whisper_init_from_file_with_params(p, cparams);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) {
        LOGW("whisper_init failed for %s", p);
        return 0;
    }
    auto* w = new WhisperContext();
    w->ctx = ctx;
    return reinterpret_cast<jlong>(w);
#else
    (void)env; (void)path;
    return 0;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_live_agor_app_voice_jni_WhisperJni_nativeTranscribe(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray pcm, jint sampleRate) {
#if defined(HAVE_WHISPER)
    if (handle == 0) return env->NewStringUTF("");
    auto* w = reinterpret_cast<WhisperContext*>(handle);

    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.translate = false;
    params.no_context = true;
    params.single_segment = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = "en";
    params.n_threads = 4;

    if (whisper_full(w->ctx, params, samples.data(), n) != 0) {
        LOGW("whisper_full failed");
        return env->NewStringUTF("");
    }

    std::string out;
    int segments = whisper_full_n_segments(w->ctx);
    for (int i = 0; i < segments; ++i) {
        const char* seg = whisper_full_get_segment_text(w->ctx, i);
        if (seg) out += seg;
    }
    return env->NewStringUTF(out.c_str());
#else
    (void)env; (void)handle; (void)pcm; (void)sampleRate;
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_live_agor_app_voice_jni_WhisperJni_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
#if defined(HAVE_WHISPER)
    if (handle == 0) return;
    auto* w = reinterpret_cast<WhisperContext*>(handle);
    if (w->ctx) whisper_free(w->ctx);
    delete w;
#else
    (void)handle;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_live_agor_app_voice_jni_WhisperJni_nativeAvailable(JNIEnv* /*env*/, jobject /*thiz*/) {
#if defined(HAVE_WHISPER)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
