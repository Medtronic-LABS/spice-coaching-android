package com.medtroniclabs.microcoaching.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * The ONLY SDK class that references Firebase types. Firebase is a `compileOnly`
 * dependency: in a host app without firebase-analytics these classes do not
 * exist at runtime, so this class must never be loaded there.
 * [CoachingAnalytics.init] guarantees that by checking for the Firebase class
 * first and catching every Throwable around [createOrNull].
 *
 * Uses the host app's DEFAULT Firebase project (the one from the host's
 * google-services.json), so events land in the host's GA with the host's user
 * id and user properties.
 */
internal class FirebaseAnalyticsSink private constructor(
    private val analytics: FirebaseAnalytics,
) : AnalyticsSink {

    override fun logEvent(name: String, params: Map<String, Any>) {
        analytics.logEvent(name, params.toBundle())
    }

    override fun logScreen(screenName: String) {
        analytics.logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, SCREEN_CLASS)
            },
        )
    }

    private fun Map<String, Any>.toBundle(): Bundle = Bundle().also { b ->
        for ((k, v) in this) {
            when (v) {
                is Long -> b.putLong(k, v)
                is Double -> b.putDouble(k, v)
                else -> b.putString(k, v.toString())
            }
        }
    }

    companion object {
        private const val SCREEN_CLASS = "MicroCoaching"

        /** Null when the host app has Firebase but never configured it. */
        fun createOrNull(context: Context): AnalyticsSink? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseAnalyticsSink(FirebaseAnalytics.getInstance(context))
        }
    }
}
