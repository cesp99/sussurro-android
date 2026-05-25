package de.aploi.sussurrobyeyed

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
 * Phone-side [Application] subclass.
 *
 * Its only job is to ensure the `sussurro_phone` Wearable capability is
 * actually registered with Google Play Services as soon as our process
 * comes up. Static declaration in `res/values/wear.xml` is the canonical
 * path GMS reads at install time, but the static cache can be flaky on
 * fresh sideloads / first launches — calling [addLocalCapability]
 * defensively makes the watch's `getCapability` lookup work reliably the
 * first time the user taps the watch screen.
 *
 * `removeLocalCapability` is intentionally not called: GMS keeps the
 * capability registered for the lifetime of the install, which is what
 * we want.
 */
class SussurroApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                Wearable.getCapabilityClient(this@SussurroApp)
                    .addLocalCapability(WearableProtocol.CAPABILITY_PHONE)
                    .await()
                Log.i(TAG, "registered local capability ${WearableProtocol.CAPABILITY_PHONE}")
            } catch (t: Throwable) {
                // DUPLICATE_CAPABILITY (4006) just means the static
                // declaration in res/values/wear.xml has already kicked in
                // and registered our capability — ideal outcome, the
                // runtime call is purely belt-and-braces. Log it at debug
                // level so a successful static registration doesn't show
                // up as a scary warning every cold start.
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
        const val TAG = "SussurroApp"
    }
}
