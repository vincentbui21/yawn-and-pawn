package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Story 3.2: the Check screen mapping and the UI-only typed answer. */
class CheckMappingTest {
    private val start = TimeSnapshot(wallMillis = 1_800_000_000_000, elapsedMillis = 1_000_000, bootCount = 1)
    private val unavailable = SnoozeAvailability.Unavailable(UnavailableReason.CatalogueNotLoaded)

    private fun at(millis: Long) = TimeSnapshot(start.wallMillis + millis, start.elapsedMillis + millis, start.bootCount)

    private fun session(
        plan: CheckPlan = CheckPlan.default(),
        item: Int = 0,
        seed: Long = 1L,
    ): SessionData =
        aSession(config = aSessionConfig().copy(checkPlan = plan), startedAt = start).let {
            it.copy(checkRun = it.checkRun.copy(seeds = listOf(seed), step = StepPointer(0, item)))
        }

    private fun grace(session: SessionData = session()) = SessionState.Grace(session.copy(graceEnd = Deadline.after(start, 20.seconds)))

    private fun map(
        state: SessionState,
        now: TimeSnapshot = start,
        input: CheckInput = CheckInput(),
    ) = mathCheckUiState(state, unavailable, now, input)

    @Test
    fun `the problem is the plugin's puzzle for the entry's seed at the current item`() {
        val session = session(item = 1, seed = 77)
        val expected = (CheckType.Math.generate(77, Difficulty.Medium, 3) as Puzzle.Math).problems[1]

        val content = assertIs<CheckContent.Math>(map(grace(session), input = CheckInput(digits = "12"))?.content)

        assertEquals(2, content.problemNumber)
        assertEquals(3, content.problemCount)
        assertEquals(expected.operands, content.operands)
        assertEquals(listOf(MathOperator.Times, MathOperator.Plus), content.operators)
        assertEquals("12", content.answer)
        assertEquals(false, content.wrong)
    }

    @Test
    fun `every core operator maps to its screen operator`() {
        val hard = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.Math, Difficulty.Hard, count = 1)))
        val hardOperators = assertIs<CheckContent.Math>(map(grace(session(hard)))?.content).operators
        val easyOperators =
            (1L..40L)
                .flatMap { seed ->
                    val easy = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.Math, Difficulty.Easy, count = 1)))
                    assertIs<CheckContent.Math>(map(grace(session(easy, seed = seed)))?.content).operators
                }.toSet()

        assertEquals(listOf(MathOperator.Times, MathOperator.Plus, MathOperator.Times), hardOperators)
        assertEquals(setOf(MathOperator.Plus, MathOperator.Minus), easyOperators)
    }

    @Test
    fun `in grace the countdown reads the grace deadline, rounded up, and freezes during a call`() {
        val seconds =
            listOf(0L, 1L, 999L, 1_000L, 6_000L, 19_001L, 20_000L, 25_000L).map { millis ->
                (map(grace(), now = at(millis))?.grace as GraceState.Running).secondsLeft
            }
        val paused = grace().let { it.copy(session = it.session.copy(pausedAt = at(5_000))) }

        assertEquals(listOf(20, 20, 20, 19, 14, 1, 0, 0), seconds)
        assertEquals(GraceState.Running(secondsLeft = 15, totalSeconds = 20), map(paused, now = at(12_000))?.grace)
    }

    @Test
    fun `loud after grace says the alarm is back, a ring without grace shows neither`() {
        val merged = SessionState.Loud(session().copy(noGraceThisRing = true))

        assertEquals(GraceState.Expired, map(SessionState.Loud(session()))?.grace)
        assertEquals(
            CheckUiState(
                grace = null,
                content = map(SessionState.Loud(session()))!!.content,
                snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded),
            ),
            map(merged),
        )
    }

    @Test
    fun `the footer and the phone-call note are the ringing screen's`() {
        val paused = SessionState.Loud(session().copy(pausedAt = at(1.seconds.inWholeMilliseconds)))

        assertEquals(WakeNote.PhoneCall, map(paused)?.note)
        assertNull(map(SessionState.Loud(session()))?.note)
        val locked = SnoozeAvailability.Unavailable(UnavailableReason.BeforeFirstUnlock)
        assertEquals(SnoozeOffer.LockedBeforeUnlock, mathCheckUiState(grace(), locked, start, CheckInput())?.snooze)
    }

    @Test
    fun `no Check screen outside Grace and Loud, on a placeholder entry or once the check is passed`() {
        assertNull(map(SessionState.Ringing(session())))
        assertNull(map(SessionState.Snoozed(session())))
        assertNull(map(SessionState.Idle))
        assertNull(map(grace(session(CheckPlan.placeholder()))))
        assertNull(map(grace(session().let { it.copy(checkRun = it.checkRun.copy(step = StepPointer(1, 0))) })))
        assertNull(map(grace(session(item = 3))))
        assertNull(map(grace(session().let { it.copy(checkRun = it.checkRun.copy(seeds = emptyList())) })))
    }

    @Test
    fun `typing keeps at most 5 digits and backspace removes the last`() {
        val typed = (1..7).fold(CheckInput()) { input, digit -> input.typed(digit) }

        assertEquals("12345", typed.digits)
        assertEquals("1234", typed.deleted().digits)
        assertEquals("", CheckInput().deleted().digits)
    }

    @Test
    fun `the input follows the engine - a new problem clears it, a wrong answer clears it and says so until typing`() {
        val p0 = CheckPosition("s", ringIndex = 1, entry = 0, item = 0, seed = 1, failedAttempts = 0)
        val typed = CheckInput(p0, digits = "42")

        assertEquals(typed, typed.following(p0), "same position: kept")
        val wrong = typed.following(p0.copy(failedAttempts = 1))
        assertEquals(CheckInput(p0.copy(failedAttempts = 1), digits = "", wrong = true), wrong)
        assertEquals(CheckInput(p0.copy(failedAttempts = 1), digits = "3", wrong = false), wrong.typed(3))
        val restart = typed.following(p0.copy(item = 0, seed = 9, failedAttempts = 1))
        assertEquals(true, restart.wrong, "a restart is a wrong answer too")
        assertEquals(CheckInput(p0.copy(item = 1)), typed.following(p0.copy(item = 1)), "next item: cleared")
        assertEquals(CheckInput(p0.copy(entry = 1, failedAttempts = 0)), wrong.following(p0.copy(entry = 1)), "next entry: cleared")
        assertEquals(CheckInput(p0.copy(ringIndex = 2)), typed.following(p0.copy(ringIndex = 2)), "next ring: cleared")
        assertEquals(CheckInput(p0.copy(seed = 5)), typed.following(p0.copy(seed = 5)), "another seed without a failed attempt: cleared")
        assertEquals(CheckInput(), typed.following(null))
        assertEquals(CheckInput(p0), CheckInput().following(p0))
    }

    @Test
    fun `the position is read from Grace and Loud only`() {
        val state = grace(session(item = 2, seed = 5))

        assertEquals(CheckPosition(state.session.sessionId, 1, 0, 2, 5, 0), checkPosition(state))
        assertEquals(checkPosition(state), checkPosition(SessionState.Loud(state.session)))
        assertNull(checkPosition(SessionState.Ringing(state.session)))
        assertNull(checkPosition(grace(session().let { it.copy(checkRun = it.checkRun.copy(step = StepPointer(1, 0))) })))
    }
}
