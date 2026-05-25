package de.aploi.sussurrobyeyed.wear

import android.app.Application
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableStatusCodes
import de.aploi.sussurrobyeyed.shared.WearableProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Watch-side [Application] subclass.
 *
 * Mirrors [de.aploi.sussurrobyeyed.SussurroApp] on the phone: registers
 * the `sussurro_watch` capability at runtime as belt-and-braces on top
 * of the static `res/values/wear.xml` declaration. The phone uses this
 * capability to detect "Sussurro watch app installed" on its onboarding
 * screen and to address watch-bound MessageClient pings.
 */
class SussurroWearApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                Wearable.getCapabilityClient(this@SussurroWearApp)
                    .addLocalCapability(WearableProtocol.CAPABILITY_WATCH)
                    .await()
                Log.i(TAG, "registered local capability ${WearableProtocol.CAPABILITY_WATCH}")
            } catch (t: Throwable) {
                // DUPLICATE_CAPABILITY (4006) means the static declaration
                // in res/values/wear.xml already kicked in. That's the
                // happy path — log at debug, not warning, so a clean cold
                // start stays clean.
                val statusCode = (t as? ApiException)?.statusCode
                if (statusCode == WearableStatusCodes.DUPLICATE_CAPABILITY) {
                    Log.d(TAG, "static capability already registered (expected)")
                } else {
                    Log.w(TAG, "addLocalCapability failed", t)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SussurroWearApp"
    }
}
