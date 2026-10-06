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
import com.yawnandpawn.app.core.checks.word.WordBank
import com.yawnandpawn.app.core.checks.word.WordList
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
        assertEquals(CheckRun(plan, ringSeeds(2, 2, fallback = true), fallbackUsed = true, fallbackSource = plan), fallback)
        assertEquals(SeedDeriver.seed(SESSION_ID, 2, 1, 3, fallback = true), fallback.seedOf(SESSION_ID, 2, 1, 3))
        assertEquals(SeedDeriver.seed(SESSION_ID, 2, 1, 3), all.seedOf(SESSION_ID, 2, 1, 3))

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
        val run = CheckRun(plan, listOf(1L, 2L), step = StepPointer(0, 1), failedAttempts = 3, totalFailedAttempts = 7)

        assertSame(run, run.withPlan(plan))
        val sameCurrent = plan.copy(entries = listOf(easy, CheckPlan.PLACEHOLDER_ENTRY))
        assertEquals(run.copy(plan = sameCurrent), run.withPlan(sameCurrent))
        val newCurrent = plan.copy(entries = listOf(CheckPlan.PLACEHOLDER_ENTRY, hard))
        assertEquals(run.copy(plan = newCurrent, step = StepPointer(0, 0)), run.withPlan(newCurrent))
        assertEquals(run.copy(step = StepPointer(0, 0), failedAttempts = 0), run.restart(), "the session's total stays")
    }

    @Test
    fun `a damaged row with fewer seeds than entries still checks answers with seeds derived from the session (Story 3_1 review)`() {
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, CameraFallbackPolicy())
        val seeds = ringSeeds(1, 2)
        val damaged = ringSession(testConfig(checkPlan = plan)).copy(checkRun = CheckRun(plan, seeds.take(1), StepPointer(1, 0)))
        val from = SessionState.Loud(damaged)

        val wrong = reducer.reduce(from, SessionEvent.CheckAnswerSubmitted(answerFor(hard, seeds[1], 0, offset = 1)), T0).state
        assertEquals(seeds, assertIs<SessionState.Loud>(wrong).session.checkRun.seeds, "the missing seed is derived and kept")
        val done = reducer.reduce(from, SessionEvent.CheckAnswerSubmitted(answerFor(hard, seeds[1], 0)), T0).state
        assertIs<SessionState.Completed>(done)
        val matched = reducer.reduce(from, SessionEvent.ImageMatchCompleted(matched = true), T0).state
        assertEquals(seeds, assertIs<SessionState.Loud>(matched).session.checkRun.seeds, "an image match is checked the same way")
    }

    @Test
    fun `a damaged row whose item is past the end of its puzzle is checked on the last item, as the screen shows it (Story 3_2 review)`() {
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, CameraFallbackPolicy())
        val seeds = listOf(101L, 202L)
        val damaged = ringSession(testConfig(checkPlan = plan)).copy(checkRun = CheckRun(plan, seeds, StepPointer(0, 5)))
        val usable = damaged.checkRun.usable(damaged.sessionId, damaged.ringIndex)

        assertEquals(StepPointer(0, 1), usable.step, "the last of the entry's 2 items")
        assertEquals(StepPointer(0, 0), CheckRun(plan, seeds, StepPointer(0, -1)).usable(SESSION_ID, 1).step)
        assertSame(usable, usable.usable(damaged.sessionId, damaged.ringIndex), "a usable run stays as it is")
        val passed = CheckRun(plan, seeds, StepPointer(2, 0))
        assertSame(passed, passed.usable(SESSION_ID, 1), "a passed check has no item to move")
        val next = reducer.reduce(SessionState.Loud(damaged), SessionEvent.CheckAnswerSubmitted(answerFor(easy, 101, 1)), T0).state
        assertEquals(StepPointer(1, 0), assertIs<SessionState.Loud>(next).session.checkRun.step, "the last item's answer passes the entry")
    }

    @Test
    fun `a restart with no seed slot for the entry still starts the item over (Story 3_1 review)`() {
        val run = CheckRun(plan, listOf(1L, 2L), step = StepPointer(2, 1), failedAttempts = 1, totalFailedAttempts = 4)
        val from = SessionState.Loud(ringSession(testConfig(checkPlan = plan)).copy(checkRun = run))

        val next = reducer(check = StepResult.InvalidRestart).reduce(from, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder), T0)

        assertEquals(
            run.copy(step = StepPointer(2, 0), failedAttempts = 2, totalFailedAttempts = 5),
            assertIs<SessionState.Loud>(next.state).session.checkRun,
        )
    }

    @Test
    fun `the session's failed attempts are counted over entries, rings and the fallback (Story 3_1 review)`() {
        val fallbackPlan = CheckPlan(CheckMode.All, listOf(hard))
        val reducer =
            SessionReducer(
                StubAvailability(SnoozeAvailability.Available(OFFER)),
                PluginCheckValidator,
                StubFallback(FallbackDecision.Allowed(fallbackPlan)),
            )
        var state: SessionState = SessionState.Idle

        fun send(
            event: SessionEvent,
            minute: Int = 0,
        ): CheckRun =
            reducer
                .reduce(state, event, at(minute.minutes))
                .also { state = it.state }
                .state
                .let(::runOf)

        send(SessionEvent.AlarmFired(SESSION_ID, testConfig(checkPlan = plan), beforeFirstUnlock = false))
        send(SessionEvent.ImUpTapped)
        val seeds = ringSeeds(1, 2)
        send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, seeds[0], 0, offset = 1)))
        send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, seeds[0], 0)))
        val nextEntry = send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, seeds[0], 1)))
        assertEquals(0 to 1, nextEntry.failedAttempts to nextEntry.totalFailedAttempts, "the entry's count resets, the session's does not")
        assertEquals(1 to 2, send(SessionEvent.ImageMatchFailed).let { it.failedAttempts to it.totalFailedAttempts })

        val snoozed = send(SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant))
        assertEquals(0 to 2, snoozed.failedAttempts to snoozed.totalFailedAttempts)
        val ring2 = send(SessionEvent.SlotFired, minute = 10)
        assertEquals(2, ring2.totalFailedAttempts, "the next ring keeps the session's count")
        send(SessionEvent.ImUpTapped, minute = 10)
        send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, ringSeeds(2, 2)[0], 0, offset = 1)), minute = 10)
        val fallback = send(FALLBACK_REQUEST, minute = 10)
        assertEquals(true to 3, fallback.fallbackUsed to fallback.totalFailedAttempts, "the fallback keeps it")
    }

    @Test
    fun `the ring after a snooze resolves a Random fallback again and keeps it as the source (Story 3_1 review)`() {
        val randomFallback = CheckPlan(CheckMode.Random, listOf(easy, hard, CheckPlan.PLACEHOLDER_ENTRY))
        val sessionId =
            (0 until 100).map { "session-$it" }.first { id ->
                val (ring1, ring2) = listOf(1, 2).map { CheckRun.forRing(randomFallback, id, it, fallback = true).plan }
                ring1 != ring2
            }
        val reducer =
            SessionReducer(
                StubAvailability(SnoozeAvailability.Available(OFFER)),
                PluginCheckValidator,
                StubFallback(FallbackDecision.Allowed(randomFallback)),
            )
        var state: SessionState = SessionState.Idle

        fun send(
            event: SessionEvent,
            minute: Int = 0,
        ): CheckRun =
            reducer
                .reduce(state, event, at(minute.minutes))
                .also { state = it.state }
                .state
                .let(::runOf)

        send(SessionEvent.AlarmFired(sessionId, testConfig(checkPlan = plan), beforeFirstUnlock = false))
        send(SessionEvent.ImUpTapped)
        val ring1 = send(FALLBACK_REQUEST)
        send(SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant))
        val ring2 = send(SessionEvent.SlotFired, minute = 10)

        // The run keeps the replaced check for history (Story 3.9).
        assertEquals(CheckRun.forRing(randomFallback, sessionId, 1, fallback = true).copy(fallbackFrom = "Math"), ring1)
        assertEquals(CheckRun.forRing(randomFallback, sessionId, 2, fallback = true).copy(fallbackFrom = "Math"), ring2)
        assertNotEquals(ring1.plan, ring2.plan, "the fallback picks again")
        assertEquals(randomFallback, ring2.fallbackSource)
    }

    private fun runOf(state: SessionState): CheckRun = assertIs<SessionState.Active>(state).session.checkRun

    @Test
    fun `a Math session runs to Completed through wrong answers, items, entries and a restore`() {
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, CameraFallbackPolicy())
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

        assertEquals(
            emptyList(),
            send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, seeds[0], 0)), 1).effects,
            "the next item: no effect",
        )
        assertEquals(StepPointer(0, 1) to 1, run().step to run().failedAttempts, "the next item keeps the entry's failed attempts")
        val nextEntry = send(SessionEvent.CheckAnswerSubmitted(answerFor(easy, seeds[0], 1)), 1)
        assertEquals(listOf(SessionEffect.StartCheckStep(1)), nextEntry.effects, "the next entry is shown (Story 3.2)")
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
    fun `a wrong Memory tap restarts only the current round with a new seed, and the session completes (Story 3-8)`() {
        val memory = CheckEntry(CheckType.MemorySequence(), Difficulty.Easy, count = 2)
        val memoryPlan = CheckPlan(CheckMode.All, listOf(memory))
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, CameraFallbackPolicy())
        var state: SessionState = SessionState.Idle

        fun run(): CheckRun = assertIs<SessionState.Active>(state).session.checkRun

        fun tap(tile: Int) {
            state = reducer.reduce(state, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Tile(tile)), at(1.minutes)).state
        }

        fun taps(): List<Int> = (memory.type.generate(run().seeds[0], memory.difficulty, memory.count) as Puzzle.Memory).taps

        state = reducer.reduce(state, SessionEvent.AlarmFired(SESSION_ID, testConfig(checkPlan = memoryPlan), false), T0).state
        state = reducer.reduce(state, SessionEvent.ImUpTapped, T0).state
        taps().take(5).forEach(::tap)
        assertEquals(StepPointer(0, 5), run().step, "round 1 done, one tap into round 2")
        val seedBefore = run().seeds[0]

        tap(taps()[5] % 9 + 1)

        assertEquals(StepPointer(0, 4), run().step, "back to round 2's first tap, not to round 1")
        assertEquals(1, run().failedAttempts)
        assertNotEquals(seedBefore, run().seeds[0], "a new sequence")
        taps().drop(4).forEach(::tap)
        assertIs<SessionState.Completed>(state)
    }

    @Test
    fun `a two-word Word plan passes on a listed anagram, item then last, and a reinstalled list keeps the scramble (Story 3-7 review)`() {
        // Every word has a listed anagram, so whichever two the seed picks, the other spelling is tried.
        val words = listOf("listen", "silent", "garden", "danger", "rescue", "secure", "master", "stream")
        val wordEntry = CheckEntry(CheckType.WordUnscramble, Difficulty.Medium, count = 2)
        val run = CheckRun(CheckPlan(CheckMode.All, listOf(wordEntry)), listOf(31L))
        try {
            WordBank.install(WordList(words))
            val puzzle = wordEntry.type.generate(31L, wordEntry.difficulty, wordEntry.count) as Puzzle.Word

            fun anagram(item: Int): CheckAnswer =
                CheckAnswer.Word(
                    WordBank.current.anagramsOf(puzzle.words[item]).first {
                        it !=
                            puzzle.words[item]
                    },
                )

            assertEquals(StepResult.ValidNextItem, PluginCheckValidator.validate(run, anagram(0)))
            assertEquals(StepResult.ValidLast, PluginCheckValidator.validate(run.copy(step = StepPointer(0, 1)), anagram(1)))

            // The process restarts and installs the same list again: the stored seed gives the same scramble and answers.
            WordBank.install(WordList(words.reversed()))
            assertEquals(puzzle, wordEntry.type.generate(31L, wordEntry.difficulty, wordEntry.count))
            assertEquals(StepResult.ValidNextItem, PluginCheckValidator.validate(run, CheckAnswer.Word(puzzle.words[0].uppercase())))
        } finally {
            WordBank.install(WordList(emptyList()))
        }
    }

    @Test
    fun `the ring after a paid snooze resolves a Random plan again with the new ring's seeds`() {
        val random = CheckPlan(CheckMode.Random, listOf(easy, CheckPlan.PLACEHOLDER_ENTRY))
        // A session whose two rings pick different entries (about half do; the search is deterministic).
        val sessionId =
            (0 until 100).map { "session-$it" }.first { id ->
                CheckRun.forRing(random, id, 1).plan != CheckRun.forRing(random, id, 2).plan
            }
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, CameraFallbackPolicy())

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
