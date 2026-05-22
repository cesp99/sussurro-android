package de.aploi.sussurrobyeyed.shared

/**
 * Wire protocol shared between Sussurro's watch (:wear) and phone (:app)
 * surfaces.
 *
 * The transport is the Wearable Data Layer:
 *  - [Paths.AUDIO_CHANNEL] is opened as a [com.google.android.gms.wearable.ChannelClient]
 *    by the watch when the user starts recording. The watch streams raw 16-bit
 *    little-endian PCM at [AUDIO_SAMPLE_RATE] mono into the channel's output
 *    stream until the user taps stop, then closes it. The phone receives the
 *    channel via [Paths.AUDIO_CHANNEL] in its listener and reads audio bytes
 *    until EOF.
 *  - [Paths.SESSION_START] / [Paths.SESSION_STOP] / [Paths.SESSION_CANCEL]
 *    are short MessageClient pings the watch sends to nudge the phone to
 *    enter the right session state. They carry no payload.
 *  - [Paths.PHONE_STATE] is a MessageClient message sent from phone -> watch
 *    whenever the session state changes (idle / receiving / transcribing /
 *    committed / error). Payload is the [SessionState] ordinal in a single
 *    byte, optionally followed by a UTF-8 detail string.
 *  - [Paths.PHONE_TRANSCRIPT] carries the final transcribed text back to the
 *    watch (UTF-8 string) so the watch can briefly show what was captured.
 *
 * All paths begin with `/sussurro/` so the listeners can scope cheaply.
 */
object WearableProtocol {

    /** Mono PCM sample rate in Hz; matches [AudioRecorder] on the phone. */
    const val AUDIO_SAMPLE_RATE: Int = 16_000

    /** Bytes per sample in the streamed PCM: signed 16-bit little-endian. */
    const val AUDIO_BYTES_PER_SAMPLE: Int = 2

    /**
     * Watch-side capability advertised by the phone. The watch uses this name
     * with `CapabilityClient` to find which connected node is the phone — we
     * do not assume there is exactly one paired phone.
     */
    const val CAPABILITY_PHONE: String = "sussurro_phone"

    /** Capability the watch advertises so the phone can identify it. */
    const val CAPABILITY_WATCH: String = "sussurro_watch"

    object Paths {
        const val ROOT: String = "/sussurro"
        const val AUDIO_CHANNEL: String = "$ROOT/audio"
        const val SESSION_START: String = "$ROOT/session/start"
        const val SESSION_STOP: String = "$ROOT/session/stop"
        const val SESSION_CANCEL: String = "$ROOT/session/cancel"
        const val PHONE_STATE: String = "$ROOT/phone/state"
        const val PHONE_TRANSCRIPT: String = "$ROOT/phone/transcript"

        /**
         * Phone -> watch directive that the user has cancelled the
         * session from the phone side (e.g. tapped "Stop" on the
         * dictation notification). The watch should tear down its
         * recorder and channel as if the user had long-pressed the
         * watch screen. No payload.
         */
        const val PHONE_CANCEL: String = "$ROOT/phone/cancel"

        /**
         * Optional live RMS / amplitude updates phone -> watch (for the
         * waveform visualisation when the watch isn't measuring locally —
         * we currently measure on the watch, but reserving the path keeps
         * future evolution cheap).
         */
        const val PHONE_LEVEL: String = "$ROOT/phone/level"
    }
}

/**
 * High-level state of a watch ↔ phone dictation session.
 *
 * Ordering is part of the wire protocol: the ordinal is what we ship over
 * MessageClient. Do not reorder or insert non-trailing values.
 */
enum class SessionState {
    /** No session in flight. Watch should show its ambient idle UI. */
    Idle,

    /** Watch is recording and streaming PCM to phone. */
    Recording,

    /** Phone has stopped receiving and is running whisper.cpp. */
    Transcribing,

    /** Transcript was committed into the focused text field on the phone. */
    Committed,

    /** Something went wrong; payload string holds a short reason. */
    Error,
    ;

    companion object {
        /** Inverse of [Enum.ordinal]; safe against unknown ordinals. */
        fun fromOrdinal(o: Int): SessionState? = entries.getOrNull(o)
    }
}

/**
 * Tiny helper to (de)serialise [SessionState] + an optional UTF-8 reason
 * into the byte payload sent on [WearableProtocol.Paths.PHONE_STATE].
 *
 * Layout: `[stateOrdinal: 1 byte][optional UTF-8 reason]`
 */
object SessionStateCodec {

    fun encode(state: SessionState, reason: String? = null): ByteArray {
        val reasonBytes = reason?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val out = ByteArray(1 + reasonBytes.size)
        out[0] = state.ordinal.toByte()
        if (reasonBytes.isNotEmpty()) {
            System.arraycopy(reasonBytes, 0, out, 1, reasonBytes.size)
        }
        return out
    }

    /** Returns null if [bytes] is empty / has an unknown state ordinal. */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.isEmpty()) return null
        val state = SessionState.fromOrdinal(bytes[0].toInt() and 0xFF) ?: return null
        val reason = if (bytes.size > 1) {
            String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
        } else null
        return Decoded(state, reason)
    }

    data class Decoded(val state: SessionState, val reason: String?)
}
