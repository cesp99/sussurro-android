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

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(ctxPtr: Long)
    external fun nativeTranscribe(
        ctxPtr: Long,
        audio: FloatArray,
        nThreads: Int,
        lang: String?,
        translate: Boolean,
    ): String

    external fun nativeSystemInfo(): String
}
