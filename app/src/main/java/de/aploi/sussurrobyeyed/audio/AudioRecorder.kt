package de.aploi.sussurrobyeyed.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 16 kHz mono PCM audio recorder for Whisper.
 *
 * Whisper expects mono float audio at exactly 16 kHz in the range [-1.0, 1.0].
 * We grab 16-bit PCM from [AudioRecord] (the format every modern Android
 * supports) and convert as we go.
 *
 * The recorder is exposed as a state machine via [state]; while running it
 * emits per-buffer RMS values to [rms] so the capsule UI can render bars.
 * Stopping returns the collected samples in one [FloatArray].
 */
class AudioRecorder {

    enum class State { Idle, Recording, Stopped, Error }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms.asStateFlow()

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private val buffer = ArrayList<Short>(SAMPLE_RATE * 8) // pre-allocate ~8s

    /**
     * Begin recording. Caller is responsible for holding RECORD_AUDIO permission.
     *
     * @throws SecurityException if the permission has been revoked.
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (_state.value == State.Recording) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize returned $minBuf")
            _state.value = State.Error
            return
        }
        // Bump the OS buffer up so we never lose audio between reads.
        val bufBytes = (minBuf * 4).coerceAtLeast(SAMPLE_RATE * 2 / 2) // ~0.5s

        // Prefer UNPROCESSED (no AGC, no noise gate) — VOICE_RECOGNITION on
        // some OEMs (notably Xiaomi/MIUI) gates aggressively and we end up
        // with near-silent audio that Whisper can't transcribe. Fall back if
        // the device refuses to give us an unprocessed stream.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )
        var ar: AudioRecord? = null
        for (src in sources) {
            val candidate = try {
                AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord ctor failed for source=$src", t)
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "using AudioSource=$src")
                ar = candidate
                break
            }
            candidate?.release()
        }
        if (ar == null) {
            Log.e(TAG, "no AudioRecord source initialised")
            _state.value = State.Error
            return
        }

        recorder = ar
        buffer.clear()
        _rms.value = 0f
        _state.value = State.Recording
        ar.startRecording()

        // Our reader buffer = ~64 ms @ 16 kHz. Smaller -> snappier RMS updates,
        // larger -> fewer syscalls. 1024 samples is a sweet spot.
        val readBuf = ShortArray(READ_BUF_SAMPLES)

        captureJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && _state.value == State.Recording) {
                    val n = ar.read(readBuf, 0, readBuf.size)
                    if (n <= 0) {
                        // AudioRecord can transiently return 0; back off briefly.
                        continue
                    }
                    synchronized(buffer) {
                        for (i in 0 until n) buffer.add(readBuf[i])
                    }
                    _rms.value = rms(readBuf, n)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "capture loop crashed", t)
                _state.value = State.Error
            }
        }
    }

    /**
     * Stop recording and return the collected audio normalised to mono float
     * samples in the range [-1.0, 1.0]. If at least [MAX_DURATION_SECONDS] of
     * audio has been recorded, the result is truncated to that.
     *
     * Safe to call from any context; will suspend until the capture loop is
     * fully shut down so the [FloatArray] is consistent.
     */
    suspend fun stopAndCollect(): FloatArray {
        if (_state.value != State.Recording) return FloatArray(0)
        _state.value = State.Stopped
        captureJob?.cancelAndJoin()
        captureJob = null

        recorder?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // recorder was never really started; ignore
            }
            it.release()
        }
        recorder = null
        _rms.value = 0f

        val collected: ShortArray = synchronized(buffer) {
            val n = min(buffer.size, MAX_DURATION_SECONDS * SAMPLE_RATE)
            ShortArray(n) { i -> buffer[i] }
        }
        buffer.clear()

        // Whisper wants float [-1, 1]. We also peak-normalise here: many
        // phones (Xiaomi/MIUI in particular) hand back surprisingly quiet
        // audio even from VOICE_RECOGNITION/UNPROCESSED, and Whisper will
        // emit empty transcripts on essentially silent input. Apply gain to
        // bring the loudest sample up to TARGET_PEAK, capped at MAX_GAIN so
        // background hiss can't blow up.
        val raw = FloatArray(collected.size)
        var peak = 0f
        for (i in collected.indices) {
            val v = collected[i] / 32768f
            raw[i] = v
            val a = if (v < 0f) -v else v
            if (a > peak) peak = a
        }
        val gain = if (peak > 0f) (TARGET_PEAK / peak).coerceAtMost(MAX_GAIN) else 1f
        if (gain != 1f) {
            for (i in raw.indices) {
                val g = raw[i] * gain
                raw[i] = when {
                    g > 1f -> 1f
                    g < -1f -> -1f
                    else -> g
                }
            }
        }
        Log.i(TAG, "stopAndCollect: ${raw.size} samples, rawPeak=${"%.3f".format(peak)} gain=${"%.2f".format(gain)}")

        debugWavDir?.let { dir ->
            try {
                writeWav(File(dir, "last_capture.wav"), raw, SAMPLE_RATE)
                Log.i(TAG, "wav dumped to ${dir.absolutePath}/last_capture.wav")
            } catch (t: Throwable) {
                Log.w(TAG, "wav dump failed", t)
            }
        }

        _state.value = State.Idle
        return raw
    }

    /** When non-null, [stopAndCollect] writes the captured audio here as a WAV. Debug only. */
    @Volatile var debugWavDir: File? = null

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        // 16-bit PCM mono WAV. We re-quantise from float so the output file
        // matches what whisper.cpp actually sees (modulo the float→short round).
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = samples[i]
            pcm[i] = when {
                v >= 1f -> Short.MAX_VALUE
                v <= -1f -> Short.MIN_VALUE
                else -> (v * 32767f).toInt().toShort()
            }
        }
        val byteRate = sampleRate * 2
        val dataSize = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                  // PCM chunk size
            putShort(1)                 // PCM format
            putShort(1)                 // mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2)                 // block align
            putShort(16)                // bits/sample
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) body.putShort(s)
        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(body.array())
        }
    }

    fun cancel() {
        if (_state.value != State.Recording) return
        captureJob?.cancel()
        captureJob = null
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
        buffer.clear()
        _rms.value = 0f
        _state.value = State.Idle
    }

    private fun rms(samples: ShortArray, length: Int): Float {
        if (length == 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        return sqrt(sum / length).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_SECONDS = 60 // ASR sweet spot; Whisper handles up to 30s windows internally.
        private const val READ_BUF_SAMPLES = 1024
        // Target peak after normalisation. Half full-scale leaves headroom and
        // matches what real speech recordings typically look like.
        private const val TARGET_PEAK = 0.5f
        // Don't amplify by more than this — beyond ~20× we're just boosting
        // background noise rather than recovering speech.
        private const val MAX_GAIN = 20f
        private const val TAG = "AudioRecorder"
    }
}
