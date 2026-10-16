package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.LockedTestReporter
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/**
 * [SessionEngine]'s keyguard: it reads [UnlockPort.isKeyguardLocked] for the steps that need it and reports a completed
 * locked test to the [LockedTestReporter] (Story 5.3). Only the engine uses it, inside its Mutex (except [preempt]).
 * Failures are logged and never thrown.
 */
internal class EngineKeyguard(
    private val unlock: UnlockPort,
    private val reporter: LockedTestReporter,
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

    /** The test the wake service is ending for a real alarm ([preempt]); its Completed is not the user's. */
    private val preempted = MutableStateFlow<String?>(null)

    /**
     * The wake service ends the test [sessionId] for a real alarm (Story 1.18) by answering its check for the user, so
     * its Completed never passes the locked-screen test (Story 5.3 review fix 10).
     */
    fun preempt(sessionId: String) {
        preempted.value = sessionId
    }

    /**
     * A committed step from [from] to [next] at [now]: on the step into Completed of a test that started locked
     * ([SessionConfig.testStartedLocked]) and that the user completed (not [preempt]ed), the pass is reported, once.
     * The report never suspends ([LockedTestReporter]): the engine's lock is never held for the write (review fix 11).
     * A reporter that throws is logged only.
     */
    fun afterStep(
        from: SessionState,
        next: SessionState,
        now: TimeSnapshot,
    ) {
        val session = (next as? SessionState.Completed)?.session ?: return
        val lockedTest = session.config.testMode && session.config.testStartedLocked
        if (from is SessionState.Completed || !lockedTest || session.sessionId == preempted.value) return
        runCatching { reporter.lockedTestPassed(Instant.fromEpochMilliseconds(now.wallMillis)) }.onFailure { e ->
            logger.log(LogEvent.OperationFailed(LockedTestReporter.RECORD, e::class.simpleName ?: "Throwable"))
        }
    }
}
