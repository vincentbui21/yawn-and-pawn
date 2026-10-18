package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Firebase's start as [AnalyticsConsentGate] follows it: `FirebaseStartup` in the app (Story 5.6).
 */
interface FirebaseState {
    /** True once Firebase has started (after the first unlock, with a Firebase configuration). */
    val ready: StateFlow<Boolean>

    /**
     * The start turned Analytics collection off right after initializing (fail closed, review fix 4). False when that
     * call failed or was not made: the gate then turns collection off itself.
     */
    val startedCollectionOff: Boolean
}

/**
 * Keeps Firebase Analytics collection in step with the user's choice (Story 5.6, AD-15). The manifest turns collection
 * off, and Firebase's start turns it off again right after initializing ([FirebaseState.startedCollectionOff]), since
 * Firebase keeps the last runtime flag and that wins over the manifest. This gate then turns it on only while consent is
 * true, the user has unlocked and Firebase has started ([FirebaseState.ready] and [AnalyticsSink.isReady]), granting
 * analytics storage only (every ad consent denied). When the start could not turn collection off, the gate does.
 * Until Firebase has started nothing is called at all.
 *
 * Only the user turning consent off ([AnalyticsConsent.asked] and no consent, after consent was on in this process)
 * resets the analytics data (review fix 7): a consent that cannot be read counts as no consent and turns collection
 * off, but it is not the user's choice, so it never deletes their data.
 *
 * The choice is applied again whenever the consent or [FirebaseState.ready] changes; the lock state is read then
 * ([UserLockState.isUserUnlocked]), not followed: Firebase itself starts only at the first unlock, which changes
 * [FirebaseState.ready], so no unlock receiver is held for the life of the process.
 *
 * A failing Analytics call is logged by type name and tried again after [RETRY_DELAY], at most [MAX_RETRIES] times in a
 * row, and at the next change. [start] follows the choice for the life of [scope] (its own app-wide scope, so it is not
 * among the app start's finite jobs) until [stop]; later calls do nothing.
 */
class AnalyticsConsentGate(
    private val store: ConsentStore,
    private val userLock: UserLockState,
    private val firebase: FirebaseState,
    private val sink: AnalyticsSink,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val running = AtomicBoolean(false)

    private var job: Job? = null

    /** Bumped by a scheduled retry, so the current inputs are applied again. */
    private val retries = MutableStateFlow(0)

    /** The failed applies in a row since the last success. */
    private var failures = 0

    /** Consent was on at some point in this process since the last reset: the user turning it off resets the data. */
    private var consentWasOn = false

    /** The collection state last set in this process; null before the first call (unknown). */
    private var applied: Boolean? = null

    fun start() {
        if (running.getAndSet(true)) return
        job =
            scope.launch {
                val gate = this
                combine(consents(), firebase.ready, retries) { consent, started, _ ->
                    Inputs(consent.consent, consent.asked, userLock.isUserUnlocked(), started)
                }.collect { inputs -> if (follow(inputs)) failures = 0 else gate.retryLater() }
            }
    }

    /** Stops following the choice (the Koin graph closes); collection stays as last set. */
    fun stop() {
        job?.cancel()
    }

    private fun consents(): Flow<AnalyticsConsent> =
        store.observe().catch { cause ->
            logger.log(LogEvent.OperationFailed(READ, cause::class.simpleName.orEmpty()))
            emit(AnalyticsConsent())
        }

    private fun CoroutineScope.retryLater() {
        if (failures++ >= MAX_RETRIES) return
        launch {
            delay(RETRY_DELAY)
            retries.value++
        }
    }

    /** Applies [inputs]; false when an Analytics call failed. */
    private fun follow(inputs: Inputs): Boolean {
        if (inputs.consent) consentWasOn = true
        if (!inputs.started || !sink.isReady) return true
        if (applied == null && firebase.startedCollectionOff) applied = false
        val enable = inputs.consent && inputs.unlocked
        val reset = inputs.turnedOff && consentWasOn
        val done = (enable == applied && !reset) || call { setCollection(enable, reset) }
        if (done) {
            applied = enable
            if (reset) consentWasOn = false
        }
        return done
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
        val asked: Boolean,
        val unlocked: Boolean,
        val started: Boolean,
    ) {
        /** The user's own "no" (stored, so asked): never the no-consent default of an unreadable choice. */
        val turnedOff: Boolean
            get() = !consent && asked
    }

    companion object {
        /** How long a failed Analytics call waits before it is tried again. */
        val RETRY_DELAY: Duration = 30.seconds

        /** The retries after a failed call, before the gate waits for the next change. */
        const val MAX_RETRIES = 5

        private const val READ = "read usage stats consent"
        private const val APPLY = "apply usage stats consent"
    }
}
