package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps Firebase Analytics collection in step with the user's choice (Story 5.6, AD-15). The manifest turns collection
 * off; this turns it on only while consent is true, the user has unlocked and Firebase has started ([firebaseStarted]
 * and [AnalyticsSink.isReady]), granting analytics storage only (every ad consent denied). Without consent it turns
 * collection off on every app start (Firebase keeps the runtime flag), and when consent goes from on to off in this
 * process it also resets the analytics data. Until Firebase has started nothing is called at all.
 *
 * The choice is applied again whenever the consent or [firebaseStarted] changes; the lock state is read then
 * ([UserLockState.isUserUnlocked]), not followed: Firebase itself starts only at the first unlock, which changes
 * [firebaseStarted], so no unlock receiver is held for the life of the process.
 *
 * A consent that cannot be read counts as no consent. A failing Analytics call is logged by type name and tried again
 * at the next change. [start] follows the choice for the life of [scope] (its own app-wide scope, so it is not among
 * the app start's finite jobs) until [stop]; later calls do nothing.
 */
class AnalyticsConsentGate(
    private val store: ConsentStore,
    private val userLock: UserLockState,
    private val firebaseStarted: StateFlow<Boolean>,
    private val sink: AnalyticsSink,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val running = AtomicBoolean(false)

    private var job: Job? = null

    /** Consent was on at some point in this process since the last reset: turning it off resets the analytics data. */
    private var consentWasOn = false

    /** The collection state last set in this process; null before the first call. */
    private var applied: Boolean? = null

    fun start() {
        if (running.getAndSet(true)) return
        job =
            scope.launch {
                combine(consents(), firebaseStarted) { consent, started -> Inputs(consent, userLock.isUserUnlocked(), started) }
                    .collect { follow(it) }
            }
    }

    /** Stops following the choice (the Koin graph closes); collection stays as last set. */
    fun stop() {
        job?.cancel()
    }

    private fun consents(): Flow<Boolean> =
        store
            .observe()
            .catch { cause ->
                logger.log(LogEvent.OperationFailed(READ, cause::class.simpleName.orEmpty()))
                emit(AnalyticsConsent())
            }.map { it.consent }

    private fun follow(inputs: Inputs) {
        if (inputs.consent) consentWasOn = true
        if (!inputs.started || !sink.isReady) return
        val enable = inputs.consent && inputs.unlocked
        if (enable == applied) return
        val reset = !enable && !inputs.consent && consentWasOn
        if (call { setCollection(enable, reset) }) {
            applied = enable
            if (reset) consentWasOn = false
        }
    }

    private fun setCollection(
        enable: Boolean,
        reset: Boolean,
    ) {
        sink.setCollectionEnabled(enable)
        if (enable) sink.grantAnalyticsStorageOnly()
        if (reset) sink.resetData()
    }

    /** Runs [block]; a failure (an exception, or Analytics missing from the classpath) is logged and gives false. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun call(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(APPLY, e::class.simpleName.orEmpty()))
            false
        } catch (e: LinkageError) {
            logger.log(LogEvent.OperationFailed(APPLY, e::class.simpleName.orEmpty()))
            false
        }

    private data class Inputs(
        val consent: Boolean,
        val unlocked: Boolean,
        val started: Boolean,
    )

    private companion object {
        const val READ = "read usage stats consent"
        const val APPLY = "apply usage stats consent"
    }
}
