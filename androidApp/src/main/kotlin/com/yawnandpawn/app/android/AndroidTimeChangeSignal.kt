package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.yawnandpawn.app.core.time.TimeChangeSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * [TimeChangeSignal] from the system broadcasts `ACTION_TIME_TICK` (every minute), `ACTION_TIME_CHANGED` (the clock
 * was set) and `ACTION_TIMEZONE_CHANGED`. The receiver is registered only while the flow is collected (a context
 * receiver: `TIME_TICK` cannot be received from the manifest) and unregistered when the collector stops.
 */
class AndroidTimeChangeSignal(
    private val context: Context,
) : TimeChangeSignal {
    override fun changes(): Flow<Unit> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (intent.action in ACTIONS) trySend(Unit)
                    }
                }
            val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
            // System broadcasts still arrive with RECEIVER_NOT_EXPORTED; nothing else may send to this receiver.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            awaitClose { context.unregisterReceiver(receiver) }
        }.conflate()

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}
