package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.session.NoBillingSnoozeAvailability
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.wake.CheckInput
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.mathCheckUiState
import kotlin.time.Duration.Companion.seconds

/**
 * Math Check screens (Story 3.2) built the way `WakeActivity` builds them: a session on the default plan (Math · Medium ·
 * 3, seed 1) through [mathCheckUiState], with the Epic 1 snooze policy ("Prices not loaded yet").
 */
object CheckSamples {
    private val start = TimeSnapshot(wallMillis = 1_800_000_000_000, elapsedMillis = 1_000_000, bootCount = 1)

    private fun session(
        plan: CheckPlan = CheckPlan.default(),
        item: Int = 0,
    ): SessionData =
        aSession(config = aSessionConfig().copy(checkPlan = plan), startedAt = start).let {
            it.copy(checkRun = it.checkRun.copy(step = StepPointer(0, item)))
        }

    private fun grace(session: SessionData) = SessionState.Grace(session.copy(graceEnd = Deadline.after(start, 20.seconds)))

    private fun map(
        state: SessionState,
        input: CheckInput = CheckInput(),
    ): CheckUiState {
        val session = (state as SessionState.Active).session
        val now = TimeSnapshot(start.wallMillis + 6_000, start.elapsedMillis + 6_000, start.bootCount)
        return checkNotNull(mathCheckUiState(state, NoBillingSnoozeAvailability().availability(session), now, input))
    }

    /** Problem 1 of 3 in Grace, 14 s left, "8" typed. */
    val grace: CheckUiState = map(grace(session()), CheckInput(digits = "8"))

    /** Problem 1 of 3 in Loud after the grace window: "Time's up. Alarm's back on until you finish.". */
    val loud: CheckUiState = map(SessionState.Loud(session()))

    /** A wrong answer in Loud: the field cleared, "Not quite. Try again.". */
    val wrong: CheckUiState = map(SessionState.Loud(session()), CheckInput(wrong = true))

    /** The last problem, 3 of 3, in Grace. */
    val lastProblem: CheckUiState = map(grace(session(item = 2)), CheckInput(digits = "1234"))

    /** The longest problem (Hard, `a × b + c × d`, the fallback's difficulty) in Grace with 5 digits typed. */
    val hard: CheckUiState =
        map(
            grace(session(CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.Math, Difficulty.Hard, count = 6))))),
            CheckInput(digits = "12345"),
        )
}
