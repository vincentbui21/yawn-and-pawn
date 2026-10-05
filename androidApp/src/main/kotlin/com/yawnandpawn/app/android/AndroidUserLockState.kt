package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [UserLockState] on `UserManager.isUserUnlocked` (Story 2.3). [observe] emits the current value, then `true` when
 * `ACTION_USER_UNLOCKED` arrives; the receiver is registered only while collected, not exported (a protected system
 * broadcast), and the value is read again after registering, so an unlock in between is not missed.
 *
 * [isUserUnlocked] never throws: the engine reads it on every step, so a failing system service must not stop a
 * dispatch or a restore and leave the alarm silent. It then says unlocked (the ring plays as configured) and logs the
 * first failure once.
 */
class AndroidUserLockState(
    private val context: Context,
    private val logger: Logger,
) : UserLockState {
    private val failureLogged = AtomicBoolean(false)

    override fun isUserUnlocked(): Boolean =
        runCatching { context.getSystemService(UserManager::class.java).isUserUnlocked }
            .onFailure { if (failureLogged.compareAndSet(false, true)) logger.log(LogEvent.OperationFailed(OPERATION, "$it")) }
            .getOrDefault(true)

    private companion object {
        const val OPERATION = "read user lock state"
    }

    override fun observe(): Flow<Boolean> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context,
                        intent: Intent,
                    ) {
                        trySend(true)
                    }
                }
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            trySend(isUserUnlocked())
            awaitClose { context.unregisterReceiver(receiver) }
        }.distinctUntilChanged()
}
