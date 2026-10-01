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
 * at app start. A runtime receiver, because the shell's implicit `am broadcast -a` does not reach manifest receivers on
 * API 26+. Exported so the shell can send it. The provider serves no data.
 */
class DebugHooksProvider : ContentProvider() {
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Below API 33 there is no flag; runtime receivers are exported.
    override fun onCreate(): Boolean {
        val context = context ?: return false
        val filter = IntentFilter(DebugCrashReceiver.ACTION_CRASH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(DebugCrashReceiver(), filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(DebugCrashReceiver(), filter)
        }
        return true
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
