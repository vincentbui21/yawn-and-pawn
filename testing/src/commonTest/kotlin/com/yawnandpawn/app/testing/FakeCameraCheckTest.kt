package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.core.session.PluginCheckValidator
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Story 3.9: the fallback table with [FakeCameraCheck] through the real reducer: allowed, denied (not a camera check, fewer
 * than 5 failures, already used, a camera type chosen) and once per session.
 */
class FakeCameraCheckTest {
    private val time = FakeTime()
    private val reducer = SessionReducer(FakeSnoozeAvailability(), PluginCheckValidator, FakeCameraCheck.policy())
    private val mathHard6 = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.Math, Difficulty.Hard, count = 6)))

    private fun grace(session: SessionData = aSession(config = aSessionConfig().copy(checkPlan = FakeCameraCheck.plan))) =
        SessionState.Grace(session.copy(graceEnd = Deadline.after(time.snapshot(), 20.seconds)))

    private fun SessionState.Grace.withRun(change: (CheckRun) -> CheckRun) =
        copy(session = session.copy(checkRun = change(session.checkRun)))

    private fun request(
        type: CheckType = CheckType.Math,
        reason: FallbackReason = FallbackReason.CameraUnavailable,
    ) = SessionEvent.FallbackRequested(type, reason)

    /** The plan after [event] in [from], and whether the first fallback entry was shown. */
    private fun outcome(
        from: SessionState,
        event: SessionEvent,
    ): Pair<CheckPlan, Boolean> {
        val transition = reducer.reduce(from, event, time.snapshot())
        val plan = (transition.state as SessionState.Active).session.checkRun.plan
        return plan to (SessionEffect.StartCheckStep(0) in transition.effects)
    }

    @Test
    fun `the fallback table`() {
        val camera = FakeCameraCheck.plan
        val rows =
            listOf(
                "camera unavailable" to (grace() to request()),
                "5 failures with a working camera" to
                    (grace().withRun { it.copy(failedAttempts = 5) } to request(reason = FallbackReason.FailedAttempts)),
                "4 failures with a working camera" to
                    (grace().withRun { it.copy(failedAttempts = 4) } to request(reason = FallbackReason.FailedAttempts)),
                "not a camera check" to (grace().withRun { it.copy(step = StepPointer(1, 0)) } to request()),
                "already used" to (grace().withRun { it.copy(fallbackUsed = true) } to request()),
                "a camera type chosen" to (grace() to request(type = CheckType.Placeholder)),
            )
        val results = rows.associate { (name, case) -> name to outcome(case.first, case.second) }

        assertEquals(
            mapOf(
                "camera unavailable" to (mathHard6 to true),
                "5 failures with a working camera" to (mathHard6 to true),
                "4 failures with a working camera" to (camera to false),
                "not a camera check" to (camera to false),
                "already used" to (camera to false),
                "a camera type chosen" to (camera to false),
            ),
            results,
        )
    }

    @Test
    fun `the fallback is used once per session`() {
        val fallen = reducer.reduce(grace(), request(), time.snapshot()).state
        val failedOnFallback =
            (fallen as SessionState.Grace).copy(session = fallen.session.copy(checkRun = fallen.session.checkRun.copy(failedAttempts = 9)))

        assertEquals(mathHard6 to false, outcome(failedOnFallback, request(reason = FallbackReason.FailedAttempts)))
        assertEquals("Placeholder", fallen.session.checkRun.fallbackFrom)
    }
}
