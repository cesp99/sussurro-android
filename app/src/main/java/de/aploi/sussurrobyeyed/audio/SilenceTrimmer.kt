package de.aploi.sussurrobyeyed.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based silence trimmer.
 *
 * Whisper's encoder always pads its input to multiples of 30 s of mel
 * frames, so dead air at the start or end of a capture wastes inference
 * time. We chunk the input into short windows, compute RMS per window, pick
 * a threshold that's robust against quiet recordings, and chop everything
 * outside the first/last loud windows (with a small hangover so we don't
 * clip soft consonants).
 *
 * Internal silences inside speech are NEVER cut — only the head and tail
 * silence is trimmed. Cutting word gaps in the middle would corrupt the
 * decoder's language model.
 *
 * Returns rich metadata so the developer screen can visualise exactly what
 * got removed and which windows were classified as silence.
 */
object SilenceTrimmer {

    /**
     * Output of [trim].
     *
     * @param trimmed the buffer to feed to Whisper; empty when the input was
     *   pure silence.
     * @param originalSamples the input length, for convenience.
     * @param leadingSamplesCut samples removed from the start.
     * @param trailingSamplesCut samples removed from the end.
     * @param windowRms per-window RMS values over the original audio. Index
     *   `i` covers samples `[i*windowSizeSamples, (i+1)*windowSizeSamples)`.
     * @param windowSizeSamples the window size used; matches [windowRms].
     * @param rmsThreshold the threshold that separated silence from speech.
     * @param hangoverSamples how many samples of padding were kept on each
     *   side of the speech region.
     */
    data class Result(
        val trimmed: FloatArray,
        val originalSamples: Int,
        val leadingSamplesCut: Int,
        val trailingSamplesCut: Int,
        val windowRms: FloatArray,
        val windowSizeSamples: Int,
        val rmsThreshold: Float,
        val hangoverSamples: Int,
    ) {
        /** True when the trimmer rejected the whole capture as silence. */
        val isAllSilence: Boolean get() = trimmed.isEmpty() && originalSamples > 0

        // equals/hashCode aren't useful here (the arrays make sense by ref);
        // keep the data class but spell out a sensible string representation.
        override fun toString(): String =
            "TrimResult(orig=$originalSamples, kept=${trimmed.size}, " +
                "leadCut=$leadingSamplesCut, trailCut=$trailingSamplesCut, " +
                "threshold=$rmsThreshold, hangover=$hangoverSamples, " +
                "windows=${windowRms.size}@${windowSizeSamples}sa)"
    }

    /**
     * Trim leading and trailing silence from [audio].
     *
     * Defaults are tuned for 16 kHz Whisper input:
     *  - 20 ms RMS windows
     *  - 80 ms hangover on each side of the speech region
     *  - threshold = max(absoluteFloor, peakRms × relativeFloor)
     *
     * @param audio mono float PCM in [-1, 1].
     * @param sampleRate samples per second; defaults to 16 kHz.
     * @param windowMs RMS window length.
     * @param hangoverMs padding kept on either side of the detected speech.
     * @param relativeFloor fraction of the loudest window we treat as silence.
     *   0.10 ≈ -20 dB below peak — works well in practice for normalised
     *   captures.
     * @param absoluteFloor an absolute RMS floor below which the threshold is
     *   never allowed to drop, so we don't classify mic noise as speech.
     */
    fun trim(
        audio: FloatArray,
        sampleRate: Int = 16_000,
        windowMs: Int = 20,
        hangoverMs: Int = 80,
        relativeFloor: Float = 0.10f,
        absoluteFloor: Float = 0.004f,
    ): Result {
        if (audio.isEmpty()) {
            return Result(
                trimmed = FloatArray(0),
                originalSamples = 0,
                leadingSamplesCut = 0,
                trailingSamplesCut = 0,
                windowRms = FloatArray(0),
                windowSizeSamples = 0,
                rmsThreshold = 0f,
                hangoverSamples = 0,
            )
        }

        val windowSize = (sampleRate * windowMs / 1000).coerceAtLeast(1)
        val hangoverSamples = (sampleRate * hangoverMs / 1000).coerceAtLeast(0)
        val nWindows = audio.size / windowSize
        if (nWindows == 0) {
            // Capture is shorter than one window; treat as all-or-nothing
            // based on its overall RMS.
            val rms = overallRms(audio, 0, audio.size)
            val threshold = max(absoluteFloor, 0f)
            return if (rms >= threshold) {
                Result(
                    trimmed = audio,
                    originalSamples = audio.size,
                    leadingSamplesCut = 0,
                    trailingSamplesCut = 0,
                    windowRms = floatArrayOf(rms),
                    windowSizeSamples = audio.size,
                    rmsThreshold = threshold,
                    hangoverSamples = 0,
                )
            } else {
                Result(
                    trimmed = FloatArray(0),
                    originalSamples = audio.size,
                    leadingSamplesCut = audio.size,
                    trailingSamplesCut = 0,
                    windowRms = floatArrayOf(rms),
                    windowSizeSamples = audio.size,
                    rmsThreshold = threshold,
                    hangoverSamples = 0,
                )
            }
        }

        val windowRms = FloatArray(nWindows) { w ->
            overallRms(audio, w * windowSize, windowSize)
        }
        val peakRms = windowRms.fold(0f) { acc, v -> if (v > acc) v else acc }
        val threshold = max(absoluteFloor, peakRms * relativeFloor)

        // Find first / last window that exceeds threshold.
        var firstSpeech = -1
        for (w in windowRms.indices) {
            if (windowRms[w] >= threshold) {
                firstSpeech = w
                break
            }
        }
        if (firstSpeech < 0) {
            // No window above threshold → pure silence.
            return Result(
                trimmed = FloatArray(0),
                originalSamples = audio.size,
                leadingSamplesCut = audio.size,
                trailingSamplesCut = 0,
                windowRms = windowRms,
                windowSizeSamples = windowSize,
                rmsThreshold = threshold,
                hangoverSamples = hangoverSamples,
            )
        }
        var lastSpeech = firstSpeech
        for (w in windowRms.indices.reversed()) {
            if (windowRms[w] >= threshold) {
                lastSpeech = w
                break
            }
        }

        val speechStart = (firstSpeech * windowSize - hangoverSamples).coerceAtLeast(0)
        val speechEndExclusive = ((lastSpeech + 1) * windowSize + hangoverSamples)
            .coerceAtMost(audio.size)

        if (speechStart >= speechEndExclusive) {
            return Result(
                trimmed = FloatArray(0),
                originalSamples = audio.size,
                leadingSamplesCut = audio.size,
                trailingSamplesCut = 0,
                windowRms = windowRms,
                windowSizeSamples = windowSize,
                rmsThreshold = threshold,
                hangoverSamples = hangoverSamples,
            )
        }

        val trimmed = audio.copyOfRange(speechStart, speechEndExclusive)
        return Result(
            trimmed = trimmed,
            originalSamples = audio.size,
            leadingSamplesCut = speechStart,
            trailingSamplesCut = audio.size - speechEndExclusive,
            windowRms = windowRms,
            windowSizeSamples = windowSize,
            rmsThreshold = threshold,
            hangoverSamples = hangoverSamples,
        )
    }

    private fun overallRms(audio: FloatArray, start: Int, length: Int): Float {
        if (length <= 0) return 0f
        var sumSq = 0.0
        val end = start + length
        for (i in start until end) {
            val s = audio[i].toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / length).toFloat()
    }
}
