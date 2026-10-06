package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.FakeBootCounter
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeFallbackPolicy
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.ui.wake.CheckPosition
import com.yawnandpawn.app.ui.wake.WakeIntent
import org.junit.Test
import kotlin.test.assertEquals

/** Story 3.2 review: the number pad keys of [WakeCheck] against the engine's position, between two recompositions. */
class WakeCheckTest {
    private val check = WakeCheck(FakeClock(), FakeMonotonicClock(), FakeBootCounter(), FakeFallbackPolicy(), FakeAccessibilityState())
    private val sent = mutableListOf<SessionEvent>()
    private var interactions = 0

    private val problem1 = CheckPosition("session-1", ringIndex = 1, entry = 0, item = 0, seed = 7, failedAttempts = 0)
    private val problem2 = problem1.copy(item = 1)

    private fun tap(
        intent: WakeIntent,
        position: CheckPosition?,
    ) = check.onKey(intent, position, send = { sent += it }, interacted = { interactions++ })

    private fun submitted() = sent.filterIsInstance<SessionEvent.CheckAnswerSubmitted>().map { it.answer }

    @Test
    fun `a second Check before the next problem shows sends nothing, so it is never a wrong answer to the next one`() {
        tap(WakeIntent.DigitTapped(4), problem1)
        tap(WakeIntent.DigitTapped(2), problem1)
        tap(WakeIntent.SubmitAnswer, problem1)
        // The engine has already moved on (or not yet): the screen has not recomposed in between.
        tap(WakeIntent.SubmitAnswer, problem2)
        tap(WakeIntent.SubmitAnswer, problem1)

        assertEquals(listOf<CheckAnswer>(CheckAnswer.Number("42")), submitted())
        assertEquals("", check.inputAt(problem1).digits, "the field is cleared on submit")
        assertEquals(4, interactions, "the digits and the ignored taps still reset the interaction deadline")
    }

    @Test
    fun `a key tapped after the engine moved on, before the screen recomposed, is typed into the new problem`() {
        tap(WakeIntent.DigitTapped(9), problem1)
        tap(WakeIntent.DigitTapped(5), problem2)

        assertEquals("5", check.inputAt(problem2).digits, "kept once the screen recomposes on problem 2")
        tap(WakeIntent.SubmitAnswer, problem2)
        assertEquals(listOf<CheckAnswer>(CheckAnswer.Number("5")), submitted())
    }

    @Test
    fun `a wrong answer after a submit still shows as wrong`() {
        tap(WakeIntent.DigitTapped(1), problem1)
        tap(WakeIntent.SubmitAnswer, problem1)

        val shown = check.inputAt(problem1.copy(failedAttempts = 1))
        assertEquals("", shown.digits)
        assertEquals(true, shown.wrong)
    }
}
