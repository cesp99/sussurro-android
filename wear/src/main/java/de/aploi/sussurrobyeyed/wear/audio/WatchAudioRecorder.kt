package de.aploi.sussurrobyeyed.wear.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import de.aploi.sussurrobyeyed.shared.WearableProtocol
import java.io.OutputStream
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
import kotlin.math.sqrt

/**
 * 16 kHz mono 16-bit PCM recorder for the watch.
 *
 * Mirrors the phone's `AudioRecorder` for capture parameters but is tailored
 * for **streaming**: instead of buffering everything in memory it pumps each
 * read directly into an [OutputStream] (typically a Wearable
 * `ChannelClient` output stream). RMS values are exposed as a [StateFlow] so
 * the watch UI can render the waveform without a phone round-trip.
 */
class WatchAudioRecorder {

    enum class State { Idle, Recording, Stopped, Error }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms.asStateFlow()

    /** Number of audio bytes (raw PCM) successfully streamed in this session. */
    private val _bytesStreamed = MutableStateFlow(0L)
    val bytesStreamed: StateFlow<Long> = _bytesStreamed.asStateFlow()

    /**
     * True when the most recent session hit [MAX_DURATION_SECONDS] and we
     * cut the recorder ourselves rather than the user tapping stop. The
     * UI / controller can use this to flash a "max length reached" hint
     * and to nudge the phone session to actually transcribe what we have.
     */
    private val _hitMaxDuration = MutableStateFlow(false)
    val hitMaxDuration: StateFlow<Boolean> = _hitMaxDuration.asStateFlow()

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    /**
     * Start recording and stream raw little-endian 16-bit PCM into [sink].
     * The caller owns [sink] and is responsible for closing it after the
     * recorder reports [State.Stopped] or [State.Error]. We never close it
     * ourselves — closing the Wearable channel from inside the recorder
     * would race the consumer.
     *
     * @throws SecurityException if RECORD_AUDIO has been revoked.
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, sink: OutputStream) {
        if (_state.value == State.Recording) return

        val minBuf = AudioRecord.getMinBufferSize(
            WearableProtocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize returned $minBuf")
            _state.value = State.Error
            return
        }
        // ~0.5 s of buffering at the OS level keeps us safe across BT
        // hiccups when the channel writer is slow.
        val bufBytes = (minBuf * 4)
            .coerceAtLeast(WearableProtocol.AUDIO_SAMPLE_RATE)

        // Watches don't usually expose UNPROCESSED, so VOICE_RECOGNITION ->
        // MIC fallback is the realistic chain.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        var ar: AudioRecord? = null
        for (src in sources) {
            val candidate = try {
                AudioRecord(
                    src,
                    WearableProtocol.AUDIO_SAMPLE_RATE,
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
        _bytesStreamed.value = 0L
        _rms.value = 0f
        _hitMaxDuration.value = false
        _state.value = State.Recording
        ar.startRecording()

        val readBuf = ShortArray(READ_BUF_SAMPLES)
        // Re-used scratch space for the little-endian byte conversion. This
        // lives outside the read loop so we don't allocate per buffer; on
        // wear silicon every allocation hurts.
        val scratch = ByteBuffer
            .allocate(readBuf.size * WearableProtocol.AUDIO_BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)

        captureJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && _state.value == State.Recording) {
                    val n = ar.read(readBuf, 0, readBuf.size)
                    if (n <= 0) continue

                    scratch.clear()
                    for (i in 0 until n) scratch.putShort(readBuf[i])
                    val bytes = scratch.position()
                    sink.write(scratch.array(), 0, bytes)
                    // Don't flush every iteration — Wearable channels handle
                    // their own buffering and frequent flushes thrash BT.

                    _bytesStreamed.value += bytes
                    _rms.value = rms(readBuf, n)

                    // Self-cap at MAX_DURATION_SECONDS so a stuck UI / lost
                    // phone session doesn't end up streaming arbitrary
                    // amounts of PCM over BT. We flip the recorder to
                    // Stopped here and let the controller observe the
                    // transition; it'll then close the channel and ask
                    // the phone to transcribe what it has.
                    if (_bytesStreamed.value >= MAX_BYTES) {
                        Log.i(TAG, "hit MAX_DURATION_SECONDS, auto-stopping")
                        _hitMaxDuration.value = true
                        _state.value = State.Stopped
                        break
                    }
                }
                runCatching { sink.flush() }
            } catch (t: Throwable) {
                Log.e(TAG, "capture loop crashed", t)
                _state.value = State.Error
            }
        }
    }

    /**
     * Stop the capture loop and release the [AudioRecord]. Safe to call
     * repeatedly and idempotent across both the user-initiated stop path
     * and the recorder's own self-cap at [MAX_DURATION_SECONDS] (which
     * already flipped [state] to [State.Stopped] from the capture loop).
     */
    suspend fun stop() {
        val current = _state.value
        if (current != State.Recording && current != State.Stopped) return
        // If we got here via the user-initiated path we still need to
        // flip the flag so the capture loop bails out; the self-cap
        // path will have already done this.
        _state.value = State.Stopped
        captureJob?.cancelAndJoin()
        captureJob = null
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
        _rms.value = 0f
        _state.value = State.Idle
    }

    fun cancel() {
        if (_state.value != State.Recording && _state.value != State.Stopped) return
        captureJob?.cancel()
        captureJob = null
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
        _bytesStreamed.value = 0L
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
        private const val TAG = "WatchAudioRecorder"
        private const val READ_BUF_SAMPLES = 1024

        /**
         * Hard ceiling on a single dictation session. Mirrors the phone's
         * `AudioRecorder.MAX_DURATION_SECONDS` so behaviour stays
         * consistent between the in-app keyboard capsule and the watch.
         */
        const val MAX_DURATION_SECONDS: Int = 60

        private const val MAX_BYTES: Long =
            MAX_DURATION_SECONDS.toLong() *
                WearableProtocol.AUDIO_SAMPLE_RATE.toLong() *
                WearableProtocol.AUDIO_BYTES_PER_SAMPLE.toLong()
    }
}
