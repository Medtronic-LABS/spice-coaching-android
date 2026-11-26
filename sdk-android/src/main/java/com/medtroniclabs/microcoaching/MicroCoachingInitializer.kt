package com.medtroniclabs.microcoaching

import android.content.Context
import androidx.startup.Initializer
import com.medtroniclabs.microcoaching.network.events.NetworkEventRecorder

/**
 * AndroidX App Startup initializer for the MicroCoaching SDK.
 *
 * This runs automatically when the host app (SPICE) starts — zero boilerplate needed.
 * It binds the network-event recorder so requests the SDK makes are captured
 * from process start.
 *
 * The SDK itself is NOT initialized here (that requires client config from SPICE).
 * The host app must still call [MicroCoachingSDK.Builder.build] in Application.onCreate().
 *
 * To disable auto-initialization (e.g., for testing), add to your AndroidManifest:
 * ```xml
 * <provider
 *     android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup"
 *     tools:node="remove" />
 * ```
 */
class MicroCoachingInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Start network-event capture before any SDK client makes a call. The
        // database opens lazily on the recorder's IO thread, never here.
        NetworkEventRecorder.bind(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
