package com.yawnandpawn.app.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.net.Connectivity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [Connectivity] on the default network (Story 4.7): online while it has [NetworkCapabilities.NET_CAPABILITY_INTERNET]
 * and [NetworkCapabilities.NET_CAPABILITY_VALIDATED], so a captive portal or a network without internet reads as
 * offline. [observeOnline] registers a default network callback only while collected, emits the current value first
 * (read again after registering, so a change in between is not missed) and then every change.
 *
 * A failing system service never stops the wake screen: the flow then says online (Play decides, and a failed payment
 * says "No charge.") and logs the failure.
 */
class AndroidConnectivity(
    private val context: Context,
    private val logger: Logger,
) : Connectivity {
    override fun observeOnline(): Flow<Boolean> =
        callbackFlow {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        trySend(capabilities.isOnline())
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }
                }
            val registered =
                runCatching { manager.registerDefaultNetworkCallback(callback) }
                    .onFailure { logger.log(LogEvent.OperationFailed(OPERATION, it::class.simpleName.orEmpty())) }
                    .isSuccess
            trySend(if (registered) manager.isOnlineNow() else true)
            awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
        }.distinctUntilChanged()

    private fun ConnectivityManager.isOnlineNow(): Boolean =
        runCatching { activeNetwork?.let(::getNetworkCapabilities)?.isOnline() == true }
            .onFailure { logger.log(LogEvent.OperationFailed(OPERATION, it::class.simpleName.orEmpty())) }
            .getOrDefault(true)

    private fun NetworkCapabilities.isOnline(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val OPERATION = "read connectivity"
    }
}
