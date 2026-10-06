package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.ConfigResolver
import com.yawnandpawn.app.core.session.FallbackDecision
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.session.StepResult
import com.yawnandpawn.app.core.session.UnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

class SessionFakesTest {
    private val time = FakeTime()
    private val config =
        ConfigResolver.resolve(
            alarm = anAlarm(id = "alarm-1"),
            globalSettings = GlobalSettings(baseFeeTier = 2),
            testMode = false,
            scheduledAt = DEFAULT_FAKE_INSTANT,
        )

    private fun session(snoozesGranted: Int = 0) =
        SessionData(
            sessionId = "session-1",
            config = config,
            ringIndex = 1,
            snoozesGranted = snoozesGranted,
            checkRun = CheckRun(config.checkPlan, listOf(1L)),
        )

    @Test
    fun `FakeFeeLadder maps tier and snooze number to snooze_usd_NN and records each call`() {
        val ladder = FakeFeeLadder()
        assertEquals(SnoozeOffer("snooze_usd_03", 2), ladder.offer(baseFeeTier = 2, snoozeNumber = 2))
        ladder.tierOf = { _, n -> n * 10 }
        assertEquals(SnoozeOffer("snooze_usd_30", 3), ladder.offer(baseFeeTier = 1, snoozeNumber = 3))
        assertEquals(listOf(2 to 2, 1 to 3), ladder.calls)
    }

    @Test
    fun `FakeSnoozeAvailability offers the next ladder price until told a reason`() {
        val ladder = FakeFeeLadder()
        val availability = FakeSnoozeAvailability(ladder)
        assertEquals(SnoozeAvailability.Available(SnoozeOffer("snooze_usd_03", 2)), availability.availability(session(snoozesGranted = 1)))
        assertEquals(listOf(2 to 2), ladder.calls)

        availability.unavailable = UnavailableReason.Offline
        assertEquals(SnoozeAvailability.Unavailable(UnavailableReason.Offline), availability.availability(session()))
        assertEquals(2, availability.asked.size)
    }

    @Test
    fun `FakeCheck returns queued results in order, then the default, and records answers`() {
        val check = FakeCheck(default = StepResult.Invalid)
        check.willReturn(StepResult.ValidNext, StepResult.ValidLast)
        val run = CheckRun(CheckPlan.placeholder(), listOf(1L))
        val results = List(3) { check.validate(run, CheckAnswer.Placeholder) }
        assertEquals(listOf(StepResult.ValidNext, StepResult.ValidLast, StepResult.Invalid), results)
        assertEquals(List(3) { CheckAnswer.Placeholder }, check.answers)
    }

    @Test
    fun `FakeFallbackPolicy returns its decision and counts calls`() {
        val fallback = FakeFallbackPolicy()
        assertEquals(FallbackDecision.NotAllowed, fallback.fallback(session()))
        val plan = CheckPlan.placeholder()
        fallback.decision = FallbackDecision.Allowed(plan)
        assertEquals(FallbackDecision.Allowed(plan), fallback.fallback(session()))
        assertEquals(2, fallback.calls)
    }

    @Test
    fun `the fakes drive the reducer through a paid snooze and a two-step check`() {
        val check = FakeCheck()
        check.willReturn(StepResult.Invalid, StepResult.ValidNext)
        val reducer = SessionReducer(FakeSnoozeAvailability(), check, FakeFallbackPolicy())
        var state: SessionState =
            reducer.reduce(SessionState.Idle, SessionEvent.AlarmFired("session-1", config, false), time.snapshot()).state

        time.advanceBy(1.minutes)
        val confirm = reducer.reduce(state, SessionEvent.SnoozeTapped, time.snapshot())
        assertEquals(listOf<SessionEffect>(SessionEffect.ShowSnoozeConfirm(SnoozeOffer("snooze_usd_02", 1))), confirm.effects)

        state = reducer.reduce(state, SessionEvent.ImUpTapped, time.snapshot()).state
        listOf(SessionEffect.WrongAnswerFeedback, null, SessionEffect.StopSound).forEach { first ->
            val transition = reducer.reduce(state, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder), time.snapshot())
            assertEquals(first, transition.effects.firstOrNull())
            state = transition.state
        }
        assertIs<SessionState.Completed>(state)
        assertEquals(0, state.session.checkRun.failedAttempts, "reset when the entry advanced")
    }
}
