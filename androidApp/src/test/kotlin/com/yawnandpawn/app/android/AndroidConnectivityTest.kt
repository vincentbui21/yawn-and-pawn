package com.yawnandpawn.app.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.7: [AndroidConnectivity] reads the default network. Online needs validated internet; the current value comes
 * first, then each change from the default network callback, which is registered only while collected.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidConnectivityTest {
    @get:Rule
    val appTeardown = StopAppRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val connectivity = AndroidConnectivity(context, FakeLogger())

    private fun capabilities(vararg capability: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps -> capability.forEach { shadowOf(caps).addCapability(it) } }

    private val validated = capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun setActive(capabilities: NetworkCapabilities) {
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    private fun callback(): ConnectivityManager.NetworkCallback = shadowOf(manager).networkCallbacks.single()

    @Test
    fun `a validated default network is online, and each change of the default network follows in order`() =
        runTest(UnconfinedTestDispatcher()) {
            setActive(validated)
            val seen = mutableListOf<Boolean>()
            val collecting = backgroundScope.launch { connectivity.observeOnline().toList(seen) }

            assertEquals(listOf(true), seen, "the current value first")
            val network = manager.activeNetwork!!
            callback().onLost(network)
            callback().onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            callback().onCapabilitiesChanged(network, validated)
            callback().onCapabilitiesChanged(network, validated)

            assertEquals(listOf(true, false, true), seen, "no repeats; internet without validation is still offline")
            collecting.cancel()
            assertTrue(shadowOf(manager).networkCallbacks.isEmpty(), "the callback is unregistered when nobody collects")
        }

    @Test
    fun `internet without validation (a captive portal) reads as offline`() =
        runTest(UnconfinedTestDispatcher()) {
            setActive(capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { connectivity.observeOnline().toList(seen) }

            assertEquals(listOf(false), seen)
        }
}
