// Sussurro by Eyed — whisper.cpp JNI bridge.
//
// This is the only piece of C++ code in the project. Everything else is in
// Kotlin. The shim is intentionally tiny: load a model, run greedy
// transcription on a float32 audio buffer, hand back the joined text.

#include <android/log.h>
#include <jni.h>
#include <cmath>
#include <cstring>
#include <string>

#include "whisper.h"
#include "ggml-backend.h"

#define TAG "sussurro-whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// On arm64-v8a we build ggml with GGML_BACKEND_DL, so the CPU backend lives
// in one of two `libggml-cpu-android_armv*.so` MODULE files alongside our
// own .so in the app's nativeLibraryDir (e.g.
// `/data/app/<pkg>-<uid>/lib/arm64-v8a/`). We get the directory passed in
// from Kotlin (`applicationContext.applicationInfo.nativeLibraryDir`)
// rather than deriving it from `dladdr`, because `dladdr` on a lib that
// was loaded directly from inside an APK returns a virtual path like
// `/.../base.apk!/lib/arm64-v8a/libsussurro-whisper.so` that filesystem
// APIs can't enumerate.
//
// armeabi-v7a uses the statically-linked CPU backend so the dir is unused.
static void load_best_cpu_backend_once(const char * dir) {
#ifdef SUSSURRO_USE_BACKEND_DL
    static bool loaded = false;
    if (loaded) return;
    if (dir == nullptr || dir[0] == '\0') {
        LOGE("nativeLibDir is empty — cannot load ggml CPU backend variants");
        return;
    }
    loaded = true;
    LOGI("loading ggml CPU backend variants from %s", dir);
    ggml_backend_load_all_from_path(dir);
#else
    (void) dir;
#endif
}

// Route whisper.cpp / ggml log output through Android's log so we can debug
// from logcat. Without this, whisper.cpp prints to stderr which is silently
// discarded by the runtime.
static void whisper_log_to_android(ggml_log_level level, const char* text, void* /*user*/) {
    if (text == nullptr) return;
    // Strip trailing newlines — whisper.cpp adds them, but Android log adds
    // its own, so doubled newlines look ugly.
    std::string s(text);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    if (s.empty()) return;
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_VERBOSE; break;
    }
    __android_log_print(prio, TAG, "%s", s.c_str());
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_de_aploi_sussurrobyeyed_whisper_WhisperLib_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jstring modelPath, jstring nativeLibDir) {

    // Install logging hooks once. Both whisper and ggml have their own setters.
    whisper_log_set(whisper_log_to_android, nullptr);
    ggml_log_set(whisper_log_to_android, nullptr);

    // Load the best CPU backend variant for this device (arm64-v8a only;
    // a no-op when the backend is statically linked into our .so).
    const char* libDirChars =
        (nativeLibDir != nullptr) ? env->GetStringUTFChars(nativeLibDir, nullptr) : nullptr;
    load_best_cpu_backend_once(libDirChars);
    if (libDirChars != nullptr) {
        env->ReleaseStringUTFChars(nativeLibDir, libDirChars);
    }

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("loading model from %s", path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    // Flash attention historically produced empty/garbled output on some ARM
    // CPUs in v1.8.4 — the most likely root cause was the fp16 ABI mismatch
    // between whisper.cpp and ggml when -march flags were applied to only
    // one half of the build. With GGML_BACKEND_DL each ggml-cpu variant is
    // an independent dlopen-ed module with consistent flags internally, so
    // there's nothing for our target to clash with. Turn it on for the
    // ~10-20% attention-layer speedup.
    cparams.flash_attn = true;

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params returned null");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_de_aploi_sussurrobyeyed_whisper_WhisperLib_nativeFree(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ctxPtr) {

    if (ctxPtr == 0) return;
    whisper_free(reinterpret_cast<whisper_context*>(ctxPtr));
}

JNIEXPORT jstring JNICALL
Java_de_aploi_sussurrobyeyed_whisper_WhisperLib_nativeTranscribe(
    JNIEnv* env, jobject /*thiz*/,
    jlong ctxPtr, jfloatArray audio, jint nThreads, jstring lang, jboolean translate) {

    if (ctxPtr == 0) {
        return env->NewStringUTF("");
    }
    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);

    jfloat* data = env->GetFloatArrayElements(audio, nullptr);
    const jsize length = env->GetArrayLength(audio);

    // Mirror the official whisper.android sample's call pattern exactly —
    // we've been chasing zero-segment output for a while and the only
    // confirmed-working Android reference is that sample. print_realtime is
    // ON so decoded text shows up in logcat as it's produced, helping us see
    // what (if anything) the decoder is generating.
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = true;
    params.print_progress   = false;
    params.print_timestamps = true;
    params.print_special    = false;
    params.translate        = translate == JNI_TRUE;
    params.n_threads        = (int) nThreads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;

    // Language: empty / "auto" => let whisper.cpp pick (it runs language
    // detection internally when params.language is null/empty and then
    // continues with transcription). We must NEVER set detect_language=true
    // here: with that flag whisper_full returns 0 immediately after detection
    // *without* decoding any segments, which looks exactly like a silent
    // transcript and was the original "model not transcribing" bug.
    const char* langChars = (lang != nullptr) ? env->GetStringUTFChars(lang, nullptr) : nullptr;
    std::string langOwned;
    if (langChars != nullptr && langChars[0] != '\0' && std::string(langChars) != "auto") {
        langOwned = langChars;
        params.language = langOwned.c_str();
    } else {
        params.language = nullptr;
    }
    params.detect_language = false;

    // Quick audio sanity check: peak amplitude and crude RMS. If the buffer is
    // ~silent Whisper will just return blank, which looks like a bug.
    float peak = 0.0f;
    double sumSq = 0.0;
    for (jsize i = 0; i < length; ++i) {
        const float s = data[i];
        const float a = s < 0 ? -s : s;
        if (a > peak) peak = a;
        sumSq += (double) s * s;
    }
    const double rmsAmp = length > 0 ? std::sqrt(sumSq / length) : 0.0;
    LOGI("transcribing %d samples on %d threads (lang=%s) peak=%.3f rms=%.3f",
         (int) length, (int) nThreads, params.language ? params.language : "auto",
         peak, rmsAmp);
    // First and last few samples — sanity-check the float buffer crossed JNI intact.
    if (length >= 8) {
        LOGI("samples[0..7] = %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f",
             data[0], data[1], data[2], data[3], data[4], data[5], data[6], data[7]);
        LOGI("samples[end-8..end-1] = %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f",
             data[length-8], data[length-7], data[length-6], data[length-5],
             data[length-4], data[length-3], data[length-2], data[length-1]);
    }

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, data, length);
    whisper_print_timings(ctx);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    if (langChars != nullptr) env->ReleaseStringUTFChars(lang, langChars);

    if (rc != 0) {
        LOGE("whisper_full failed: rc=%d", rc);
        return env->NewStringUTF("");
    }

    const int nSegments = whisper_full_n_segments(ctx);
    LOGI("whisper_full returned %d segments", nSegments);
    std::string out;
    out.reserve(256);
    for (int i = 0; i < nSegments; ++i) {
        const char* seg = whisper_full_get_segment_text(ctx, i);
        if (seg) {
            LOGI("seg[%d] (%d chars): %s", i, (int) strlen(seg), seg);
            out.append(seg);
        } else {
            LOGW("seg[%d] is null", i);
        }
    }

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_de_aploi_sussurrobyeyed_whisper_WhisperLib_nativeSystemInfo(
    JNIEnv* env, jobject /*thiz*/) {
    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "");
}

// Returns [sample_ms, encode_ms, decode_ms, batchd_ms, prompt_ms] for the
// most recent transcription on `ctxPtr`, or null if unavailable. Used by
// the dev screen to diagnose where time is being spent.
JNIEXPORT jfloatArray JNICALL
Java_de_aploi_sussurrobyeyed_whisper_WhisperLib_nativeLastTimings(
    JNIEnv* env, jobject /*thiz*/, jlong ctxPtr) {

    if (ctxPtr == 0) return nullptr;
    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);
    whisper_timings* t = whisper_get_timings(ctx);
    if (t == nullptr) return nullptr;

    jfloat values[5] = {
        t->sample_ms,
        t->encode_ms,
        t->decode_ms,
        t->batchd_ms,
        t->prompt_ms,
    };
    jfloatArray out = env->NewFloatArray(5);
    if (out == nullptr) return nullptr;
    env->SetFloatArrayRegion(out, 0, 5, values);
    return out;
}

} // extern "C"
