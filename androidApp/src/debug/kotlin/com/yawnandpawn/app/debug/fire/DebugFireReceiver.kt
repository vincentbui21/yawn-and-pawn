package com.yawnandpawn.app.debug.fire

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Debug builds only (Story 1.18):
 * `adb shell am broadcast -a com.yawnandpawn.app.debug.FIRE --ei seconds N [--es alarmId ID] [--ez test true|false]`
 * rings that alarm (or a synthetic one) in N seconds as a real or test session, through [DebugFire].
 *
 * Registered at runtime by [DebugFireProvider] (implicit broadcasts never reach manifest receivers on API 26+), so the
 * app process must be running: open the app first. Only senders holding `android.permission.DUMP` (the adb shell) can
 * reach it. The result is written to logcat under [TAG].
 */
class DebugFireReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != DebugFire.ACTION) return
        val request =
            DebugFire.FireRequest(
                seconds = intent.getIntExtra(DebugFire.EXTRA_SECONDS, DebugFire.DEFAULT_SECONDS),
                alarmId = intent.getStringExtra(DebugFire.EXTRA_ALARM_ID),
                test = intent.getBooleanExtra(DebugFire.EXTRA_TEST, false),
            )
        val debugFire = DebugFire(get(), get(), get(), get(), get(), get())
        val pending = goAsync()
        get<ApplicationScope>().launch {
            try {
                when (val armed = debugFire.fire(request)) {
                    is Outcome.Success -> Log.i(TAG, "armed $request to ring at ${armed.value}")
                    is Outcome.Failure -> Log.w(TAG, "could not arm $request: ${armed.error}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "YawnAndPawnDebugFire"

        /** Held by the adb shell; no app can send the fire broadcast without it. */
        const val SENDER_PERMISSION = "android.permission.DUMP"

        /** Registers a receiver for [DebugFire.ACTION] on [context]'s application, reachable from the adb shell. */
        fun register(context: Context): DebugFireReceiver {
            val receiver = DebugFireReceiver()
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
            context.applicationContext.registerReceiver(receiver, IntentFilter(DebugFire.ACTION), SENDER_PERMISSION, null, flags)
            return receiver
        }
    }
}
