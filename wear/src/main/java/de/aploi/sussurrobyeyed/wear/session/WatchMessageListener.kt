package de.aploi.sussurrobyeyed.wear.session

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.aploi.sussurrobyeyed.shared.SessionStateCodec
import de.aploi.sussurrobyeyed.shared.WearableProtocol

/**
 * Receives state updates and the final transcript from the phone.
 *
 * The phone publishes:
 *  - [WearableProtocol.Paths.PHONE_STATE] — encoded with [SessionStateCodec]
 *  - [WearableProtocol.Paths.PHONE_TRANSCRIPT] — UTF-8 string
 *
 * Anything else under `/sussurro/phone` is ignored.
 */
class WatchMessageListener : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearableProtocol.Paths.PHONE_STATE -> {
                val decoded = SessionStateCodec.decode(event.data)
                if (decoded == null) {
                    Log.w(TAG, "PHONE_STATE arrived with empty/invalid payload")
                    return
                }
                Log.i(TAG, "phone -> ${decoded.state} (${decoded.reason ?: "no reason"})")
                WatchSessionController.updatePhoneState(decoded.state, decoded.reason)
            }
            WearableProtocol.Paths.PHONE_TRANSCRIPT -> {
                val text = event.data.toString(Charsets.UTF_8)
                Log.i(TAG, "phone transcript (${text.length} chars)")
                WatchSessionController.updateLastTranscript(text)
            }
            WearableProtocol.Paths.PHONE_CANCEL -> {
                // The user tapped "Stop" on the phone notification. Tear
                // down our local recorder + channel as if they'd long-
                // pressed the watch screen; the controller's cancel()
                // already publishes the right state transitions.
                Log.i(TAG, "phone -> cancel")
                WatchSessionController.cancelInBackground(applicationContext)
            }
            else -> {
                Log.v(TAG, "ignored message: ${event.path}")
            }
        }
    }

    companion object {
        private const val TAG = "WatchMessageListener"
    }
}
