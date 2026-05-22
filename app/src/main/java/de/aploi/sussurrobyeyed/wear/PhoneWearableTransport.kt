package de.aploi.sussurrobyeyed.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import de.aploi.sussurrobyeyed.shared.SessionState
import de.aploi.sussurrobyeyed.shared.SessionStateCodec
import de.aploi.sussurrobyeyed.shared.WearableProtocol
import kotlinx.coroutines.tasks.await

/**
 * Phone-side wrapper for the Wearable Data Layer. Mirror of the watch's
 * `WearableTransport` but with the message direction inverted: phone -> watch
 * state pings and the final transcript text.
 *
 * Stateless and cheap to construct; safe to reuse the same instance for the
 * lifetime of [WatchSessionService].
 */
internal class PhoneWearableTransport(context: Context) {

    private val appContext = context.applicationContext
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(appContext)

    /**
     * Resolve the watch node id we should publish state to. Uses the
     * `sussurro_watch` capability advertised by the wear app.
     */
    suspend fun findWatchNodeId(): String? {
        return try {
            val info = capabilityClient
                .getCapability(WearableProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                .await()
            info.nodes.firstOrNull { it.isNearby }?.id
                ?: info.nodes.firstOrNull()?.id
        } catch (t: Throwable) {
            Log.w(TAG, "findWatchNodeId failed", t)
            null
        }
    }

    suspend fun sendState(nodeId: String, state: SessionState, reason: String? = null): Boolean {
        val payload = SessionStateCodec.encode(state, reason)
        return sendMessage(nodeId, WearableProtocol.Paths.PHONE_STATE, payload)
    }

    suspend fun sendTranscript(nodeId: String, text: String): Boolean {
        return sendMessage(
            nodeId,
            WearableProtocol.Paths.PHONE_TRANSCRIPT,
            text.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Ask the watch to abandon the in-flight session. Used when the user
     * taps "Stop" on the phone's dictation notification — the watch's
     * recorder needs to know to release the mic, not just our local
     * end of the pipeline.
     */
    suspend fun sendCancel(nodeId: String): Boolean {
        return sendMessage(nodeId, WearableProtocol.Paths.PHONE_CANCEL, ByteArray(0))
    }

    private suspend fun sendMessage(
        node: String,
        path: String,
        payload: ByteArray,
    ): Boolean {
        return try {
            messageClient.sendMessage(node, path, payload).await()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMessage($path) failed", t)
            false
        }
    }

    companion object {
        private const val TAG = "PhoneWearableTransport"
    }
}
