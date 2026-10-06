package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.SeedDeriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes

/** Story 3.1: the AD-9 plugins inside the session: [PluginCheckValidator], [CheckRun] and the reducer's seeds. */
class CheckPluginSessionTest {
    private val easy = CheckEntry(CheckType.Math, Difficulty.Easy, count = 2)
    private val hard = CheckEntry(CheckType.Math, Difficulty.Hard, count = 1)
    private val plan = CheckPlan(CheckMode.All, listOf(easy, hard))

    private fun answerFor(
        entry: CheckEntry,
        seed: Long,
        item: Int,
        offset: Int = 0,
    ): CheckAnswer {
        val problems = (entry.type.generate(seed, entry.difficulty, entry.count) as Puzzle.Math).problems
        return CheckAnswer.Number((problems[item].answer + offset).toString())
    }

    @Test
    fun `plugin results map onto the AD-2 rows`() {
        val mapped =
            listOf(CheckResult.ItemCorrect, CheckResult.Correct, CheckResult.Wrong, CheckResult.WrongRestart).map { result ->
                PluginCheckValidator.stepResultOf(result, lastEntry = false) to PluginCheckValidator.stepResultOf(result, lastEntry = true)
            }

        assertEquals(
            listOf(
                StepResult.ValidNextItem to StepResult.ValidNextItem,
                StepResult.ValidNext to StepResult.ValidLast,
                StepResult.Invalid to StepResult.Invalid,
                StepResult.InvalidRestart to StepResult.InvalidRestart,
            ),
            mapped,
        )
    }

    @Test
    fun `the plugin validator checks the current item of the current entry against the puzzle of the entry's seed`() {
        val seeds = listOf(101L, 202L)
        val run = CheckRun(plan, seeds)

        assertEquals(StepResult.ValidNextItem, PluginCheckValidator.validate(run, answerFor(easy, 101, 0)))
        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(run, answerFor(easy, 101, 0, offset = 1)))
        val onItem2 = run.copy(step = StepPointer(0, 1))
        assertEquals(StepResult.ValidNext, PluginCheckValidator.validate(onItem2, answerFor(easy, 101, 1)))
        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(onItem2, answerFor(easy, 101, 0)), "item 1's answer on item 2")
        val onLast = run.copy(step = StepPointer(1, 0))
        assertEquals(StepResult.ValidLast, PluginCheckValidator.validate(onLast, answerFor(hard, 202, 0)))
        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(onLast, answerFor(hard, 303, 0)), "another seed's answer")
    }

    @Test
    fun `the plugin validator rejects a passed check, a missing seed and the placeholder only by its own answer`() {
        val answer = answerFor(easy, 101, 0)

        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(CheckRun(plan, listOf(101L, 202L), StepPointer(2, 0)), answer))
        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(CheckRun(plan, emptyList()), answer))
        assertEquals(StepResult.ValidLast, PluginCheckValidator.validate(CheckRun(CheckPlan.placeholder(), SEEDS), CheckAnswer.Placeholder))
        assertEquals(StepResult.ValidNext, PluginCheckValidator.validate(CheckRun(TWO_STEPS, listOf(1L, 2L)), CheckAnswer.Placeholder))
        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(CheckRun(CheckPlan.placeholder(), SEEDS), answer))
    }

    @Test
    fun `a ring's run is the plan resolved for the ring with each entry's first seed`() {
        val all = CheckRun.forRing(plan, SESSION_ID, ringIndex = 2)
        assertEquals(CheckRun(plan, ringSeeds(2, 2)), all)

        val fallback = CheckRun.forRing(plan, SESSION_ID, ringIndex = 2, fallback = true)
        assertEquals(CheckRun(plan, ringSeeds(2, 2, fallback = true), fallbackUsed = true), fallback)
        assertEquals(listOf(SeedDeriver.FALLBACK_BASE, SeedDeriver.FALLBACK_BASE + 1), listOf(0, 1).map(fallback::seedKey))
        assertEquals(listOf(0, 1), listOf(0, 1).map(all::seedKey))

        val random = CheckRun.forRing(plan.copy(mode = CheckMode.Random), SESSION_ID, ringIndex = 1)
        assertEquals(CheckMode.All, random.plan.mode)
        assertEquals(1, random.plan.entries.size)
        assertEquals(ringSeeds(1, 1), random.seeds)

        val substituted = CheckRun.forRing(plan, SESSION_ID, ringIndex = 1) { TWO_STEPS.copy(entries = TWO_STEPS.entries + hard) }
        assertEquals(3, substituted.plan.entries.size)
        assertEquals(ringSeeds(1, 3), substituted.seeds, "one seed per entry of the substituted plan")
    }

    @Test
    fun `a new plan keeps the item while the current entry stays, a restart drops all progress`() {
        val run = CheckRun(plan, listOf(1L, 2L), step = StepPointer(0, 1), failedAttempts = 3)

        assertSame(run, run.withPlan(plan))
        val sameCurrent = plan.copy(entries = listOf(easy, CheckPlan.PLACEHOLDER_ENTRY))
        assertEquals(run.copy(plan = sameCurrent), run.withPlan(sameCurrent))
        val newCurrent = plan.copy(entries = listOf(CheckPlan.PLACEHOLDER_ENTRY, hard))
        assertEquals(run.copy(plan = newCurrent, step = StepPointer(0, 0)), run.withPlan(newCurrent))
        assertEquals(run.copy(step = StepPointer(0, 0), failedAttempts = 0), run.restart())
    }

    @Test
    fun `a Math session runs to Completed through wrong answers, items, entries and a restore`() {
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, NoFallbackPolicy)
        var state: SessionState = SessionState.Idle

        fun send(
            event: SessionEvent,
            minute: Int,
        ): Transition = reducer.reduce(state, event, at(minute.minutes)).also { state = it.state }

        fun run(): CheckRun = assertIs<SessionState.Active>(state).session.checkRun

        send(SessionEvent.AlarmFired(SESSION_ID, testConfig(checkPlan = plan), beforeFirstUnlock = false), 0)
        val seeds = ringSeeds(1, 2)
        assertEquals(CheckRun(plan, seeds), run())

        assertEquals(listOf(SessionEffect.Mute, SessionEffect.StartCheckStep(0)), send(SessionEvent.ImUpTapped, 0).effects)
        val wrong = send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, seeds[0], 0, offset = 1)), 0)
        assertEquals(listOf(SessionEffect.WrongAnswerFeedback), wrong.effects)
        assertEquals(StepPointer(0, 0) to 1, run().step to run().failedAttempts)

        send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, seeds[0], 0)), 1)
        assertEquals(StepPointer(0, 1) to 1, run().step to run().failedAttempts, "the next item keeps the entry's failed attempts")
        send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, seeds[0], 1)), 1)
        assertEquals(StepPointer(1, 0) to 0, run().step to run().failedAttempts, "the next entry starts with none")

        val restored = assertIs<StoredSession.Found>(SessionJson.decode(SessionJson.encode(state))).state
        state = reducer.reduce(restored, SessionEvent.ProcessRestored, at(2.minutes)).state
        assertEquals(StepPointer(1, 0), run().step, "the restore keeps the position and the seeds")
        assertIs<SessionState.Grace>(state)

        send(SessionEvent.CheckAnswerSubmitted(answerFor(hard, seeds[1], 0)), 2)
        val completed = assertIs<SessionState.Completed>(state)
        assertEquals(StepPointer(2, 0), completed.session.checkRun.step)
    }

    @Test
    fun `the ring after a paid snooze resolves a Random plan again with the new ring's seeds`() {
        val random = CheckPlan(CheckMode.Random, listOf(easy, CheckPlan.PLACEHOLDER_ENTRY))
        // A session whose two rings pick different entries (about half do; the search is deterministic).
        val sessionId =
            (0 until 100).map { "session-$it" }.first { id ->
                CheckRun.forRing(random, id, 1).plan != CheckRun.forRing(random, id, 2).plan
            }
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, NoFallbackPolicy)

        val ringing = reducer.reduce(SessionState.Idle, SessionEvent.AlarmFired(sessionId, testConfig(checkPlan = random), false), T0).state
        val ring1 = assertIs<SessionState.Ringing>(ringing).session.checkRun
        val snoozed = reducer.reduce(ringing, SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant), at(1.minutes)).state
        assertEquals(ring1, assertIs<SessionState.Snoozed>(snoozed).session.checkRun, "nothing to drop on the first ring yet")
        val ring2 = assertIs<SessionState.Ringing>(reducer.reduce(snoozed, SessionEvent.SlotFired, at(10.minutes)).state).session

        assertEquals(2, ring2.ringIndex)
        assertNotEquals(ring1.plan, ring2.checkRun.plan, "another type this ring")
        assertEquals(listOf(SeedDeriver.seed(sessionId, 2, 0, 0)), ring2.checkRun.seeds)
        assertEquals(listOf(SeedDeriver.seed(sessionId, 1, 0, 0)), ring1.seeds)
    }
}
