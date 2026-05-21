package de.aploi.sussurrobyeyed.whisper

import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thread-safe wrapper around the [WhisperLib] JNI surface.
 *
 * whisper.cpp's context is *not* safe to touch from more than one thread, so
 * we pin all native interactions to a single-threaded dispatcher and gate
 * concurrent transcriptions behind a [Mutex].
 *
 * Construct via [WhisperEngine.load] — failures throw a clean exception
 * instead of crashing in JNI.
 */
class WhisperEngine private constructor(
    private var ctxPtr: Long,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "whisper-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val mutex = Mutex()

    @Volatile private var released = false

    /**
     * Transcribe a mono float32 PCM buffer at 16 kHz.
     *
     * @param audio Raw samples in [-1.0, 1.0].
     * @param language ISO 639-1 code (e.g. "en"), or null / "auto" for autodetect.
     * @param translate true to translate the transcript to English, false to keep the source language.
     * @param threads Worker threads; clamp to a sane value (see [defaultThreadCount]).
     */
    suspend fun transcribe(
        audio: FloatArray,
        language: String? = null,
        translate: Boolean = false,
        threads: Int = defaultThreadCount(),
    ): String {
        if (released) throw IllegalStateException("WhisperEngine already released")
        if (audio.isEmpty()) return ""

        return mutex.withLock {
            withContext(dispatcher) {
                WhisperLib.nativeTranscribe(
                    ctxPtr = ctxPtr,
                    audio = audio,
                    nThreads = threads.coerceIn(1, 8),
                    lang = language,
                    translate = translate,
                ).trim()
            }
        }
    }

    /**
     * Free the underlying whisper.cpp context. After release the engine cannot
     * be used again. Safe to call multiple times.
     */
    suspend fun release() {
        if (released) return
        released = true
        mutex.withLock {
            withContext(dispatcher) {
                if (ctxPtr != 0L) {
                    WhisperLib.nativeFree(ctxPtr)
                    ctxPtr = 0L
                }
            }
        }
        dispatcher.close()
    }

    @Suppress("ProtectedInFinal")
    protected fun finalize() {
        if (!released) {
            // We're being collected without an explicit release(). Try our best.
            runBlocking { release() }
        }
    }

    companion object {
        private const val TAG = "WhisperEngine"

        /**
         * Load a whisper.cpp model from disk.
         *
         * @throws IllegalStateException if the model file is missing or fails to load.
         */
        fun load(modelFile: File): WhisperEngine {
            check(modelFile.isFile) { "Whisper model not found: ${modelFile.absolutePath}" }
            val ptr = WhisperLib.nativeInit(modelFile.absolutePath)
            if (ptr == 0L) {
                Log.e(TAG, "nativeInit returned 0 for ${modelFile.absolutePath}")
                throw IllegalStateException("Failed to load Whisper model")
            }
            return WhisperEngine(ptr)
        }

        /**
         * Reasonable default thread count for whisper.cpp on Android.
         *
         * Whisper's encoder is the dominant cost and parallelises well up to
         * the number of physical performance cores. Recent flagship SoCs have
         * 8 cores (1 prime + 3 perf + 4 efficiency); using 6 leaves room for
         * the rest of the system while keeping the encoder fast. Older /
         * budget devices simply use what they have.
         */
        fun defaultThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return cores.coerceIn(2, 6)
        }
    }
}
