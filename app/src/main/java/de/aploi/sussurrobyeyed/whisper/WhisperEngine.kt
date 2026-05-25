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
     * Wall-clock breakdown of the most recent [transcribe] call, in
     * milliseconds. Useful for debugging where time goes (encoder vs decoder
     * is the most informative split).
     *
     * @property sampleMs sampling logic
     * @property encodeMs encoder pass — whisper's bulk cost; scales with audio
     *   length rounded up to 30 s windows.
     * @property decodeMs decoder pass — scales with output token count.
     * @property batchdMs batched decode (used by some sampling modes).
     * @property promptMs prompt processing.
     */
    data class Timings(
        val sampleMs: Float,
        val encodeMs: Float,
        val decodeMs: Float,
        val batchdMs: Float,
        val promptMs: Float,
    ) {
        val totalMs: Float get() = sampleMs + encodeMs + decodeMs + batchdMs + promptMs
    }

    /**
     * Read whisper.cpp's per-stage timings from the most recent transcription.
     * Returns null if no transcription has run, or if the engine is released.
     */
    suspend fun lastTimings(): Timings? {
        if (released) return null
        return mutex.withLock {
            withContext(dispatcher) {
                if (ctxPtr == 0L) return@withContext null
                val raw = WhisperLib.nativeLastTimings(ctxPtr) ?: return@withContext null
                if (raw.size < 5) return@withContext null
                Timings(
                    sampleMs = raw[0],
                    encodeMs = raw[1],
                    decodeMs = raw[2],
                    batchdMs = raw[3],
                    promptMs = raw[4],
                )
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
         * @param modelFile the ggml `.bin` to load.
         * @param nativeLibDir the app's native library directory (from
         *   `applicationContext.applicationInfo.nativeLibraryDir`). Required
         *   on arm64-v8a so ggml can dlopen the right CPU backend variant.
         * @throws IllegalStateException if the model file is missing or fails to load.
         */
        fun load(modelFile: File, nativeLibDir: String): WhisperEngine {
            check(modelFile.isFile) { "Whisper model not found: ${modelFile.absolutePath}" }
            val ptr = WhisperLib.nativeInit(modelFile.absolutePath, nativeLibDir)
            if (ptr == 0L) {
                Log.e(TAG, "nativeInit returned 0 for ${modelFile.absolutePath} (libDir=$nativeLibDir)")
                throw IllegalStateException("Failed to load Whisper model")
            }
            return WhisperEngine(ptr)
        }

        /**
         * Reasonable default thread count for whisper.cpp on Android.
         *
         * The big trap on modern phones is **heterogeneous cores**: a
         * Snapdragon 8 Gen 2 has 1× Cortex-X3 + 4× Cortex-A715/A710 +
         * 3× Cortex-A510. The A510 efficiency cores run roughly 3× slower
         * than the perf cores, so spilling into them with a uniform thread
         * pool stalls the fast cores waiting for the slow ones. We cap at
         * 4 to keep ggml's worker threads on the prime + perf cluster
         * (which is 4 cores wide on most flagships and 3-4 on mid-range).
         *
         * Older / budget devices simply use what they have.
         */
        fun defaultThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return cores.coerceIn(2, 4)
        }
    }
}
