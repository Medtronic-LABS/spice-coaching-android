package com.medtroniclabs.microcoaching

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.medtroniclabs.microcoaching.network.events.NetworkEventRecorder
import com.medtroniclabs.microcoaching.network.events.NetworkEventSinkHolder
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicroCoachingInitializerTest {

    @Before
    fun setUp() {
        NetworkEventRecorder.resetForTesting()
        NetworkEventRecorder.openStoreForContext = { error("the store must not open during startup") }
    }

    @After
    fun tearDown() = NetworkEventRecorder.resetForTesting()

    @Test
    fun create_bindsTheNetworkEventRecorder() {
        MicroCoachingInitializer().create(ApplicationProvider.getApplicationContext<Context>())

        assertSame(NetworkEventRecorder, NetworkEventSinkHolder.current())
    }
}
