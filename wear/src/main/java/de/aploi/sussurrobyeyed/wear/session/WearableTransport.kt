package de.aploi.sussurrobyeyed.wear.session

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import de.aploi.sussurrobyeyed.shared.WearableProtocol
import kotlinx.coroutines.tasks.await

/**
 * Thin wrapper around the Wearable Data Layer used by the watch.
 *
 * Responsibilities:
 *  - Locate the paired phone via `CapabilityClient` (the phone advertises
 *    [WearableProtocol.CAPABILITY_PHONE]).
 *  - Open a [ChannelClient] audio channel and hand back its output stream.
 *  - Send short [MessageClient] pings (start/stop/cancel).
 *
 * Every call is suspending and lets the underlying Play Services [Task] do
 * its own threading; we don't pin to a dispatcher.
 */
internal class WearableTransport(context: Context) {

    private val appContext = context.applicationContext
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(appContext)
    private val channelClient: ChannelClient = Wearable.getChannelClient(appContext)

    /**
     * Resolve a single phone node id. Returns null if no paired phone is
     * currently advertising the [WearableProtocol.CAPABILITY_PHONE] capability
     * (e.g. phone is off, BT disconnected, or the phone app isn't installed).
     */
    suspend fun findPhoneNodeId(): String? {
        return try {
            val info = capabilityClient
                .getCapability(WearableProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
            info.nodes.firstOrNull { it.isNearby }?.id
                ?: info.nodes.firstOrNull()?.id
        } catch (t: Throwable) {
            Log.w(TAG, "findPhoneNodeId failed", t)
            null
        }
    }

    suspend fun openAudioChannel(phoneNodeId: String): ChannelClient.Channel? {
        return try {
            channelClient.openChannel(phoneNodeId, WearableProtocol.Paths.AUDIO_CHANNEL).await()
        } catch (t: Throwable) {
            Log.e(TAG, "openAudioChannel failed", t)
            null
        }
    }

    suspend fun outputStream(channel: ChannelClient.Channel): java.io.OutputStream? {
        return try {
            channelClient.getOutputStream(channel).await()
        } catch (t: Throwable) {
            Log.e(TAG, "getOutputStream failed", t)
            null
        }
    }

    suspend fun closeChannel(channel: ChannelClient.Channel) {
        runCatching { channelClient.close(channel).await() }
    }

    suspend fun sendStart(phoneNodeId: String): Boolean = sendMessage(phoneNodeId, WearableProtocol.Paths.SESSION_START)
    suspend fun sendStop(phoneNodeId: String): Boolean = sendMessage(phoneNodeId, WearableProtocol.Paths.SESSION_STOP)
    suspend fun sendCancel(phoneNodeId: String): Boolean = sendMessage(phoneNodeId, WearableProtocol.Paths.SESSION_CANCEL)

    private suspend fun sendMessage(node: String, path: String, payload: ByteArray = ByteArray(0)): Boolean {
        return try {
            messageClient.sendMessage(node, path, payload).await()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMessage($path) failed", t)
            false
        }
    }

    companion object {
        private const val TAG = "WearableTransport"
    }
}
