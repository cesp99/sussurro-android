package de.aploi.sussurrobyeyed.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.aploi.sussurrobyeyed.shared.WearableProtocol

/**
 * Phone-side endpoint of the Wearable Data Layer protocol.
 *
 * Two responsibilities:
 *  - When the watch opens an audio channel ([WearableProtocol.Paths.AUDIO_CHANNEL]),
 *    we hand it off to [WatchPhoneSessionService] which owns the recorder /
 *    Whisper pipeline.
 *  - Forward short MessageClient pings (start / stop / cancel) to the same
 *    service.
 */
class WatchPhoneListener : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearableProtocol.Paths.AUDIO_CHANNEL) {
            Log.v(TAG, "ignored channel ${channel.path}")
            return
        }
        Log.i(TAG, "audio channel opened from ${channel.nodeId}")
        // ChannelClient.Channel implements Parcelable, so we pass the Channel
        // instance through the intent directly — there's no public `token`
        // accessor on the GMS Channel type to rehydrate it on the other end.
        val intent = Intent(this, WatchPhoneSessionService::class.java).apply {
            action = WatchPhoneSessionService.ACTION_AUDIO_CHANNEL
            putExtra(WatchPhoneSessionService.EXTRA_NODE_ID, channel.nodeId)
            putExtra(WatchPhoneSessionService.EXTRA_CHANNEL, channel as android.os.Parcelable)
        }
        startForegroundService(intent)
    }

    override fun onChannelClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        Log.i(TAG, "channel closed reason=$closeReason err=$appSpecificErrorCode")
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearableProtocol.Paths.SESSION_START -> {
                val intent = Intent(this, WatchPhoneSessionService::class.java).apply {
                    action = WatchPhoneSessionService.ACTION_SESSION_START
                    putExtra(WatchPhoneSessionService.EXTRA_NODE_ID, event.sourceNodeId)
                }
                startForegroundService(intent)
            }
            WearableProtocol.Paths.SESSION_STOP -> {
                val intent = Intent(this, WatchPhoneSessionService::class.java).apply {
                    action = WatchPhoneSessionService.ACTION_SESSION_STOP
                    putExtra(WatchPhoneSessionService.EXTRA_NODE_ID, event.sourceNodeId)
                }
                startForegroundService(intent)
            }
            WearableProtocol.Paths.SESSION_CANCEL -> {
                val intent = Intent(this, WatchPhoneSessionService::class.java).apply {
                    action = WatchPhoneSessionService.ACTION_SESSION_CANCEL
                    putExtra(WatchPhoneSessionService.EXTRA_NODE_ID, event.sourceNodeId)
                }
                startService(intent)
            }
            else -> Log.v(TAG, "ignored message ${event.path}")
        }
    }

    companion object {
        private const val TAG = "WatchPhoneListener"
    }
}
