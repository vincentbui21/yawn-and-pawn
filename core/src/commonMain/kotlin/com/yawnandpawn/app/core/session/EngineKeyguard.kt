package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.LockedTestRecorder
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * [SessionEngine]'s keyguard: it reads [UnlockPort.isKeyguardLocked] for the steps that need it and reports a completed
 * locked test to the [LockedTestRecorder] (Story 5.3). Only the engine uses it, inside its Mutex. Failures are logged and
 * never thrown.
 */
internal class EngineKeyguard(
    private val unlock: UnlockPort,
    private val lockedTest: LockedTestRecorder,
    private val logger: Logger,
) {
    /**
     * The keyguard for [event] from [from]. Only a Pay (Spike S1) and a test ring starting from Idle (Story 5.3) make that
     * platform call. For a Pay a port that cannot tell counts as locked: the unlock request then succeeds at once on an
     * unlocked phone (S1 run L-s1), whereas a launch behind a keyguard shows nothing. For a test it counts as unlocked, so
     * the locked-screen test never passes by mistake.
     */
    fun lockedFor(
        event: SessionEvent,
        from: SessionState,
    ): Boolean =
        when {
            event is SessionEvent.PayConfirmed -> {
                runCatching { unlock.isKeyguardLocked() }.getOrDefault(true)
            }

            event is SessionEvent.TestAlarmFired && from == SessionState.Idle -> {
                runCatching { unlock.isKeyguardLocked() }.getOrDefault(false)
            }

            else -> {
                false
            }
        }

    /**
     * A committed step from [from] to [next] at [now]: on the step into Completed of a test that started locked
     * ([SessionConfig.testStartedLocked]) the pass is recorded, once. A failed or throwing record is logged only.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun afterStep(
        from: SessionState,
        next: SessionState,
        now: TimeSnapshot,
    ) {
        val completedLockedTest = (next as? SessionState.Completed)?.session?.config?.let { it.testMode && it.testStartedLocked } == true
        if (from is SessionState.Completed || !completedLockedTest) return
        try {
            val recorded = lockedTest.lockedTestPassed(Instant.fromEpochMilliseconds(now.wallMillis))
            if (recorded is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(RECORD, recorded.error))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.log(LogEvent.OperationFailed(RECORD, e::class.simpleName ?: "Throwable"))
        }
    }

    private companion object {
        const val RECORD = "record locked test"
    }
}
