package com.medtroniclabs.microcoaching.network.events

import okhttp3.OkHttpClient

/** Installs network-event capture on an OkHttp client builder. */
internal object NetworkEvents {

    /**
     * Adds [NetworkEventInterceptor] as the innermost application interceptor so
     * far and [ConnectionProbe] as a network interceptor. Call it immediately
     * before `build()`. The sink is read from [NetworkEventSinkHolder] on every
     * call, so a client built before the store is bound starts recording once it is.
     * A builder that is already instrumented is returned unchanged, so a
     * `newBuilder()` of an instrumented client never records a call twice.
     */
    fun instrument(b: OkHttpClient.Builder): OkHttpClient.Builder {
        if (b.interceptors().any { it is NetworkEventInterceptor }) return b
        return b.addInterceptor(NetworkEventInterceptor({ NetworkEventSinkHolder.current() }))
            .addNetworkInterceptor(ConnectionProbe)
    }
}

/** The process-wide sink that instrumented clients write to; null until the store binds. */
internal object NetworkEventSinkHolder {
    @Volatile
    var sink: NetworkEventSink? = null

    fun current(): NetworkEventSink? = sink
}
