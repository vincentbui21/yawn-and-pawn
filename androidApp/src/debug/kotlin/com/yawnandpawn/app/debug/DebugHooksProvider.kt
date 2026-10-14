package com.yawnandpawn.app.debug

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import com.yawnandpawn.app.debug.billing.DebugFakeBillingReceiver
import com.yawnandpawn.app.debug.billing.DebugFakeBillingToggle

/** The debug-only test crash (Story 1.19): `adb shell am broadcast -a com.yawnandpawn.app.debug.CRASH`. */
class DebugTestCrash : RuntimeException("Debug test crash (adb broadcast com.yawnandpawn.app.debug.CRASH)")

/** Throws [DebugTestCrash] on the main thread, so Crashlytics records a fatal crash. */
class DebugCrashReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION_CRASH) throw DebugTestCrash()
    }

    companion object {
        const val ACTION_CRASH = "com.yawnandpawn.app.debug.CRASH"
    }
}

/**
 * Debug builds only (declared in the debug manifest; release builds never contain it): registers [DebugCrashReceiver]
 * and the fake billing toggle ([DebugFakeBillingReceiver], Story 4.12) at app start. Runtime receivers, because the
 * shell's implicit `am broadcast -a` does not reach manifest receivers on API 26+. Exported so the shell can send them.
 * The provider serves no data.
 */
class DebugHooksProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return false
        register(context, DebugCrashReceiver(), IntentFilter(DebugCrashReceiver.ACTION_CRASH))
        // Story 4.12: the hidden fake billing toggle (read at the next app start).
        register(context, DebugFakeBillingReceiver(), IntentFilter(DebugFakeBillingToggle.ACTION))
        return true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Below API 33 there is no flag; runtime receivers are exported.
    private fun register(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
