package de.aploi.sussurrobyeyed.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import de.aploi.sussurrobyeyed.shared.WearableProtocol
import kotlinx.coroutines.tasks.await

/**
 * Lightweight wrapper around `CapabilityClient` for the onboarding /
 * settings UI. The result is a snapshot, not a flow — callers refresh on
 * resume.
 *
 * Lives outside [PhoneWearableTransport] so the UI doesn't have to depend
 * on the internal session-service plumbing just to ask "is a Sussurro
 * watch reachable?".
 */
object WatchPresence {

    private const val TAG = "WatchPresence"

    /**
     * Returns true when a Sussurro-capable Wear OS node is paired and
     * currently reachable over the data layer. Returns false on any
     * lookup failure — the network call going wrong shouldn't make the
     * UI claim the watch is paired when we don't know.
     */
    suspend fun isWatchReachable(context: Context): Boolean {
        return try {
            val info = Wearable.getCapabilityClient(context.applicationContext)
                .getCapability(WearableProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                .await()
            info.nodes.isNotEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "isWatchReachable failed", t)
            false
        }
    }
}
