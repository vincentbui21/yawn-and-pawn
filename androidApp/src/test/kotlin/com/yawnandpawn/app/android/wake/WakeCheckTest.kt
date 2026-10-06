package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.FallbackDecision
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.FakeBootCounter
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeFallbackPolicy
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.ui.wake.CheckPosition
import com.yawnandpawn.app.ui.wake.WakeIntent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.yawnandpawn.app.ui.checks.CheckType as UiCheckType

/**
 * Story 3.2 review: the number pad keys of [WakeCheck] against the engine's position, between two recompositions.
 * Story 3.9 review: the fallback link opens the picker only while the fallback is offered for the current state.
 */
class WakeCheckTest {
    private val policy = FakeFallbackPolicy(FallbackDecision.NotAllowed)
    private val talkBack = FakeAccessibilityState()
    private val check = WakeCheck(FakeClock(), FakeMonotonicClock(), FakeBootCounter(), policy, talkBack)
    private val grace = aSession().let { SessionState.Grace(it.copy(graceEnd = it.interactionDeadline)) }
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

    private fun tapLink() = check.onFallback(WakeIntent.FallbackLinkClicked, grace, send = { sent += it }, interacted = { interactions++ })

    @Test
    fun `a link tap from a stale frame, once the fallback is no longer offered, arms nothing for later`() {
        tapLink()
        assertEquals(1, interactions, "the tap still counts as an interaction")
        assertTrue(sent.isEmpty())

        policy.decision = FallbackDecision.Allowed(CameraFallbackPolicy.fallbackPlan(CheckType.Math))
        assertNull(check.picker(grace), "offered again later, the picker stays closed until the link is tapped")

        tapLink()
        assertNotNull(check.picker(grace), "a tap while offered opens it")
    }

    @Test
    fun `a picked card asks for its check, and Memory Sequence is the numbered variant while TalkBack is on`() {
        fun pick(type: UiCheckType) = check.onFallback(WakeIntent.FallbackChosen(type), grace, send = { sent += it }, interacted = {})

        pick(UiCheckType.WordUnscramble)
        pick(UiCheckType.MemorySequence)
        talkBack.screenReaderOn = true
        pick(UiCheckType.MemorySequence)

        assertEquals(
            listOf(CheckType.WordUnscramble, CheckType.MemorySequence(), CheckType.MemorySequence(numbered = true)),
            sent.filterIsInstance<SessionEvent.FallbackRequested>().map { it.type },
        )
    }
}
