package de.aploi.sussurrobyeyed.whisper

/**
 * Raw JNI surface to whisper.cpp. Don't call these from app code — use
 * [WhisperEngine] instead, which handles threading and lifecycle.
 *
 * `ctxPtr` is an opaque `whisper_context*` from native; treat it as a token.
 * Calling any method with a stale pointer is undefined behaviour and will
 * almost certainly crash.
 */
internal object WhisperLib {
    init {
        System.loadLibrary("sussurro-whisper")
    }

    /**
     * @param modelPath absolute path to the ggml `.bin` file on disk.
     * @param nativeLibDir absolute path to the app's `nativeLibraryDir` (use
     *   `applicationContext.applicationInfo.nativeLibraryDir`). On arm64-v8a
     *   we ship multiple `libggml-cpu-android_*.so` variants and ggml needs
     *   to dlopen the best one at runtime; the dir tells it where to look.
     *   Ignored on armeabi-v7a (CPU backend is statically linked there).
     */
    external fun nativeInit(modelPath: String, nativeLibDir: String): Long
    external fun nativeFree(ctxPtr: Long)
    external fun nativeTranscribe(
        ctxPtr: Long,
        audio: FloatArray,
        nThreads: Int,
        lang: String?,
        translate: Boolean,
    ): String

    external fun nativeSystemInfo(): String

    /**
     * Last transcription's wall-clock breakdown, layout matching whisper.cpp's
     * `whisper_timings`:
     *   [0] sample_ms — sampler time per generation
     *   [1] encode_ms — encoder pass(es)
     *   [2] decode_ms — decoder pass(es)
     *   [3] batchd_ms — batched decode
     *   [4] prompt_ms — prompt processing
     *
     * Returns null when no transcription has run yet, or the context is freed.
     */
    external fun nativeLastTimings(ctxPtr: Long): FloatArray?
}
