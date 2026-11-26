package com.medtroniclabs.microcoaching.network.events

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Collections
import java.util.WeakHashMap

/**
 * Network interceptor that tells [NetworkEventInterceptor] whether a failing
 * call had a connection, so a timeout can be split into `connectionTimeout`
 * and `receiveTimeout` without reading exception messages. OkHttp runs network
 * interceptors only once a connection (and TLS) is established.
 *
 * A call is marked on entry and unmarked when its hop returns a response, so
 * after a redirect a connect failure on the next hop is not mistaken for a
 * receive timeout. A failing hop leaves the mark for the application
 * interceptor, which reads it and clears it in its `finally`.
 *
 * The set has weak, identity-keyed entries (`RealCall` keeps `Object`
 * equality), so a call that never reaches the application interceptor's
 * `finally` cannot keep it growing.
 */
internal object ConnectionProbe : Interceptor {

    private val connected: MutableSet<Call> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Call, Boolean>()))

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        connected.add(call)
        val response = chain.proceed(chain.request())
        connected.remove(call)
        return response
    }

    fun isConnected(call: Call): Boolean = connected.contains(call)

    fun clear(call: Call) {
        connected.remove(call)
    }

    /** Calls currently marked; for leak tests. */
    fun pendingCount(): Int = connected.size
}
