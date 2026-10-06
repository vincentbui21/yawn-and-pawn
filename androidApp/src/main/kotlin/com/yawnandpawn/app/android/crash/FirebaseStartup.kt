package com.yawnandpawn.app.android.crash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import java.util.concurrent.atomic.AtomicBoolean

/** The app has a Firebase configuration: the google-services plugin ran with a `google-services.json`. */
fun isFirebaseConfigured(context: Context): Boolean = FirebaseOptions.fromResource(context) != null

/**
 * Starts Firebase (Crashlytics only) after the user's first unlock (AD-15, Story 1.19). `FirebaseInitProvider` is
 * removed from the manifest, so nothing starts it earlier: before the first unlock credential-protected storage is
 * closed, and the Direct Boot ring must not depend on it. Without a configuration ([configured] false) nothing starts.
 *
 * [start] runs from `Application.onCreate`: unlocked, it starts at once; locked, a receiver registered at runtime
 * waits for `ACTION_USER_UNLOCKED`. The unlock signals (Story 2.4) call it again, so it is idempotent: it starts
 * nothing twice, registers no second receiver while one waits and logs a missing configuration only once.
 */
class FirebaseStartup(
    private val context: Context,
    private val logger: Logger,
    private val configured: () -> Boolean = { isFirebaseConfigured(context) },
    private val unlocked: () -> Boolean = { context.getSystemService(UserManager::class.java).isUserUnlocked },
    private val initialize: () -> Unit = {
        FirebaseApp.initializeApp(context)
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
    },
) {
    @Volatile
    var started = false
        private set

    private val reportedUnconfigured = AtomicBoolean(false)

    /** A receiver already waits for the unlock. */
    private val waiting = AtomicBoolean(false)

    fun start() {
        when {
            started -> Unit
            !configured() -> reportUnconfigured()
            unlocked() -> startNow()
            !waiting.getAndSet(true) -> startAtUnlock()
        }
    }

    private fun reportUnconfigured() {
        if (!reportedUnconfigured.getAndSet(true)) {
            logger.log(LogEvent.OperationFailed("start crash reporting", "no Firebase configuration; crashes are only logged"))
        }
    }

    /** Locked: a receiver registered at runtime starts Firebase at `ACTION_USER_UNLOCKED`. */
    private fun startAtUnlock() {
        val registered = AtomicBoolean(true)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: Intent,
                ) {
                    if (registered.getAndSet(false)) context.unregisterReceiver(this)
                    startNow()
                }
            }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        // A protected system broadcast; not exported, as no other app may send it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        // An unlock between the first check and the registration sent its broadcast already: check again.
        if (unlocked()) {
            if (registered.getAndSet(false)) context.unregisterReceiver(receiver)
            startNow()
        }
    }

    @Synchronized
    private fun startNow() {
        if (started) return
        initialize()
        started = true
    }
}
