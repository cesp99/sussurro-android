package de.aploi.sussurrobyeyed.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-singleton holder for the most recent transcript the phone
 * received from the watch this session.
 *
 * Lives outside [WatchPhoneSessionService] so the settings screen can
 * subscribe without having to bind to a foreground service that may not
 * even be running. Intentionally not persisted: the value resets when
 * the process dies, which keeps the UI honest about "what I last
 * dictated *in this session*" without needing user-visible storage of
 * their dictation.
 */
internal object LastWatchTranscript {

    private val _value = MutableStateFlow<String?>(null)
    val value: StateFlow<String?> = _value.asStateFlow()

    fun set(text: String) {
        _value.value = text
    }
}
