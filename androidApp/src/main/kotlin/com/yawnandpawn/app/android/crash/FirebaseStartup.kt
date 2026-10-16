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
import com.yawnandpawn.app.android.telemetry.AnalyticsSink
import com.yawnandpawn.app.android.telemetry.FirebaseState
import com.yawnandpawn.app.android.telemetry.NoOpAnalyticsSink
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** The app has a Firebase configuration: the google-services plugin ran with a `google-services.json`. */
fun isFirebaseConfigured(context: Context): Boolean = FirebaseOptions.fromResource(context) != null

/**
 * Whether this build has a Firebase configuration ([isFirebaseConfigured]), read once and bound in Koin, so a test can
 * run the production wiring as a configured build (Story 5.6 review fix 6).
 */
class FirebaseConfiguration(
    val present: Boolean,
)

/**
 * Starts Firebase (Crashlytics only) after the user's first unlock (AD-15, Story 1.19). `FirebaseInitProvider` is
 * removed from the manifest, so nothing starts it earlier: before the first unlock credential-protected storage is
 * closed, and the Direct Boot ring must not depend on it. Without a configuration ([configured] false) nothing starts.
 *
 * [start] runs from `Application.onCreate`: unlocked, it starts at once; locked, a receiver registered at runtime
 * waits for `ACTION_USER_UNLOCKED`. The unlock signals (Story 2.4) call it again, so it is idempotent: it starts
 * nothing twice, registers no second receiver while one waits and logs a missing configuration only once.
 *
 * Right after initializing it turns Analytics collection off through [analytics], before [ready] turns true (Story 5.6
 * review fix 4, fail closed): Firebase keeps the last runtime collection flag and that wins over the manifest, so a
 * phone whose user said yes once would otherwise collect from the start. Only the consent gate turns it on again. A
 * failing call is logged; [startedCollectionOff] then stays false and the gate turns collection off itself.
 *
 * [ready] turns true once Firebase has started (Story 5.6): the Analytics consent gate waits for it, so Analytics is
 * never touched before the first unlock or without a configuration.
 */
class FirebaseStartup(
    private val context: Context,
    private val logger: Logger,
    private val analytics: AnalyticsSink = NoOpAnalyticsSink,
    private val configured: () -> Boolean = { isFirebaseConfigured(context) },
    private val unlocked: () -> Boolean = { context.getSystemService(UserManager::class.java).isUserUnlocked },
    private val initialize: () -> Unit = {
        FirebaseApp.initializeApp(context)
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
    },
) : FirebaseState {
    @Volatile
    var started = false
        private set

    private val readyFlow = MutableStateFlow(false)

    /** True once [initialize] has succeeded (and collection was turned off); it never goes back to false. */
    override val ready: StateFlow<Boolean> = readyFlow.asStateFlow()

    @Volatile
    private var collectionOff = false

    override val startedCollectionOff: Boolean
        get() = collectionOff

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
        collectionOff = turnCollectionOff()
        readyFlow.value = true
    }

    /** Turns Analytics collection off at once (fail closed); a failure is logged by type name and gives false. */
    @Suppress("TooGenericExceptionCaught")
    private fun turnCollectionOff(): Boolean =
        try {
            analytics.isReady.also { ready -> if (ready) analytics.setCollectionEnabled(false) }
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(CLOSE_ANALYTICS, e::class.simpleName.orEmpty()))
            false
        } catch (e: LinkageError) {
            logger.log(LogEvent.OperationFailed(CLOSE_ANALYTICS, e::class.simpleName.orEmpty()))
            false
        }

    private companion object {
        const val CLOSE_ANALYTICS = "turn usage stats collection off"
    }
}
